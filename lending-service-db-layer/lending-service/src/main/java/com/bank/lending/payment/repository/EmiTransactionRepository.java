package com.bank.lending.payment.repository;

import com.bank.lending.payment.domain.EmiTransaction;
import com.bank.lending.payment.domain.EmiTransactionId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EmiTransactionRepository extends JpaRepository<EmiTransaction, EmiTransactionId> {

    List<EmiTransaction> findByAccountIdOrderByValueDateDesc(Long accountId);

    // Idempotency check before posting — see unique index on
    // (account_id, idempotency_key) in V6.
    Optional<EmiTransaction> findByAccountIdAndIdempotencyKey(Long accountId, String idempotencyKey);
}
