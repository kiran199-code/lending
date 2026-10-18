package com.bank.lending.iam.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** Small helpers for JDBC access so that every repository reads and writes times and nulls the same way. */
public final class Db {
    private Db() {}

    /** Parameter form of an instant: the PostgreSQL driver accepts OffsetDateTime, not Instant. */
    public static OffsetDateTime ts(Instant i) {
        return i == null ? null : OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    public static Instant instant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime o = rs.getObject(col, OffsetDateTime.class);
        return o == null ? null : o.toInstant();
    }

    public static LocalDate date(ResultSet rs, String col) throws SQLException {
        return rs.getObject(col, LocalDate.class);
    }

    public static Long longOrNull(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    public static Integer intOrNull(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    public static BigDecimal decimal(ResultSet rs, String col) throws SQLException {
        return rs.getBigDecimal(col);
    }

    /** First row or empty. (queryForObject throws on no row; this does not.) */
    public static <T> Optional<T> one(JdbcTemplate j, String sql, RowMapper<T> m, Object... args) {
        List<T> l = j.query(sql, m, args);
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    /** A single value from a query that always returns one row (count, RETURNING, aggregate). */
    public static <T> T scalar(JdbcTemplate j, String sql, Class<T> type, Object... args) {
        return j.queryForObject(sql, type, args);
    }

    public static String marks(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(i == 0 ? "?" : ",?");
        return sb.toString();
    }

    /** Escapes LIKE wildcards in user text and wraps it for a contains search. */
    public static String likeContains(String text) {
        String t = text.toLowerCase().replace("\\", "\\\\").replace("_", "\\_").replace("\u0025", "\\\u0025");
        return "\u0025" + t + "\u0025";
    }
}
