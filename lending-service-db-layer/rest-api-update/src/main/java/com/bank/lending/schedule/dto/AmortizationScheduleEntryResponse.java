package com.bank.lending.schedule.dto;

import com.bank.lending.schedule.domain.AmortizationScheduleEntry;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record AmortizationScheduleEntryResponse(
        Long scheduleId,
        Long accountId,
        Integer installmentNumber,
        LocalDate dueDate,
        BigDecimal principalComponent,
        BigDecimal interestComponent,
        BigDecimal outstandingBalance,
        AmortizationScheduleEntry.InstallmentStatus installmentStatus,
        Integer scheduleVersion,
        OffsetDateTime createdAt
) {
    public static AmortizationScheduleEntryResponse from(AmortizationScheduleEntry e) {
        return new AmortizationScheduleEntryResponse(
                e.getScheduleId(), e.getAccountId(), e.getInstallmentNumber(), e.getDueDate(),
                e.getPrincipalComponent(), e.getInterestComponent(), e.getOutstandingBalance(),
                e.getInstallmentStatus(), e.getScheduleVersion(), e.getCreatedAt()
        );
    }
}
