package com.bank.lending.demand;

import com.bank.lending.account.domain.LoanAccount;
import com.bank.lending.demand.service.DemandService;
import com.bank.lending.demand.service.PaymentPlanner;
import com.bank.lending.demand.service.PaymentPlanner.Plan;
import com.bank.lending.demand.service.PaymentPlanner.SurplusTreatment;
import com.bank.lending.demand.util.PaymentWaterfall.Due;
import com.bank.lending.payment.domain.EmiTransaction.TransactionType;
import com.bank.lending.schedule.domain.AmortizationScheduleEntry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Spring-free: what a payment would do, and whether it is allowed, with the schedule faked. */
class PaymentPlannerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 12);

    private final DemandService demands = Mockito.mock(DemandService.class);
    private final PaymentPlanner planner = new PaymentPlanner(demands);

    private static BigDecimal n(String v) {
        return new BigDecimal(v);
    }

    private LoanAccount account(String advance) {
        return LoanAccount.builder().accountId(1L).advanceBalance(n(advance)).build();
    }

    /** Instalments not yet due: each 1,000 principal + 100 interest, balance after payment falling by 1,000 from 9,000. */
    private void undemanded(int count) {
        List<AmortizationScheduleEntry> list = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(AmortizationScheduleEntry.builder().accountId(1L).installmentNumber(5 + i)
                    .dueDate(TODAY.plusDays(30L * (i + 1)))
                    .principalComponent(n("1000.00")).interestComponent(n("100.00"))
                    .outstandingBalance(n("9000.00").subtract(n("1000.00").multiply(BigDecimal.valueOf(i)))).build());
        }
        Mockito.when(demands.undemandedEntries(1L, TODAY)).thenReturn(list);
    }

    private static List<Due> owing(String interest, String principal) {
        return List.of(new Due(1L, 4, TODAY.minusDays(7), BigDecimal.ZERO, n(interest), n(principal)));
    }

    @Test
    void anEmiPaymentThatFitsWhatIsDueIsAllowedWithNoSurplus() {
        undemanded(3);
        Plan p = planner.plan(account("0"), TODAY, n("1100.00"), TransactionType.EMI, owing("100.00", "1000.00"));

        assertThat(p.allowed()).isTrue();
        assertThat(p.surplus()).isEqualByComparingTo("0");
    }

    @Test
    void anEmiPaymentBeyondWhatIsDueIsHeldAsAdvance() {
        undemanded(3);
        Plan p = planner.plan(account("0"), TODAY, n("1600.00"), TransactionType.EMI, owing("100.00", "1000.00"));

        assertThat(p.allowed()).isTrue();
        assertThat(p.treatment()).isEqualTo(SurplusTreatment.ADVANCE);
        assertThat(p.surplus()).isEqualByComparingTo("500.00");
    }

    @Test
    void anEmiPaymentBeyondEverythingStillPayableIsRefused() {
        undemanded(2); // 2 x 1,100 still to come
        Plan p = planner.plan(account("0"), TODAY, n("3400.00"), TransactionType.EMI, owing("100.00", "1000.00"));

        assertThat(p.allowed()).isFalse();
        assertThat(p.message()).contains("foreclosure");
    }

    @Test
    void advanceAlreadyHeldUsesUpTheRoom() {
        undemanded(2);
        // 2,200 still to come, 1,000 already held as advance: only 1,200 more can be taken, so 1,100 due + 1,300 surplus is too much
        Plan p = planner.plan(account("1000.00"), TODAY, n("2400.00"), TransactionType.EMI, owing("100.00", "1000.00"));

        assertThat(p.allowed()).isFalse();
    }

    @Test
    void aPartPaymentFirstClearsWhatIsDueThenReducesPrincipal() {
        undemanded(3);
        Plan p = planner.plan(account("0"), TODAY, n("3100.00"), TransactionType.PART_PAYMENT, owing("100.00", "1000.00"));

        assertThat(p.allowed()).isTrue();
        assertThat(p.treatment()).isEqualTo(SurplusTreatment.PRINCIPAL);
        assertThat(p.surplus()).isEqualByComparingTo("2000.00");
    }

    @Test
    void aPartPaymentLargerThanTheOutstandingPrincipalIsRefused() {
        undemanded(3); // principal base = 9,000 + 1,000 = 10,000
        Plan p = planner.plan(account("0"), TODAY, n("11200.00"), TransactionType.PART_PAYMENT, owing("100.00", "1000.00"));

        assertThat(p.allowed()).isFalse();
        assertThat(p.message()).contains("foreclosure");
    }

    @Test
    void aPartPaymentWithNoLaterInstalmentLeftIsRefused() {
        undemanded(0);
        Plan p = planner.plan(account("0"), TODAY, n("2000.00"), TransactionType.PART_PAYMENT, owing("100.00", "1000.00"));

        assertThat(p.allowed()).isFalse();
    }
}
