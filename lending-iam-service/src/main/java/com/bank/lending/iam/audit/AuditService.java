package com.bank.lending.iam.audit;

import com.bank.lending.iam.common.Db;
import com.bank.lending.iam.common.Json;
import com.bank.lending.iam.common.RequestContext;
import com.bank.lending.iam.domain.AuditHasher;
import com.bank.lending.iam.domain.DetailSanitizer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes and reads the append-only, hash-chained audit trail. {@link #record} joins the caller's transaction,
 * so a change and its audit row commit or roll back together. Inserts are serialised by an advisory lock so
 * that the "previous hash" each row carries is always the row before it.
 */
@Service
public class AuditService {
    private static final long CHAIN_LOCK = 7_000_001L;
    private static final int VERIFY_CHUNK = 2000;

    public record Entry(String actorType, String actor, String action, String targetType, String targetId, String outcome,
                        String ip, String correlationId, Map<String, Object> detail) {}

    public record AuditRow(long id, Instant occurredAt, String actorType, String actor, String action, String targetType,
                           String targetId, String outcome, String ip, String correlationId, Map<String, Object> detail, String rowHash) {}

    public record Page(List<AuditRow> rows, long total) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AuditService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Records an event by the current caller (taken from the request context). */
    @Transactional
    public void record(String action, String targetType, String targetId, String outcome, Object... detailKv) {
        RequestContext c = RequestContext.current();
        recordAs(c.actorType(), c.actor(), action, targetType, targetId, outcome, detailKv);
    }

    /** Records an event by an explicit actor (sign-in, system jobs). */
    @Transactional
    public void recordAs(String actorType, String actor, String action, String targetType, String targetId, String outcome, Object... detailKv) {
        RequestContext c = RequestContext.current();
        Map<String, Object> detail = new LinkedHashMap<>();
        for (int i = 0; i + 1 < detailKv.length; i += 2) {
            if (detailKv[i + 1] != null) detail.put(String.valueOf(detailKv[i]), detailKv[i + 1]);
        }
        append(new Entry(actorType, trunc(actor, 80), action, targetType, trunc(targetId, 80), outcome, trunc(c.ip(), 64), trunc(c.correlationId(), 64), detail));
    }

    @Transactional
    public long append(Entry e) {
        jdbc.execute("SELECT pg_advisory_xact_lock(" + CHAIN_LOCK + ")");
        String prev = Db.one(jdbc, "SELECT row_hash FROM audit_log ORDER BY audit_id DESC LIMIT 1", (rs, i) -> rs.getString("row_hash")).orElse(AuditHasher.GENESIS);
        Instant at = AuditHasher.normalise(Instant.now(clock));
        String detail = Json.write(DetailSanitizer.sanitizeMap(e.detail() == null ? Map.of() : e.detail()));
        String hash = AuditHasher.hash(prev, at, e.actorType(), e.actor(), e.action(), e.targetType(), e.targetId(), e.outcome(), e.ip(), e.correlationId(), detail);
        return Db.scalar(jdbc, "INSERT INTO audit_log (occurred_at, actor_type, actor, action, target_type, target_id, outcome, ip, correlation_id, detail, prev_hash, row_hash) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?) RETURNING audit_id", Long.class,
                Db.ts(at), e.actorType(), e.actor(), e.action(), e.targetType(), e.targetId(), e.outcome(), e.ip(), e.correlationId(), detail, prev, hash);
    }

    // ------------------------------------------------------------------ reading
    public Page search(String actor, String action, String targetType, String targetId, String outcome, Instant from, Instant to, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (notBlank(actor)) { where.append(" AND actor = ?"); args.add(actor); }
        if (notBlank(action)) { where.append(" AND action = ?"); args.add(action); }
        if (notBlank(targetType)) { where.append(" AND target_type = ?"); args.add(targetType); }
        if (notBlank(targetId)) { where.append(" AND target_id = ?"); args.add(targetId); }
        if (notBlank(outcome)) { where.append(" AND outcome = ?"); args.add(outcome); }
        if (from != null) { where.append(" AND occurred_at >= ?"); args.add(Db.ts(from)); }
        if (to != null) { where.append(" AND occurred_at < ?"); args.add(Db.ts(to)); }
        long total = Db.scalar(jdbc, "SELECT count(*) FROM audit_log" + where, Long.class, args.toArray());
        List<Object> a2 = new ArrayList<>(args);
        a2.add(size);
        a2.add((long) page * size);
        List<AuditRow> rows = jdbc.query("SELECT audit_id, occurred_at, actor_type, actor, action, target_type, target_id, outcome, ip, correlation_id, detail, row_hash "
                + "FROM audit_log" + where + " ORDER BY audit_id DESC LIMIT ? OFFSET ?", (rs, i) -> new AuditRow(rs.getLong("audit_id"), Db.instant(rs, "occurred_at"),
                rs.getString("actor_type"), rs.getString("actor"), rs.getString("action"), rs.getString("target_type"), rs.getString("target_id"),
                rs.getString("outcome"), rs.getString("ip"), rs.getString("correlation_id"), Json.readMap(rs.getString("detail")), rs.getString("row_hash")), a2.toArray());
        return new Page(rows, total);
    }

    public record VerifyResult(boolean ok, long rowsChecked, Long firstBrokenId, String reason, Instant from, Instant to) {}

    /** Recomputes the chain over a time range (whole table when both are null). */
    public VerifyResult verify(Instant from, Instant to) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> base = new ArrayList<>();
        if (from != null) { where.append(" AND occurred_at >= ?"); base.add(Db.ts(from)); }
        if (to != null) { where.append(" AND occurred_at < ?"); base.add(Db.ts(to)); }

        Long firstId = Db.scalar(jdbc, "SELECT min(audit_id) FROM audit_log" + where, Long.class, base.toArray());
        if (firstId == null) return new VerifyResult(true, 0, null, null, from, to);

        String prevOfFirst = Db.one(jdbc, "SELECT row_hash FROM audit_log WHERE audit_id < ? ORDER BY audit_id DESC LIMIT 1",
                (rs, i) -> rs.getString("row_hash"), firstId).orElse(null);
        boolean atBeginning = prevOfFirst == null;
        String expectedPrev = prevOfFirst;
        long checked = 0;
        long lastId = firstId - 1;
        while (true) {
            List<Object> args = new ArrayList<>(base);
            args.add(lastId);
            args.add(VERIFY_CHUNK);
            List<AuditHasher.Row> rows = jdbc.query("SELECT audit_id, occurred_at, actor_type, actor, action, target_type, target_id, outcome, ip, correlation_id, detail, prev_hash, row_hash "
                    + "FROM audit_log" + where + " AND audit_id > ? ORDER BY audit_id LIMIT ?", (rs, i) -> new AuditHasher.Row(rs.getLong("audit_id"), Db.instant(rs, "occurred_at"),
                    rs.getString("actor_type"), rs.getString("actor"), rs.getString("action"), rs.getString("target_type"), rs.getString("target_id"),
                    rs.getString("outcome"), rs.getString("ip"), rs.getString("correlation_id"), rs.getString("detail"), rs.getString("prev_hash"), rs.getString("row_hash")), args.toArray());
            if (rows.isEmpty()) break;
            AuditHasher.Verification v = AuditHasher.verify(rows, expectedPrev, atBeginning && checked == 0);
            checked += v.count();
            if (!v.ok()) return new VerifyResult(false, checked, v.firstBrokenId(), v.reason(), from, to);
            expectedPrev = rows.get(rows.size() - 1).rowHash();
            lastId = rows.get(rows.size() - 1).id();
            if (rows.size() < VERIFY_CHUNK) break;
        }
        return new VerifyResult(true, checked, null, null, from, to);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String trunc(String s, int n) {
        return s == null ? null : (s.length() > n ? s.substring(0, n) : s);
    }
}
