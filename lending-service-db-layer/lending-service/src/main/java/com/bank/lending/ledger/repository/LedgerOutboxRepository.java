package com.bank.lending.ledger.repository;

import com.bank.lending.ledger.domain.LedgerOutboxEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface LedgerOutboxRepository extends JpaRepository<LedgerOutboxEntry, Long> {

    // Poller picks up PENDING rows oldest-first.
    @Query("SELECT o FROM LedgerOutboxEntry o WHERE o.postingStatus = 'PENDING' ORDER BY o.createdAt ASC")
    List<LedgerOutboxEntry> findPendingOrderByCreatedAt(Pageable pageable);
}
