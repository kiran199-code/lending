package com.bank.lending.chit.calc;

import com.bank.lending.chit.common.Money;
import com.bank.lending.chit.domain.rules.DefaultThreshold;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Decides whether a ticket has breached the group's default threshold. An instalment counts as missed only when
 * something is still owed on it and it is more than {@code graceDays} past its due date (the same grace the late fee
 * uses, so a subscriber is never "in default" on a day when no fee is yet charged). The threshold trips on either
 * condition: the number of missed instalments, or the age in days of the oldest one.
 */
public final class DefaultDetector {

    /** An instalment's due date and what is still owed on its subscription. */
    public record OverdueItem(LocalDate dueDate, BigDecimal subscriptionOutstanding) {
    }

    /**
     * @param missedInstalments instalments past grace with money outstanding
     * @param oldestDaysOverdue days since the due date of the oldest of them (0 when none)
     * @param outstanding       subscription outstanding on those instalments
     * @param breached          whether the threshold is met
     * @param reason            plain-language reason, null when not breached
     */
    public record Verdict(int missedInstalments, int oldestDaysOverdue, BigDecimal outstanding, boolean breached, String reason) {
    }

    private DefaultDetector() {
    }

    public static Verdict evaluate(DefaultThreshold threshold, int graceDays, LocalDate today, List<OverdueItem> items) {
        int missed = 0;
        int oldest = 0;
        BigDecimal outstanding = Money.ZERO;
        for (OverdueItem item : items) {
            if (item.subscriptionOutstanding() == null || item.subscriptionOutstanding().signum() <= 0) {
                continue;
            }
            long days = ChronoUnit.DAYS.between(item.dueDate(), today);
            if (days <= graceDays) {
                continue;
            }
            missed++;
            oldest = (int) Math.max(oldest, days);
            outstanding = outstanding.add(item.subscriptionOutstanding());
        }
        String reason = null;
        if (threshold != null && missed > 0) {
            if (threshold.missedInstalments() != null && missed >= threshold.missedInstalments()) {
                reason = missed + " instalment(s) unpaid, the limit is " + threshold.missedInstalments();
            } else if (threshold.daysOverdue() != null && oldest >= threshold.daysOverdue()) {
                reason = "an instalment is " + oldest + " days overdue, the limit is " + threshold.daysOverdue() + " days";
            }
        }
        return new Verdict(missed, oldest, Money.scale(outstanding), reason != null, reason);
    }
}
