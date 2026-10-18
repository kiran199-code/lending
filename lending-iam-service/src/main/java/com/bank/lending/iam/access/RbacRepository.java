package com.bank.lending.iam.access;

import com.bank.lending.iam.access.RbacModels.*;
import com.bank.lending.iam.common.Db;
import com.bank.lending.iam.domain.SodChecker;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

@Repository
public class RbacRepository {
    static final RowMapper<RoleRow> ROLE = (rs, i) -> new RoleRow(rs.getString("role_code"), rs.getString("role_name"),
            rs.getString("role_kind"), rs.getString("description"), rs.getBoolean("mfa_required"), rs.getBoolean("system_role"), rs.getString("status"));
    static final RowMapper<PermissionRow> PERM = (rs, i) -> new PermissionRow(rs.getString("permission_code"), rs.getString("module"),
            rs.getString("description"), rs.getString("risk_level"), rs.getBoolean("requires_mfa"), rs.getString("applies_to"));
    static final RowMapper<LimitRow> LIMIT = (rs, i) -> new LimitRow(rs.getString("role_code"), rs.getString("permission_code"),
            rs.getString("currency"), Db.decimal(rs, "max_amount"));

    private final JdbcTemplate jdbc;

    public RbacRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ catalogue
    public List<PermissionRow> permissions() {
        return jdbc.query("SELECT permission_code, module, description, risk_level, requires_mfa, applies_to FROM permission ORDER BY module, permission_code", PERM);
    }

    // ------------------------------------------------------------------ roles
    public List<RoleRow> roles() {
        return jdbc.query("SELECT role_code, role_name, role_kind, description, mfa_required, system_role, status FROM role ORDER BY role_kind, role_code", ROLE);
    }

    public Optional<RoleRow> role(String code) {
        return Db.one(jdbc, "SELECT role_code, role_name, role_kind, description, mfa_required, system_role, status FROM role WHERE role_code = ?", ROLE, code);
    }

    public void insertRole(String code, String name, String kind, String description, boolean mfaRequired) {
        jdbc.update("INSERT INTO role (role_code, role_name, role_kind, description, mfa_required, system_role) VALUES (?,?,?,?,?,FALSE)",
                code, name, kind, description, mfaRequired);
    }

    public void updateRole(String code, String name, String description, boolean mfaRequired, String status) {
        jdbc.update("UPDATE role SET role_name = ?, description = ?, mfa_required = ?, status = ?, updated_at = now() WHERE role_code = ?",
                name, description, mfaRequired, status, code);
    }

    public Set<String> rolePermissions(String code) {
        return new TreeSet<>(jdbc.query("SELECT permission_code FROM role_permission WHERE role_code = ?", (rs, i) -> rs.getString("permission_code"), code));
    }

    public Map<String, Set<String>> permissionsByRole() {
        Map<String, Set<String>> m = new HashMap<>();
        jdbc.query("SELECT role_code, permission_code FROM role_permission", (rs, i) -> {
            m.computeIfAbsent(rs.getString("role_code"), k -> new TreeSet<>()).add(rs.getString("permission_code"));
            return null;
        });
        return m;
    }

    /** Adds and removes only the difference, so the ceilings of permissions that stay are kept. */
    public void replaceRolePermissions(String code, Set<String> perms) {
        Set<String> current = rolePermissions(code);
        for (String p : current) {
            if (!perms.contains(p)) jdbc.update("DELETE FROM role_permission WHERE role_code = ? AND permission_code = ?", code, p); // its ceilings cascade
        }
        for (String p : perms) {
            if (!current.contains(p)) jdbc.update("INSERT INTO role_permission (role_code, permission_code) VALUES (?,?)", code, p);
        }
        jdbc.update("UPDATE role SET updated_at = now() WHERE role_code = ?", code);
    }

    public List<LimitRow> roleLimits(String code) {
        return jdbc.query("SELECT role_code, permission_code, currency, max_amount FROM permission_limit WHERE role_code = ? ORDER BY permission_code, currency", LIMIT, code);
    }

    public void replaceRoleLimits(String code, List<LimitRow> limits) {
        jdbc.update("DELETE FROM permission_limit WHERE role_code = ?", code);
        for (LimitRow l : limits) {
            jdbc.update("INSERT INTO permission_limit (role_code, permission_code, currency, max_amount) VALUES (?,?,?,?)",
                    code, l.permissionCode(), l.currency(), l.maxAmount());
        }
        jdbc.update("UPDATE role SET updated_at = now() WHERE role_code = ?", code);
    }

    // ------------------------------------------------------------------ assignments
    public List<Assignment> activeAssignments(long userId) {
        return jdbc.query("SELECT assignment_id, user_id, role_code, valid_from, valid_to, granted_by, granted_at FROM user_role "
                        + "WHERE user_id = ? AND ended_at IS NULL ORDER BY role_code",
                (rs, i) -> new Assignment(rs.getLong("assignment_id"), rs.getLong("user_id"), rs.getString("role_code"),
                        Db.date(rs, "valid_from"), Db.date(rs, "valid_to"), rs.getString("granted_by"), Db.instant(rs, "granted_at")), userId);
    }

    /** Ends the user's current assignments and records the new set. The history stays in the table. */
    public void replaceAssignments(long userId, List<RoleGrant> grants, String grantedBy) {
        jdbc.update("UPDATE user_role SET ended_at = now() WHERE user_id = ? AND ended_at IS NULL", userId);
        for (RoleGrant g : grants) {
            jdbc.update("INSERT INTO user_role (user_id, role_code, valid_from, valid_to, granted_by) VALUES (?,?,?,?,?)",
                    userId, g.roleCode(), g.validFrom() == null ? LocalDate.now() : g.validFrom(), g.validTo(), grantedBy);
        }
    }

    public void endAllAssignments(long userId) {
        // valid_to may not precede valid_from (an assignment that was scheduled to start later is simply ended before it began)
        jdbc.update("UPDATE user_role SET ended_at = now(), valid_to = GREATEST(valid_from, LEAST(coalesce(valid_to, CURRENT_DATE), CURRENT_DATE)) WHERE user_id = ? AND ended_at IS NULL", userId);
    }

    /** Users who hold the role now (active assignment, within its dates, user not offboarded). */
    public List<Long> holdersOfRole(String roleCode, LocalDate today) {
        return jdbc.query("SELECT DISTINCT ur.user_id FROM user_role ur JOIN app_user u ON u.user_id = ur.user_id "
                        + "WHERE ur.role_code = ? AND ur.ended_at IS NULL AND ur.valid_from <= ? AND (ur.valid_to IS NULL OR ur.valid_to >= ?) AND u.status <> 'OFFBOARDED'",
                (rs, i) -> rs.getLong("user_id"), roleCode, today, today);
    }

    /** Current role codes of every user who holds any (for checking a new separation-of-duties rule). */
    public Map<Long, List<String>> allCurrentRoles(LocalDate today) {
        Map<Long, List<String>> m = new LinkedHashMap<>();
        jdbc.query("SELECT ur.user_id, ur.role_code FROM user_role ur JOIN app_user u ON u.user_id = ur.user_id WHERE ur.ended_at IS NULL AND ur.valid_from <= ? "
                + "AND (ur.valid_to IS NULL OR ur.valid_to >= ?) AND u.status <> 'OFFBOARDED' ORDER BY ur.user_id", (rs, i) -> {
            m.computeIfAbsent(rs.getLong("user_id"), k -> new ArrayList<>()).add(rs.getString("role_code"));
            return null;
        }, today, today);
        return m;
    }

    public List<String> activeRoleCodes(long userId, LocalDate today) {
        return jdbc.query("SELECT DISTINCT role_code FROM user_role WHERE user_id = ? AND ended_at IS NULL AND valid_from <= ? AND (valid_to IS NULL OR valid_to >= ?) ORDER BY role_code",
                (rs, i) -> rs.getString("role_code"), userId, today, today);
    }

    // ------------------------------------------------------------------ scopes
    public List<ScopeRow> scopes(long userId) {
        return jdbc.query("SELECT scope_type, scope_value FROM user_scope WHERE user_id = ? ORDER BY scope_type, scope_value",
                (rs, i) -> new ScopeRow(rs.getString("scope_type"), rs.getString("scope_value")), userId);
    }

    public void replaceScopes(long userId, List<ScopeRow> scopes) {
        jdbc.update("DELETE FROM user_scope WHERE user_id = ?", userId);
        for (ScopeRow s : scopes) jdbc.update("INSERT INTO user_scope (user_id, scope_type, scope_value) VALUES (?,?,?)", userId, s.type(), s.value());
    }

    // ------------------------------------------------------------------ separation of duties
    public List<SodChecker.Rule> sodRules() {
        return jdbc.query("SELECT rule_id, permission_a, permission_b, description FROM sod_rule ORDER BY rule_id",
                (rs, i) -> new SodChecker.Rule(rs.getLong("rule_id"), rs.getString("permission_a"), rs.getString("permission_b"), rs.getString("description")));
    }

    public long insertSodRule(String a, String b, String description) {
        return Db.scalar(jdbc, "INSERT INTO sod_rule (permission_a, permission_b, description) VALUES (?,?,?) RETURNING rule_id", Long.class, a, b, description);
    }

    public Optional<SodChecker.Rule> sodRule(long id) {
        return Db.one(jdbc, "SELECT rule_id, permission_a, permission_b, description FROM sod_rule WHERE rule_id = ?",
                (rs, i) -> new SodChecker.Rule(rs.getLong("rule_id"), rs.getString("permission_a"), rs.getString("permission_b"), rs.getString("description")), id);
    }

    public int deleteSodRule(long id) {
        return jdbc.update("DELETE FROM sod_rule WHERE rule_id = ?", id);
    }

    // ------------------------------------------------------------------ effective access (token claims, decisions)
    /** The permissions granted by each of the identity's effective roles, and each role's ceilings. */
    public record EffectiveAccess(Set<String> roles, Set<String> permissions, Map<String, List<Map<String, java.math.BigDecimal>>> ceilings) {}

    /**
     * Roles are effective when assigned, within their dates and ACTIVE. A role that requires MFA is effective only
     * when {@code mfaEnrolled} is true. For a service account pass the client's roles via {@link #effectiveForRoles}.
     */
    public EffectiveAccess effectiveForUser(long userId, boolean mfaEnrolled, LocalDate today) {
        List<String> roles = jdbc.query("SELECT DISTINCT r.role_code FROM user_role ur JOIN role r ON r.role_code = ur.role_code "
                        + "WHERE ur.user_id = ? AND ur.ended_at IS NULL AND ur.valid_from <= ? AND (ur.valid_to IS NULL OR ur.valid_to >= ?) "
                        + "AND r.status = 'ACTIVE' AND (r.mfa_required = FALSE OR ?) ORDER BY r.role_code",
                (rs, i) -> rs.getString("role_code"), userId, today, today, mfaEnrolled);
        return effectiveForRoles(roles);
    }

    /** True when one of the user's effective roles requires MFA. */
    public boolean requiresMfa(long userId, LocalDate today) {
        return Db.scalar(jdbc, "SELECT count(*) FROM user_role ur JOIN role r ON r.role_code = ur.role_code WHERE ur.user_id = ? AND ur.ended_at IS NULL "
                + "AND ur.valid_from <= ? AND (ur.valid_to IS NULL OR ur.valid_to >= ?) AND r.status = 'ACTIVE' AND r.mfa_required = TRUE", Long.class, userId, today, today) > 0;
    }

    public List<String> clientRoles(String clientId) {
        return jdbc.query("SELECT r.role_code FROM oauth_client_role cr JOIN role r ON r.role_code = cr.role_code WHERE cr.client_id = ? AND r.status = 'ACTIVE' ORDER BY r.role_code",
                (rs, i) -> rs.getString("role_code"), clientId);
    }

    public EffectiveAccess effectiveForRoles(List<String> roles) {
        Set<String> roleSet = new LinkedHashSet<>(roles);
        Set<String> perms = new TreeSet<>();
        Map<String, List<Map<String, java.math.BigDecimal>>> ceilings = new HashMap<>();
        if (roles.isEmpty()) return new EffectiveAccess(roleSet, perms, ceilings);
        Object[] args = roles.toArray();
        // permission per role, then the role's limits for it
        Map<String, Map<String, Map<String, java.math.BigDecimal>>> perRole = new LinkedHashMap<>(); // perm -> role -> currency -> max
        jdbc.query("SELECT role_code, permission_code FROM role_permission WHERE role_code IN (" + Db.marks(roles.size()) + ")", (rs, i) -> {
            perms.add(rs.getString("permission_code"));
            perRole.computeIfAbsent(rs.getString("permission_code"), k -> new LinkedHashMap<>())
                    .computeIfAbsent(rs.getString("role_code"), k -> new HashMap<>());
            return null;
        }, args);
        jdbc.query("SELECT role_code, permission_code, currency, max_amount FROM permission_limit WHERE role_code IN (" + Db.marks(roles.size()) + ")", (rs, i) -> {
            Map<String, Map<String, java.math.BigDecimal>> byRole = perRole.get(rs.getString("permission_code"));
            if (byRole != null && byRole.containsKey(rs.getString("role_code"))) {
                byRole.get(rs.getString("role_code")).put(rs.getString("currency"), Db.decimal(rs, "max_amount"));
            }
            return null;
        }, args);
        perRole.forEach((perm, byRole) -> ceilings.put(perm, new ArrayList<>(byRole.values())));
        return new EffectiveAccess(roleSet, perms, ceilings);
    }
}
