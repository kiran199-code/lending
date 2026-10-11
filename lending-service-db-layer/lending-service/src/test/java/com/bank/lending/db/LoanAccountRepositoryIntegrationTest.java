package com.bank.lending.db;

import com.bank.lending.account.domain.LoanAccount;
import com.bank.lending.account.repository.LoanAccountRepository;
import com.bank.lending.mortgage.domain.Mortgage;
import com.bank.lending.mortgage.repository.MortgageRepository;
import com.bank.lending.payment.domain.EmiTransaction;
import com.bank.lending.product.repository.LoanProductRepository;
import com.bank.lending.payment.repository.EmiTransactionRepository;
import com.bank.lending.schedule.domain.AmortizationScheduleEntry;
import com.bank.lending.schedule.repository.AmortizationScheduleRepository;
import com.bank.lending.schedule.repository.InterestAccrualRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class LoanAccountRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired private LoanAccountRepository loanAccountRepository;
    @Autowired private LoanProductRepository loanProductRepository;
    @Autowired private MortgageRepository mortgageRepository;
    @Autowired private AmortizationScheduleRepository scheduleRepository;
    @Autowired private InterestAccrualRepository accrualRepository;
    @Autowired private EmiTransactionRepository emiTransactionRepository;

    @Test
    void createsUnsecuredRetailLoanAccount() {
        LoanAccount account = loanAccountRepository.save(unsecuredAccount("PL-000001", 1001L));

        assertThat(account.getAccountId()).isNotNull();
        assertThat(account.getAccountStatus()).isEqualTo(LoanAccount.AccountStatus.PENDING_DISBURSEMENT);
        assertThat(account.getVersion()).isZero();
    }

    @Test
    void duplicateSanctionRefIsRejectedAtDbLevel_enforcingIdempotency() {
        loanAccountRepository.save(unsecuredAccount("PL-000002", 2001L));
        loanAccountRepository.flush();

        assertThatThrownBy(() -> {
            loanAccountRepository.save(unsecuredAccount("PL-000003", 2001L)); // same sanctionRefId
            loanAccountRepository.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void createsMortgageLoanAccountWithCircularLink() {
        // Step 1: create loan_account without mortgage_id (mirrors real flow —
        // mortgage.account_id needs the account to exist first)
        LoanAccount account = loanAccountRepository.save(
                LoanAccount.builder()
                        .accountNumber("HL-000001")
                        .customerId(3001L)
                        .sanctionRefId(3001L)
                        .productCode("HL01")
                        .productId(productId("HL01"))
                        .accountType(LoanAccount.AccountType.MORTGAGE)
                        .sanctionedAmount(new BigDecimal("5000000.00"))
                        .tenureMonths(240)
                        .rateType(LoanAccount.RateType.FLOATING)
                        .interestRate(new BigDecimal("8.5000"))
                        .benchmarkRateRef("REPO")
                        .spread(new BigDecimal("2.0000"))
                        .dayCountConvention(LoanAccount.DayCountConvention.ACT_365)
                        .accountStatus(LoanAccount.AccountStatus.PENDING_DISBURSEMENT)
                        .build()
        );

        // Step 2: create mortgage referencing the account
        Mortgage mortgage = mortgageRepository.save(
                Mortgage.builder()
                        .accountId(account.getAccountId())
                        .propertyType(Mortgage.PropertyType.RESIDENTIAL)
                        .propertyAddress("Flat 302, Aparna Moonstone, Gopanpally, Hyderabad")
                        .valuationAmount(new BigDecimal("6250000.00"))
                        .valuationDate(LocalDate.of(2026, 6, 1))
                        .valuerRef("VAL-2026-0456")
                        .ltvRatio(new BigDecimal("80.00"))
                        .chargeStatus(Mortgage.ChargeStatus.PENDING)
                        .legalVerificationStatus(Mortgage.LegalVerificationStatus.PENDING)
                        .build()
        );

        // Step 3: link back — account.mortgage_id -> mortgage.mortgage_id
        account.setMortgageId(mortgage.getMortgageId());
        loanAccountRepository.save(account);
        loanAccountRepository.flush();

        LoanAccount reloaded = loanAccountRepository.findById(account.getAccountId()).orElseThrow();
        assertThat(reloaded.getMortgageId()).isEqualTo(mortgage.getMortgageId());

        Mortgage reloadedMortgage = mortgageRepository.findByAccountId(account.getAccountId()).orElseThrow();
        assertThat(reloadedMortgage.getChargeStatus()).isEqualTo(Mortgage.ChargeStatus.PENDING);
    }

    @Test
    void unsecuredAccountCannotCarryAMortgageId_dbCheckConstraintEnforced() {
        LoanAccount account = unsecuredAccount("PL-000004", 4001L);
        account.setMortgageId(999L); // invalid: unsecured account with a mortgage link

        assertThatThrownBy(() -> {
            loanAccountRepository.save(account);
            loanAccountRepository.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void amortizationScheduleStoresMultipleVersionsAndCurrentVersionIsMax() {
        LoanAccount account = loanAccountRepository.save(unsecuredAccount("PL-000005", 5001L));

        scheduleRepository.save(scheduleEntry(account.getAccountId(), 1, 1));
        scheduleRepository.save(scheduleEntry(account.getAccountId(), 1, 2)); // regenerated version

        Optional<Integer> currentVersion = scheduleRepository.findCurrentVersion(account.getAccountId());
        assertThat(currentVersion).contains(2);

        List<AmortizationScheduleEntry> v2Entries =
                scheduleRepository.findByAccountIdAndScheduleVersionOrderByInstallmentNumber(account.getAccountId(), 2);
        assertThat(v2Entries).hasSize(1);
    }

    @Test
    void interestAccrualBatchRerunForSameBusinessDateDoesNotDuplicate() {
        LoanAccount account = loanAccountRepository.save(unsecuredAccount("PL-000006", 6001L));
        LocalDate businessDate = LocalDate.of(2026, 7, 6);

        int firstInsert = accrualRepository.insertIfAbsent(
                account.getAccountId(), businessDate, new BigDecimal("125.5000"), new BigDecimal("500000.00"));
        int secondInsert = accrualRepository.insertIfAbsent( // simulate batch re-run
                account.getAccountId(), businessDate, new BigDecimal("125.5000"), new BigDecimal("500000.00"));

        assertThat(firstInsert).isEqualTo(1);
        assertThat(secondInsert).isEqualTo(0); // ON CONFLICT DO NOTHING — idempotent

        Optional<com.bank.lending.schedule.domain.InterestAccrual> row =
                accrualRepository.findByAccountIdAndBusinessDate(account.getAccountId(), businessDate);
        assertThat(row).isPresent();
    }

    @Test
    void emiTransactionIdempotencyKeyPreventsDuplicatePosting() {
        LoanAccount account = loanAccountRepository.save(unsecuredAccount("PL-000007", 7001L));

        EmiTransaction txn = EmiTransaction.builder()
                .accountId(account.getAccountId())
                .installmentNumber(1)
                .transactionType(EmiTransaction.TransactionType.EMI)
                .amount(new BigDecimal("15000.00"))
                .principalPaid(new BigDecimal("12000.00"))
                .interestPaid(new BigDecimal("3000.00"))
                .paymentChannel(EmiTransaction.PaymentChannel.NACH)
                .paymentStatus(EmiTransaction.PaymentStatus.SUCCESS)
                .valueDate(LocalDate.of(2026, 7, 5))
                .idempotencyKey("NACH-BATCH-20260705-ACC7001-INST1")
                .build();

        emiTransactionRepository.save(txn);
        emiTransactionRepository.flush();

        assertThatThrownBy(() -> {
            EmiTransaction duplicate = EmiTransaction.builder()
                    .accountId(account.getAccountId())
                    .installmentNumber(1)
                    .transactionType(EmiTransaction.TransactionType.EMI)
                    .amount(new BigDecimal("15000.00"))
                    .paymentChannel(EmiTransaction.PaymentChannel.NACH)
                    .paymentStatus(EmiTransaction.PaymentStatus.SUCCESS)
                    .valueDate(LocalDate.of(2026, 7, 5))
                    .idempotencyKey("NACH-BATCH-20260705-ACC7001-INST1") // same key, same account
                    .build();
            emiTransactionRepository.save(duplicate);
            emiTransactionRepository.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---- fixtures ----

    /** Products PL01 / HL01 are seeded by migration V10. */
    private Long productId(String productCode) {
        return loanProductRepository.findByProductCode(productCode).orElseThrow().getProductId();
    }

    private LoanAccount unsecuredAccount(String accountNumber, long sanctionRefId) {
        return LoanAccount.builder()
                .accountNumber(accountNumber)
                .customerId(1L)
                .sanctionRefId(sanctionRefId)
                .productCode("PL01")
                .productId(productId("PL01"))
                .accountType(LoanAccount.AccountType.UNSECURED)
                .sanctionedAmount(new BigDecimal("500000.00"))
                .tenureMonths(36)
                .rateType(LoanAccount.RateType.FIXED)
                .interestRate(new BigDecimal("11.5000"))
                .dayCountConvention(LoanAccount.DayCountConvention.ACT_365)
                .accountStatus(LoanAccount.AccountStatus.PENDING_DISBURSEMENT)
                .build();
    }

    private AmortizationScheduleEntry scheduleEntry(Long accountId, int installmentNumber, int version) {
        return AmortizationScheduleEntry.builder()
                .accountId(accountId)
                .installmentNumber(installmentNumber)
                .dueDate(LocalDate.of(2026, 8, 5))
                .principalComponent(new BigDecimal("12000.00"))
                .interestComponent(new BigDecimal("3000.00"))
                .outstandingBalance(new BigDecimal("488000.00"))
                .installmentStatus(AmortizationScheduleEntry.InstallmentStatus.PENDING)
                .scheduleVersion(version)
                .build();
    }
}
