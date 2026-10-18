package com.bank.lending.iam.approval;

import com.bank.lending.iam.approval.ApprovalModels.*;
import com.bank.lending.iam.common.Db;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class ApprovalRepository {
    private static final String TYPE_COLS = "type_code, type_name, maker_permission, checker_permission, approvals_required, pending_hours, consume_hours, callback_url, active";
    private static final RowMapper<TypeRow> TYPE = (rs, i) -> new TypeRow(rs.getString("type_code"), rs.getString("type_name"), rs.getString("maker_permission"),
            rs.getString("checker_permission"), rs.getInt("approvals_required"), rs.getInt("pending_hours"), rs.getInt("consume_hours"),
            rs.getString("callback_url"), rs.getBoolean("active"));

    private static final String ACTION_COLS = "action_id::text AS action_id, type_code, target_ref, summary, payload, payload_hash, amount, currency, branch_code, product_code, "
            + "status, maker, submitted_by, idempotency_key, approvals_needed, created_at, expires_at, decided_at, consume_deadline, consumed_at, consumed_by, consumer_ref, decision_comment, version";
    private static final RowMapper<ActionRow> ACTION = (rs, i) -> new ActionRow(rs.getString("action_id"), rs.getString("type_code"), rs.getString("target_ref"),
            rs.getString("summary"), rs.getString("payload"), rs.getString("payload_hash"), Db.decimal(rs, "amount"), rs.getString("currency"),
            rs.getString("branch_code"), rs.getString("product_code"), rs.getString("status"), rs.getString("maker"), rs.getString("submitted_by"),
            rs.getString("idempotency_key"), rs.getInt("approvals_needed"), Db.instant(rs, "created_at"), Db.instant(rs, "expires_at"),
            Db.instant(rs, "decided_at"), Db.instant(rs, "consume_deadline"), Db.instant(rs, "consumed_at"), rs.getString("consumed_by"),
            rs.getString("consumer_ref"), rs.getString("decision_comment"), rs.getLong("version"));

    private final JdbcTemplate jdbc;

    public ApprovalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ types
    public List<TypeRow> types() {
        return jdbc.query("SELECT " + TYPE_COLS + " FROM approval_type ORDER BY type_code", TYPE);
    }

    public Optional<TypeRow> type(String code) {
        return Db.one(jdbc, "SELECT " + TYPE_COLS + " FROM approval_type WHERE type_code = ?", TYPE, code);
    }

    public void insertType(TypeRow t) {
        jdbc.update("INSERT INTO approval_type (type_code, type_name, maker_permission, checker_permission, approvals_required, pending_hours, consume_hours, callback_url, active) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)", t.code(), t.name(), t.makerPermission(), t.checkerPermission(), t.approvalsRequired(),
                t.pendingHours(), t.consumeHours(), t.callbackUrl(), t.active());
    }

    public void updateType(String code, String name, int approvalsRequired, int pendingHours, int consumeHours, String callbackUrl, boolean active) {
        jdbc.update("UPDATE approval_type SET type_name = ?, approvals_required = ?, pending_hours = ?, consume_hours = ?, callback_url = ?, active = ?, updated_at = now() WHERE type_code = ?",
                name, approvalsRequired, pendingHours, consumeHours, callbackUrl, active, code);
    }

    // ------------------------------------------------------------------ actions
    /** @return false when an action with the same type and Idempotency-Key exists (inserted by a concurrent request): nothing is written and the transaction stays usable */
    public boolean insertAction(String id, TypeRow type, String targetRef, String summary, String payload, String payloadHash, BigDecimal amount,
                             String currency, String branch, String product, String maker, String submittedBy, String idempotencyKey,
                             Instant createdAt, Instant expiresAt) {
        return jdbc.update("INSERT INTO pending_action (action_id, type_code, target_ref, summary, payload, payload_hash, amount, currency, branch_code, product_code, "
                        + "status, maker, submitted_by, idempotency_key, approvals_needed, created_at, expires_at) VALUES (?::uuid,?,?,?,?,?,?,?,?,?,'PENDING',?,?,?,?,?,?) "
                        + "ON CONFLICT DO NOTHING",
                id, type.code(), targetRef, summary, payload, payloadHash, amount, currency, branch, product, maker, submittedBy, idempotencyKey,
                type.approvalsRequired(), Db.ts(createdAt), Db.ts(expiresAt)) == 1;
    }

    public Optional<ActionRow> action(String id) {
        return Db.one(jdbc, "SELECT " + ACTION_COLS + " FROM pending_action WHERE action_id = ?::uuid", ACTION, id);
    }

    public Optional<ActionRow> actionForUpdate(String id) {
        return Db.one(jdbc, "SELECT " + ACTION_COLS + " FROM pending_action WHERE action_id = ?::uuid FOR UPDATE", ACTION, id);
    }

    public Optional<ActionRow> byIdempotencyKey(String typeCode, String key) {
        return Db.one(jdbc, "SELECT " + ACTION_COLS + " FROM pending_action WHERE type_code = ? AND idempotency_key = ?", ACTION, typeCode, key);
    }

    public record Page(List<ActionRow> rows, long total) {}

    public Page search(String status, String typeCode, String maker, String targetRef, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) { where.append(" AND status = ?"); args.add(status); }
        if (typeCode != null && !typeCode.isBlank()) { where.append(" AND type_code = ?"); args.add(typeCode); }
        if (maker != null && !maker.isBlank()) { where.append(" AND maker = ?"); args.add(maker); }
        if (targetRef != null && !targetRef.isBlank()) { where.append(" AND target_ref = ?"); args.add(targetRef); }
        long total = Db.scalar(jdbc, "SELECT count(*) FROM pending_action" + where, Long.class, args.toArray());
        List<Object> a2 = new ArrayList<>(args);
        a2.add(size);
        a2.add((long) page * size);
        return new Page(jdbc.query("SELECT " + ACTION_COLS + " FROM pending_action" + where + " ORDER BY created_at DESC LIMIT ? OFFSET ?", ACTION, a2.toArray()), total);
    }

    /** Pending, not expired, of the given types, not made by {@code notMaker} and not already decided by {@code notApprover}. */
    public List<ActionRow> inbox(List<String> typeCodes, String notMaker, Instant now, int limit) {
        if (typeCodes.isEmpty()) return List.of();
        List<Object> args = new ArrayList<>(typeCodes);
        args.add(notMaker);
        args.add(Db.ts(now));
        args.add(notMaker);
        args.add(limit);
        return jdbc.query("SELECT " + ACTION_COLS + " FROM pending_action WHERE status = 'PENDING' AND type_code IN (" + Db.marks(typeCodes.size()) + ") "
                + "AND lower(maker) <> lower(?) AND expires_at > ? AND NOT EXISTS (SELECT 1 FROM approval_decision d WHERE d.action_id = pending_action.action_id AND lower(d.approver) = lower(?)) "
                + "ORDER BY created_at LIMIT ?", ACTION, args.toArray());
    }

    // ------------------------------------------------------------------ decisions
    public List<DecisionRow> decisions(String actionId) {
        return jdbc.query("SELECT decision_id, action_id::text AS action_id, approver, decision, comment, decided_at FROM approval_decision WHERE action_id = ?::uuid ORDER BY decision_id",
                (rs, i) -> new DecisionRow(rs.getLong("decision_id"), rs.getString("action_id"), rs.getString("approver"), rs.getString("decision"),
                        rs.getString("comment"), Db.instant(rs, "decided_at")), actionId);
    }

    /** @return false when this approver already decided this action */
    public boolean insertDecision(String actionId, String approver, String decision, String comment, Instant at) {
        return jdbc.update("INSERT INTO approval_decision (action_id, approver, decision, comment, decided_at) VALUES (?::uuid,?,?,?,?) ON CONFLICT DO NOTHING",
                actionId, approver, decision, comment, Db.ts(at)) == 1;
    }

    public long approvalCount(String actionId) {
        return Db.scalar(jdbc, "SELECT count(*) FROM approval_decision WHERE action_id = ?::uuid AND decision = 'APPROVE'", Long.class, actionId);
    }

    // ------------------------------------------------------------------ status changes
    /** Moves a PENDING action to a final or approved state; false if it was no longer PENDING. */
    public boolean decide(String id, String newStatus, Instant at, Instant consumeDeadline, String comment) {
        return jdbc.update("UPDATE pending_action SET status = ?, decided_at = ?, consume_deadline = ?, decision_comment = coalesce(?, decision_comment), version = version + 1 "
                + "WHERE action_id = ?::uuid AND status = 'PENDING'", newStatus, Db.ts(at), Db.ts(consumeDeadline), comment, id) == 1;
    }

    /** The single atomic step APPROVED to CONSUMED. */
    public boolean consume(String id, String by, String ref, Instant now) {
        return jdbc.update("UPDATE pending_action SET status = 'CONSUMED', consumed_at = ?, consumed_by = ?, consumer_ref = ?, version = version + 1 "
                + "WHERE action_id = ?::uuid AND status = 'APPROVED' AND consume_deadline > ?", Db.ts(now), by, ref, id, Db.ts(now)) == 1;
    }

    public boolean expire(String id, Instant now) {
        return jdbc.update("UPDATE pending_action SET status = 'EXPIRED', decided_at = coalesce(decided_at, ?), version = version + 1 "
                + "WHERE action_id = ?::uuid AND status IN ('PENDING','APPROVED')", Db.ts(now), id) == 1;
    }

    public List<String> expiredIds(Instant now, int limit) {
        return jdbc.query("SELECT action_id::text AS action_id FROM pending_action WHERE (status = 'PENDING' AND expires_at <= ?) "
                        + "OR (status = 'APPROVED' AND consume_deadline <= ?) ORDER BY created_at LIMIT ? FOR UPDATE SKIP LOCKED",
                (rs, i) -> rs.getString("action_id"), Db.ts(now), Db.ts(now), limit);
    }

    public List<ActionRow> pendingOfMaker(String maker) {
        return jdbc.query("SELECT " + ACTION_COLS + " FROM pending_action WHERE status = 'PENDING' AND lower(maker) = lower(?) FOR UPDATE", ACTION, maker);
    }

    public long countByStatus(String status) {
        return Db.scalar(jdbc, "SELECT count(*) FROM pending_action WHERE status = ?", Long.class, status);
    }
}
