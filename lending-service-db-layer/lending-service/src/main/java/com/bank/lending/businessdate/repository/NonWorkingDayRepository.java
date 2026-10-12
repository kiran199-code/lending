package com.bank.lending.businessdate.repository;

import com.bank.lending.businessdate.domain.NonWorkingDay;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface NonWorkingDayRepository extends JpaRepository<NonWorkingDay, LocalDate> {

    List<NonWorkingDay> findByHolidayDateBetweenOrderByHolidayDate(LocalDate from, LocalDate to);
}
