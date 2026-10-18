package com.bank.lending.iam.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The policy decision (section 9.2). Pure: everything it needs is in the arguments, so it can be tested with a table.
 * Order: status, grant, scopes (branch, product, currency), ceiling, account link, step-up. The first failure wins.
 */
public final class AuthzDecisionEngine {
    public static final String OK = "OK";
    public static final String USER_NOT_ACTIVE = "USER_NOT_ACTIVE";
    public static final String NOT_GRANTED = "NOT_GRANTED";
    public static final String SCOPE_BRANCH = "SCOPE_BRANCH";
    public static final String SCOPE_PRODUCT = "SCOPE_PRODUCT";
    public static final String SCOPE_CURRENCY = "SCOPE_CURRENCY";
    public static final String AMOUNT_ABOVE_LIMIT = "AMOUNT_ABOVE_LIMIT";
    public static final String ACCOUNT_NOT_LINKED = "ACCOUNT_NOT_LINKED";
    public static final String STEP_UP_REQUIRED = "STEP_UP_REQUIRED";

    /** One permission of the catalogue. */
    public record PermissionInfo(String code, boolean requiresMfa, String risk) {}

    /** A customer link. */
    public record Link(String type, String customerId, String accountRef, String status, LocalDate validFrom, LocalDate validTo) {
        public boolean effective(LocalDate today) {
            return "ACTIVE".equals(status) && !today.isBefore(validFrom) && (validTo == null || !today.isAfter(validTo));
        }
    }

    /**
     * Everything known about who is acting.
     * @param ceilings per permission, one entry per granting role: currency (or "*") to the ceiling.
     *                 An empty map means that role grants the permission without a ceiling.
     * @param scopes   scope type (BRANCH, PRODUCT, CURRENCY) to its values; "*" means all; absent or empty means unrestricted
     */
    public record Subject(String kind, boolean active, String customerId, Set<String> permissions,
                          Map<String, List<Map<String, BigDecimal>>> ceilings, Map<String, Set<String>> scopes, List<Link> links) {}

    public record Check(String permission, String branch, String product, String currency, BigDecimal amount,
                        String accountRef, String accountCustomerId, Instant mfaAt) {
        public Check withMfa(Instant at) {
            return new Check(permission, branch, product, currency, amount, accountRef, accountCustomerId, at);
        }
    }

    public record Decision(boolean allowed, String code, boolean stepUpRequired, BigDecimal limit) {
        static Decision ok() { return new Decision(true, OK, false, null); }
        static Decision deny(String code) { return new Decision(false, code, false, null); }
    }

    private final Duration stepUpWindow;
    private final Set<String> accountBound;
    private final Map<String, PermissionInfo> catalogue;

    public AuthzDecisionEngine(Duration stepUpWindow, Set<String> accountBound, Map<String, PermissionInfo> catalogue) {
        this.stepUpWindow = stepUpWindow;
        this.accountBound = accountBound;
        this.catalogue = catalogue;
    }

    public Decision decide(Subject s, Check c, Instant now, LocalDate today) {
        if (!s.active()) return Decision.deny(USER_NOT_ACTIVE);
        if (c.permission() == null || !s.permissions().contains(c.permission())) return Decision.deny(NOT_GRANTED);

        if (!scopeAllows(s, "BRANCH", c.branch())) return Decision.deny(SCOPE_BRANCH);
        if (!scopeAllows(s, "PRODUCT", c.product())) return Decision.deny(SCOPE_PRODUCT);
        if (!scopeAllows(s, "CURRENCY", c.currency())) return Decision.deny(SCOPE_CURRENCY);

        if (c.amount() != null) {
            BigDecimal greatest = greatestCeiling(s, c.permission(), c.currency());
            if (greatest != null && c.amount().compareTo(greatest) > 0) {
                return new Decision(false, AMOUNT_ABOVE_LIMIT, false, greatest);
            }
        }

        if ("CUSTOMER".equals(s.kind()) && accountBound.contains(c.permission())) {
            if (!linked(s, c, today)) return Decision.deny(ACCOUNT_NOT_LINKED);
        }

        PermissionInfo info = catalogue.get(c.permission());
        if (info != null && info.requiresMfa()) {
            boolean fresh = c.mfaAt() != null && !c.mfaAt().plus(stepUpWindow).isBefore(now) && !c.mfaAt().isAfter(now.plusSeconds(60));
            if (!fresh) return new Decision(false, STEP_UP_REQUIRED, true, null);
        }
        return Decision.ok();
    }

    /** No scope of the type, or "*", or the value itself. A call without the attribute is not restricted by it. */
    static boolean scopeAllows(Subject s, String type, String value) {
        if (value == null || value.isBlank()) return true;
        Set<String> held = s.scopes() == null ? null : s.scopes().get(type);
        if (held == null || held.isEmpty() || held.contains("*")) return true;
        return held.contains(value);
    }

    /**
     * Greatest ceiling over the roles that grant the permission, for the call's currency (or the "*" row).
     * Null means no ceiling applies: some granting role has none for that currency.
     */
    static BigDecimal greatestCeiling(Subject s, String permission, String currency) {
        List<Map<String, BigDecimal>> perRole = s.ceilings() == null ? null : s.ceilings().get(permission);
        if (perRole == null || perRole.isEmpty()) return null;
        BigDecimal greatest = null;
        for (Map<String, BigDecimal> limits : perRole) {
            BigDecimal applicable = null;
            if (currency != null) applicable = limits.get(currency);
            if (applicable == null) applicable = limits.get("*");
            if (applicable == null) return null; // this role has no ceiling for the call
            if (greatest == null || applicable.compareTo(greatest) > 0) greatest = applicable;
        }
        return greatest;
    }

    private static boolean linked(Subject s, Check c, LocalDate today) {
        if (c.accountRef() == null && c.accountCustomerId() == null) return false;
        for (Link l : s.links()) {
            if (!l.effective(today)) continue;
            if ("ACCOUNT".equals(l.type()) && c.accountRef() != null && c.accountRef().equals(l.accountRef())) return true;
            if ("CUSTOMER".equals(l.type()) && c.accountCustomerId() != null && c.accountCustomerId().equals(l.customerId())) return true;
        }
        return false;
    }
}
