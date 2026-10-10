package com.bank.lending.statement;

import com.bank.lending.account.domain.LoanAccount;
import com.bank.lending.payment.domain.EmiTransaction;
import com.bank.lending.schedule.domain.AmortizationScheduleEntry;
import com.bank.lending.schedule.domain.AmortizationScheduleEntry.InstallmentStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Spring-free: builds a small statement in memory and checks a real PDF comes out. */
class LoanStatementPdfRendererTest {

    private final LoanStatementPdfRenderer renderer =
            new LoanStatementPdfRenderer("Test Bank", "INR", "Call customer care.");

    private LoanAccount account(LoanAccount.AccountStatus status) {
        return LoanAccount.builder()
                .accountId(1L).accountNumber("PL01261010220407").customerId(42L).sanctionRefId(9001L)
                .productCode("PL01").accountType(LoanAccount.AccountType.UNSECURED)
                .sanctionedAmount(new BigDecimal("120000.00")).disbursedAmount(new BigDecimal("120000.00"))
                .tenureMonths(12).rateType(LoanAccount.RateType.FIXED).interestRate(new BigDecimal("12.0000"))
                .dayCountConvention(LoanAccount.DayCountConvention.ACT_365)
                .emiAmount(new BigDecimal("10661.85")).emiStartDate(LocalDate.of(2026, 11, 5))
                .accountStatus(status).version(0).build();
    }

    private List<AmortizationScheduleEntry> schedule(int n, int paid) {
        List<AmortizationScheduleEntry> list = new ArrayList<>();
        BigDecimal balance = new BigDecimal("120000.00");
        for (int i = 1; i <= n; i++) {
            BigDecimal principal = new BigDecimal("9461.85");
            balance = balance.subtract(principal);
            list.add(AmortizationScheduleEntry.builder()
                    .accountId(1L).installmentNumber(i).dueDate(LocalDate.of(2026, 10, 5).plusMonths(i - 1))
                    .principalComponent(principal).interestComponent(new BigDecimal("1200.00"))
                    .outstandingBalance(balance)
                    .installmentStatus(i <= paid ? InstallmentStatus.PAID : InstallmentStatus.PENDING)
                    .scheduleVersion(1).build());
        }
        return list;
    }

    private EmiTransaction payment(long id, LocalDate date, int inst, EmiTransaction.PaymentStatus status) {
        return EmiTransaction.builder()
                .transactionId(id).valueDate(date).accountId(1L).installmentNumber(inst)
                .transactionType(EmiTransaction.TransactionType.EMI).amount(new BigDecimal("10661.85"))
                .principalPaid(new BigDecimal("9461.85")).interestPaid(new BigDecimal("1200.00"))
                .paymentChannel(EmiTransaction.PaymentChannel.NACH).paymentStatus(status).build();
    }

    @Test
    void rendersStatementWithScheduleAndPayments() {
        var schedule = schedule(12, 2);
        schedule.get(1).setInstallmentStatus(InstallmentStatus.OVERDUE);
        var payments = List.of(payment(1, LocalDate.of(2026, 10, 5), 1, EmiTransaction.PaymentStatus.SUCCESS));
        var data = new LoanStatementData(account(LoanAccount.AccountStatus.ACTIVE), schedule, payments,
                LocalDate.of(2026, 12, 1));

        byte[] pdf = renderer.render(data);

        assertThat(new String(Arrays.copyOf(pdf, 5), StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(pdf.length).isGreaterThan(2_000);
        assertThat(data.installmentsTotal()).isEqualTo(12);
        assertThat(data.installmentsPaid()).isEqualTo(1);
        assertThat(data.overdueAmount()).isEqualByComparingTo("10661.85");
        assertThat(data.totalPaid()).isEqualByComparingTo("10661.85");
    }

    @Test
    void rendersWhenNothingDisbursedYet() {
        var acc = account(LoanAccount.AccountStatus.PENDING_DISBURSEMENT);
        acc.setDisbursedAmount(null);
        acc.setEmiAmount(null);
        acc.setEmiStartDate(null);
        var data = new LoanStatementData(acc, List.of(), List.of(), LocalDate.of(2026, 10, 10));

        byte[] pdf = renderer.render(data);

        assertThat(new String(Arrays.copyOf(pdf, 5), StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(data.nextInstallment()).isEmpty();
    }

    @Test
    void failedPaymentsDoNotCountTowardsTotalPaid() {
        var payments = List.of(
                payment(1, LocalDate.of(2026, 10, 5), 1, EmiTransaction.PaymentStatus.FAILED),
                payment(2, LocalDate.of(2026, 10, 6), 1, EmiTransaction.PaymentStatus.SUCCESS));
        var data = new LoanStatementData(account(LoanAccount.AccountStatus.ACTIVE), schedule(12, 1), payments,
                LocalDate.of(2026, 10, 10));

        assertThat(data.totalPaid()).isEqualByComparingTo("10661.85");
    }
}
