package com.bank.lending.limits;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * SQL for the limit_outbox queue.
 *
 * <p>{@link #insert} runs inside the caller's transaction, so the row commits or rolls back with the loan change.
 * Everything else (claiming a row, recording its outcome) runs in its own short transaction: delivery usually starts from
 * an afterCommit callback, where the business transaction is already finished but still bound to the thread, and a plain
 * JdbcTemplate call there would silently join it and never commit.
 */
@Repository
public class LimitOutboxRepository {

    public record Row(long id, long accountId, String operation, String facilityRef, String reservationRef, BigDecimal amount,
                      String currency, String branch, LocalDate businessDate, String reason, String idempotencyKey,
                      String status, int attempts, String lastError, OffsetDateTime nextAttemptAt, OffsetDateTime createdAt,
                      OffsetDateTime sentAt) {}

    private static final String COLUMNS = "id, account_id, operation, facility_ref, reservation_ref, amount, currency_code, "
            + "branch_code, business_date, reason, idempotency_key, status, attempts, last_error, next_attempt_at, created_at, sent_at";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate own;

    public LimitOutboxRepository(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.own = new TransactionTemplate(txManager);
        this.own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Joins the caller's transaction. Returns the new row's id, or null when a row with this idempotency key already exists (nothing is added). */
    public Long insert(long accountId, String operation, String facilityRef, String reservationRef, BigDecimal amount,
                       String currency, String branch, LocalDate businessDate, String reason, String idempotencyKey) {
        List<Long> ids = jdbc.query("""
                INSERT INTO limit_outbox(account_id, operation, facility_ref, reservation_ref, amount, currency_code, branch_code,
                                         business_date, reason, idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (idempotency_key) DO NOTHING
                RETURNING id
                """, (rs, i) -> rs.getLong(1), accountId, operation, facilityRef, reservationRef, amount, currency, branch,
                businessDate, trim(reason, 200), idempotencyKey);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /**
     * Takes up to {@code limit} due rows and leases them: next_attempt_at moves {@code leaseMinutes} ahead, so another node (or the
     * next sweep) leaves them alone while this one works. A row is only taken when no earlier PENDING row exists for the same
     * account, which keeps one account's calls in order (confirm, then repayments, then close).
     */
    public List<Row> claimDue(int limit, int leaseMinutes) {
        return own.execute(s -> jdbc.query(claimSql("c.status = 'PENDING' AND c.next_attempt_at <= now()", "LIMIT ?"),
                (rs, i) -> map(rs), leaseMinutes, limit));
    }

    /** Same lease, for one row we were just told about. Empty when it is not due, is leased by someone else, or waits behind an earlier row. */
    public List<Row> claimOne(long id, int leaseMinutes) {
        return own.execute(s -> jdbc.query(claimSql("c.id = ? AND c.status = 'PENDING' AND c.next_attempt_at <= now()", "LIMIT 1"),
                (rs, i) -> map(rs), leaseMinutes, id));
    }

    private static String claimSql(String where, String limitClause) {
        return """
                UPDATE limit_outbox o
                   SET attempts = o.attempts + 1, next_attempt_at = now() + (? * interval '1 minute')
                 WHERE o.id IN (
                        SELECT c.id FROM limit_outbox c
                         WHERE %s
                           AND NOT EXISTS (SELECT 1 FROM limit_outbox p
                                            WHERE p.account_id = c.account_id AND p.status = 'PENDING' AND p.id < c.id)
                         ORDER BY c.id
                         %s
                         FOR UPDATE SKIP LOCKED)
                RETURNING %s
                """.formatted(where, limitClause, COLUMNS);
    }

    public void markSent(long id) {
        own.execute(s -> jdbc.update("UPDATE limit_outbox SET status = 'SENT', sent_at = now(), last_error = NULL WHERE id = ?", id));
    }

    /** The call no longer applies (no position to repay, position already closed...). Kept for the record, never retried. */
    public void markSkipped(long id, String why) {
        own.execute(s -> jdbc.update("UPDATE limit_outbox SET status = 'SKIPPED', last_error = ? WHERE id = ?", trim(why, 500), id));
    }

    /** LCM refused the call. Needs a person: fix the cause, then requeue it. Later rows of the same account keep waiting only while this one is PENDING, so DEAD releases them. */
    public void markDead(long id, String why) {
        own.execute(s -> jdbc.update("UPDATE limit_outbox SET status = 'DEAD', last_error = ? WHERE id = ?", trim(why, 500), id));
    }

    public void markRetry(long id, String why, int delayMinutes) {
        own.execute(s -> jdbc.update("UPDATE limit_outbox SET last_error = ?, next_attempt_at = now() + (? * interval '1 minute') WHERE id = ?",
                trim(why, 500), delayMinutes, id));
    }

    /** DEAD -> PENDING with a fresh attempt count. Returns false when the row is not DEAD. */
    public boolean requeue(long id) {
        Integer n = own.execute(s -> jdbc.update("""
                UPDATE limit_outbox SET status = 'PENDING', attempts = 0, next_attempt_at = now(), last_error = NULL
                 WHERE id = ? AND status = 'DEAD'
                """, id));
        return n != null && n == 1;
    }

    public List<Row> list(String status, int limit) {
        if (status == null || status.isBlank()) {
            return jdbc.query("SELECT " + COLUMNS + " FROM limit_outbox ORDER BY id DESC LIMIT ?", (rs, i) -> map(rs), limit);
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM limit_outbox WHERE status = ? ORDER BY id DESC LIMIT ?", (rs, i) -> map(rs), status, limit);
    }

    public Row get(long id) {
        List<Row> rows = jdbc.query("SELECT " + COLUMNS + " FROM limit_outbox WHERE id = ?", (rs, i) -> map(rs), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long count(String status) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM limit_outbox WHERE status = ?", Long.class, status);
        return n == null ? 0 : n;
    }

    private static Row map(ResultSet rs) throws SQLException {
        return new Row(rs.getLong("id"), rs.getLong("account_id"), rs.getString("operation"), rs.getString("facility_ref"),
                rs.getString("reservation_ref"), rs.getBigDecimal("amount"), rs.getString("currency_code"), rs.getString("branch_code"),
                rs.getObject("business_date", LocalDate.class), rs.getString("reason"), rs.getString("idempotency_key"),
                rs.getString("status"), rs.getInt("attempts"), rs.getString("last_error"),
                rs.getObject("next_attempt_at", OffsetDateTime.class), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("sent_at", OffsetDateTime.class));
    }

    private static String trim(String s, int max) {
        return s == null ? null : (s.length() <= max ? s : s.substring(0, max));
    }
}
