package com.bank.lending.mortgage.repository;

import com.bank.lending.mortgage.domain.Mortgage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MortgageRepository extends JpaRepository<Mortgage, Long> {

    Optional<Mortgage> findByAccountId(Long accountId);

    List<Mortgage> findByChargeStatus(Mortgage.ChargeStatus chargeStatus);

    // Supports the quarterly "revaluation due" flagging job — mortgages whose
    // last valuation predates the cutoff (e.g. now() - 3 years).
    @Query("SELECT m FROM Mortgage m WHERE m.valuationDate < :cutoffDate")
    List<Mortgage> findDueForRevaluation(LocalDate cutoffDate);
}
