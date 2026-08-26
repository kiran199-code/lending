package com.bank.lending.schedule.repository;

import com.bank.lending.schedule.domain.AmortizationScheduleEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AmortizationScheduleRepository extends JpaRepository<AmortizationScheduleEntry, Long> {

    // "Current" schedule = the highest schedule_version for the account.
    @Query("SELECT MAX(s.scheduleVersion) FROM AmortizationScheduleEntry s WHERE s.accountId = :accountId")
    Optional<Integer> findCurrentVersion(@Param("accountId") Long accountId);

    List<AmortizationScheduleEntry> findByAccountIdAndScheduleVersionOrderByInstallmentNumber(
            Long accountId, Integer scheduleVersion);

    List<AmortizationScheduleEntry> findByAccountIdAndScheduleVersionAndInstallmentStatus(
            Long accountId, Integer scheduleVersion, AmortizationScheduleEntry.InstallmentStatus status);

    Optional<AmortizationScheduleEntry> findByAccountIdAndScheduleVersionAndInstallmentNumber(
            Long accountId, Integer scheduleVersion, Integer installmentNumber);
}
