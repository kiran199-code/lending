package com.bank.lending.businessdate.dto;

import com.bank.lending.businessdate.domain.BusinessDateStatus;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public record BusinessDateResponse(
        LocalDate businessDate,
        LocalDate previousBusinessDate,
        LocalDate nextBusinessDate,
        BusinessDateStatus status,
        boolean workingDay,
        LocalDate systemDate,
        String updatedBy,
        OffsetDateTime updatedAt
) {
}
