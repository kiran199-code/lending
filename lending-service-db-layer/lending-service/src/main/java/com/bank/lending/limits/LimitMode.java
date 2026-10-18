package com.bank.lending.limits;

/**
 * How lending uses the Limit and Collateral Management service (lending.lcm.mode).
 * <ul>
 *   <li>OFF - lending ignores limits completely: no calls, no database writes. The default.</li>
 *   <li>SHADOW - lending reports every drawdown, repayment and closure to LCM, but LCM only records what it would have
 *       rejected; nothing is ever blocked. Use it to compare LCM's view with reality before enforcing.</li>
 *   <li>LIVE - a disbursement that does not fit the facility limit is rejected.</li>
 * </ul>
 */
public enum LimitMode {
    OFF, SHADOW, LIVE;

    public static LimitMode parse(String value) {
        if (value == null || value.isBlank()) {
            return OFF;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("lending.lcm.mode must be off, shadow or live but was '" + value + "'");
        }
    }
}
