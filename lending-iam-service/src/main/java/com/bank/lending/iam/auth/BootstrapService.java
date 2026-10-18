package com.bank.lending.iam.auth;

import com.bank.lending.iam.access.RbacModels.RoleGrant;
import com.bank.lending.iam.access.RbacRepository;
import com.bank.lending.iam.audit.AuditService;
import com.bank.lending.iam.client.ClientRepository;
import com.bank.lending.iam.common.SecretHasher;
import com.bank.lending.iam.common.Validate;
import com.bank.lending.iam.config.IamSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.bank.lending.iam.domain.PasswordPolicy;
import com.bank.lending.iam.user.UserRepository;
import com.bank.lending.iam.user.UserRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Start-up administrator provisioning, in two cases and only when the bootstrap settings are present:
 * <ol>
 *   <li><b>First start</b> (no user at all): creates one administrator holding the three identity roles, flagged BOOTSTRAP
 *       in the audit trail, and the command-line sign-in client.</li>
 *   <li><b>Recovery</b> (users exist, but nobody who can administer users is able to sign in: no ACTIVE, EXPIRED or LOCKED
 *       user holds a current IAM_USER_ADMIN assignment): the bootstrap account is put back as it was on the first start,
 *       with the configured password, its second factor cleared (it enrols again at the next sign-in) and the three roles.
 *       If it never existed it is created, unless its name belongs to somebody else.</li>
 * </ol>
 * This is the one place that does not apply the separation-of-duties rules (the three roles together break two of them),
 * which is why the runbook requires two named administrators and then suspends this account and removes the settings.
 * Whoever can set the environment of the service can do this; that is the same trust as holding the encryption key.
 */
@Service
public class BootstrapService {
    /** The command-line sign-in client that lets the first administrator obtain a token before any console exists (see tools/iam_token.py). */
    public static final String ADMIN_CLI = "iam-admin-cli";

    private static final Logger log = LoggerFactory.getLogger(BootstrapService.class);
    private static final List<String> IDENTITY_ROLES = List.of("IAM_USER_ADMIN", "IAM_ACCESS_ADMIN", "IAM_SECURITY_ADMIN");

    private final UserRepository users;
    private final ClientRepository clients;
    private final RbacRepository rbac;
    private final SecretHasher hasher;
    private final PasswordPolicy policy;
    private final AuditService audit;
    private final RevocationService revocation;
    private final IamSettings settings;
    private final Clock clock;

    public BootstrapService(UserRepository users, ClientRepository clients, RbacRepository rbac, SecretHasher hasher, PasswordPolicy policy, AuditService audit, RevocationService revocation, IamSettings settings, Clock clock) {
        this.users = users;
        this.clients = clients;
        this.rbac = rbac;
        this.hasher = hasher;
        this.policy = policy;
        this.audit = audit;
        this.revocation = revocation;
        this.settings = settings;
        this.clock = clock;
    }

    /** @return true when the bootstrap administrator was created or recovered */
    @Transactional
    public boolean run() {
        String name = settings.bootstrapUsername;
        String pw = settings.bootstrapPassword;
        boolean configured = name != null && !name.isBlank() && pw != null && !pw.isBlank();
        users.lockBootstrap();   // two instances starting together: one at a time
        boolean empty = users.count() == 0;
        if (!configured) {
            if (empty) log.warn("There is no user and no bootstrap administrator is configured (IAM_BOOTSTRAP_USERNAME / IAM_BOOTSTRAP_PASSWORD): nobody can sign in.");
            return false;
        }
        String username = Validate.username(name);
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (!empty && canSomeoneAdminister(today)) return false;

        List<PasswordPolicy.Violation> v = policy.check(pw, username, List.of(), hasher);
        if (!v.isEmpty()) {
            throw new IllegalStateException("IAM_BOOTSTRAP_PASSWORD does not meet the password policy: " + v.stream().map(PasswordPolicy.Violation::message).toList());
        }
        Instant now = Instant.now(clock);
        String hash = hasher.hash(pw);
        Optional<UserRow> existing = users.findByUsername(username);
        long id;
        if (existing.isPresent()) {
            UserRow u = existing.get();
            if (!u.bootstrap()) {
                log.error("Nobody can administer users, but the bootstrap name '{}' belongs to an ordinary account; choose another IAM_BOOTSTRAP_USERNAME.", username);
                return false;
            }
            id = u.id();
            users.setPassword(id, hash, now, false, "ACTIVE");
            users.addPasswordHistory(id, hash);
            users.clearMfa(id);
            revocation.revokeUser(id, username);
        } else {
            id = users.insert(username, "STAFF", "Bootstrap administrator", Validate.email(settings.bootstrapEmail, true), null, null, "ACTIVE", hash, now, false, true, "bootstrap");
            users.addPasswordHistory(id, hash);
        }
        rbac.replaceAssignments(id, IDENTITY_ROLES.stream().map(r -> new RoleGrant(r, today, null)).toList(), "bootstrap");
        audit.recordAs("SYSTEM", "BOOTSTRAP", existing.isPresent() ? "BOOTSTRAP_RECOVERY" : "USER_CREATED", "USER", username, "SUCCESS", "bootstrap", true,
                "roles", IDENTITY_ROLES,
                "note", existing.isPresent()
                        ? "Nobody who administers users could sign in: the bootstrap account was restored from configuration. Create named administrators, suspend it and remove the settings."
                        : "First administrator created from configuration. Create two named administrators and suspend this account.");
        if (empty && clients.find(ADMIN_CLI).isEmpty()) {
            // a public client for the authorization code flow with PKCE; the redirect goes to a local port, so the code never leaves the administrator's machine
            clients.insert(ADMIN_CLI, "Administrator command line", "PUBLIC", null, List.of("authorization_code", "refresh_token"),
                    List.of("http://127.0.0.1:8765/callback"), List.of(), List.of("openid", "profile"),
                    settings.staffAccessSeconds, settings.staffRefreshSeconds, "bootstrap");
            audit.recordAs("SYSTEM", "BOOTSTRAP", "CLIENT_CREATED", "CLIENT", ADMIN_CLI, "SUCCESS", "kind", "PUBLIC", "redirects", "127.0.0.1:8765 only");
        }
        return true;
    }

    /** True when some user who may sign in (or will be able to shortly: expired password, a lock that times out) holds IAM_USER_ADMIN now. */
    private boolean canSomeoneAdminister(LocalDate today) {
        for (long uid : rbac.holdersOfRole("IAM_USER_ADMIN", today)) {
            String status = users.findById(uid).map(UserRow::status).orElse("");
            if (status.equals("ACTIVE") || status.equals("EXPIRED") || status.equals("LOCKED")) return true;
        }
        return false;
    }
}
