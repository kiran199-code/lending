package com.bank.lending.limits;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Delivers limit_outbox rows to LCM.
 *
 * <p>Delivery is attempted once right after the loan transaction commits (on a single background thread, so the payment or
 * disbursement response never waits for LCM) and again by a sweep for anything that is still PENDING. A row ends as
 * <ul>
 *   <li>SENT - LCM applied it (or had already applied it: every call carries the row's Idempotency-Key),</li>
 *   <li>SKIPPED - it no longer applies: the account has no utilisation position in LCM (it was disbursed before it was linked), the
 *       position is already closed, or LCM does not know the reservation,</li>
 *   <li>DEAD - LCM said no for a reason a retry will not change, or the attempts ran out. Shown by GET /api/v1/limits/outbox and
 *       put back with POST /api/v1/limits/outbox/{id}/requeue once the cause is fixed.</li>
 * </ul>
 */
@Service
public class LimitOutboxService {

    private static final Logger log = LoggerFactory.getLogger(LimitOutboxService.class);

    /** LCM answers that mean "nothing left to do for this row". */
    static final Set<String> SKIP_CODES = Set.of("NO_POSITION", "POSITION_CLOSED", "NOT_FOUND");

    private final LimitOutboxRepository repo;
    private final LimitClient client;
    private final LimitSettings settings;
    private volatile Executor executor = newExecutor();

    public LimitOutboxService(LimitOutboxRepository repo, LimitClient client, LimitSettings settings) {
        this.repo = repo;
        this.client = client;
        this.settings = settings;
    }

    /** Tests run delivery inline with {@code Runnable::run}. */
    void setExecutor(Executor executor) {
        this.executor = executor;
    }

    private static Executor newExecutor() {
        ThreadPoolExecutor ex = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1000), r -> {
            Thread t = new Thread(r, "limit-outbox");
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.DiscardPolicy()); // a dropped job is picked up by the sweep: its row is still PENDING
        return ex;
    }

    /** Called after commit. Never throws and never blocks the caller. */
    public void deliverSoon(long outboxId) {
        try {
            executor.execute(() -> deliverOne(outboxId));
        } catch (RuntimeException e) {
            log.warn("Could not queue limit delivery for outbox row {}: {}", outboxId, e.toString());
        }
    }

    void deliverOne(long outboxId) {
        try {
            for (LimitOutboxRepository.Row row : repo.claimOne(outboxId, settings.leaseMinutes())) {
                process(row);
            }
        } catch (RuntimeException e) {
            log.warn("Limit delivery for outbox row {} failed unexpectedly: {}", outboxId, e.toString());
        }
    }

    @Scheduled(cron = "${lending.lcm.outbox.retry-cron:0 * * * * *}")
    public void scheduledSweep() {
        if (settings.enabled()) {
            retryDue();
        }
    }

    /** Delivers what is due now. Returns the number of rows worked on. */
    public int retryDue() {
        int done = 0;
        try {
            List<LimitOutboxRepository.Row> rows = new java.util.ArrayList<>(repo.claimDue(settings.retryBatch(), settings.leaseMinutes()));
            rows.sort(Comparator.comparingLong(LimitOutboxRepository.Row::id));
            for (LimitOutboxRepository.Row row : rows) {
                process(row);
                done++;
            }
        } catch (RuntimeException e) {
            log.warn("Limit outbox sweep failed: {}", e.toString());
        }
        return done;
    }

    void process(LimitOutboxRepository.Row row) {
        try {
            switch (row.operation()) {
                case "CONFIRM_DISBURSEMENT" -> confirm(row);
                case "REPAY" -> client.repay(row.accountId(), row.amount(), row.idempotencyKey());
                case "CLOSE" -> client.close(row.accountId(), row.reason(), row.idempotencyKey());
                default -> throw new LimitRejectedException("UNKNOWN_OPERATION", 400, "Unknown operation " + row.operation());
            }
            repo.markSent(row.id());
        } catch (LimitRejectedException e) {
            String why = e.code() + ": " + e.getMessage();
            if (SKIP_CODES.contains(e.code())) {
                repo.markSkipped(row.id(), why);
            } else {
                log.warn("LCM refused limit outbox row {} ({} account {}): {}", row.id(), row.operation(), row.accountId(), why);
                repo.markDead(row.id(), why);
            }
        } catch (RuntimeException e) { // LimitUnavailableException, or anything unexpected: retry later
            if (row.attempts() >= settings.maxAttempts()) {
                log.error("Limit outbox row {} ({} account {}) given up after {} attempts: {}", row.id(), row.operation(),
                        row.accountId(), row.attempts(), e.toString());
                repo.markDead(row.id(), "gave up after " + row.attempts() + " attempts: " + e.getMessage());
            } else {
                repo.markRetry(row.id(), e.getMessage(), backoffMinutes(row.attempts()));
            }
        }
    }

    /**
     * Confirms the reservation taken at disbursement. When there is none (LCM was unreachable then, and the loan went ahead
     * because fail-open is on) or LCM has since released it, the drawing is recorded now with a fresh reservation that is not
     * enforced: the money is already out, so the point is to make LCM's numbers true, not to refuse.
     */
    private void confirm(LimitOutboxRepository.Row row) {
        String ref = row.reservationRef();
        if (ref != null) {
            try {
                client.confirm(ref, row.amount(), row.idempotencyKey());
                return;
            } catch (LimitRejectedException e) {
                if (!"RESERVATION_RELEASED".equals(e.code()) && !"NOT_FOUND".equals(e.code())) {
                    throw e;
                }
            }
        }
        LimitClient.ReservationResult late = client.reserve(row.facilityRef(), row.amount(), row.currency(), row.branch(),
                row.accountId(), false, settings.reservationTtlMinutes(), row.businessDate(), row.idempotencyKey() + "-late");
        client.confirm(late.reservationRef(), row.amount(), row.idempotencyKey() + "-late");
    }

    /** 1, 2, 4, ... capped at 60 minutes. */
    static int backoffMinutes(int attempts) {
        return (int) Math.min(60, 1L << Math.max(0, Math.min(attempts, 10)));
    }
}
