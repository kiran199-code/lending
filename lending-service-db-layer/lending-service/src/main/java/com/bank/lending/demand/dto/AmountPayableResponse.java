package com.bank.lending.demand.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What a borrower owes on a business date: every demand with something left to pay (oldest first), the split into
 * overdue and due today, any advance held, and the next instalment that is not yet due.
 */
public record AmountPayableResponse(
        Long accountId,
        LocalDate asOfDate,
        String accountStatus,
        long daysPastDue,
        List<DemandLine> demands,
        BigDecimal overdueAmount,
        BigDecimal dueTodayAmount,
        BigDecimal penalInterest,
        BigDecimal advanceBalance,
        BigDecimal amountPayable,
        NextInstallment nextInstallment
) {
    /** raised is false for an instalment that is due but whose demand the daily job has not created yet. */
    public record DemandLine(
            Integer installmentNumber,
            LocalDate dueDate,
            BigDecimal principalDue,
            BigDecimal interestDue,
            BigDecimal principalPaid,
            BigDecimal interestPaid,
            BigDecimal principalOutstanding,
            BigDecimal interestOutstanding,
            BigDecimal outstanding,
            String status,
            boolean raised
    ) {
    }

    public record NextInstallment(Integer installmentNumber, LocalDate dueDate, BigDecimal principal,
                                  BigDecimal interest, BigDecimal amount) {
    }
}
