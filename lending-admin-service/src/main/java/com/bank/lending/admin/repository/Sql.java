package com.bank.lending.admin.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Small shared helpers for the query repositories. */
final class Sql {

    private Sql() {
    }

    /**
     * The schedule rows that are "current" for each account: the highest schedule_version. A
     * part-payment or rate reset writes a new version, so older versions are history.
     */
    static final String CURRENT_SCHEDULE = """
            cur AS (
                SELECT s.* FROM amortization_schedule s
                WHERE s.schedule_version = (SELECT MAX(m.schedule_version) FROM amortization_schedule m
                                            WHERE m.account_id = s.account_id)
            )
            """;

    /** Accounts that still have instalments to collect. */
    static final String LIVE_ACCOUNT = " a.account_status NOT IN ('PENDING_DISBURSEMENT','CLOSED','FORECLOSED','WRITTEN_OFF')";

    static int clampSize(int size) {
        return Math.max(1, Math.min(size, 200));
    }

    static int clampPage(int page) {
        return Math.max(0, page);
    }

    static LocalDate date(ResultSet rs, String col) throws SQLException {
        return rs.getObject(col, LocalDate.class);
    }

    static OffsetDateTime ts(ResultSet rs, String col) throws SQLException {
        return rs.getObject(col, OffsetDateTime.class);
    }

    static BigDecimal zeroIfNull(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    static Integer intOrNull(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    static Long longOrNull(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    static int dpd(LocalDate dueDate, LocalDate today) {
        if (dueDate == null || !dueDate.isBefore(today)) {
            return 0;
        }
        return (int) java.time.temporal.ChronoUnit.DAYS.between(dueDate, today);
    }
}
