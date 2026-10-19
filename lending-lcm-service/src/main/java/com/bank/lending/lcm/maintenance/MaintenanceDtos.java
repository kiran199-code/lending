package com.bank.lending.lcm.maintenance;

import java.time.OffsetDateTime;
import java.util.Map;

public final class MaintenanceDtos {
    private MaintenanceDtos() {}

    /** Whether clearing is switched on, and how many rows each table holds now. */
    public record StatusView(boolean resetEnabled, Map<String, Long> rowCounts) {}

    /** Request body of the clear: the caller types the confirmation word. */
    public record ClearRequest(String confirm) {}

    /** What a clear removed: the rows each table held just before it was emptied. */
    public record ClearResult(Map<String, Long> deleted, long totalRows, String clearedBy, OffsetDateTime clearedAt, String message) {}
}
