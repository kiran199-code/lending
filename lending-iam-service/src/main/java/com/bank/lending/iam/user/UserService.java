package com.bank.lending.iam.user;

import com.bank.lending.iam.access.AccessService;
import com.bank.lending.iam.access.RbacModels.*;
import com.bank.lending.iam.access.RbacRepository;
import com.bank.lending.iam.approval.ApprovalService;
import com.bank.lending.iam.audit.AuditService;
import com.bank.lending.iam.auth.CredentialService;
import com.bank.lending.iam.auth.MfaService;
import com.bank.lending.iam.auth.RevocationService;
import com.bank.lending.iam.common.IamException;
import com.bank.lending.iam.common.Validate;
import com.bank.lending.iam.customer.CustomerRepository;
import com.bank.lending.iam.domain.SodChecker;
import com.bank.lending.iam.events.EventService;
import com.bank.lending.iam.user.UserDtos.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Creating and administering people: profile, lifecycle, roles and scopes. */
@Service
public class UserService {
    private static final Set<String> CAN_SUSPEND = Set.of("ACTIVE", "LOCKED", "EXPIRED", "PENDING_ACTIVATION");

    private final UserRepository users;
    private final RbacRepository rbac;
    private final CustomerRepository customers;
    private final CredentialService credentials;
    private final MfaService mfa;
    private final RevocationService revocation;
    private final ApprovalService approvals;
    private final AccessService access;
    private final AuditService audit;
    private final EventService events;
    private final Clock clock;

    public UserService(UserRepository users, RbacRepository rbac, CustomerRepository customers, CredentialService credentials, MfaService mfa,
                       RevocationService revocation, ApprovalService approvals, AccessService access, AuditService audit, EventService events, Clock clock) {
        this.users = users;
        this.rbac = rbac;
        this.customers = customers;
        this.credentials = credentials;
        this.mfa = mfa;
        this.revocation = revocation;
        this.approvals = approvals;
        this.access = access;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    // ------------------------------------------------------------------ reading
    public UserRow row(long id) {
        return users.findById(id).orElseThrow(() -> IamException.notFound("User", String.valueOf(id)));
    }

    public UserRow rowByUsername(String username) {
        return users.findByUsername(username).orElseThrow(() -> IamException.notFound("User", username));
    }

    public UserView view(UserRow u) {
        List<RoleAssignmentView> roles = rbac.activeAssignments(u.id()).stream()
                .map(a -> new RoleAssignmentView(a.roleCode(), a.validFrom(), a.validTo(), a.grantedBy(), a.grantedAt())).toList();
        Map<String, List<String>> scopes = new TreeMap<>();
        for (ScopeRow s : rbac.scopes(u.id())) scopes.computeIfAbsent(s.type(), k -> new ArrayList<>()).add(s.value());
        return new UserView(u.id(), u.username(), u.kind(), u.displayName(), u.email(), u.mobile(), u.customerId(), u.status(), u.mfaEnabled(),
                u.mustChangePassword(), u.passwordChangedAt(), u.lastLoginAt(), u.lockedUntil(), u.bootstrap(), u.createdAt(), u.createdBy(), roles, scopes);
    }

    public PageView<UserView> search(String kind, String status, String text, Integer page, Integer size) {
        int p = Validate.page(page), s = Validate.size(size);
        UserRepository.Page r = users.search(kind, status, text, p, s);
        return new PageView<>(r.rows().stream().map(this::view).toList(), r.total(), p, s);
    }

    public AccessView accessOf(long id) {
        UserRow u = row(id);
        AccessService.Snapshot s = access.forUserRow(u);
        Map<String, List<Map<String, String>>> ceilings = new TreeMap<>();
        s.ceilings().forEach((perm, perRole) -> {
            List<Map<String, String>> rows = new ArrayList<>();
            for (Map<String, BigDecimal> limits : perRole) {
                if (limits.isEmpty()) { rows.add(Map.of("currency", "*", "max", "unlimited")); continue; }
                limits.forEach((cur, max) -> rows.add(Map.of("currency", cur, "max", max.toPlainString())));
            }
            if (perRole.stream().anyMatch(m -> !m.isEmpty())) ceilings.put(perm, rows);
        });
        Map<String, List<String>> scopes = new TreeMap<>();
        s.scopes().forEach((k, v) -> scopes.put(k, new ArrayList<>(v)));
        return new AccessView(u.username(), u.kind(), u.status(), new ArrayList<>(s.roles()), new ArrayList<>(s.permissions()), ceilings, scopes);
    }

    // ------------------------------------------------------------------ create / update
    @Transactional
    public CreatedUser create(CreateUserRequest req, String actor) {
        String username = Validate.username(req.username());
        String kind = Validate.oneOf(req.kind(), "kind", "STAFF", "CUSTOMER");
        String name = Validate.displayName(req.displayName());
        String email = Validate.email(req.email(), true);
        String mobile = Validate.mobile(req.mobile());
        String customerId = req.customerId() == null || req.customerId().isBlank() ? null : Validate.ref(req.customerId(), "customerId");
        if ("CUSTOMER".equals(kind) && customerId == null) throw IamException.badRequest("CUSTOMER_ID_REQUIRED", "A customer user needs a customerId");
        if ("STAFF".equals(kind) && customerId != null) throw IamException.badRequest("VALIDATION_FAILED", "A staff user has no customerId");
        if (users.findByUsername(username).isPresent()) throw IamException.conflict("USERNAME_TAKEN", "That username is already used (usernames are never reused)");

        long id = users.insert(username, kind, name, email, mobile, customerId, "CUSTOMER".equals(kind) ? "PENDING_ACTIVATION" : "ACTIVE",
                null, null, false, false, actor);
        UserRow u = row(id);
        CredentialService.IssuedSecret secret;
        if ("CUSTOMER".equals(kind)) {
            rbac.replaceAssignments(id, List.of(new RoleGrant("CUSTOMER_SELF", today(), null)), actor);
            secret = credentials.issueActivationToken(u);
        } else {
            secret = credentials.issueTemporaryPassword(u);
        }
        audit.record("USER_CREATED", "USER", username, "SUCCESS", "kind", kind, "displayName", name, "email", email, "customerId", customerId);
        events.publish(EventService.USER_CREATED, "user:" + username, "username", username, "kind", kind, "customerId", customerId);
        return new CreatedUser(view(row(id)), secret.kind(), secret.value(), secret.expiresAt());
    }

    @Transactional
    public UserView update(long id, UpdateUserRequest req) {
        UserRow u = row(id);
        if ("OFFBOARDED".equals(u.status())) throw IamException.conflict("INVALID_STATE", "An offboarded user cannot be changed");
        String name = Validate.displayName(req.displayName());
        String email = Validate.email(req.email(), true);
        String mobile = Validate.mobile(req.mobile());
        users.updateProfile(id, name, email, mobile);
        audit.record("USER_UPDATED", "USER", u.username(), "SUCCESS",
                "before", mapOf("displayName", u.displayName(), "email", u.email(), "mobile", u.mobile()),
                "after", mapOf("displayName", name, "email", email, "mobile", mobile));
        return view(row(id));
    }

    // ------------------------------------------------------------------ lifecycle
    private void statusChange(UserRow u, String to, String action) {
        users.setStatus(u.id(), to);
        audit.record(action, "USER", u.username(), "SUCCESS", "from", u.status(), "to", to);
        events.publish(EventService.USER_STATUS_CHANGED, "user:" + u.username(), "username", u.username(), "from", u.status(), "to", to);
    }

    private static void notSelf(UserRow u, String actor, String what) {
        if (u.username().equalsIgnoreCase(actor)) throw IamException.conflict("SELF_ACTION_NOT_ALLOWED", "You cannot " + what + " your own account");
    }

    @Transactional
    public UserView suspend(long id, String actor) {
        UserRow u = users.findByIdForUpdate(id).orElseThrow(() -> IamException.notFound("User", String.valueOf(id)));
        notSelf(u, actor, "suspend");
        if (!CAN_SUSPEND.contains(u.status())) throw IamException.conflict("INVALID_STATE", "A " + u.status() + " user cannot be suspended");
        statusChange(u, "SUSPENDED", "USER_SUSPENDED");
        revocation.revokeUser(u.id(), u.username());
        access.invalidateAll();
        return view(row(id));
    }

    @Transactional
    public UserView reinstate(long id) {
        UserRow u = users.findByIdForUpdate(id).orElseThrow(() -> IamException.notFound("User", String.valueOf(id)));
        if (!"SUSPENDED".equals(u.status())) throw IamException.conflict("INVALID_STATE", "Only a SUSPENDED user can be reinstated");
        // a user who never set a password goes back to waiting for activation
        statusChange(u, u.passwordHash() == null ? "PENDING_ACTIVATION" : "ACTIVE", "USER_REINSTATED");
        users.unlock(id);
        access.invalidateAll();
        return view(row(id));
    }

    @Transactional
    public UserView unlock(long id) {
        UserRow u = users.findByIdForUpdate(id).orElseThrow(() -> IamException.notFound("User", String.valueOf(id)));
        if (!"LOCKED".equals(u.status()) && u.failedAttempts() == 0) throw IamException.conflict("INVALID_STATE", "The user is not locked");
        users.unlock(id);
        audit.record("USER_UNLOCKED", "USER", u.username(), "SUCCESS");
        events.publish(EventService.USER_STATUS_CHANGED, "user:" + u.username(), "username", u.username(), "from", u.status(), "to", "LOCKED".equals(u.status()) ? "ACTIVE" : u.status());
        access.invalidateAll();
        return view(row(id));
    }

    @Transactional
    public UserView offboard(long id, String actor) {
        UserRow u = users.findByIdForUpdate(id).orElseThrow(() -> IamException.notFound("User", String.valueOf(id)));
        notSelf(u, actor, "offboard");
        if ("OFFBOARDED".equals(u.status())) throw IamException.conflict("INVALID_STATE", "The user is already offboarded");
        rbac.endAllAssignments(id);
        int withdrawn = approvals.cancelAllOf(u.username(), "Maker offboarded");
        int links = u.customer() ? customers.revokeAll(id, actor, "User offboarded") : 0;
        users.invalidateTokens(id, "TEMP_PASSWORD");
        users.invalidateTokens(id, "ACTIVATION");
        statusChange(u, "OFFBOARDED", "USER_OFFBOARDED");
        revocation.revokeUser(id, u.username());
        audit.record("USER_OFFBOARD_DETAIL", "USER", u.username(), "SUCCESS", "pendingActionsCancelled", withdrawn, "linksRevoked", links);
        access.invalidateAll();
        return view(row(id));
    }

    @Transactional
    public UserView expirePassword(long id) {
        UserRow u = users.findByIdForUpdate(id).orElseThrow(() -> IamException.notFound("User", String.valueOf(id)));
        if (u.passwordHash() == null || "OFFBOARDED".equals(u.status()) || "SUSPENDED".equals(u.status())) {
            throw IamException.conflict("INVALID_STATE", "The password of a " + u.status() + " user cannot be expired");
        }
        users.forcePasswordChange(id);
        audit.record("PASSWORD_EXPIRED_BY_ADMIN", "USER", u.username(), "SUCCESS");
        return view(row(id));
    }

    @Transactional
    public SecretView resetPassword(long id, String actor) {
        UserRow u = users.findByIdForUpdate(id).orElseThrow(() -> IamException.notFound("User", String.valueOf(id)));
        notSelf(u, actor, "reset the password of");
        if ("OFFBOARDED".equals(u.status()) || "SUSPENDED".equals(u.status())) throw IamException.conflict("INVALID_STATE", "Reinstate the user before resetting the password");
        CredentialService.IssuedSecret s = "PENDING_ACTIVATION".equals(u.status()) ? credentials.issueActivationToken(u) : credentials.issueTemporaryPassword(u);
        revocation.revokeUser(id, u.username());
        audit.record("PASSWORD_RESET", "USER", u.username(), "SUCCESS", "issued", s.kind(), "expiresAt", s.expiresAt().toString());
        return new SecretView(s.kind(), s.value(), s.expiresAt());
    }

    @Transactional
    public UserView resetMfa(long id, String actor) {
        UserRow u = users.findByIdForUpdate(id).orElseThrow(() -> IamException.notFound("User", String.valueOf(id)));
        mfa.reset(u, actor);
        access.invalidateAll();
        return view(row(id));
    }

    // ------------------------------------------------------------------ roles and scopes
    @Transactional
    public UserView assignRoles(long id, AssignRolesRequest req, String actor) {
        UserRow u = users.findByIdForUpdate(id).orElseThrow(() -> IamException.notFound("User", String.valueOf(id)));
        notSelf(u, actor, "change the roles of");
        if ("OFFBOARDED".equals(u.status())) throw IamException.conflict("INVALID_STATE", "An offboarded user cannot be given roles");
        List<RoleGrant> grants = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (UserDtos.RoleGrantRequest g : req.roles()) {
            String code = Validate.code(g.roleCode(), "roleCode");
            if (!seen.add(code)) throw IamException.badRequest("VALIDATION_FAILED", "Role " + code + " is listed twice");
            RoleRow role = rbac.role(code).orElseThrow(() -> IamException.unprocessable("UNKNOWN_ROLE", "Unknown role " + code));
            if (!"ACTIVE".equals(role.status())) throw IamException.unprocessable("ROLE_RETIRED", "Role " + code + " is retired");
            String expectedKind = "CUSTOMER".equals(u.kind()) ? "CUSTOMER" : "STAFF";
            if (!expectedKind.equals(role.kind())) {
                throw IamException.unprocessable("ROLE_KIND_MISMATCH", "Role " + code + " is a " + role.kind() + " role and cannot be given to a " + u.kind() + " user");
            }
            if (g.validFrom() != null && g.validTo() != null && g.validTo().isBefore(g.validFrom())) {
                throw IamException.badRequest("VALIDATION_FAILED", "validTo is before validFrom for " + code);
            }
            grants.add(new RoleGrant(code, g.validFrom() == null ? today() : g.validFrom(), g.validTo()));
        }
        // separation of duties over the whole resulting set (assignments that are dated in the future count too: they will be held)
        Map<String, Set<String>> byRole = rbac.permissionsByRole();
        Set<String> perms = SodChecker.union(grants.stream().map(RoleGrant::roleCode).toList(), byRole);
        List<SodChecker.Violation> v = SodChecker.violations(perms, rbac.sodRules());
        if (!v.isEmpty()) {
            throw IamException.conflict("SOD_CONFLICT", "These roles together break a separation-of-duties rule: " + SodChecker.describe(v),
                    v.stream().map(x -> x.permissionA() + " + " + x.permissionB() + ": " + x.description()).toList());
        }
        List<String> before = rbac.activeAssignments(id).stream().map(Assignment::roleCode).toList();
        rbac.replaceAssignments(id, grants, actor);
        List<String> after = grants.stream().map(RoleGrant::roleCode).sorted().toList();
        audit.record("ROLES_ASSIGNED", "USER", u.username(), "SUCCESS", "before", before, "after", after,
                "added", after.stream().filter(x -> !before.contains(x)).toList(), "removed", before.stream().filter(x -> !after.contains(x)).toList());
        events.publish(EventService.ROLES_CHANGED, "user:" + u.username(), "username", u.username(), "roles", after);
        access.invalidateAll();
        return view(row(id));
    }

    @Transactional
    public UserView assignScopes(long id, AssignScopesRequest req, String actor) {
        UserRow u = users.findByIdForUpdate(id).orElseThrow(() -> IamException.notFound("User", String.valueOf(id)));
        notSelf(u, actor, "change the scopes of");
        if ("OFFBOARDED".equals(u.status())) throw IamException.conflict("INVALID_STATE", "An offboarded user cannot be given scopes");
        List<ScopeRow> scopes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ScopeRequest s : req.scopes()) {
            String type = Validate.oneOf(s.type(), "type", "BRANCH", "PRODUCT", "CURRENCY");
            String value = "CURRENCY".equals(type) ? Validate.currency(s.value()) : Validate.scopeValue(s.value());
            if (seen.add(type + "|" + value)) scopes.add(new ScopeRow(type, value));
        }
        // "*" next to named values would hide them: refuse the ambiguity
        for (String type : List.of("BRANCH", "PRODUCT", "CURRENCY")) {
            long star = scopes.stream().filter(s -> s.type().equals(type) && s.value().equals("*")).count();
            long named = scopes.stream().filter(s -> s.type().equals(type) && !s.value().equals("*")).count();
            if (star > 0 && named > 0) throw IamException.badRequest("VALIDATION_FAILED", "Scope type " + type + " lists * and named values; use one or the other");
        }
        Map<String, List<String>> before = new LinkedHashMap<>();
        for (ScopeRow s : rbac.scopes(id)) before.computeIfAbsent(s.type(), k -> new ArrayList<>()).add(s.value());
        rbac.replaceScopes(id, scopes);
        Map<String, List<String>> after = new LinkedHashMap<>();
        for (ScopeRow s : scopes) after.computeIfAbsent(s.type(), k -> new ArrayList<>()).add(s.value());
        audit.record("SCOPES_ASSIGNED", "USER", u.username(), "SUCCESS", "before", before, "after", after);
        events.publish(EventService.ROLES_CHANGED, "user:" + u.username(), "username", u.username(), "scopesChanged", true);
        access.invalidateAll();
        return view(row(id));
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }
}
