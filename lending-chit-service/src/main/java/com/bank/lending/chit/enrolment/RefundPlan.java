package com.bank.lending.chit.enrolment;

import com.bank.lending.chit.common.Money;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What is handed back when a ticket withdraws or a group is cancelled before it starts. Per ticket, in ticket-number
 * order: the security deposit first (it is the member's money held as a liability), then the enrolment fee, then the
 * registration fee. A lien is released, not paid. Amounts that were never received, or were already refunded, are not
 * listed.
 */
public final class RefundPlan {

    public enum Kind { DEPOSIT, ENROLMENT_FEE, REGISTRATION_FEE, LIEN_RELEASE }

    /** What a ticket still holds: null or zero means nothing of that kind. */
    public record TicketMoney(long ticketId, int ticketNo, BigDecimal enrolmentFee, BigDecimal registrationFee,
                              BigDecimal deposit, BigDecimal lien) {
    }

    public record Item(Kind kind, long ticketId, int ticketNo, BigDecimal amount) {
    }

    private RefundPlan() {
    }

    /** Group cancelled: everything received is returned. */
    public static List<Item> forCancellation(List<TicketMoney> tickets) {
        List<Item> out = new ArrayList<>();
        tickets.stream().sorted(Comparator.comparingInt(TicketMoney::ticketNo)).forEach(t -> add(out, t, true));
        return out;
    }

    /**
     * One ticket withdraws before activation: the deposit (and lien) is returned; fees are returned only when the
     * setting says so (default: kept, because the foreman did the enrolment work).
     */
    public static List<Item> forWithdrawal(TicketMoney ticket, boolean refundFees) {
        List<Item> out = new ArrayList<>();
        add(out, ticket, refundFees);
        return out;
    }

    public static BigDecimal totalCash(List<Item> items) {
        BigDecimal sum = Money.ZERO;
        for (Item i : items) {
            if (i.kind() != Kind.LIEN_RELEASE) {
                sum = sum.add(i.amount());
            }
        }
        return Money.scale(sum);
    }

    private static void add(List<Item> out, TicketMoney t, boolean fees) {
        item(out, Kind.DEPOSIT, t, t.deposit());
        item(out, Kind.LIEN_RELEASE, t, t.lien());
        if (fees) {
            item(out, Kind.ENROLMENT_FEE, t, t.enrolmentFee());
            item(out, Kind.REGISTRATION_FEE, t, t.registrationFee());
        }
    }

    private static void item(List<Item> out, Kind kind, TicketMoney t, BigDecimal amount) {
        if (Money.isPositive(amount)) {
            out.add(new Item(kind, t.ticketId(), t.ticketNo(), Money.scale(amount)));
        }
    }
}
