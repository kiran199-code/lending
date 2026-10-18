package com.bank.lending.iam.auth;

import com.bank.lending.iam.audit.AuditService;
import com.bank.lending.iam.common.Hashing;
import com.bank.lending.iam.common.IamException;
import com.bank.lending.iam.common.RequestContext;
import com.bank.lending.iam.common.SecretHasher;
import com.bank.lending.iam.config.IamSettings;
import com.bank.lending.iam.customer.ConsentService;
import com.bank.lending.iam.domain.PasswordGenerator;
import com.bank.lending.iam.domain.PasswordPolicy;
import com.bank.lending.iam.events.EventService;
import com.bank.lending.iam.user.UserRepository;
import com.bank.lending.iam.user.UserRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Everything that sets or changes a password: first activation, temporary passwords, change by the user. */
@Service
public class CredentialService {
    /** A secret shown once to whoever created it. */
    public record IssuedSecret(String kind, String value, Instant expiresAt) {}

    private final UserRepository users;
    private final PasswordPolicy policy;
    private final SecretHasher hasher;
    private final AuditService audit;
    private final EventService events;
    private final RevocationService revocation;
    private final Authenticator authenticator;
    private final ConsentService consents;
    private final IamSettings settings;
    private final Clock clock;

    public CredentialService(UserRepository users, PasswordPolicy policy, SecretHasher hasher, AuditService audit, EventService events,
                             RevocationService revocation, Authenticator authenticator, ConsentService consents, IamSettings settings, Clock clock) {
        this.users = users;
        this.policy = policy;
        this.hasher = hasher;
        this.audit = audit;
        this.events = events;
        this.revocation = revocation;
        this.authenticator = authenticator;
        this.consents = consents;
        this.settings = settings;
        this.clock = clock;
    }

    /** Throws 422 PASSWORD_POLICY listing every broken rule. */
    public void enforcePolicy(UserRow u, String newPassword) {
        List<PasswordPolicy.Violation> v = policy.check(newPassword, u.username(), users.passwordHistory(u.id(), policy.historySize()), hasher);
        if (!v.isEmpty()) {
            throw IamException.unprocessable("PASSWORD_POLICY", "The password does not meet the policy", v.stream().map(PasswordPolicy.Violation::message).toList());
        }
    }

    /** Stores a new password chosen by the user: the user is ACTIVE, nothing is pending. */
    private void storeChosenPassword(UserRow u, String newPassword) {
        Instant now = Instant.now(clock);
        String hash = hasher.hash(newPassword);
        String status = "EXPIRED".equals(u.status()) || "PENDING_ACTIVATION".equals(u.status()) ? "ACTIVE" : u.status();
        users.setPassword(u.id(), hash, now, false, status);
        users.addPasswordHistory(u.id(), hash);
        users.invalidateTokens(u.id(), "TEMP_PASSWORD");
    }

    /** The user changes their own password, proving the current one (also used by the forced-change page). */
    // a refusal here happens before anything is written, and the failure counter and audit row must survive it
    @Transactional(noRollbackFor = IamException.class)
    public void changeOwnPassword(String username, String current, String newPassword) {
        Authenticator.Result r = authenticator.verifyPasswordOnly(username, current);
        if (r.outcome() == Authenticator.Outcome.FAILED) {
            throw IamException.unauthorized("INVALID_CREDENTIALS", "Invalid credentials or account not available");
        }
        UserRow u = users.findByUsernameForUpdate(username).orElseThrow(() -> IamException.unauthorized("INVALID_CREDENTIALS", "Invalid credentials or account not available"));
        if (hasher.matches(newPassword, u.passwordHash())) {
            throw IamException.unprocessable("PASSWORD_POLICY", "The password does not meet the policy", List.of("The new password must differ from the current one"));
        }
        enforcePolicy(u, newPassword);
        storeChosenPassword(u, newPassword);
        // other sessions of this user are ended: their tokens carry the old credentials
        revocation.revokeUser(u.id(), u.username());
        audit.recordAs("USER", u.username(), "PASSWORD_CHANGED", "USER", u.username(), "SUCCESS");
    }

    /**
     * Generates a temporary password for a user (administrator action). The user must change it at the next sign-in;
     * it expires after the configured hours.
     */
    @Transactional
    public IssuedSecret issueTemporaryPassword(UserRow u) {
        Instant now = Instant.now(clock);
        String temp = PasswordGenerator.temporary();
        String hash = hasher.hash(temp);
        users.invalidateTokens(u.id(), "TEMP_PASSWORD");
        Instant expires = now.plus(Duration.ofHours(settings.tempPasswordHours));
        users.insertToken(u.id(), "TEMP_PASSWORD", Hashing.sha256Hex("temp:" + Hashing.randomToken(24)), expires);
        // a temporary password is a password the user must replace: ACTIVE, but sign-in only reaches the change page
        users.setPassword(u.id(), hash, now, true, "ACTIVE".equals(u.status()) || "LOCKED".equals(u.status()) || "EXPIRED".equals(u.status()) ? "ACTIVE" : u.status());
        users.addPasswordHistory(u.id(), hash);
        return new IssuedSecret("TEMPORARY_PASSWORD", temp, expires);
    }

    /** A new one-time activation token (customers waiting to activate). */
    @Transactional
    public IssuedSecret issueActivationToken(UserRow u) {
        Instant expires = Instant.now(clock).plus(Duration.ofHours(settings.activationHours));
        String raw = Hashing.randomToken(32);
        users.invalidateTokens(u.id(), "ACTIVATION");
        users.insertToken(u.id(), "ACTIVATION", Hashing.sha256Hex(raw), expires);
        return new IssuedSecret("ACTIVATION_TOKEN", raw, expires);
    }

    /** The public activation: token, new password, and the codes of every consent the customer accepts. */
    // a refusal here happens before anything is written, and the failure counter and audit row must survive it
    @Transactional(noRollbackFor = IamException.class)
    public void activate(String token, String newPassword, List<String> acceptedPurposes) {
        UserRepository.TokenRow t = users.findToken(Hashing.sha256Hex(token == null ? "" : token)).orElse(null);
        Instant now = Instant.now(clock);
        if (t == null || !"ACTIVATION".equals(t.purpose()) || t.usedAt() != null || !now.isBefore(t.expiresAt())) {
            audit.recordAs("ANONYMOUS", "anonymous", "ACTIVATION_FAILED", "USER", t == null ? null : String.valueOf(t.userId()), "FAILURE", "reason", "TOKEN_INVALID");
            throw IamException.badRequest("TOKEN_INVALID", "The activation link is not valid or has expired");
        }
        UserRow u = users.findByIdForUpdate(t.userId()).orElseThrow(() -> IamException.badRequest("TOKEN_INVALID", "The activation link is not valid or has expired"));
        if (!"PENDING_ACTIVATION".equals(u.status())) {
            throw IamException.badRequest("TOKEN_INVALID", "The activation link is not valid or has expired");
        }
        consents.requireMandatory(acceptedPurposes);   // 400 CONSENT_REQUIRED
        enforcePolicy(u, newPassword);
        if (!users.useToken(t.tokenId())) {            // two requests with one token: one wins
            throw IamException.badRequest("TOKEN_INVALID", "The activation link is not valid or has expired");
        }
        storeChosenPassword(u, newPassword);
        consents.recordAll(u.id(), acceptedPurposes, null);
        RequestContext.current().as("USER", u.username());
        audit.record("USER_ACTIVATED", "USER", u.username(), "SUCCESS");
        events.publish(EventService.USER_STATUS_CHANGED, "user:" + u.username(), "username", u.username(), "from", "PENDING_ACTIVATION", "to", "ACTIVE");
    }
}
