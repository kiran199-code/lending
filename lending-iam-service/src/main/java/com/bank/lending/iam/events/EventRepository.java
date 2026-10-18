package com.bank.lending.iam.events;

import com.bank.lending.iam.common.Db;
import com.bank.lending.iam.events.EventModels.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class EventRepository {
    private static final RowMapper<SubscriptionRow> SUB = (rs, i) -> new SubscriptionRow(rs.getLong("sub_id"), rs.getString("name"), rs.getString("url"),
            rs.getString("event_types"), rs.getString("secret_enc"), rs.getBoolean("active"), rs.getString("approval_type_code"), Db.instant(rs, "created_at"));
    private static final String SUB_COLS = "sub_id, name, url, event_types, secret_enc, active, approval_type_code, created_at";
    private static final RowMapper<DeliveryRow> DELIVERY = (rs, i) -> new DeliveryRow(rs.getLong("delivery_id"), rs.getLong("event_id"), rs.getLong("sub_id"),
            rs.getString("status"), rs.getInt("attempts"), Db.instant(rs, "next_attempt_at"), rs.getString("last_error"), Db.instant(rs, "delivered_at"), Db.instant(rs, "created_at"));

    private final JdbcTemplate jdbc;

    public EventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ outbox
    public long insertEvent(String type, String subjectKey, String payload) {
        return Db.scalar(jdbc, "INSERT INTO event_outbox (event_type, subject_key, payload) VALUES (?,?,?) RETURNING event_id", Long.class, type, subjectKey, payload);
    }

    public List<EventRow> unfannedEvents(int limit) {
        return jdbc.query("SELECT event_id, event_type, subject_key, payload, created_at FROM event_outbox WHERE fanned_out_at IS NULL ORDER BY event_id LIMIT ? FOR UPDATE SKIP LOCKED",
                (rs, i) -> new EventRow(rs.getLong("event_id"), rs.getString("event_type"), rs.getString("subject_key"), rs.getString("payload"), Db.instant(rs, "created_at")), limit);
    }

    public void markFannedOut(long eventId) {
        jdbc.update("UPDATE event_outbox SET fanned_out_at = now() WHERE event_id = ?", eventId);
    }

    public long countUnfanned() {
        return Db.scalar(jdbc, "SELECT count(*) FROM event_outbox WHERE fanned_out_at IS NULL", Long.class);
    }

    // ------------------------------------------------------------------ subscriptions
    public List<SubscriptionRow> subscriptions() {
        return jdbc.query("SELECT " + SUB_COLS + " FROM webhook_subscription ORDER BY sub_id", SUB);
    }

    public List<SubscriptionRow> activeSubscriptions() {
        return jdbc.query("SELECT " + SUB_COLS + " FROM webhook_subscription WHERE active = TRUE ORDER BY sub_id", SUB);
    }

    public Optional<SubscriptionRow> subscription(long id) {
        return Db.one(jdbc, "SELECT " + SUB_COLS + " FROM webhook_subscription WHERE sub_id = ?", SUB, id);
    }

    public Optional<SubscriptionRow> subscriptionByIdempotencyKey(String key) {
        return Db.one(jdbc, "SELECT " + SUB_COLS + " FROM webhook_subscription WHERE idempotency_key = ?", SUB, key);
    }

    public Optional<SubscriptionRow> callbackOfType(String typeCode) {
        return Db.one(jdbc, "SELECT " + SUB_COLS + " FROM webhook_subscription WHERE approval_type_code = ?", SUB, typeCode);
    }

    public long insertSubscription(String name, String url, String eventTypes, String secretEnc, String approvalTypeCode, String createdBy, String idempotencyKey) {
        return Db.scalar(jdbc, "INSERT INTO webhook_subscription (name, url, event_types, secret_enc, approval_type_code, created_by, idempotency_key) VALUES (?,?,?,?,?,?,?) RETURNING sub_id",
                Long.class, name, url, eventTypes, secretEnc, approvalTypeCode, createdBy, idempotencyKey);
    }

    public void updateSubscription(long id, String name, String url, String eventTypes, boolean active) {
        jdbc.update("UPDATE webhook_subscription SET name = ?, url = ?, event_types = ?, active = ? WHERE sub_id = ?", name, url, eventTypes, active, id);
    }

    public void updateSubscriptionSecret(long id, String secretEnc) {
        jdbc.update("UPDATE webhook_subscription SET secret_enc = ? WHERE sub_id = ?", secretEnc, id);
    }

    /** Deleting a subscription keeps its delivery history only if it has none; otherwise it is deactivated. */
    public boolean deleteOrDeactivate(long id) {
        long n = Db.scalar(jdbc, "SELECT count(*) FROM webhook_delivery WHERE sub_id = ?", Long.class, id);
        if (n == 0) {
            jdbc.update("DELETE FROM webhook_subscription WHERE sub_id = ?", id);
            return true;
        }
        jdbc.update("UPDATE webhook_subscription SET active = FALSE WHERE sub_id = ?", id);
        return false;
    }

    // ------------------------------------------------------------------ deliveries
    public void insertDelivery(long eventId, long subId) {
        jdbc.update("INSERT INTO webhook_delivery (event_id, sub_id) VALUES (?,?) ON CONFLICT (event_id, sub_id) DO NOTHING", eventId, subId);
    }

    public Optional<DeliveryRow> delivery(long id) {
        return Db.one(jdbc, "SELECT delivery_id, event_id, sub_id, status, attempts, next_attempt_at, last_error, delivered_at, created_at FROM webhook_delivery WHERE delivery_id = ?", DELIVERY, id);
    }

    public List<DeliveryRow> deliveries(String status, int page, int size) {
        List<Object> args = new ArrayList<>();
        String where = "";
        if (status != null && !status.isBlank()) { where = " WHERE status = ?"; args.add(status); }
        args.add(size);
        args.add((long) page * size);
        return jdbc.query("SELECT delivery_id, event_id, sub_id, status, attempts, next_attempt_at, last_error, delivered_at, created_at FROM webhook_delivery" + where
                + " ORDER BY delivery_id DESC LIMIT ? OFFSET ?", DELIVERY, args.toArray());
    }

    /**
     * Deliveries that are due, oldest event first. A delivery waits while an earlier event with the same key
     * for the same subscription is still PENDING or FAILED, so events of one key arrive in order.
     */
    public List<DueDelivery> due(Instant now, int limit) {
        return jdbc.query("SELECT d.delivery_id, d.attempts, e.event_id, e.event_type, e.payload, e.created_at AS event_created_at, s.sub_id, s.url, s.secret_enc "
                + "FROM webhook_delivery d JOIN event_outbox e ON e.event_id = d.event_id JOIN webhook_subscription s ON s.sub_id = d.sub_id "
                + "WHERE d.status = 'PENDING' AND d.next_attempt_at <= ? AND s.active = TRUE "
                + "AND NOT EXISTS (SELECT 1 FROM webhook_delivery d2 JOIN event_outbox e2 ON e2.event_id = d2.event_id "
                + "WHERE d2.sub_id = d.sub_id AND e2.subject_key = e.subject_key AND e2.event_id < e.event_id AND d2.status IN ('PENDING','FAILED')) "
                + "ORDER BY e.event_id LIMIT ?",
                (rs, i) -> new DueDelivery(rs.getLong("delivery_id"), rs.getInt("attempts"), rs.getLong("event_id"), rs.getString("event_type"), rs.getString("payload"),
                        Db.instant(rs, "event_created_at"), rs.getLong("sub_id"), rs.getString("url"), rs.getString("secret_enc")), Db.ts(now), limit);
    }

    public void markDelivered(long deliveryId, Instant at) {
        jdbc.update("UPDATE webhook_delivery SET status = 'DELIVERED', attempts = attempts + 1, delivered_at = ?, last_error = NULL WHERE delivery_id = ?", Db.ts(at), deliveryId);
    }

    public void markAttemptFailed(long deliveryId, boolean giveUp, Instant nextAttempt, String error) {
        jdbc.update("UPDATE webhook_delivery SET status = ?, attempts = attempts + 1, next_attempt_at = ?, last_error = ? WHERE delivery_id = ?",
                giveUp ? "FAILED" : "PENDING", Db.ts(nextAttempt), error == null ? null : (error.length() > 290 ? error.substring(0, 290) : error), deliveryId);
    }

    public boolean replay(long deliveryId, Instant now) {
        return jdbc.update("UPDATE webhook_delivery SET status = 'PENDING', attempts = 0, next_attempt_at = ?, last_error = NULL WHERE delivery_id = ? AND status = 'FAILED'", Db.ts(now), deliveryId) == 1;
    }

    public long countDeliveries(String status) {
        return Db.scalar(jdbc, "SELECT count(*) FROM webhook_delivery WHERE status = ?", Long.class, status);
    }
}
