package com.bank.lending.demand.repository;

import com.bank.lending.demand.domain.PenalInterest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface PenalInterestRepository extends JpaRepository<PenalInterest, Long> {

    /** Every charge on an account, oldest first. */
    List<PenalInterest> findByAccountIdOrderByBusinessDateAscDemandIdAsc(Long accountId);

    /** The last date penal interest was charged on a demand, or null if never. */
    @Query("SELECT MAX(p.businessDate) FROM PenalInterest p WHERE p.demandId = :demandId")
    LocalDate lastChargedDate(@Param("demandId") Long demandId);
}
