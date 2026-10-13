package com.bank.lending.payment.repository;

import com.bank.lending.payment.domain.EmiTransaction;
import com.bank.lending.payment.domain.EmiTransactionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface EmiTransactionRepository extends JpaRepository<EmiTransaction, EmiTransactionId> {

    List<EmiTransaction> findByAccountIdOrderByValueDateDesc(Long accountId);

    // Idempotency check before posting — see unique index on
    // (account_id, idempotency_key) in V6.
    Optional<EmiTransaction> findByAccountIdAndIdempotencyKey(Long accountId, String idempotencyKey);

    /**
     * Next value of the sequence behind emi_transaction.transaction_id (a BIGSERIAL on a partitioned table).
     * The id is a part of the composite key (transaction_id, value_date), which Hibernate cannot generate,
     * so the service assigns it before saving. pg_get_serial_sequence avoids hard-coding the sequence name.
     */
    @Query(value = "SELECT nextval(pg_get_serial_sequence('emi_transaction', 'transaction_id'))", nativeQuery = true)
    Long nextTransactionId();
}
