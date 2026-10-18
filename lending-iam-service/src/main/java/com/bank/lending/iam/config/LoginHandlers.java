package com.bank.lending.iam.config;

import com.bank.lending.iam.web.LoginPagesController;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;

import java.io.IOException;

/** What happens after the sign-in form is submitted. */
public final class LoginHandlers {
    private LoginHandlers() {}

    /** Remembers when this session last signed in, so that a prompt=login request can tell a fresh sign-in from an old one. */
    public static class Success extends SavedRequestAwareAuthenticationSuccessHandler {
        public Success() {
            setDefaultTargetUrl("/");
        }

        @Override
        public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws ServletException, IOException {
            HttpSession session = request.getSession();
            session.setAttribute(ReauthFilter.SIGNED_IN_AT, System.currentTimeMillis());
            session.removeAttribute(LoginPagesController.PENDING_USER);
            super.onAuthenticationSuccess(request, response, authentication);
        }
    }

    /**
     * Wrong details always lead to the same page and message. Only when the password was right is the person sent to the
     * page for the missing step; the user name is kept in the session, not in the address.
     */
    public static class Failure implements AuthenticationFailureHandler {
        @Override
        public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response, AuthenticationException e) throws IOException {
            if (e instanceof IamAuthException x) {
                request.getSession().setAttribute(LoginPagesController.PENDING_USER, x.username());
                String target = switch (x.outcome()) {
                    case PASSWORD_CHANGE_REQUIRED -> "/change-password";
                    case MFA_ENROLMENT_REQUIRED -> "/enrol";
                    case CONSENT_REQUIRED -> "/consent";
                    default -> "/login?error";
                };
                response.sendRedirect(request.getContextPath() + target);
                return;
            }
            response.sendRedirect(request.getContextPath() + "/login?error");
        }
    }
}
