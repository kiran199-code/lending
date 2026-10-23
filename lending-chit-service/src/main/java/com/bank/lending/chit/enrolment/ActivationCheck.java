package com.bank.lending.chit.enrolment;

import com.bank.lending.chit.common.ChitException;
import com.bank.lending.chit.domain.GroupStatus;
import com.bank.lending.chit.domain.SecurityRule;
import com.bank.lending.chit.domain.rules.ChitRules;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Everything that must be true before a group starts. All blockers are reported together, so an operator fixes them in
 * one pass instead of discovering them one refusal at a time.
 */
public final class ActivationCheck {

    public enum SecurityState { NONE, HELD, VERIFIED }

    /** A live (not withdrawn) ticket and the state of its security row. */
    public record TicketState(int ticketNo, SecurityState security) {
    }

    public record Blocker(String code, String message) {
    }

    public record Input(GroupStatus status, ChitRules rules, List<TicketState> liveTickets, LocalDate businessDate,
                        LocalDate firstDueDate) {
    }

    private ActivationCheck() {
    }

    public static List<Blocker> blockers(Input in) {
        List<Blocker> out = new ArrayList<>();
        if (in.status() != GroupStatus.FULL) {
            out.add(new Blocker("GROUP_NOT_FULL", "The group is " + in.status() + "; it must be FULL (every ticket enrolled)"));
        }
        int n = in.rules().ticketCount();
        TreeSet<Integer> numbers = new TreeSet<>();
        for (TicketState t : in.liveTickets()) {
            numbers.add(t.ticketNo());
        }
        boolean complete = in.liveTickets().size() == n && numbers.size() == n
                && numbers.first() == 1 && numbers.last() == n;
        if (!complete) {
            out.add(new Blocker("TICKETS_INCOMPLETE", in.liveTickets().size() + " tickets enrolled; tickets 1.." + n
                    + " are needed"));
        }
        SecurityRule rule = in.rules().securityRule() == null ? SecurityRule.NONE : in.rules().securityRule();
        if (rule != SecurityRule.NONE) {
            List<Integer> missing = new ArrayList<>();
            List<Integer> unverified = new ArrayList<>();
            Set<Integer> seen = new HashSet<>();
            for (TicketState t : in.liveTickets()) {
                if (!seen.add(t.ticketNo())) {
                    continue;
                }
                if (t.security() == SecurityState.NONE) {
                    missing.add(t.ticketNo());
                } else if (t.security() == SecurityState.HELD) {
                    unverified.add(t.ticketNo());
                }
            }
            if (!missing.isEmpty()) {
                out.add(new Blocker("SECURITY_MISSING", "No security recorded for ticket(s) " + sorted(missing)));
            }
            if (!unverified.isEmpty()) {
                out.add(new Blocker("SECURITY_NOT_VERIFIED", "Security not yet verified for ticket(s) " + sorted(unverified)));
            }
        }
        if (in.firstDueDate() != null && in.firstDueDate().isBefore(in.businessDate())) {
            out.add(new Blocker("FIRST_DUE_DATE_PASSED", "The first instalment date " + in.firstDueDate()
                    + " is before the business date " + in.businessDate() + "; move the start date and activate again"));
        }
        return out;
    }

    public static void requireClear(Input in) {
        List<Blocker> b = blockers(in);
        if (!b.isEmpty()) {
            StringBuilder sb = new StringBuilder("The group cannot be activated: ");
            for (int i = 0; i < b.size(); i++) {
                sb.append(i > 0 ? "; " : "").append(b.get(i).code()).append(" (").append(b.get(i).message()).append(")");
            }
            throw new ChitException("ACTIVATION_BLOCKED", sb.toString());
        }
    }

    private static List<Integer> sorted(List<Integer> v) {
        List<Integer> c = new ArrayList<>(v);
        java.util.Collections.sort(c);
        return c;
    }
}
