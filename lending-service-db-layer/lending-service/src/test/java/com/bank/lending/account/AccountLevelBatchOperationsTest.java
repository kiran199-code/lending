package com.bank.lending.account;

import com.bank.lending.account.domain.LoanAccount;
import com.bank.lending.demand.repository.LoanDemandRepository;
import com.bank.lending.account.dto.DelinquencyResultResponse;
import com.bank.lending.account.dto.RateResetResultResponse;
import com.bank.lending.account.repository.LoanAccountRepository;
import com.bank.lending.account.service.LoanAccountService;
import com.bank.lending.account.util.AccountNumberGenerator;
import com.bank.lending.benchmark.domain.BenchmarkRate;
import com.bank.lending.benchmark.service.BenchmarkRateService;
import com.bank.lending.businessdate.service.BusinessDateService;
import com.bank.lending.ledger.service.LedgerOutboxService;
import com.bank.lending.product.repository.LoanProductRepository;
import com.bank.lending.product.service.AccountTermsResolver;
import com.bank.lending.schedule.domain.AmortizationScheduleEntry;
import com.bank.lending.schedule.repository.AmortizationScheduleRepository;
import com.bank.lending.schedule.service.AmortizationScheduleService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Spring-free: the per-account operations the batch service calls (classification, rate reset). */
class AccountLevelBatchOperationsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 12);

    private final LoanAccountRepository accounts = Mockito.mock(LoanAccountRepository.class);
    private final AmortizationScheduleRepository schedule = Mockito.mock(AmortizationScheduleRepository.class);
    private final AmortizationScheduleService scheduleService = Mockito.mock(AmortizationScheduleService.class);
    private final BenchmarkRateService benchmarks = Mockito.mock(BenchmarkRateService.class);
    private final LoanProductRepository products = Mockito.mock(LoanProductRepository.class);

    private final LoanAccountService service = new LoanAccountService(accounts, schedule, scheduleService,
            Mockito.mock(AccountNumberGenerator.class), Mockito.mock(LedgerOutboxService.class),
            Mockito.mock(AccountTermsResolver.class), products, benchmarks, Mockito.mock(BusinessDateService.class),
            Mockito.mock(LoanDemandRepository.class));

    private LoanAccount account(LoanAccount.AccountStatus status) {
        LoanAccount a = LoanAccount.builder().accountId(1L).productId(10L).accountStatus(status)
                .rateType(LoanAccount.RateType.FLOATING).benchmarkRateRef("MCLR_1Y")
                .interestRate(new BigDecimal("9.5000")).spread(new BigDecimal("1.0000")).build();
        Mockito.when(accounts.findById(1L)).thenReturn(Optional.of(a));
        return a;
    }

    private static AmortizationScheduleEntry installment(int no, LocalDate due,
                                                         AmortizationScheduleEntry.InstallmentStatus status) {
        return AmortizationScheduleEntry.builder().accountId(1L).installmentNumber(no).dueDate(due)
                .principalComponent(new BigDecimal("1000.00")).interestComponent(new BigDecimal("100.00"))
                .outstandingBalance(new BigDecimal("9000.00")).installmentStatus(status).scheduleVersion(1).build();
    }

    // ---- classification ----

    @Test
    void anInstalmentFortyDaysOverdueMovesTheAccountToSma1AndIsFlaggedOverdue() {
        LoanAccount a = account(LoanAccount.AccountStatus.ACTIVE);
        AmortizationScheduleEntry due = installment(1, TODAY.minusDays(40), AmortizationScheduleEntry.InstallmentStatus.PENDING);
        Mockito.when(schedule.findCurrentVersion(1L)).thenReturn(Optional.of(1));
        Mockito.when(schedule.findByAccountIdAndScheduleVersionOrderByInstallmentNumber(1L, 1)).thenReturn(List.of(due));

        DelinquencyResultResponse r = service.classifyOne(1L, TODAY);

        assertThat(r.daysPastDue()).isEqualTo(40);
        assertThat(r.previousStatus()).isEqualTo(LoanAccount.AccountStatus.ACTIVE);
        assertThat(r.newStatus()).isEqualTo(LoanAccount.AccountStatus.NPA_SMA1);
        assertThat(r.statusChanged()).isTrue();
        assertThat(a.getAccountStatus()).isEqualTo(LoanAccount.AccountStatus.NPA_SMA1);
        assertThat(due.getInstallmentStatus()).isEqualTo(AmortizationScheduleEntry.InstallmentStatus.OVERDUE);
    }

    @Test
    void reclassifyingTheSameAccountOnTheSameDateChangesNothingTheSecondTime() {
        account(LoanAccount.AccountStatus.ACTIVE);
        AmortizationScheduleEntry due = installment(1, TODAY.minusDays(40), AmortizationScheduleEntry.InstallmentStatus.PENDING);
        Mockito.when(schedule.findCurrentVersion(1L)).thenReturn(Optional.of(1));
        Mockito.when(schedule.findByAccountIdAndScheduleVersionOrderByInstallmentNumber(1L, 1)).thenReturn(List.of(due));

        service.classifyOne(1L, TODAY);
        DelinquencyResultResponse second = service.classifyOne(1L, TODAY);

        assertThat(second.statusChanged()).isFalse();
        assertThat(second.newStatus()).isEqualTo(LoanAccount.AccountStatus.NPA_SMA1);
    }

    @Test
    void anAccountWithNothingOverdueStaysActive() {
        account(LoanAccount.AccountStatus.ACTIVE);
        AmortizationScheduleEntry future = installment(1, TODAY.plusDays(10), AmortizationScheduleEntry.InstallmentStatus.PENDING);
        Mockito.when(schedule.findCurrentVersion(1L)).thenReturn(Optional.of(1));
        Mockito.when(schedule.findByAccountIdAndScheduleVersionOrderByInstallmentNumber(1L, 1)).thenReturn(List.of(future));

        DelinquencyResultResponse r = service.classifyOne(1L, TODAY);

        assertThat(r.daysPastDue()).isZero();
        assertThat(r.statusChanged()).isFalse();
    }

    @Test
    void closedAccountsAreSkippedAndLeftUntouched() {
        LoanAccount a = account(LoanAccount.AccountStatus.CLOSED);

        DelinquencyResultResponse r = service.classifyOne(1L, TODAY);

        assertThat(r.skipped()).isTrue();
        assertThat(a.getAccountStatus()).isEqualTo(LoanAccount.AccountStatus.CLOSED);
        Mockito.verifyNoInteractions(schedule);
    }

    // ---- rate reset ----

    private void benchmarkRate(String value) {
        Mockito.when(benchmarks.getRateAsOf("MCLR_1Y", TODAY)).thenReturn(
                BenchmarkRate.builder().benchmarkRef("MCLR_1Y").effectiveDate(TODAY).rate(new BigDecimal(value)).build());
    }

    @Test
    void aNewBenchmarkRateResetsTheAccountToBenchmarkPlusSpread() {
        LoanAccount a = account(LoanAccount.AccountStatus.ACTIVE);
        benchmarkRate("9.0000");
        Mockito.when(products.findById(10L)).thenReturn(Optional.empty());
        Mockito.when(schedule.findCurrentVersion(1L)).thenReturn(Optional.of(1));
        Mockito.when(schedule.findByAccountIdAndScheduleVersionAndInstallmentStatus(
                1L, 1, AmortizationScheduleEntry.InstallmentStatus.PENDING))
                .thenReturn(List.of(installment(3, TODAY.plusDays(5), AmortizationScheduleEntry.InstallmentStatus.PENDING)));

        RateResetResultResponse r = service.resetRateForAccount(1L, TODAY, "MCLR_1Y");

        assertThat(r.outcome()).isEqualTo(RateResetResultResponse.Outcome.RESET);
        assertThat(r.previousRate()).isEqualByComparingTo("9.5");
        assertThat(r.newRate()).isEqualByComparingTo("10.0");
        assertThat(a.getInterestRate()).isEqualByComparingTo("10.0");
        Mockito.verify(scheduleService).regenerateSchedule(Mockito.eq(a), Mockito.any(), Mockito.eq(new BigDecimal("10.0000")),
                Mockito.eq(1), Mockito.eq(TODAY.plusDays(5)), Mockito.eq(3));
    }

    @Test
    void whenTheComputedRateEqualsTheCurrentRateNothingIsRegenerated() {
        account(LoanAccount.AccountStatus.ACTIVE);
        benchmarkRate("8.5000"); // 8.5 + 1.0 spread = 9.5 = current
        Mockito.when(products.findById(10L)).thenReturn(Optional.empty());

        RateResetResultResponse r = service.resetRateForAccount(1L, TODAY, "MCLR_1Y");

        assertThat(r.outcome()).isEqualTo(RateResetResultResponse.Outcome.UNCHANGED);
        Mockito.verifyNoInteractions(scheduleService);
    }

    @Test
    void anAccountOnAnotherBenchmarkIsSkippedWithoutLookingUpAnyRate() {
        account(LoanAccount.AccountStatus.ACTIVE);

        RateResetResultResponse r = service.resetRateForAccount(1L, TODAY, "TBILL_91D");

        assertThat(r.outcome()).isEqualTo(RateResetResultResponse.Outcome.SKIPPED);
        Mockito.verifyNoInteractions(benchmarks, scheduleService);
    }

    @Test
    void fixedRateAndNonActiveAccountsAreSkipped() {
        LoanAccount a = account(LoanAccount.AccountStatus.NPA_SMA1);
        assertThat(service.resetRateForAccount(1L, TODAY, null).outcome())
                .isEqualTo(RateResetResultResponse.Outcome.SKIPPED);

        a.setAccountStatus(LoanAccount.AccountStatus.ACTIVE);
        a.setRateType(LoanAccount.RateType.FIXED);
        assertThat(service.resetRateForAccount(1L, TODAY, null).outcome())
                .isEqualTo(RateResetResultResponse.Outcome.SKIPPED);
    }
}
