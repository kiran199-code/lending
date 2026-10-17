package com.bank.lending.db;

import com.bank.lending.account.domain.LoanAccount;
import com.bank.lending.account.repository.LoanAccountRepository;
import com.bank.lending.demand.domain.LoanDemand;
import com.bank.lending.demand.domain.LoanDemand.DemandStatus;
import com.bank.lending.demand.domain.PaymentAllocation;
import com.bank.lending.demand.repository.LoanDemandRepository;
import com.bank.lending.demand.repository.PaymentAllocationRepository;
import com.bank.lending.demand.service.DemandService;
import com.bank.lending.demand.util.PaymentWaterfall;
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
 * Demands against a real database: what gets raised for which due dates, that raising twice changes nothing, how an
 * account that existed before demands (some instalments already PAID) is brought across, and that money applied to a
 * demand clears it and marks the schedule row PAID.
 */
@Transactional
class DemandServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired private LoanAccountRepository accounts;
    @Autowired private LoanProductRepository products;
    @Autowired private AmortizationScheduleRepository schedule;
    @Autowired private LoanDemandRepository demandRepository;
    @Autowired private PaymentAllocationRepository allocationRepository;
    @Autowired private DemandService demandService;

    private static final LocalDate JULY = LocalDate.of(2026, 7, 5);

    /** Instalments 1..4 due on the 5th of Jul..Oct, each 800 interest + 9,200 principal. */
    private LoanAccount accountWithSchedule(String number, long sanctionRef, InstallmentStatus firstStatus) {
        LoanAccount a = accounts.save(LoanAccount.builder()
                .accountNumber(number).customerId(1L).sanctionRefId(sanctionRef).productCode("PL01")
                .productId(products.findByProductCode("PL01").orElseThrow().getProductId())
                .accountType(LoanAccount.AccountType.UNSECURED)
                .sanctionedAmount(new BigDecimal("500000.00")).tenureMonths(36)
                .rateType(LoanAccount.RateType.FIXED).interestRate(new BigDecimal("11.5000"))
                .dayCountConvention(LoanAccount.DayCountConvention.ACT_365)
                .accountStatus(LoanAccount.AccountStatus.PENDING_DISBURSEMENT).build());
        for (int i = 1; i <= 4; i++) {
            schedule.save(AmortizationScheduleEntry.builder().accountId(a.getAccountId()).installmentNumber(i)
                    .dueDate(JULY.plusMonths(i - 1))
                    .principalComponent(new BigDecimal("9200.00")).interestComponent(new BigDecimal("800.00"))
                    .outstandingBalance(new BigDecimal("400000.00").subtract(new BigDecimal("9200.00").multiply(BigDecimal.valueOf(i))))
                    .installmentStatus(i == 1 ? firstStatus : InstallmentStatus.PENDING).scheduleVersion(1).build());
        }
        return a;
    }

    @Test
    void raisesADemandOnlyForInstalmentsDueOnOrBeforeTheDate() {
        LoanAccount a = accountWithSchedule("PL-DEM-0001", 9101L, InstallmentStatus.PENDING);

        int created = demandService.raiseDue(a, LocalDate.of(2026, 9, 5));

        assertThat(created).isEqualTo(3);
        List<LoanDemand> all = demandRepository.findByAccountIdOrderByInstallmentNumberAsc(a.getAccountId());
        assertThat(all).extracting(LoanDemand::getInstallmentNumber).containsExactly(1, 2, 3);
        assertThat(all).allSatisfy(d -> {
            assertThat(d.getStatus()).isEqualTo(DemandStatus.OPEN);
            assertThat(d.outstanding()).isEqualByComparingTo("10000.00");
        });
        assertThat(all.get(0).getDueDate()).isEqualTo(JULY);
    }

    @Test
    void raisingAgainCreatesNothingNew() {
        LoanAccount a = accountWithSchedule("PL-DEM-0002", 9102L, InstallmentStatus.PENDING);

        demandService.raiseDue(a, LocalDate.of(2026, 8, 5));
        int again = demandService.raiseDue(a, LocalDate.of(2026, 8, 5));
        int later = demandService.raiseDue(a, LocalDate.of(2026, 9, 5));

        assertThat(again).isZero();
        assertThat(later).isEqualTo(1);
        assertThat(demandRepository.findByAccountIdOrderByInstallmentNumberAsc(a.getAccountId())).hasSize(3);
    }

    @Test
    void anInstalmentPaidBeforeDemandsExistedBecomesAClearedDemand() {
        LoanAccount a = accountWithSchedule("PL-DEM-0003", 9103L, InstallmentStatus.PAID);

        demandService.raiseDue(a, LocalDate.of(2026, 8, 5));

        List<LoanDemand> all = demandRepository.findByAccountIdOrderByInstallmentNumberAsc(a.getAccountId());
        assertThat(all.get(0).getStatus()).isEqualTo(DemandStatus.CLEARED);
        assertThat(all.get(0).outstanding()).isEqualByComparingTo("0");
        assertThat(all.get(1).getStatus()).isEqualTo(DemandStatus.OPEN);
        assertThat(demandService.openDemands(a.getAccountId())).extracting(LoanDemand::getInstallmentNumber).containsExactly(2);
    }

    @Test
    void moneyAppliedToAnOldDemandClearsItAndMarksTheScheduleRowPaid() {
        LoanAccount a = accountWithSchedule("PL-DEM-0004", 9104L, InstallmentStatus.OVERDUE);
        demandService.raiseDue(a, LocalDate.of(2026, 8, 5));
        List<LoanDemand> open = demandService.openDemands(a.getAccountId());

        PaymentWaterfall.Result result = PaymentWaterfall.apply(DemandService.toDues(open), new BigDecimal("10500.00"));
        demandService.applyLines(a, open, result.lines(), LocalDate.of(2026, 8, 6), 1L, PaymentAllocation.Source.PAYMENT);

        List<LoanDemand> all = demandRepository.findByAccountIdOrderByInstallmentNumberAsc(a.getAccountId());
        assertThat(all.get(0).getStatus()).isEqualTo(DemandStatus.CLEARED);
        assertThat(all.get(1).getStatus()).isEqualTo(DemandStatus.PART_PAID);
        assertThat(all.get(1).getInterestPaid()).isEqualByComparingTo("500.00");
        assertThat(schedule.findByAccountIdAndScheduleVersionAndInstallmentNumber(a.getAccountId(), 1, 1).orElseThrow()
                .getInstallmentStatus()).isEqualTo(InstallmentStatus.PAID);
        assertThat(allocationRepository.findAll()).filteredOn(l -> a.getAccountId().equals(l.getAccountId()))
                .extracting(l -> l.getAmount().toPlainString())
                .containsExactlyInAnyOrder("800.00", "9200.00", "500.00");
    }

    @Test
    void anAdvanceHeldOnTheAccountIsAppliedWhenTheNextDemandIsRaised() {
        LoanAccount a = accountWithSchedule("PL-DEM-0005", 9105L, InstallmentStatus.PAID);
        a.setAdvanceBalance(new BigDecimal("4000.00"));
        accounts.save(a);

        demandService.raiseDue(a, LocalDate.of(2026, 8, 5)); // demand 2 raised, 4,000 of the advance goes to it

        LoanDemand second = demandRepository.findByAccountIdOrderByInstallmentNumberAsc(a.getAccountId()).get(1);
        assertThat(second.getInterestPaid()).isEqualByComparingTo("800.00");
        assertThat(second.getPrincipalPaid()).isEqualByComparingTo("3200.00");
        assertThat(second.getStatus()).isEqualTo(DemandStatus.PART_PAID);
        assertThat(a.getAdvanceBalance()).isEqualByComparingTo("0");
    }
}
