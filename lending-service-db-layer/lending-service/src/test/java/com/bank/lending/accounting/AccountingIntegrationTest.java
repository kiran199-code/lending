package com.bank.lending.accounting;

import com.bank.lending.account.domain.LoanAccount;
import com.bank.lending.account.repository.LoanAccountRepository;
import com.bank.lending.accounting.dto.AccountingDtos.ReconciliationResult;
import com.bank.lending.accounting.dto.AccountingDtos.ReconciliationRow;
import com.bank.lending.accounting.repository.AccountingConfigRepository;
import com.bank.lending.accounting.repository.JournalRepository;
import com.bank.lending.accounting.service.AccountingPoster;
import com.bank.lending.accounting.service.ReconciliationService;
import com.bank.lending.db.AbstractIntegrationTest;
import com.bank.lending.demand.service.DemandService;
import com.bank.lending.payment.domain.EmiTransaction.PaymentChannel;
import com.bank.lending.payment.service.EmiPaymentService;
import com.bank.lending.product.repository.LoanProductRepository;
import com.bank.lending.schedule.domain.AmortizationScheduleEntry;
import com.bank.lending.schedule.domain.AmortizationScheduleEntry.InstallmentStatus;
import com.bank.lending.schedule.repository.AmortizationScheduleRepository;
import com.bank.lending.schedule.service.InterestAccrualService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The accounting module wired into the loan flows, against a real database: that each business event leaves the
 * journal balances the loan tables imply, that repeating an event journals nothing twice, and that the reconciliation
 * agrees. Each test works in its own account and rolls back.
 *
 * <p>Account shape: 18,400 disbursed; two instalments of 9,200 principal + 800 interest due 5 Jul and 5 Aug 2026;
 * 11.5% p.a. ACT/365, so a day's interest on 18,400 is 5.7973 and four days (1-4 Jul) are 23.1892, journalled as 23.19.
 *
 * <p>The reconciliation covers every account in the database, so these tests assert that <em>their</em> account does
 * not appear among the differences rather than that the whole run passes.
 */
@Transactional
class AccountingIntegrationTest extends AbstractIntegrationTest {

    private static final LocalDate JUL_1 = LocalDate.of(2026, 7, 1);
    private static final LocalDate DUE = LocalDate.of(2026, 7, 5);

    private static final String PRINCIPAL_NOT_DUE = "LOAN_PRINCIPAL";
    private static final String PRINCIPAL_DUE = "PRINCIPAL_DUE";
    private static final String INT_ACCRUED = "INT_ACCRUED_RECEIVABLE";
    private static final String INT_DUE = "INT_DUE_RECEIVABLE";
    private static final String PENAL = "PENAL_RECEIVABLE";
    private static final String ADVANCE = "ADVANCE_LIABILITY";

    @Autowired private LoanAccountRepository accounts;
    @Autowired private LoanProductRepository products;
    @Autowired private AmortizationScheduleRepository schedule;
    @Autowired private DemandService demandService;
    @Autowired private EmiPaymentService payments;
    @Autowired private InterestAccrualService accruals;
    @Autowired private AccountingPoster poster;
    @Autowired private JournalRepository journals;
    @Autowired private AccountingConfigRepository config;
    @Autowired private ReconciliationService reconciliation;

    private LoanAccount disbursedAccount(String number, long sanctionRef) {
        LoanAccount a = accounts.save(LoanAccount.builder()
                .accountNumber(number).customerId(1L).sanctionRefId(sanctionRef).productCode("PL01")
                .productId(products.findByProductCode("PL01").orElseThrow().getProductId())
                .accountType(LoanAccount.AccountType.UNSECURED)
                .sanctionedAmount(new BigDecimal("18400.00")).tenureMonths(2)
                .rateType(LoanAccount.RateType.FIXED).interestRate(new BigDecimal("11.5000"))
                .dayCountConvention(LoanAccount.DayCountConvention.ACT_365)
                .emiStartDate(JUL_1)
                .accountStatus(LoanAccount.AccountStatus.ACTIVE).build());
        for (int i = 1; i <= 2; i++) {
            schedule.save(AmortizationScheduleEntry.builder().accountId(a.getAccountId()).installmentNumber(i)
                    .dueDate(DUE.plusMonths(i - 1))
                    .principalComponent(new BigDecimal("9200.00")).interestComponent(new BigDecimal("800.00"))
                    .outstandingBalance(new BigDecimal("18400.00").subtract(new BigDecimal("9200.00").multiply(BigDecimal.valueOf(i))))
                    .installmentStatus(InstallmentStatus.PENDING).scheduleVersion(1).build());
        }
        poster.disbursed(a, new BigDecimal("18400.00"), BigDecimal.ZERO);
        return a;
    }

    private void accrue(LoanAccount a, LocalDate from, LocalDate to) {
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            accruals.accrueAccount(a.getAccountId(), d);
        }
    }

    private BigDecimal balance(LoanAccount a, String role) {
        int ledger = config.primaryLedger().orElseThrow().ledgerId();
        return journals.netDebit(a.getAccountId(), ledger, role);
    }

    private void assertBalances(LoanAccount a, String principalNotDue, String principalDue, String intAccrued,
                                String intDue, String penal, String advance) {
        assertThat(balance(a, PRINCIPAL_NOT_DUE)).as("principal not yet due").isEqualByComparingTo(principalNotDue);
        assertThat(balance(a, PRINCIPAL_DUE)).as("principal due").isEqualByComparingTo(principalDue);
        assertThat(balance(a, INT_ACCRUED)).as("interest accrued").isEqualByComparingTo(intAccrued);
        assertThat(balance(a, INT_DUE)).as("interest due").isEqualByComparingTo(intDue);
        assertThat(balance(a, PENAL)).as("penal interest").isEqualByComparingTo(penal);
        assertThat(balance(a, ADVANCE).negate()).as("advance held").isEqualByComparingTo(advance);
    }

    /** The account must not be among the accounts any reconciliation bucket reports as differing. */
    private void assertReconciled(LoanAccount a) {
        ReconciliationResult result = reconciliation.run(null);
        String marker = "account " + a.getAccountId() + ":";
        for (ReconciliationRow row : result.checks()) {
            if (row.checkCode().startsWith("RECON_")) {
                String detail = row.detail() == null ? "" : row.detail();
                assertThat(detail).as(row.checkCode()).doesNotContain(marker);
            }
        }
        assertThat(result.checks()).filteredOn(r -> "LEDGER_BALANCED".equals(r.checkCode()))
                .allSatisfy(r -> assertThat(r.status()).isEqualTo("PASS"));
    }

    @Test
    void disbursementPutsThePrincipalOnTheBooks() {
        LoanAccount a = disbursedAccount("PL-ACC-0001", 9301L);

        assertBalances(a, "18400.00", "0", "0", "0", "0", "0");
        assertReconciled(a);
    }

    @Test
    void accrualJournalsTheRoundedRunningTotal() {
        LoanAccount a = disbursedAccount("PL-ACC-0002", 9302L);

        accrue(a, JUL_1, JUL_1.plusDays(3)); // 4 days = 23.1892

        assertBalances(a, "18400.00", "0", "23.19", "0", "0", "0");
        assertReconciled(a);
    }

    @Test
    void runningTheSameAccrualAgainJournalsNothingNew() {
        LoanAccount a = disbursedAccount("PL-ACC-0003", 9303L);
        accrue(a, JUL_1, JUL_1.plusDays(3));
        long before = journals.countForAccount(a.getAccountId());

        accrue(a, JUL_1, JUL_1.plusDays(3));

        assertThat(journals.countForAccount(a.getAccountId())).isEqualTo(before);
        assertBalances(a, "18400.00", "0", "23.19", "0", "0", "0");
    }

    @Test
    void raisingTheDemandMovesBothComponentsAndTrueUpsInterestToTheInstalment() {
        LoanAccount a = disbursedAccount("PL-ACC-0004", 9304L);
        accrue(a, JUL_1, JUL_1.plusDays(3));

        demandService.raiseDue(a, DUE);

        // interest of 800 falls due: the 23.19 accrued is topped up by 776.81, then all 800 moves to "due"
        assertBalances(a, "9200.00", "9200.00", "0", "800.00", "0", "0");
        assertReconciled(a);
    }

    @Test
    void raisingTheDemandAgainJournalsNothingNew() {
        LoanAccount a = disbursedAccount("PL-ACC-0005", 9305L);
        accrue(a, JUL_1, JUL_1.plusDays(3));
        demandService.raiseDue(a, DUE);
        long before = journals.countForAccount(a.getAccountId());

        demandService.raiseDue(a, DUE);

        assertThat(journals.countForAccount(a.getAccountId())).isEqualTo(before);
    }

    @Test
    void paymentOnTheDueDateClearsTheDemand() {
        LoanAccount a = disbursedAccount("PL-ACC-0006", 9306L);
        accrue(a, JUL_1, JUL_1.plusDays(3));

        payments.postEmiPayment(a.getAccountId(), DUE, new BigDecimal("10000.00"), PaymentChannel.MANUAL, "ACC-PAY-6");

        assertBalances(a, "9200.00", "0", "0", "0", "0", "0");
        assertReconciled(a);
    }

    @Test
    void repeatingAPaymentWithTheSameKeyJournalsNothingNew() {
        LoanAccount a = disbursedAccount("PL-ACC-0007", 9307L);
        accrue(a, JUL_1, JUL_1.plusDays(3));
        payments.postEmiPayment(a.getAccountId(), DUE, new BigDecimal("10000.00"), PaymentChannel.MANUAL, "ACC-PAY-7");
        long before = journals.countForAccount(a.getAccountId());

        payments.postEmiPayment(a.getAccountId(), DUE, new BigDecimal("10000.00"), PaymentChannel.MANUAL, "ACC-PAY-7");

        assertThat(journals.countForAccount(a.getAccountId())).isEqualTo(before);
        assertBalances(a, "9200.00", "0", "0", "0", "0", "0");
    }

    @Test
    void aLatePaymentSettlesPenalInterestAndHoldsTheRestAsAdvance() {
        LoanAccount a = disbursedAccount("PL-ACC-0008", 9308L);
        accrue(a, JUL_1, JUL_1.plusDays(3));

        // paid on 10 Jul: penal runs 6th..9th, 4 days x 6.5753 = 26.3012, owed as 26.30
        payments.postEmiPayment(a.getAccountId(), LocalDate.of(2026, 7, 10), new BigDecimal("10100.00"),
                PaymentChannel.MANUAL, "ACC-PAY-8");

        // 10,000 + 26.30 settled; the other 73.70 is held as advance for the next instalment
        assertBalances(a, "9200.00", "0", "0", "0", "0", "73.70");
        assertReconciled(a);
    }

    @Test
    void foreclosureClosesEveryBucket() {
        LoanAccount a = disbursedAccount("PL-ACC-0009", 9309L);
        accrue(a, JUL_1, JUL_1.plusDays(3));

        // 4 Jul, before the first instalment is due: all 18,400 principal and 4 days' interest are paid off
        payments.foreclose(a.getAccountId(), LocalDate.of(2026, 7, 4), PaymentChannel.MANUAL, "ACC-FC-9");

        assertBalances(a, "0", "0", "0", "0", "0", "0");
        assertReconciled(a);
    }
}
