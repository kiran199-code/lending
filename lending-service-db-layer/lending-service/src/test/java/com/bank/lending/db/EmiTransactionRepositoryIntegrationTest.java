package com.bank.lending.db;

import com.bank.lending.account.domain.LoanAccount;
import com.bank.lending.account.repository.LoanAccountRepository;
import com.bank.lending.payment.domain.EmiTransaction;
import com.bank.lending.payment.domain.EmiTransaction.TransactionType;
import com.bank.lending.payment.repository.EmiTransactionRepository;
import com.bank.lending.product.repository.LoanProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the payment-record insert. emi_transaction is a partitioned table keyed by (transaction_id, value_date) and
 * the entity maps that as a composite id, which Hibernate cannot generate: saving a payment used to fail with
 * "Identity generation isn't supported for composite ids", so every EMI, part-payment and foreclosure returned 500.
 * The service now assigns transaction_id from the table's sequence; these tests save one record of each payment type
 * the way the service does and read it back.
 */
@Transactional
class EmiTransactionRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired private LoanAccountRepository loanAccountRepository;
    @Autowired private LoanProductRepository loanProductRepository;
    @Autowired private EmiTransactionRepository emiTransactionRepository;

    @Test
    void nextTransactionIdKeepsIncreasing() {
        Long first = emiTransactionRepository.nextTransactionId();
        Long second = emiTransactionRepository.nextTransactionId();

        assertThat(first).isNotNull();
        assertThat(second).isGreaterThan(first);
    }

    @Test
    void savesAndReadsBackEveryPaymentType() {
        LoanAccount account = loanAccountRepository.save(account("PL-EMI-0001", 8101L));
        LocalDate valueDate = LocalDate.of(2026, 7, 5);

        for (TransactionType type : List.of(TransactionType.EMI, TransactionType.PART_PAYMENT, TransactionType.FORECLOSURE)) {
            EmiTransaction saved = saveNew(account.getAccountId(), type, valueDate, "KEY-" + type);

            assertThat(saved.getTransactionId()).isNotNull();
            assertThat(emiTransactionRepository.findByAccountIdAndIdempotencyKey(account.getAccountId(), "KEY-" + type))
                    .isPresent()
                    .get()
                    .satisfies(row -> {
                        assertThat(row.getTransactionType()).isEqualTo(type);
                        assertThat(row.getTransactionId()).isEqualTo(saved.getTransactionId());
                    });
        }

        emiTransactionRepository.flush();
        assertThat(emiTransactionRepository.findByAccountIdOrderByValueDateDesc(account.getAccountId())).hasSize(3);
    }

    @Test
    void separatePaymentsGetSeparateIds() {
        LoanAccount account = loanAccountRepository.save(account("PL-EMI-0002", 8102L));

        EmiTransaction a = saveNew(account.getAccountId(), TransactionType.EMI, LocalDate.of(2026, 7, 5), "KEY-A");
        EmiTransaction b = saveNew(account.getAccountId(), TransactionType.EMI, LocalDate.of(2026, 7, 5), "KEY-B");

        assertThat(a.getTransactionId()).isNotEqualTo(b.getTransactionId());
    }

    /** Same steps as EmiPaymentService.saveNew. */
    private EmiTransaction saveNew(Long accountId, TransactionType type, LocalDate valueDate, String idempotencyKey) {
        EmiTransaction txn = EmiTransaction.builder()
                .accountId(accountId)
                .installmentNumber(type == TransactionType.EMI ? 1 : null)
                .transactionType(type)
                .amount(new BigDecimal("15000.00"))
                .principalPaid(new BigDecimal("12000.00"))
                .interestPaid(new BigDecimal("3000.00"))
                .paymentChannel(EmiTransaction.PaymentChannel.MANUAL)
                .paymentStatus(EmiTransaction.PaymentStatus.SUCCESS)
                .valueDate(valueDate)
                .idempotencyKey(idempotencyKey)
                .build();
        txn.setTransactionId(emiTransactionRepository.nextTransactionId());
        return emiTransactionRepository.save(txn);
    }

    private LoanAccount account(String accountNumber, long sanctionRefId) {
        return LoanAccount.builder()
                .accountNumber(accountNumber)
                .customerId(1L)
                .sanctionRefId(sanctionRefId)
                .productCode("PL01")
                .productId(loanProductRepository.findByProductCode("PL01").orElseThrow().getProductId())
                .accountType(LoanAccount.AccountType.UNSECURED)
                .sanctionedAmount(new BigDecimal("500000.00"))
                .tenureMonths(36)
                .rateType(LoanAccount.RateType.FIXED)
                .interestRate(new BigDecimal("11.5000"))
                .dayCountConvention(LoanAccount.DayCountConvention.ACT_365)
                .accountStatus(LoanAccount.AccountStatus.PENDING_DISBURSEMENT)
                .build();
    }
}
