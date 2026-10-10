package com.bank.lending.statement;

import com.bank.lending.account.domain.LoanAccount;
import com.bank.lending.payment.domain.EmiTransaction;
import com.bank.lending.schedule.domain.AmortizationScheduleEntry;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Everything a customer statement shows, plus the handful of figures derived from it. Kept free of
 * any PDF code so the same data can later back an Excel or JSON export.
 *
 * @param schedule full repayment view in installment order: installments already paid under an
 *                 earlier schedule version, followed by the current schedule
 * @param payments payments that actually reached the account (successful or still processing),
 *                 oldest first
 */
public record LoanStatementData(
        LoanAccount account,
        List<AmortizationScheduleEntry> schedule,
        List<EmiTransaction> payments,
        LocalDate statementDate
) {

    public int installmentsTotal() {
        return schedule.size();
    }

    public long installmentsPaid() {
        return schedule.stream()
                .filter(e -> e.getInstallmentStatus() == AmortizationScheduleEntry.InstallmentStatus.PAID)
                .count();
    }

    /** The first installment that is not yet paid (upcoming or overdue). */
    public Optional<AmortizationScheduleEntry> nextInstallment() {
        return schedule.stream()
                .filter(e -> e.getInstallmentStatus() != AmortizationScheduleEntry.InstallmentStatus.PAID)
                .min(Comparator.comparing(AmortizationScheduleEntry::getInstallmentNumber));
    }

    public static BigDecimal emiOf(AmortizationScheduleEntry e) {
        return e.getPrincipalComponent().add(e.getInterestComponent());
    }

    public BigDecimal overdueAmount() {
        return schedule.stream()
                .filter(e -> e.getInstallmentStatus() == AmortizationScheduleEntry.InstallmentStatus.OVERDUE)
                .map(LoanStatementData::emiOf)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal totalPaid() {
        return payments.stream()
                .filter(p -> p.getPaymentStatus() == EmiTransaction.PaymentStatus.SUCCESS)
                .map(EmiTransaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Principal still owed, or empty when it cannot be stated yet (loan not disbursed).
     * Closed and foreclosed loans owe nothing.
     */
    public Optional<BigDecimal> outstandingPrincipal() {
        switch (account.getAccountStatus()) {
            case PENDING_DISBURSEMENT:
                return Optional.empty();
            case CLOSED:
            case FORECLOSED:
                return Optional.of(BigDecimal.ZERO);
            default:
                break;
        }
        Optional<AmortizationScheduleEntry> lastPaid = schedule.stream()
                .filter(e -> e.getInstallmentStatus() == AmortizationScheduleEntry.InstallmentStatus.PAID)
                .max(Comparator.comparing(AmortizationScheduleEntry::getInstallmentNumber));
        if (lastPaid.isPresent()) {
            return Optional.of(lastPaid.get().getOutstandingBalance());
        }
        return Optional.ofNullable(account.getDisbursedAmount());
    }
}
