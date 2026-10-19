package com.bank.lending.maintenance.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;

/** What a clear removed (the rows each table held just before it was emptied) and the business date it left behind. */
public record ClearDataResponse(
        Map<String, Long> deleted,
        long totalRows,
        String clearedBy,
        OffsetDateTime clearedAt,
        LocalDate businessDate,
        String message
) {
}
