package com.bank.lending.businessdate.repository;

import com.bank.lending.businessdate.domain.BusinessDateHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface BusinessDateHistoryRepository extends JpaRepository<BusinessDateHistory, LocalDate> {

    List<BusinessDateHistory> findTop30ByOrderByBusinessDateDesc();
}
