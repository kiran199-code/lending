package com.bank.lending.demand.repository;

import com.bank.lending.demand.domain.PaymentAllocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface PaymentAllocationRepository extends JpaRepository<PaymentAllocation, Long> {

    List<PaymentAllocation> findByTransactionIdAndValueDateOrderByAllocationIdAsc(Long transactionId, LocalDate valueDate);
}
