package com.bank.lending.limits;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** In-memory limit_outbox with the same claim rules as the SQL (due, PENDING, nothing earlier PENDING for the account, leased). */
class FakeLimitOutbox extends LimitOutboxRepository {

    static class E {
        long id; long accountId; String operation, facilityRef, reservationRef, currency, branch, reason, key, status = "PENDING", lastError;
        BigDecimal amount; LocalDate businessDate; int attempts; Instant next;
    }

    final List<E> rows = new ArrayList<>();
    Instant now = Instant.parse("2026-10-10T10:00:00Z");
    private long seq = 0;

    FakeLimitOutbox() {
        super(null, null);
    }

    void advanceMinutes(long m) {
        now = now.plusSeconds(m * 60);
    }

    E byKey(String key) {
        return rows.stream().filter(r -> r.key.equals(key)).findFirst().orElse(null);
    }

    @Override
    public Long insert(long accountId, String operation, String facilityRef, String reservationRef, BigDecimal amount, String currency,
                       String branch, LocalDate businessDate, String reason, String idempotencyKey) {
        if (byKey(idempotencyKey) != null) {
            return null;
        }
        E e = new E();
        e.id = ++seq; e.accountId = accountId; e.operation = operation; e.facilityRef = facilityRef; e.reservationRef = reservationRef;
        e.amount = amount; e.currency = currency; e.branch = branch; e.businessDate = businessDate; e.reason = reason;
        e.key = idempotencyKey; e.next = now;
        rows.add(e);
        return e.id;
    }

    private List<Row> claim(java.util.function.Predicate<E> wanted, int limit, int leaseMinutes) {
        List<Row> out = new ArrayList<>();
        List<E> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparingLong(r -> r.id));
        for (E c : sorted) {
            if (out.size() >= limit) break;
            boolean blocked = rows.stream().anyMatch(p -> p.accountId == c.accountId && p.status.equals("PENDING") && p.id < c.id);
            if (c.status.equals("PENDING") && !c.next.isAfter(now) && !blocked && wanted.test(c)) {
                c.attempts++;
                c.next = now.plusSeconds(leaseMinutes * 60L);
                out.add(row(c));
            }
        }
        return out;
    }

    @Override
    public List<Row> claimDue(int limit, int leaseMinutes) {
        // like the SQL, the whole batch is chosen against the state before it started
        List<E> snapshotPending = new ArrayList<>(rows.stream().filter(r -> r.status.equals("PENDING")).toList());
        List<Row> out = new ArrayList<>();
        List<E> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparingLong(r -> r.id));
        for (E c : sorted) {
            if (out.size() >= limit) break;
            boolean blocked = snapshotPending.stream().anyMatch(p -> p.accountId == c.accountId && p.id < c.id);
            if (c.status.equals("PENDING") && !c.next.isAfter(now) && !blocked) {
                out.add(row(c));
            }
        }
        for (Row r : out) {
            E e = at(r.id());
            e.attempts++;
            e.next = now.plusSeconds(leaseMinutes * 60L);
        }
        return out.stream().map(r -> row(at(r.id()))).toList();
    }

    @Override
    public List<Row> claimOne(long id, int leaseMinutes) {
        return claim(e -> e.id == id, 1, leaseMinutes);
    }

    E at(long id) {
        return rows.stream().filter(r -> r.id == id).findFirst().orElseThrow();
    }

    @Override public void markSent(long id) { at(id).status = "SENT"; at(id).lastError = null; }
    @Override public void markSkipped(long id, String why) { at(id).status = "SKIPPED"; at(id).lastError = why; }
    @Override public void markDead(long id, String why) { at(id).status = "DEAD"; at(id).lastError = why; }
    @Override public void markRetry(long id, String why, int delayMinutes) { at(id).lastError = why; at(id).next = now.plusSeconds(delayMinutes * 60L); }

    @Override
    public boolean requeue(long id) {
        E e = at(id);
        if (!e.status.equals("DEAD")) return false;
        e.status = "PENDING"; e.attempts = 0; e.next = now; e.lastError = null;
        return true;
    }

    @Override public Row get(long id) { return row(at(id)); }
    @Override public long count(String status) { return rows.stream().filter(r -> r.status.equals(status)).count(); }
    @Override public List<Row> list(String status, int limit) { return rows.stream().filter(r -> status == null || r.status.equals(status)).map(this::row).toList(); }

    private Row row(E e) {
        OffsetDateTime n = e.next.atOffset(ZoneOffset.UTC);
        return new Row(e.id, e.accountId, e.operation, e.facilityRef, e.reservationRef, e.amount, e.currency, e.branch, e.businessDate,
                e.reason, e.key, e.status, e.attempts, e.lastError, n, n, null);
    }
}
