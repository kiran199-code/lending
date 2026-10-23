package com.bank.lending.chit.domain.rules;

/**
 * When a ticket is flagged DEFAULTED: after {@code missedInstalments} unpaid instalments, or when an instalment is
 * {@code daysOverdue} days overdue, whichever comes first. Either may be null, not both.
 */
public record DefaultThreshold(Integer missedInstalments, Integer daysOverdue) {

    public static DefaultThreshold ofMissedInstalments(int missed) {
        return new DefaultThreshold(missed, null);
    }
}
