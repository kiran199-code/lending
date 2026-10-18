package com.bank.lending.iam.web;

import com.bank.lending.iam.auth.Authenticator;
import com.bank.lending.iam.auth.CredentialService;
import com.bank.lending.iam.auth.MfaService;
import com.bank.lending.iam.audit.AuditService;
import com.bank.lending.iam.common.IamException;
import com.bank.lending.iam.customer.ConsentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * The pages a person sees while signing in: the form itself, and the three steps that can interrupt it
 * (a new password, a second factor to enrol, new terms to accept). Each step re-checks the password, with the same
 * failure counting and lockout as the sign-in, so none of them can be used to guess passwords.
 */
@Controller
public class LoginPagesController {
    /** Set by the failure handler when the password was right but a step is missing. */
    public static final String PENDING_USER = "iam.pendingUser";
    static final String ENROL_USER = "iam.enrolUser";
    static final String ENROL_SECRET = "iam.enrolSecret";
    static final String ENROL_URI = "iam.enrolUri";
    static final String ENROL_AT = "iam.enrolAt";
    static final long ENROL_VALID_MILLIS = 10 * 60_000L;

    private static final String CT = "text/html;charset=UTF-8";
    private static final String GENERIC = "Sign-in failed. Check your details and try again.";

    private final Authenticator authenticator;
    private final CredentialService credentials;
    private final MfaService mfa;
    private final ConsentService consents;
    private final AuditService audit;
    private final Clock clock;

    public LoginPagesController(Authenticator authenticator, CredentialService credentials, MfaService mfa, ConsentService consents, AuditService audit, Clock clock) {
        this.authenticator = authenticator;
        this.credentials = credentials;
        this.mfa = mfa;
        this.consents = consents;
        this.audit = audit;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ landing and sign-in
    @GetMapping(value = "/", produces = CT)
    public void home(HttpServletRequest rq, HttpServletResponse rs) throws IOException {
        write(rs, Html.page("Sign-in service", "<h1>Lending Platform</h1><p>This is the sign-in service. Open the application you came from to sign in.</p>"));
    }

    @GetMapping(value = "/login", produces = CT)
    public void login(HttpServletRequest rq, HttpServletResponse rs) throws IOException {
        StringBuilder b = new StringBuilder("<h1>Sign in</h1>");
        if (rq.getParameter("error") != null) b.append(Html.message("err", GENERIC));
        if (rq.getParameter("changed") != null) b.append(Html.message("ok", "Your password was changed. Sign in with the new password."));
        if (rq.getParameter("enrolled") != null) b.append(Html.message("ok", "Two-step verification is on. Sign in with your password and a code."));
        if (rq.getParameter("consented") != null) b.append(Html.message("ok", "Thank you. Sign in to continue."));
        b.append("<form method=\"post\" action=\"/login\">").append(csrf(rq))
                .append(Html.field("User name", "username", "text", "", "username"))
                .append(Html.field("Password", "password", "password", "", "current-password"))
                .append("<label for=\"totp\">Verification code (if you use two-step verification)</label>")
                .append("<input id=\"totp\" name=\"totp\" type=\"text\" inputmode=\"numeric\" autocomplete=\"one-time-code\" maxlength=\"20\">")
                .append("<button type=\"submit\">Sign in</button></form>");
        write(rs, Html.page("Sign in", b.toString()));
    }

    // ------------------------------------------------------------------ change password
    @GetMapping(value = "/change-password", produces = CT)
    public void changePasswordForm(HttpServletRequest rq, HttpServletResponse rs) throws IOException {
        write(rs, changePasswordPage(rq, pending(rq), null, null));
    }

    @PostMapping(value = "/change-password", produces = CT)
    public void changePassword(HttpServletRequest rq, HttpServletResponse rs,
                               @RequestParam(value = "username", defaultValue = "") String username,
                               @RequestParam(value = "currentPassword", defaultValue = "") String current,
                               @RequestParam(value = "newPassword", defaultValue = "") String next,
                               @RequestParam(value = "confirmPassword", defaultValue = "") String confirm) throws IOException {
        if (!next.equals(confirm)) {
            write(rs, changePasswordPage(rq, username, "The two new passwords are not the same.", null));
            return;
        }
        try {
            credentials.changeOwnPassword(username.trim(), current, next);
        } catch (IamException e) {
            if ("PASSWORD_POLICY".equals(e.code())) write(rs, changePasswordPage(rq, username, e.getMessage(), e.details()));
            else write(rs, changePasswordPage(rq, username, "The user name or current password is not correct.", null));
            return;
        }
        HttpSession s = rq.getSession(false);
        if (s != null) s.removeAttribute(PENDING_USER);
        rs.sendRedirect("/login?changed");
    }

    private String changePasswordPage(HttpServletRequest rq, String username, String error, List<String> details) {
        StringBuilder b = new StringBuilder("<h1>Choose a new password</h1>");
        if (error != null) b.append(Html.message("err", error));
        if (details != null && !details.isEmpty()) b.append(Html.list(details));
        b.append("<form method=\"post\" action=\"/change-password\">").append(csrf(rq))
                .append(Html.field("User name", "username", "text", username, "username"))
                .append(Html.field("Current password", "currentPassword", "password", "", "current-password"))
                .append(Html.field("New password", "newPassword", "password", "", "new-password"))
                .append(Html.field("New password again", "confirmPassword", "password", "", "new-password"))
                .append("<button type=\"submit\">Change password</button></form>");
        return Html.page("New password", b.toString());
    }

    // ------------------------------------------------------------------ enrol a second factor
    @GetMapping(value = "/enrol", produces = CT)
    public void enrolForm(HttpServletRequest rq, HttpServletResponse rs) throws IOException {
        HttpSession s = rq.getSession(false);
        if (s != null && enrolling(s) != null) {
            write(rs, enrolCodePage(rq, (String) s.getAttribute(ENROL_SECRET), (String) s.getAttribute(ENROL_URI), null));
            return;
        }
        write(rs, enrolStartPage(rq, pending(rq), null));
    }

    @PostMapping(value = "/enrol/start", produces = CT)
    public void enrolStart(HttpServletRequest rq, HttpServletResponse rs,
                           @RequestParam(value = "username", defaultValue = "") String username,
                           @RequestParam(value = "password", defaultValue = "") String password) throws IOException {
        Authenticator.Result r = authenticator.verifyPasswordOnly(username.trim(), password);
        if (r.outcome() == Authenticator.Outcome.PASSWORD_CHANGE_REQUIRED) {
            rq.getSession().setAttribute(PENDING_USER, r.user().username());
            rs.sendRedirect("/change-password");
            return;
        }
        if (r.outcome() != Authenticator.Outcome.SUCCESS && r.outcome() != Authenticator.Outcome.MFA_ENROLMENT_REQUIRED) {
            write(rs, enrolStartPage(rq, username, "The user name or password is not correct."));
            return;
        }
        if (r.user().mfaEnabled()) {
            write(rs, enrolStartPage(rq, username, "Two-step verification is already on for this account."));
            return;
        }
        MfaService.Enrolment e = mfa.enrol(r.user().username());
        HttpSession s = rq.getSession();
        s.setAttribute(ENROL_USER, r.user().username());
        s.setAttribute(ENROL_SECRET, e.secret());
        s.setAttribute(ENROL_URI, e.otpauthUri());
        s.setAttribute(ENROL_AT, clock.millis());
        write(rs, enrolCodePage(rq, e.secret(), e.otpauthUri(), null));
    }

    @PostMapping(value = "/enrol/confirm", produces = CT)
    public void enrolConfirm(HttpServletRequest rq, HttpServletResponse rs, @RequestParam(value = "code", defaultValue = "") String code) throws IOException {
        HttpSession s = rq.getSession(false);
        String user = s == null ? null : enrolling(s);
        if (user == null) {
            rs.sendRedirect("/enrol");
            return;
        }
        List<String> recovery;
        try {
            recovery = mfa.confirm(user, code.trim());
        } catch (IamException e) {
            write(rs, enrolCodePage(rq, (String) s.getAttribute(ENROL_SECRET), (String) s.getAttribute(ENROL_URI), "That code is not correct. Try the next one shown by the app."));
            return;
        }
        s.removeAttribute(ENROL_USER);
        s.removeAttribute(ENROL_SECRET);
        s.removeAttribute(ENROL_URI);
        s.removeAttribute(ENROL_AT);
        s.removeAttribute(PENDING_USER);
        StringBuilder b = new StringBuilder("<h1>Two-step verification is on</h1>")
                .append("<p>Keep these recovery codes somewhere safe. Each works once if you lose your phone. They are not shown again.</p>");
        for (String c : recovery) b.append("<code>").append(Html.esc(c)).append("</code>");
        b.append("<p><a href=\"/login?enrolled\">Continue to sign in</a></p>");
        write(rs, Html.page("Recovery codes", b.toString()));
    }

    private String enrolling(HttpSession s) {
        Object user = s.getAttribute(ENROL_USER);
        Object at = s.getAttribute(ENROL_AT);
        if (user instanceof String u && at instanceof Long t && clock.millis() - t <= ENROL_VALID_MILLIS) return u;
        return null;
    }

    private String enrolStartPage(HttpServletRequest rq, String username, String error) {
        StringBuilder b = new StringBuilder("<h1>Turn on two-step verification</h1>")
                .append("<p>Your role requires a second step at sign-in. Confirm your password to begin.</p>");
        if (error != null) b.append(Html.message("err", error));
        b.append("<form method=\"post\" action=\"/enrol/start\">").append(csrf(rq))
                .append(Html.field("User name", "username", "text", username, "username"))
                .append(Html.field("Password", "password", "password", "", "current-password"))
                .append("<button type=\"submit\">Continue</button></form>");
        return Html.page("Two-step verification", b.toString());
    }

    private String enrolCodePage(HttpServletRequest rq, String secret, String uri, String error) {
        StringBuilder b = new StringBuilder("<h1>Add this account to your authenticator app</h1>")
                .append("<p>In your authenticator app choose to add an account and enter this key. Then type the six-digit code it shows.</p>")
                .append("<code>").append(Html.esc(secret)).append("</code>")
                .append("<p class=\"hint\">Or open this link on the phone that has the app:</p><code>").append(Html.esc(uri)).append("</code>");
        if (error != null) b.append(Html.message("err", error));
        b.append("<form method=\"post\" action=\"/enrol/confirm\">").append(csrf(rq))
                .append("<label for=\"code\">Code from the app</label><input id=\"code\" name=\"code\" type=\"text\" inputmode=\"numeric\" autocomplete=\"one-time-code\" maxlength=\"10\" required>")
                .append("<button type=\"submit\">Turn on</button></form>");
        return Html.page("Two-step verification", b.toString());
    }

    // ------------------------------------------------------------------ accept new terms
    @GetMapping(value = "/consent", produces = CT)
    public void consentForm(HttpServletRequest rq, HttpServletResponse rs) throws IOException {
        write(rs, consentPage(rq, pending(rq), null));
    }

    @PostMapping(value = "/consent", produces = CT)
    public void consent(HttpServletRequest rq, HttpServletResponse rs,
                        @RequestParam(value = "username", defaultValue = "") String username,
                        @RequestParam(value = "password", defaultValue = "") String password,
                        @RequestParam(value = "accept", required = false) List<String> accepted) throws IOException {
        Authenticator.Result r = authenticator.verifyPasswordOnly(username.trim(), password);
        if (r.outcome() == Authenticator.Outcome.PASSWORD_CHANGE_REQUIRED) {
            rq.getSession().setAttribute(PENDING_USER, r.user().username());
            rs.sendRedirect("/change-password");
            return;
        }
        if (r.outcome() != Authenticator.Outcome.SUCCESS && r.outcome() != Authenticator.Outcome.MFA_ENROLMENT_REQUIRED || !r.user().customer()) {
            write(rs, consentPage(rq, username, "The user name or password is not correct."));
            return;
        }
        List<String> codes = accepted == null ? List.of() : accepted;
        try {
            consents.requireMandatory(codes);
            consents.recordAll(r.user().id(), codes, null);
        } catch (IamException e) {
            write(rs, consentPage(rq, username, "Please accept every item marked required to continue."));
            return;
        }
        audit.recordAs("USER", r.user().username(), "CONSENT_ACCEPTED_AT_SIGN_IN", "USER", r.user().username(), "SUCCESS", "purposes", codes);
        HttpSession s = rq.getSession(false);
        if (s != null) s.removeAttribute(PENDING_USER);
        rs.sendRedirect("/login?consented");
    }

    private String consentPage(HttpServletRequest rq, String username, String error) {
        StringBuilder b = new StringBuilder("<h1>Please review and accept</h1>");
        if (error != null) b.append(Html.message("err", error));
        b.append("<form method=\"post\" action=\"/consent\">").append(csrf(rq));
        for (Map<String, Object> p : consents.activePurposes().stream().map(consents::purposeView).toList()) {
            boolean mandatory = Boolean.TRUE.equals(p.get("mandatory"));
            b.append("<label><input type=\"checkbox\" name=\"accept\" value=\"").append(Html.esc(String.valueOf(p.get("purpose")))).append("\"")
                    .append(mandatory ? " required" : "").append("> ").append(Html.esc(String.valueOf(p.get("description"))))
                    .append(mandatory ? " (required)" : " (optional)").append("</label>");
        }
        b.append(Html.field("User name", "username", "text", username, "username"))
                .append(Html.field("Password", "password", "password", "", "current-password"))
                .append("<button type=\"submit\">Accept and continue</button></form>");
        return Html.page("Terms", b.toString());
    }

    // ------------------------------------------------------------------ helpers
    private static String pending(HttpServletRequest rq) {
        HttpSession s = rq.getSession(false);
        Object u = s == null ? null : s.getAttribute(PENDING_USER);
        return u instanceof String str ? str : "";
    }

    private static String csrf(HttpServletRequest rq) {
        Object t = rq.getAttribute(CsrfToken.class.getName());
        if (t instanceof CsrfToken c) return Html.csrf(c.getParameterName(), c.getToken());
        return "";
    }

    private static void write(HttpServletResponse rs, String html) throws IOException {
        rs.setContentType(CT);
        rs.setHeader("Cache-Control", "no-store");
        rs.setHeader("Pragma", "no-cache");
        rs.getWriter().write(html);
    }
}
