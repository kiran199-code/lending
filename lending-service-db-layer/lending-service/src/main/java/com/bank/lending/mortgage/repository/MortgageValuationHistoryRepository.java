package com.bank.lending.mortgage.repository;

import com.bank.lending.mortgage.domain.MortgageValuationHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MortgageValuationHistoryRepository extends JpaRepository<MortgageValuationHistory, Long> {

    List<MortgageValuationHistory> findByMortgageIdOrderByValuationDateDesc(Long mortgageId);
}
