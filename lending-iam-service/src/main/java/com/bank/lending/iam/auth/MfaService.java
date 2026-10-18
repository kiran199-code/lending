package com.bank.lending.iam.auth;

import com.bank.lending.iam.access.RbacRepository;
import com.bank.lending.iam.audit.AuditService;
import com.bank.lending.iam.common.IamException;
import com.bank.lending.iam.config.IamSettings;
import com.bank.lending.iam.crypto.SecretCrypto;
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
import java.util.List;

/** TOTP enrolment, recovery codes, disabling and administrator reset. */
@Service
public class MfaService {
    public record Enrolment(String secret, String otpauthUri) {}

    private final UserRepository users;
    private final RbacRepository rbac;
    private final TotpService totp;
    private final SecretCrypto crypto;
    private final AuditService audit;
    private final RevocationService revocation;
    private final IamSettings settings;
    private final Clock clock;

    public MfaService(UserRepository users, RbacRepository rbac, TotpService totp, SecretCrypto crypto, AuditService audit,
                      RevocationService revocation, IamSettings settings, Clock clock) {
        this.users = users;
        this.rbac = rbac;
        this.totp = totp;
        this.crypto = crypto;
        this.audit = audit;
        this.revocation = revocation;
        this.settings = settings;
        this.clock = clock;
    }

    private UserRow user(String username) {
        return users.findByUsernameForUpdate(username).orElseThrow(() -> IamException.notFound("User", username));
    }

    /** Step 1: a new secret, shown once. It is not active until confirmed. */
    @Transactional
    public Enrolment enrol(String username) {
        UserRow u = user(username);
        if (u.mfaEnabled()) throw IamException.conflict("MFA_ALREADY_ENABLED", "Multi-factor authentication is already on; disable it first to enrol again");
        String secret = totp.newSecret();
        users.setMfaPending(u.id(), crypto.encrypt(secret, "mfa-pending:" + u.id()));
        audit.record("MFA_ENROL_STARTED", "USER", u.username(), "SUCCESS");
        return new Enrolment(secret, totp.otpauthUri(settings.mfaIssuerLabel, u.username(), secret));
    }

    /** Step 2: a code from the new secret proves it. Returns the recovery codes, shown once. */
    // a refusal here happens before anything is written, and the failure counter and audit row must survive it
    @Transactional(noRollbackFor = IamException.class)
    public List<String> confirm(String username, String code) {
        UserRow u = user(username);
        if (u.mfaPendingEnc() == null) throw IamException.conflict("MFA_NOT_STARTED", "Start enrolment first");
        String secret = crypto.decrypt(u.mfaPendingEnc(), "mfa-pending:" + u.id());
        TotpService.Result r = totp.verify(secret, code, Instant.now(clock), null);
        if (r.status() != TotpService.Status.OK) {
            audit.record("MFA_FAILED", "USER", u.username(), "FAILURE", "reason", "ENROL_BAD_CODE");
            throw IamException.unprocessable("INVALID_CODE", "The code is not correct");
        }
        users.enableMfa(u.id(), crypto.encrypt(secret, "mfa:" + u.id()), r.step());
        List<String> codes = RecoveryCodes.generate();
        users.replaceRecoveryCodes(u.id(), codes.stream().map(RecoveryCodes::hash).toList());
        audit.record("MFA_ENROLLED", "USER", u.username(), "SUCCESS");
        return codes;
    }

    /** True when the code is right now (used by disable and by new recovery codes); consumes the step. */
    private void requireValidCode(UserRow u, String code) {
        if (!u.mfaEnabled()) throw IamException.conflict("MFA_NOT_ENABLED", "Multi-factor authentication is not on");
        String secret = crypto.decrypt(u.mfaSecretEnc(), "mfa:" + u.id());
        TotpService.Result r = totp.verify(secret, code, Instant.now(clock), u.mfaLastStep());
        if (r.status() != TotpService.Status.OK || !users.useMfaStep(u.id(), r.step())) {
            audit.record("MFA_FAILED", "USER", u.username(), "FAILURE", "reason", r.status() == TotpService.Status.REPLAY ? "MFA_CODE_REPLAYED" : "BAD_MFA_CODE");
            throw IamException.unprocessable("INVALID_CODE", "The code is not correct");
        }
    }

    // a refusal here happens before anything is written, and the failure counter and audit row must survive it
    @Transactional(noRollbackFor = IamException.class)
    public void disable(String username, String code) {
        UserRow u = user(username);
        if (rbac.requiresMfa(u.id(), LocalDate.now(clock.withZone(ZoneOffset.UTC)))) {
            throw IamException.conflict("MFA_REQUIRED_BY_ROLE", "A role you hold requires multi-factor authentication");
        }
        requireValidCode(u, code);
        users.clearMfa(u.id());
        audit.record("MFA_DISABLED", "USER", u.username(), "SUCCESS");
    }

    // a refusal here happens before anything is written, and the failure counter and audit row must survive it
    @Transactional(noRollbackFor = IamException.class)
    public List<String> replaceRecoveryCodes(String username, String code) {
        UserRow u = user(username);
        requireValidCode(u, code);
        List<String> codes = RecoveryCodes.generate();
        users.replaceRecoveryCodes(u.id(), codes.stream().map(RecoveryCodes::hash).toList());
        audit.record("MFA_RECOVERY_CODES_REPLACED", "USER", u.username(), "SUCCESS");
        return codes;
    }

    /** Administrator: clears the user's enrolment; sessions end so a new factor is needed. */
    @Transactional
    public void reset(UserRow target, String actor) {
        if (target.username().equalsIgnoreCase(actor)) throw IamException.conflict("SELF_ACTION_NOT_ALLOWED", "You cannot reset your own multi-factor authentication");
        users.clearMfa(target.id());
        revocation.revokeUser(target.id(), target.username());
        audit.record("MFA_RESET", "USER", target.username(), "SUCCESS");
    }

    public boolean enabled(String username) {
        return users.findByUsername(username).map(UserRow::mfaEnabled).orElse(false);
    }
}
