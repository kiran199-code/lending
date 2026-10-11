package com.bank.lending.account.dto;

import com.bank.lending.account.domain.LoanAccount;

import java.time.LocalDate;

/** Outcome of classifying a single account as of a date. */
public record DelinquencyResultResponse(
        Long accountId,
        LocalDate asOfDate,
        long daysPastDue,
        LoanAccount.AccountStatus previousStatus,
        LoanAccount.AccountStatus newStatus,
        boolean statusChanged,
        boolean skipped
) {
}
