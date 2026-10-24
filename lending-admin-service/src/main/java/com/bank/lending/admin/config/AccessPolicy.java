package com.bank.lending.admin.config;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Who may call which admin API. One list, read top to bottom, first match wins; a call that matches no rule is for ADMIN only.
 * ADMIN (the original console login) passes every rule, so a rule only names the other roles that may use it.
 *
 * <ul>
 *   <li>Everyone who signs in gets every inquiry: dashboard, accounts and their detail, statement, upcoming payments, overdue, payments, interest
 *       accruals and the ledger outbox. Reading limits and collateral data is open too, because the account detail and the loan screens show the
 *       loan's facility and collateral; only changing it is restricted.</li>
 *   <li>BATCH_OPS: batch jobs, end of day, configuration (batch job settings).</li>
 *   <li>LOAN_OPS: create, disburse and pay loans (operations), and products.</li>
 *   <li>LIMITS_OPS: every change to facilities, collateral, valuations, allocations, approvals and monitoring.</li>
 *   <li>Chit fund screens: reading is open to every signed-in user; creating groups, enrolling, activating and cancelling are ADMIN only for now (no chit role yet, so the call matches no rule).</li>
 *   <li>Accounting screens (except the branch and currency lists the forms need) and Maintenance: ADMIN only.</li>
 * </ul>
 *
 * Patterns are exact paths, or a path ending in {@code /**} (which also matches the path itself), as in Spring's matcher, so the same rules feed
 * {@link SecurityConfig} and the unit test.
 */
public final class AccessPolicy {

    public static final String ADMIN = "ADMIN";
    public static final String BATCH_OPS = "BATCH_OPS";
    public static final String LOAN_OPS = "LOAN_OPS";
    public static final String LIMITS_OPS = "LIMITS_OPS";

    /** {@code method} null = any method; {@code roles} empty = any signed-in user (ADMIN is always allowed in addition). */
    public record Rule(String method, String pattern, List<String> roles) {
        boolean matches(String m, String path) {
            if (method != null && !method.equalsIgnoreCase(m)) {
                return false;
            }
            if (pattern.endsWith("/**")) {
                String base = pattern.substring(0, pattern.length() - 3);
                return path.equals(base) || path.startsWith(base + "/");
            }
            return pattern.equals(path);
        }
    }

    private static final String A = "/api/admin";

    public static final List<Rule> RULES = List.of(
            // inquiries: every signed-in user
            new Rule("GET", A + "/me", List.of()),
            new Rule("GET", A + "/business-date", List.of()),
            new Rule("GET", A + "/dashboard", List.of()),
            new Rule("GET", A + "/accounts/**", List.of()),
            new Rule("GET", A + "/upcoming-payments", List.of()),
            new Rule("GET", A + "/overdue/**", List.of()),
            new Rule("GET", A + "/payments", List.of()),
            new Rule("GET", A + "/accruals/**", List.of()),
            new Rule("GET", A + "/ledger-outbox", List.of()),
            // read-only reference data the loan and limit forms need, and the facility / collateral shown on a loan
            new Rule("GET", A + "/accounting/branches", List.of()),
            new Rule("GET", A + "/accounting/currencies", List.of()),
            new Rule("GET", A + "/limits/**", List.of()),
            new Rule("GET", A + "/chit/**", List.of()),
            // actions
            new Rule(null, A + "/limits/**", List.of(LIMITS_OPS)),
            new Rule(null, A + "/operations/**", List.of(LOAN_OPS)),
            new Rule(null, A + "/products/**", List.of(LOAN_OPS)),
            new Rule(null, A + "/batch/**", List.of(BATCH_OPS)),
            new Rule(null, A + "/eod/**", List.of(BATCH_OPS)),
            new Rule(null, A + "/config/**", List.of(BATCH_OPS)));

    private AccessPolicy() {}

    public static Optional<Rule> match(String method, String path) {
        return RULES.stream().filter(r -> r.matches(method, path)).findFirst();
    }

    /** @param roles role names without the ROLE_ prefix */
    public static boolean allowed(String method, String path, Collection<String> roles) {
        if (roles.contains(ADMIN)) {
            return true;
        }
        return match(method, path).map(r -> r.roles().isEmpty() || r.roles().stream().anyMatch(roles::contains)).orElse(false);
    }
}
