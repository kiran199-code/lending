package com.bank.lending.iam.access;

import com.bank.lending.iam.access.RbacModels.*;
import com.bank.lending.iam.access.RoleDtos.*;
import com.bank.lending.iam.audit.AuditService;
import com.bank.lending.iam.common.IamException;
import com.bank.lending.iam.common.Validate;
import com.bank.lending.iam.domain.SodChecker;
import com.bank.lending.iam.events.EventService;
import com.bank.lending.iam.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Roles, their permissions and ceilings, and the separation-of-duties rules. */
@Service
public class RoleService {
    private static final Pattern ROLE_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{2,39}$");
    private static final Map<String, String> KIND_LETTER = Map.of("STAFF", "S", "CUSTOMER", "C", "SERVICE", "V");

    private final RbacRepository rbac;
    private final UserRepository users;
    private final AuthzService authz;
    private final AccessService access;
    private final AuditService audit;
    private final EventService events;
    private final Clock clock;

    public RoleService(RbacRepository rbac, UserRepository users, AuthzService authz, AccessService access, AuditService audit, EventService events, Clock clock) {
        this.rbac = rbac;
        this.users = users;
        this.authz = authz;
        this.access = access;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    // ------------------------------------------------------------------ reading
    public List<PermissionView> permissions() {
        return rbac.permissions().stream().map(p -> new PermissionView(p.code(), p.module(), p.description(), p.risk(), p.requiresMfa(), p.appliesTo())).toList();
    }

    public List<RoleView> roles() {
        Map<String, Set<String>> perms = rbac.permissionsByRole();
        return rbac.roles().stream().map(r -> toView(r, perms.getOrDefault(r.code(), Set.of()), List.of())).toList();
    }

    public RoleView role(String code) {
        RoleRow r = rbac.role(code).orElseThrow(() -> IamException.notFound("Role", code));
        return toView(r, rbac.rolePermissions(code), rbac.roleLimits(code));
    }

    private static RoleView toView(RoleRow r, Set<String> perms, List<LimitRow> limits) {
        return new RoleView(r.code(), r.name(), r.kind(), r.description(), r.mfaRequired(), r.systemRole(), r.status(), new ArrayList<>(new TreeSet<>(perms)),
                limits.stream().map(l -> new LimitView(l.permissionCode(), l.currency(), l.maxAmount())).toList());
    }

    // ------------------------------------------------------------------ roles
    @Transactional
    public RoleView create(CreateRoleRequest r) {
        String code = r.code() == null ? "" : r.code().trim();
        if (!ROLE_CODE.matcher(code).matches()) throw IamException.badRequest("VALIDATION_FAILED", "code must be upper case letters, digits and underscore, 3 to 40 characters, starting with a letter");
        String kind = Validate.oneOf(r.kind(), "kind", "STAFF", "CUSTOMER", "SERVICE");
        if (rbac.role(code).isPresent()) throw IamException.conflict("ROLE_EXISTS", "A role with this code exists");
        String description = Validate.text(r.description(), "description", 300, false);
        rbac.insertRole(code, Validate.text(r.name(), "name", 80, true), kind, description == null ? "" : description, r.mfaRequired() != null && r.mfaRequired());
        if (r.permissions() != null && !r.permissions().isEmpty()) applyPermissions(code, kind, new TreeSet<>(r.permissions()), true);
        audit.record("ROLE_CREATED", "ROLE", code, "SUCCESS", "kind", kind, "permissions", r.permissions() == null ? List.of() : new TreeSet<>(r.permissions()));
        return role(code);
    }

    @Transactional
    public RoleView update(String code, UpdateRoleRequest r) {
        RoleRow old = rbac.role(code).orElseThrow(() -> IamException.notFound("Role", code));
        if (old.systemRole()) throw IamException.conflict("SYSTEM_ROLE", "A system role cannot be edited");
        String status = Validate.oneOf(r.status(), "status", "ACTIVE", "RETIRED");
        rbac.updateRole(code, Validate.text(r.name(), "name", 80, true), r.description() == null ? "" : r.description().trim(), r.mfaRequired(), status);
        audit.record("ROLE_UPDATED", "ROLE", code, "SUCCESS",
                "before", Map.of("name", old.name(), "mfaRequired", old.mfaRequired(), "status", old.status()),
                "after", Map.of("name", r.name(), "mfaRequired", r.mfaRequired(), "status", status),
                "holders", rbac.holdersOfRole(code, today()).size());
        access.invalidateAll();
        return role(code);
    }

    @Transactional
    public RoleView replacePermissions(String code, ReplacePermissionsRequest r) {
        RoleRow role = rbac.role(code).orElseThrow(() -> IamException.notFound("Role", code));
        if (role.systemRole()) throw IamException.conflict("SYSTEM_ROLE", "A system role cannot be edited");
        Set<String> before = rbac.rolePermissions(code);
        Set<String> after = new TreeSet<>(r.permissions());
        applyPermissions(code, role.kind(), after, false);
        audit.record("ROLE_PERMISSIONS_CHANGED", "ROLE", code, "SUCCESS", "added", after.stream().filter(p -> !before.contains(p)).toList(),
                "removed", before.stream().filter(p -> !after.contains(p)).toList());
        events.publish(EventService.ROLES_CHANGED, "role:" + code, "role", code, "permissionsChanged", true);
        access.invalidateAll();
        return role(code);
    }

    /** Validates against the catalogue, the role's kind and separation of duties (for the role itself and for each current holder). */
    private void applyPermissions(String code, String kind, Set<String> perms, boolean creating) {
        Map<String, PermissionRow> catalogue = authz.permissions();
        for (String p : perms) {
            PermissionRow pr = catalogue.get(p);
            if (pr == null) throw IamException.unprocessable("UNKNOWN_PERMISSION", "Unknown permission " + p);
            if (!pr.appliesTo().contains(KIND_LETTER.get(kind))) {
                throw IamException.unprocessable("PERMISSION_KIND_MISMATCH", p + " cannot be given to a " + kind + " role");
            }
        }
        List<SodChecker.Rule> rules = rbac.sodRules();
        List<SodChecker.Violation> own = SodChecker.violations(perms, rules);
        if (!own.isEmpty()) {
            throw IamException.conflict("SOD_CONFLICT", "The role would hold both sides of a separation-of-duties rule: " + SodChecker.describe(own));
        }
        if (!creating) {
            Map<String, Set<String>> byRole = new HashMap<>(rbac.permissionsByRole());
            byRole.put(code, perms);
            List<String> breaching = new ArrayList<>();
            Map<Long, List<String>> holders = new HashMap<>();
            for (Long uid : rbac.holdersOfRole(code, today())) holders.put(uid, rbac.activeRoleCodes(uid, today()));
            holders.forEach((uid, roles) -> {
                if (!SodChecker.violations(SodChecker.union(roles, byRole), rules).isEmpty()) {
                    breaching.add(users.findById(uid).map(u -> u.username()).orElse(String.valueOf(uid)));
                }
            });
            if (!breaching.isEmpty()) {
                throw IamException.conflict("SOD_CONFLICT", "A current holder of this role would break a separation-of-duties rule", breaching);
            }
        }
        rbac.replaceRolePermissions(code, perms);
    }

    @Transactional
    public RoleView replaceLimits(String code, ReplaceLimitsRequest r) {
        rbac.role(code).orElseThrow(() -> IamException.notFound("Role", code));
        Set<String> perms = rbac.rolePermissions(code);
        List<LimitRow> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (LimitRequest l : r.limits()) {
            if (!perms.contains(l.permission())) throw IamException.unprocessable("PERMISSION_NOT_IN_ROLE", "The role does not hold " + l.permission());
            String cur = Validate.currency(l.currency());
            if (l.maxAmount() == null || l.maxAmount().signum() < 0) throw IamException.badRequest("VALIDATION_FAILED", "maxAmount must be zero or more");
            if (!seen.add(l.permission() + "|" + cur)) throw IamException.badRequest("VALIDATION_FAILED", "Duplicate limit for " + l.permission() + " " + cur);
            rows.add(new LimitRow(code, l.permission(), cur, l.maxAmount()));
        }
        List<LimitRow> before = rbac.roleLimits(code);
        rbac.replaceRoleLimits(code, rows);
        audit.record("ROLE_LIMITS_CHANGED", "ROLE", code, "SUCCESS", "before", before.stream().map(x -> x.permissionCode() + " " + x.currency() + " " + x.maxAmount().toPlainString()).toList(),
                "after", rows.stream().map(x -> x.permissionCode() + " " + x.currency() + " " + x.maxAmount().toPlainString()).toList());
        events.publish(EventService.ROLES_CHANGED, "role:" + code, "role", code, "limitsChanged", true);
        access.invalidateAll();
        return role(code);
    }

    // ------------------------------------------------------------------ separation-of-duties rules
    public List<SodRuleView> sodRules() {
        return rbac.sodRules().stream().map(x -> new SodRuleView(x.ruleId(), x.permissionA(), x.permissionB(), x.description())).toList();
    }

    @Transactional
    public SodRuleView addSodRule(CreateSodRuleRequest r) {
        Map<String, PermissionRow> catalogue = authz.permissions();
        if (!catalogue.containsKey(r.permissionA()) || !catalogue.containsKey(r.permissionB())) throw IamException.unprocessable("UNKNOWN_PERMISSION", "Both permissions must be in the catalogue");
        if (r.permissionA().equals(r.permissionB())) throw IamException.unprocessable("SAME_PERMISSION", "A rule needs two different permissions");
        String a = r.permissionA().compareTo(r.permissionB()) < 0 ? r.permissionA() : r.permissionB();
        String b = a.equals(r.permissionA()) ? r.permissionB() : r.permissionA();
        if (rbac.sodRules().stream().anyMatch(x -> x.permissionA().equals(a) && x.permissionB().equals(b))) throw IamException.conflict("RULE_EXISTS", "This rule exists");
        // refused while someone already holds both
        SodChecker.Rule candidate = new SodChecker.Rule(0, a, b, r.description());
        Map<String, Set<String>> byRole = rbac.permissionsByRole();
        List<String> breaching = new ArrayList<>();
        rbac.allCurrentRoles(today()).forEach((uid, roles) -> {
            if (!SodChecker.violations(SodChecker.union(roles, byRole), List.of(candidate)).isEmpty()) {
                breaching.add(users.findById(uid).map(u -> u.username()).orElse(String.valueOf(uid)));
            }
        });
        if (!breaching.isEmpty()) throw IamException.conflict("SOD_CONFLICT", "Current holders already hold both permissions: change their roles first", breaching);
        long id = rbac.insertSodRule(a, b, Validate.text(r.description(), "description", 200, true));
        audit.record("SOD_RULE_ADDED", "SOD_RULE", String.valueOf(id), "SUCCESS", "a", a, "b", b);
        return new SodRuleView(id, a, b, r.description());
    }

    @Transactional
    public void deleteSodRule(long id) {
        SodChecker.Rule rule = rbac.sodRule(id).orElseThrow(() -> IamException.notFound("Separation-of-duties rule", String.valueOf(id)));
        rbac.deleteSodRule(id);
        audit.record("SOD_RULE_REMOVED", "SOD_RULE", String.valueOf(id), "SUCCESS", "a", rule.permissionA(), "b", rule.permissionB());
    }
}
