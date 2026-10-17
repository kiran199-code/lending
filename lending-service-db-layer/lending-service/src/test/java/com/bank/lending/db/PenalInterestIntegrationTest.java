package com.bank.lending.db;

import com.bank.lending.account.domain.LoanAccount;
import com.bank.lending.account.repository.LoanAccountRepository;
import com.bank.lending.demand.domain.LoanDemand;
import com.bank.lending.demand.domain.LoanDemand.DemandStatus;
import com.bank.lending.demand.domain.PaymentAllocation;
import com.bank.lending.demand.domain.PenalInterest;
import com.bank.lending.demand.repository.LoanDemandRepository;
import com.bank.lending.demand.service.DemandService;
import com.bank.lending.demand.service.PenalInterestService;
import com.bank.lending.demand.util.PaymentWaterfall;
import com.bank.lending.product.domain.LoanProduct;
import com.bank.lending.product.repository.LoanProductRepository;
import com.bank.lending.schedule.domain.AmortizationScheduleEntry;
import com.bank.lending.schedule.domain.AmortizationScheduleEntry.InstallmentStatus;
import com.bank.lending.schedule.repository.AmortizationScheduleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Penal interest against a real database: one charge per overdue day, nothing twice, the product's grace days, cap and
 * on/off switch, accounts that do not accrue, and penal interest being paid first and keeping a demand open until paid.
 *
 * <p>Every account here has one demand of 10,000 (800 interest + 9,200 principal) due 5 Jul 2026. At the product default
 * of 24% p.a. on ACT/365 a day costs 10,000 x 24% / 365 = 6.5753.
 */
@Transactional
class PenalInterestIntegrationTest extends AbstractIntegrationTest {

    private static final LocalDate DUE = LocalDate.of(2026, 7, 5);

    @Autowired private LoanAccountRepository accounts;
    @Autowired private LoanProductRepository products;
    @Autowired private AmortizationScheduleRepository schedule;
    @Autowired private LoanDemandRepository demandRepository;
    @Autowired private DemandService demandService;
    @Autowired private PenalInterestService penalService;

    private LoanAccount overdueAccount(String number, long sanctionRef) {
        LoanAccount a = accounts.save(LoanAccount.builder()
                .accountNumber(number).customerId(1L).sanctionRefId(sanctionRef).productCode("PL01")
                .productId(products.findByProductCode("PL01").orElseThrow().getProductId())
                .accountType(LoanAccount.AccountType.UNSECURED)
                .sanctionedAmount(new BigDecimal("500000.00")).tenureMonths(36)
                .rateType(LoanAccount.RateType.FIXED).interestRate(new BigDecimal("11.5000"))
                .dayCountConvention(LoanAccount.DayCountConvention.ACT_365)
                .accountStatus(LoanAccount.AccountStatus.ACTIVE).build());
        for (int i = 1; i <= 2; i++) {
            schedule.save(AmortizationScheduleEntry.builder().accountId(a.getAccountId()).installmentNumber(i)
                    .dueDate(DUE.plusMonths(i - 1))
                    .principalComponent(new BigDecimal("9200.00")).interestComponent(new BigDecimal("800.00"))
                    .outstandingBalance(new BigDecimal("400000.00"))
                    .installmentStatus(InstallmentStatus.PENDING).scheduleVersion(1).build());
        }
        demandService.raiseDue(a, DUE); // raises instalment 1 only
        return a;
    }

    private LoanDemand onlyDemand(LoanAccount a) {
        List<LoanDemand> all = demandRepository.findByAccountIdOrderByInstallmentNumberAsc(a.getAccountId());
        assertThat(all).hasSize(1);
        return all.get(0);
    }

    private void setPenal(boolean enabled, String rate, int graceDays, String capPercent) {
        LoanProduct p = products.findByProductCode("PL01").orElseThrow();
        p.setPenalInterestEnabled(enabled);
        p.setPenalInterestRate(new BigDecimal(rate));
        p.setPenalGraceDays(graceDays);
        p.setPenalCapPercent(capPercent == null ? null : new BigDecimal(capPercent));
        products.saveAndFlush(p);
    }

    @Test
    void chargesOneDayAtATimeFromTheDayAfterTheDueDate() {
        LoanAccount a = overdueAccount("PL-PEN-0001", 9201L);

        List<PenalInterest> written = penalService.accrueThrough(a, LocalDate.of(2026, 7, 13)); // 6th..13th = 8 days

        assertThat(written).hasSize(8);
        assertThat(written.get(0).getBusinessDate()).isEqualTo(DUE.plusDays(1));
        assertThat(written).allSatisfy(w -> {
            assertThat(w.getBaseAmount()).isEqualByComparingTo("10000.00");
            assertThat(w.getAnnualRate()).isEqualByComparingTo("24.0000");
            assertThat(w.getAmount()).isEqualByComparingTo("6.5753");
            assertThat(w.getSuspended()).isFalse();
        });
        assertThat(onlyDemand(a).getPenalAccrued()).isEqualByComparingTo("52.6024");
        assertThat(onlyDemand(a).penalOutstanding()).isEqualByComparingTo("52.60");
    }

    @Test
    void runningAgainChargesNothingAndALaterRunChargesOnlyTheNewDays() {
        LoanAccount a = overdueAccount("PL-PEN-0002", 9202L);

        penalService.accrueThrough(a, LocalDate.of(2026, 7, 13));
        List<PenalInterest> again = penalService.accrueThrough(a, LocalDate.of(2026, 7, 13));
        List<PenalInterest> later = penalService.accrueThrough(a, LocalDate.of(2026, 7, 15));

        assertThat(again).isEmpty();
        assertThat(later).hasSize(2);
        assertThat(penalService.history(a.getAccountId())).hasSize(10);
        assertThat(onlyDemand(a).getPenalAccrued()).isEqualByComparingTo("65.7530");
    }

    @Test
    void aJobThatMissedDaysCatchesUpToTheSameTotal() {
        LoanAccount a = overdueAccount("PL-PEN-0003", 9203L);

        penalService.accrueForAccount(a.getAccountId(), LocalDate.of(2026, 7, 6));
        var result = penalService.accrueForAccount(a.getAccountId(), LocalDate.of(2026, 7, 13)); // 7th..13th

        assertThat(result.accrualsCreated()).isEqualTo(7);
        assertThat(result.penalOutstanding()).isEqualByComparingTo("52.60");
        assertThat(onlyDemand(a).getPenalAccrued()).isEqualByComparingTo("52.6024");
    }

    @Test
    void graceDaysDelayTheFirstCharge() {
        setPenal(true, "24.0000", 3, null);
        LoanAccount a = overdueAccount("PL-PEN-0004", 9204L);

        List<PenalInterest> written = penalService.accrueThrough(a, LocalDate.of(2026, 7, 13)); // 9th..13th

        assertThat(written).hasSize(5);
        assertThat(written.get(0).getBusinessDate()).isEqualTo(LocalDate.of(2026, 7, 9));
    }

    @Test
    void theCapStopsChargingOnceReached() {
        setPenal(true, "24.0000", 0, "1.00"); // 1% of 10,000 = 100
        LoanAccount a = overdueAccount("PL-PEN-0005", 9205L);

        penalService.accrueThrough(a, LocalDate.of(2026, 8, 1)); // 27 days would cost about 177.53
        penalService.accrueThrough(a, LocalDate.of(2026, 8, 20));

        assertThat(onlyDemand(a).getPenalAccrued()).isEqualByComparingTo("100.0000");
    }

    @Test
    void aProductWithPenalInterestSwitchedOffChargesNothing() {
        setPenal(false, "24.0000", 0, null);
        LoanAccount a = overdueAccount("PL-PEN-0006", 9206L);

        assertThat(penalService.accrueThrough(a, LocalDate.of(2026, 7, 13))).isEmpty();
        assertThat(onlyDemand(a).getPenalAccrued()).isEqualByComparingTo("0");
    }

    @Test
    void theRateOnTheProductIsTheRateCharged() {
        setPenal(true, "18.0000", 0, null);
        LoanAccount a = overdueAccount("PL-PEN-0007", 9207L);

        List<PenalInterest> written = penalService.accrueThrough(a, LocalDate.of(2026, 7, 6));

        assertThat(written).hasSize(1);
        assertThat(written.get(0).getAnnualRate()).isEqualByComparingTo("18.0000");
        assertThat(written.get(0).getAmount()).isEqualByComparingTo("4.9315"); // 10,000 x 18% / 365
    }

    @Test
    void closedAndForeclosedAccountsAccrueNothing() {
        LoanAccount closed = overdueAccount("PL-PEN-0008", 9208L);
        closed.setAccountStatus(LoanAccount.AccountStatus.CLOSED);
        LoanAccount foreclosed = overdueAccount("PL-PEN-0009", 9209L);
        foreclosed.setAccountStatus(LoanAccount.AccountStatus.FORECLOSED);

        assertThat(penalService.accrueThrough(closed, LocalDate.of(2026, 7, 13))).isEmpty();
        assertThat(penalService.accrueThrough(foreclosed, LocalDate.of(2026, 7, 13))).isEmpty();
    }

    @Test
    void chargesOnAnNpaAccountAreFlaggedSuspendedFromSubstandardOnward() {
        LoanAccount sma = overdueAccount("PL-PEN-0010", 9210L);
        sma.setAccountStatus(LoanAccount.AccountStatus.NPA_SMA1);
        LoanAccount sub = overdueAccount("PL-PEN-0011", 9211L);
        sub.setAccountStatus(LoanAccount.AccountStatus.NPA_SUBSTANDARD);

        assertThat(penalService.accrueThrough(sma, LocalDate.of(2026, 7, 7)))
                .hasSize(2).allSatisfy(w -> assertThat(w.getSuspended()).isFalse());
        assertThat(penalService.accrueThrough(sub, LocalDate.of(2026, 7, 7)))
                .hasSize(2).allSatisfy(w -> assertThat(w.getSuspended()).isTrue());
    }

    @Test
    void aPaymentClearsPenalInterestFirstAndTheDemandStaysOpenUntilItIsAllPaid() {
        LoanAccount a = overdueAccount("PL-PEN-0012", 9212L);
        penalService.accrueThrough(a, LocalDate.of(2026, 7, 13)); // 52.60 owed
        List<LoanDemand> open = demandService.openDemands(a.getAccountId());

        PaymentWaterfall.Result first = PaymentWaterfall.apply(DemandService.toDues(open), new BigDecimal("10000.00"));
        demandService.applyLines(a, open, first.lines(), LocalDate.of(2026, 7, 14), 1L, PaymentAllocation.Source.PAYMENT);

        LoanDemand d = onlyDemand(a);
        assertThat(d.getPenalPaid()).isEqualByComparingTo("52.60");
        assertThat(d.getInterestPaid()).isEqualByComparingTo("800.00");
        assertThat(d.getPrincipalPaid()).isEqualByComparingTo("9147.40");
        assertThat(d.getStatus()).isEqualTo(DemandStatus.PART_PAID);
        assertThat(d.outstanding()).isEqualByComparingTo("52.60");

        List<LoanDemand> still = demandService.openDemands(a.getAccountId());
        PaymentWaterfall.Result rest = PaymentWaterfall.apply(DemandService.toDues(still), new BigDecimal("52.60"));
        demandService.applyLines(a, still, rest.lines(), LocalDate.of(2026, 7, 15), 2L, PaymentAllocation.Source.PAYMENT);

        assertThat(onlyDemand(a).getStatus()).isEqualTo(DemandStatus.CLEARED);
        assertThat(schedule.findByAccountIdAndScheduleVersionAndInstallmentNumber(a.getAccountId(), 1, 1).orElseThrow()
                .getInstallmentStatus()).isEqualTo(InstallmentStatus.PAID);
    }

    @Test
    void penalInterestIsChargedOnWhatIsStillUnpaidNotOnTheWholeDemand() {
        LoanAccount a = overdueAccount("PL-PEN-0013", 9213L);
        penalService.accrueThrough(a, LocalDate.of(2026, 7, 7)); // 6th and 7th on 10,000
        List<LoanDemand> open = demandService.openDemands(a.getAccountId());
        PaymentWaterfall.Result pay = PaymentWaterfall.apply(DemandService.toDues(open), new BigDecimal("5013.15"));
        demandService.applyLines(a, open, pay.lines(), LocalDate.of(2026, 7, 8), 1L, PaymentAllocation.Source.PAYMENT);
        // 13.15 penal (2 x 6.5753 = 13.1506 -> 13.15), then 5,000 off the demand, leaving 5,000 unpaid

        List<PenalInterest> written = penalService.accrueThrough(a, LocalDate.of(2026, 7, 10)); // 8th, 9th, 10th

        assertThat(written).hasSize(3);
        assertThat(written).allSatisfy(w -> {
            assertThat(w.getBaseAmount()).isEqualByComparingTo("5000.00"); // the 8th's close already has the payment
            assertThat(w.getAmount()).isEqualByComparingTo("3.2877");      // 5,000 x 24% / 365
        });
    }
}
