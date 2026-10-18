package com.bank.lending.iam.auth;

import com.bank.lending.iam.access.RbacRepository;
import com.bank.lending.iam.audit.AuditService;
import com.bank.lending.iam.common.Hashing;
import com.bank.lending.iam.common.MetricsSink;
import com.bank.lending.iam.common.SecretHasher;
import com.bank.lending.iam.crypto.SecretCrypto;
import com.bank.lending.iam.customer.ConsentService;
import com.bank.lending.iam.domain.LockoutPolicy;
import com.bank.lending.iam.domain.PasswordPolicy;
import com.bank.lending.iam.domain.RecoveryCodes;
import com.bank.lending.iam.domain.TotpService;
import com.bank.lending.iam.user.UserRepository;
import com.bank.lending.iam.user.UserRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * The sign-in decision. It returns a result instead of throwing, so that failure counters and audit rows commit.
 * Wrong user, wrong password, locked, suspended and wrong code all look the same to the caller (outcome FAILED);
 * the audit trail records the real reason. A known and an unknown username both cost one bcrypt comparison.
 */
@Service
public class Authenticator {
    public enum Outcome { SUCCESS, FAILED, PASSWORD_CHANGE_REQUIRED, MFA_ENROLMENT_REQUIRED, CONSENT_REQUIRED }

    /** @param mfaAt the instant a second factor was verified in this sign-in, or null */
    public record Result(Outcome outcome, UserRow user, Instant mfaAt) {
        static Result failed() { return new Result(Outcome.FAILED, null, null); }
    }

    private final UserRepository users;
    private final RbacRepository rbac;
    private final PasswordPolicy passwordPolicy;
    private final LockoutPolicy lockout;
    private final SecretHasher hasher;
    private final TotpService totp;
    private final SecretCrypto crypto;
    private final ConsentService consents;
    private final AuditService audit;
    private final MetricsSink metrics;
    private final Clock clock;
    private final String dummyHash;

    public Authenticator(UserRepository users, RbacRepository rbac, PasswordPolicy passwordPolicy, LockoutPolicy lockout, SecretHasher hasher,
                         TotpService totp, SecretCrypto crypto, ConsentService consents, AuditService audit, MetricsSink metrics, Clock clock) {
        this.users = users;
        this.rbac = rbac;
        this.passwordPolicy = passwordPolicy;
        this.lockout = lockout;
        this.hasher = hasher;
        this.totp = totp;
        this.crypto = crypto;
        this.consents = consents;
        this.audit = audit;
        this.metrics = metrics;
        this.clock = clock;
        this.dummyHash = hasher.hash("dummy-" + Hashing.randomToken(12));
    }

    /** Full sign-in: password, second factor when enrolled, mandatory consents. */
    @Transactional
    public Result login(String username, String password, String otp) {
        return core(username, password, otp, true);
    }

    /**
     * Checks the password only (change-password and enrolment pages), with the same failure counting and lockout.
     * Returns SUCCESS, PASSWORD_CHANGE_REQUIRED or MFA_ENROLMENT_REQUIRED for a correct password, FAILED otherwise.
     */
    @Transactional
    public Result verifyPasswordOnly(String username, String password) {
        return core(username, password, null, false);
    }

    private Result core(String usernameIn, String password, String otp, boolean fullLogin) {
        String username = usernameIn == null ? "" : usernameIn.trim();
        Instant now = Instant.now(clock);
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        Optional<UserRow> found = username.isEmpty() ? Optional.empty() : users.findByUsernameForUpdate(username);
        if (found.isEmpty()) {
            hasher.matches(password == null ? "" : password, dummyHash);
            audit.recordAs("ANONYMOUS", trunc(username), "LOGIN_FAILED", "USER", trunc(username), "FAILURE", "reason", "UNKNOWN_USER");
            metrics.count("iam.signin", "result", "failure");
            return Result.failed();
        }
        UserRow u = found.get();

        if ("LOCKED".equals(u.status())) {
            if (lockout.isLocked(u.lockedUntil(), now)) {
                hasher.matches(password == null ? "" : password, dummyHash);
                return failedNoCount(u, "LOCKED");
            }
            users.clearFailures(u.id(), "ACTIVE", null);
            u = users.findByIdForUpdate(u.id()).orElseThrow();
        }

        boolean passwordOk = u.passwordHash() != null
                ? hasher.matches(password == null ? "" : password, u.passwordHash())
                : hasher.matches(password == null ? "" : password, dummyHash) && false;
        if (!passwordOk) return failedCount(u, "BAD_PASSWORD", now);

        if (!("ACTIVE".equals(u.status()) || "EXPIRED".equals(u.status()))) {
            return failedNoCount(u, "NOT_AVAILABLE_" + u.status());
        }

        // The password is right. Temporary password past its time?
        if (u.mustChangePassword()) {
            Optional<UserRepository.TokenRow> temp = users.latestOpenToken(u.id(), "TEMP_PASSWORD");
            if (temp.isPresent() && !now.isBefore(temp.get().expiresAt())) {
                return failedNoCount(u, "TEMP_PASSWORD_EXPIRED");
            }
        }
        boolean aged = passwordPolicy.isExpired(u.passwordChangedAt(), u.customer(), now);
        if (u.mustChangePassword() || "EXPIRED".equals(u.status()) || aged) {
            if (aged && !"EXPIRED".equals(u.status())) users.markPasswordExpired(u.id());
            users.clearFailures(u.id(), "EXPIRED", null);
            audit.recordAs("USER", u.username(), "LOGIN_PASSWORD_CHANGE_REQUIRED", "USER", u.username(), "SUCCESS");
            return new Result(Outcome.PASSWORD_CHANGE_REQUIRED, u, null);
        }

        boolean mfaRequiredByRole = rbac.requiresMfa(u.id(), today);
        if (!u.mfaEnabled()) {
            if (mfaRequiredByRole) {
                users.clearFailures(u.id(), "ACTIVE", null);
                audit.recordAs("USER", u.username(), "LOGIN_MFA_ENROLMENT_REQUIRED", "USER", u.username(), "FAILURE");
                return new Result(Outcome.MFA_ENROLMENT_REQUIRED, u, null);
            }
        } else if (fullLogin) {
            Result mfa = checkSecondFactor(u, otp, now);
            if (mfa != null) return mfa;
        }

        if (fullLogin) {
            if (u.customer() && !consents.purposesNeedingAcceptance(u.id()).isEmpty()) {
                users.clearFailures(u.id(), "ACTIVE", null);
                audit.recordAs("USER", u.username(), "LOGIN_CONSENT_REQUIRED", "USER", u.username(), "FAILURE");
                return new Result(Outcome.CONSENT_REQUIRED, u, null);
            }
            users.clearFailures(u.id(), "ACTIVE", now);
            audit.recordAs("USER", u.username(), "LOGIN_SUCCESS", "USER", u.username(), "SUCCESS", "mfa", u.mfaEnabled());
            metrics.count("iam.signin", "result", "success");
            return new Result(Outcome.SUCCESS, u, u.mfaEnabled() ? now : null);
        }
        users.clearFailures(u.id(), "ACTIVE", null);
        return new Result(Outcome.SUCCESS, u, null);
    }

    /** Null when the second factor is good (the step or recovery code is consumed); otherwise the failure result. */
    private Result checkSecondFactor(UserRow u, String otp, Instant now) {
        if (otp == null || otp.isBlank()) {
            return mfaFailed(u, "MFA_CODE_MISSING", now);
        }
        if (RecoveryCodes.looksLikeRecoveryCode(otp)) {
            if (!users.consumeRecoveryCode(u.id(), RecoveryCodes.hash(otp))) return mfaFailed(u, "BAD_RECOVERY_CODE", now);
            audit.recordAs("USER", u.username(), "MFA_RECOVERY_CODE_USED", "USER", u.username(), "SUCCESS", "remaining", users.unusedRecoveryCodes(u.id()));
            return null;
        }
        String secret = crypto.decrypt(u.mfaSecretEnc(), "mfa:" + u.id());
        TotpService.Result r = totp.verify(secret, otp, now, u.mfaLastStep());
        if (r.status() == TotpService.Status.REPLAY) return mfaFailed(u, "MFA_CODE_REPLAYED", now);
        if (r.status() != TotpService.Status.OK) return mfaFailed(u, "BAD_MFA_CODE", now);
        if (!users.useMfaStep(u.id(), r.step())) return mfaFailed(u, "MFA_CODE_REPLAYED", now);
        return null;
    }

    private Result mfaFailed(UserRow u, String reason, Instant now) {
        audit.recordAs("USER", u.username(), "MFA_FAILED", "USER", u.username(), "FAILURE", "reason", reason);
        return failedCount(u, reason, now);
    }

    private Result failedCount(UserRow u, String reason, Instant now) {
        LockoutPolicy.Outcome o = lockout.onFailure(u.failedAttempts(), now);
        users.recordFailure(u.id(), o.failedAttempts(), o.lockedUntil(), o.lockedNow() ? "LOCKED" : u.status());
        audit.recordAs("USER", u.username(), "LOGIN_FAILED", "USER", u.username(), "FAILURE", "reason", reason, "failedAttempts", o.failedAttempts());
        if (o.lockedNow()) {
            audit.recordAs("SYSTEM", "authenticator", "USER_LOCKED", "USER", u.username(), "SUCCESS", "until", o.lockedUntil().toString());
            metrics.count("iam.signin", "result", "locked");
        }
        metrics.count("iam.signin", "result", "failure");
        return Result.failed();
    }

    private Result failedNoCount(UserRow u, String reason) {
        audit.recordAs("USER", u.username(), "LOGIN_FAILED", "USER", u.username(), "FAILURE", "reason", reason);
        metrics.count("iam.signin", "result", "failure");
        return Result.failed();
    }

    private static String trunc(String s) {
        return s.length() > 60 ? s.substring(0, 60) : s;
    }
}
