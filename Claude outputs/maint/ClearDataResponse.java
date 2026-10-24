package com.bank.lending.maintenance.dto;

import java.time.OffsetDateTime;
import java.util.Map;

/** What a clear removed: the number of rows each table held just before it was emptied. */
public record ClearDataResponse(
        Map<String, Long> deleted,
        long totalRows,
        String clearedBy,
        OffsetDateTime clearedAt,
        String message
) {
}
