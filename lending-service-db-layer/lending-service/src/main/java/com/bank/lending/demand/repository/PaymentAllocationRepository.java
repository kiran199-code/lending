package com.bank.lending.demand.repository;

import com.bank.lending.demand.domain.PaymentAllocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface PaymentAllocationRepository extends JpaRepository<PaymentAllocation, Long> {

    List<PaymentAllocation> findByTransactionIdAndValueDateOrderByAllocationIdAsc(Long transactionId, LocalDate valueDate);

    /** Lines applied to a demand in the given buckets, oldest value date first. */
    List<PaymentAllocation> findByDemandIdAndBucketInOrderByValueDateAsc(Long demandId,
                                                                          Collection<PaymentAllocation.Bucket> buckets);
}
