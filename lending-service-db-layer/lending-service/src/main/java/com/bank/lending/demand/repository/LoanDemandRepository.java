package com.bank.lending.demand.repository;

import com.bank.lending.demand.domain.LoanDemand;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LoanDemandRepository extends JpaRepository<LoanDemand, Long> {

    /** Demands that still have something to pay, oldest instalment first. */
    List<LoanDemand> findByAccountIdAndStatusNotOrderByInstallmentNumberAsc(Long accountId, LoanDemand.DemandStatus status);

    List<LoanDemand> findByAccountIdOrderByInstallmentNumberAsc(Long accountId);

    /** The most recently due demand, cleared or not. */
    Optional<LoanDemand> findTopByAccountIdOrderByDueDateDesc(Long accountId);

    @Query("SELECT d.installmentNumber FROM LoanDemand d WHERE d.accountId = :accountId")
    List<Integer> findDemandedInstallmentNumbers(@Param("accountId") Long accountId);
}
