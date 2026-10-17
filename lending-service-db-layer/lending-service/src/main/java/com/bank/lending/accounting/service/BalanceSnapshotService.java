package com.bank.lending.accounting.service;

import com.bank.lending.accounting.repository.ReconciliationRepository;
import com.bank.lending.businessdate.service.BusinessDateService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Writes the daily balance of every GL account (opening, debits, credits, closing as net debit). Re-running for a date
 * replaces that date's rows, so the end-of-day job can safely be repeated.
 */
@Service
public class BalanceSnapshotService {

    private final ReconciliationRepository repository;
    private final BusinessDateService businessDates;

    public BalanceSnapshotService(ReconciliationRepository repository, BusinessDateService businessDates) {
        this.repository = repository;
        this.businessDates = businessDates;
    }

    /** @return how many account balances were written */
    @Transactional
    public int snapshot(LocalDate requestedDate) {
        LocalDate date = requestedDate == null ? businessDates.currentDate() : requestedDate;
        businessDates.assertNotFuture(date, "businessDate");
        return repository.snapshot(date);
    }
}
