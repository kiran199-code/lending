package com.bank.lending.batch.maintenance;

import java.time.OffsetDateTime;
import java.util.Map;

public final class MaintenanceDtos {
    private MaintenanceDtos() {}

    /**
     * Whether clearing is switched on, whether something is running right now (which would make a clear refuse), and how many
     * rows each table holds.
     */
    public record StatusView(boolean resetEnabled, boolean busy, String busyReason, Map<String, Long> rowCounts) {}

    /** Request body of the clear: the caller types the confirmation word. */
    public record ClearRequest(String confirm) {}

    /** What a clear removed: the rows each table held just before it was emptied. */
    public record ClearResult(Map<String, Long> deleted, long totalRows, String clearedBy, OffsetDateTime clearedAt, String message) {}
}
