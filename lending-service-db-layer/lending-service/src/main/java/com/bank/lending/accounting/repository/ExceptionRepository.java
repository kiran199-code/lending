package com.bank.lending.accounting.repository;

import com.bank.lending.accounting.dto.AccountingDtos.ExceptionRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Posting exceptions: events that could not be journalled in non-blocking mode. */
@Repository
public class ExceptionRepository {

    public record Pending(long exceptionId, String payload, int attempts) {
    }

    private static final String COLUMNS = "exception_id, event_code, account_id, idempotency_key, reason, error_message, "
            + "eod_gate, attempts, status, created_at, last_attempt_at, resolved_by, resolved_note";

    private final JdbcTemplate jdbc;

    public ExceptionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Records a failure, or counts another attempt when the same key already has an open exception. */
    public void record(String eventCode, Long accountId, String key, String payloadJson, String reason, String message,
                       boolean eodGate) {
        String shortMessage = message == null ? null : message.substring(0, Math.min(message.length(), 1000));
        jdbc.update("INSERT INTO acct_posting_exception(event_code, account_id, idempotency_key, payload, reason, "
                        + "error_message, eod_gate) VALUES (?, ?, ?, ?::jsonb, ?, ?, ?) "
                        + "ON CONFLICT (idempotency_key) WHERE status = 'OPEN' DO UPDATE SET "
                        + "attempts = acct_posting_exception.attempts + 1, last_attempt_at = now(), "
                        + "reason = EXCLUDED.reason, error_message = EXCLUDED.error_message, payload = EXCLUDED.payload",
                eventCode, accountId, key, payloadJson, reason, shortMessage, eodGate);
    }

    public Optional<Pending> findOpen(long exceptionId) {
        return jdbc.query("SELECT exception_id, payload::text, attempts FROM acct_posting_exception "
                        + "WHERE exception_id = ? AND status = 'OPEN'",
                (rs, i) -> new Pending(rs.getLong(1), rs.getString(2), rs.getInt(3)), exceptionId).stream().findFirst();
    }

    public void markRetryFailed(long exceptionId, String reason, String message) {
        jdbc.update("UPDATE acct_posting_exception SET attempts = attempts + 1, last_attempt_at = now(), reason = ?, "
                        + "error_message = ? WHERE exception_id = ?", reason,
                message == null ? null : message.substring(0, Math.min(message.length(), 1000)), exceptionId);
    }

    public void resolve(long exceptionId, String status, String actor, String note) {
        jdbc.update("UPDATE acct_posting_exception SET status = ?, resolved_by = ?, resolved_note = ?, "
                + "last_attempt_at = now() WHERE exception_id = ?", status, actor, note, exceptionId);
    }

    public List<ExceptionRow> list(String status, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM acct_posting_exception WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY exception_id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), (rs, i) -> new ExceptionRow(rs.getLong("exception_id"),
                rs.getString("event_code"), JournalRepository.nullableLong(rs, "account_id"),
                rs.getString("idempotency_key"), rs.getString("reason"), rs.getString("error_message"),
                rs.getBoolean("eod_gate"), rs.getInt("attempts"), rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("last_attempt_at", OffsetDateTime.class),
                rs.getString("resolved_by"), rs.getString("resolved_note")), args.toArray());
    }

    public long countOpen(boolean gateOnly) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM acct_posting_exception WHERE status = 'OPEN'"
                + (gateOnly ? " AND eod_gate" : ""), Long.class);
        return n == null ? 0L : n;
    }
}
