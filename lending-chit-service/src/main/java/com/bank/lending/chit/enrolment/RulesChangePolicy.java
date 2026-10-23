package com.bank.lending.chit.enrolment;

import com.bank.lending.chit.domain.rules.ChitRules;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Once a ticket has been enrolled, the terms the subscriber agreed to on the money side cannot change under them. In a
 * DRAFT group, or an open group with no tickets yet, anything may change. With tickets enrolled the fields below are
 * locked; the other rules (auction mechanics, late fee, dividend handling and so on) may still be revised and create a
 * new rules version. Assumption for review: the lock list is deliberately short.
 */
public final class RulesChangePolicy {

    private RulesChangePolicy() {
    }

    /** Names of the locked fields whose value differs between the two rule sets. Empty means the change is allowed. */
    public static List<String> lockedFieldsChanged(ChitRules before, ChitRules after) {
        List<String> out = new ArrayList<>();
        if (differs(before.chitValue(), after.chitValue())) {
            out.add("chitValue");
        }
        if (!Objects.equals(before.ticketCount(), after.ticketCount())) {
            out.add("ticketCount");
        }
        if (!Objects.equals(before.cycleCount(), after.cycleCount())) {
            out.add("cycleCount");
        }
        if (differs(before.subscription(), after.subscription())) {
            out.add("subscription");
        }
        if (before.frequency() != after.frequency()) {
            out.add("frequency");
        }
        if (differs(before.enrolmentFee(), after.enrolmentFee())) {
            out.add("enrolmentFee");
        }
        if (differs(before.registrationFee(), after.registrationFee())) {
            out.add("registrationFee");
        }
        if (before.securityRule() != after.securityRule()) {
            out.add("securityRule");
        }
        if (differs(before.securityDepositPct(), after.securityDepositPct())) {
            out.add("securityDepositPct");
        }
        return out;
    }

    private static boolean differs(java.math.BigDecimal a, java.math.BigDecimal b) {
        if (a == null || b == null) {
            return a != b;
        }
        return a.compareTo(b) != 0;
    }
}
