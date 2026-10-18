package com.bank.lending.limits;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * The only class the loan services talk to. It applies {@code lending.lcm.mode}:
 *
 * <pre>
 *   off     every method returns at once: no call to LCM, no database write.
 *   shadow  limits are recorded in LCM but never block anything; an LCM problem is logged and ignored.
 *   live    limits are enforced; a refused disbursement is stopped; if LCM cannot be reached the disbursement is
 *           stopped too, unless lending.lcm.fail-open=true.
 * </pre>
 *
 * Only accounts that carry a facility reference (loan_account.limit_facility_ref) are limit-controlled; for the others every
 * method is a no-op in every mode.
 *
 * <p>Disbursement is two-phase. {@link #reserveForDisbursement} reserves the amount in LCM before the loan changes
 * (a refusal stops the loan, and nothing has been written yet); {@link #disbursed} then queues the confirmation in the same
 * database transaction as the loan, so it is sent exactly when the disbursement commits. If the loan transaction rolls back
 * instead, the reservation is released at once (and would expire on its own after its TTL).
 * Repayments and closure are only queued: a loan must never fail to take a customer's money because LCM is slow.
 */
@Component
public class LimitGateway {

    private static final Logger log = LoggerFactory.getLogger(LimitGateway.class);

    /** LCM answers to a create-time check that mean the facility itself cannot be used. The amount codes are advisory then: a sanction is not a drawdown. */
    static final Set<String> CREATE_BLOCKING = Set.of("FACILITY_NOT_ACTIVE", "FACILITY_NOT_EFFECTIVE", "FACILITY_EXPIRED",
            "CURRENCY_MISMATCH", "BRANCH_NOT_ALLOWED");

    /** The loan account, reduced to what limits need. */
    public record Subject(long accountId, String facilityRef, String currency, String branch) {
        public boolean linked() {
            return facilityRef != null && !facilityRef.isBlank();
        }
    }

    /** Outcome of {@link #reserveForDisbursement}: the LCM reservation (if any) and whether the drawing must be reported afterwards. */
    public record Ticket(String reservationRef, boolean track) {
        public static final Ticket NONE = new Ticket(null, false);
    }

    private final LimitSettings settings;
    private final LimitClient client;
    private final LimitOutboxRepository outbox;
    private final LimitOutboxService outboxService;

    public LimitGateway(LimitSettings settings, LimitClient client, LimitOutboxRepository outbox, LimitOutboxService outboxService) {
        this.settings = settings;
        this.client = client;
        this.outbox = outbox;
        this.outboxService = outboxService;
    }

    public LimitMode mode() {
        return settings.mode();
    }

    // ---------------------------------------------------------------------------------------------------- create

    /** Before an account is created against a facility. Throws only in live mode. */
    public void checkOnCreate(String facilityRef, BigDecimal sanctionedAmount, String currency, String branch, LocalDate businessDate) {
        if (!settings.enabled() || facilityRef == null || facilityRef.isBlank()) {
            return;
        }
        boolean live = settings.mode() == LimitMode.LIVE;
        try {
            LimitClient.CheckResult r = client.check(facilityRef, sanctionedAmount, currency, branch, businessDate);
            if (r.allowed()) {
                return;
            }
            if (live && CREATE_BLOCKING.contains(r.code())) {
                throw new LimitRejectedException(r.code(), 422, r.message());
            }
            log.warn("LCM advisory on creating an account against {} ({}): {} {}", facilityRef, settings.mode(), r.code(), r.message());
        } catch (LimitRejectedException e) {
            if (live) {
                throw e; // includes an unknown facility reference
            }
            log.warn("LCM refused the create-time check against {} (shadow mode, ignored): {} {}", facilityRef, e.code(), e.getMessage());
        } catch (LimitUnavailableException e) {
            if (live && !settings.failOpen()) {
                throw e;
            }
            log.warn("LCM unavailable for the create-time check against {} ({}): {}", facilityRef, settings.mode(), e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------------- disburse

    /** Call after the account's state has been validated and before anything is changed. */
    public Ticket reserveForDisbursement(Subject s, BigDecimal amount, LocalDate businessDate) {
        if (!settings.enabled() || !s.linked()) {
            return Ticket.NONE;
        }
        boolean live = settings.mode() == LimitMode.LIVE;
        String key = "lend-reserve-" + s.accountId() + "-" + UUID.randomUUID();
        LimitClient.ReservationResult r;
        try {
            r = client.reserve(s.facilityRef(), amount, s.currency(), s.branch(), s.accountId(), live,
                    settings.reservationTtlMinutes(), businessDate, key);
        } catch (LimitRejectedException e) {
            if (live) {
                throw e;
            }
            log.warn("LCM refused the reservation for account {} (shadow mode, ignored): {} {}", s.accountId(), e.code(), e.getMessage());
            return Ticket.NONE;
        } catch (LimitUnavailableException e) {
            if (live && !settings.failOpen()) {
                throw e;
            }
            // The loan goes ahead; the drawing is reported later with a late reservation (see LimitOutboxService).
            log.warn("LCM unavailable for the reservation for account {} ({}); the drawing will be reported when LCM is back: {}",
                    s.accountId(), settings.mode(), e.getMessage());
            return new Ticket(null, true);
        }
        if (r.wouldReject() != null) {
            log.warn("Shadow check: account {} disbursement of {} on {} would have been refused by LCM: {}",
                    s.accountId(), amount, s.facilityRef(), r.wouldReject());
        }
        releaseIfRolledBack(r.reservationRef(), s.accountId());
        return new Ticket(r.reservationRef(), true);
    }

    /** Call in the same transaction as the loan change. Queues the confirmation; it is sent when the transaction commits. */
    public void disbursed(Subject s, Ticket ticket, BigDecimal amount, LocalDate businessDate) {
        if (!settings.enabled() || !s.linked() || ticket == null || !ticket.track()) {
            return;
        }
        enqueue(s, "CONFIRM_DISBURSEMENT", ticket.reservationRef(), amount, businessDate, null, "lend-confirm-" + s.accountId());
    }

    // ------------------------------------------------------------------------------------- repay / close

    /** Principal actually repaid by payment {@code transactionId}. Interest and penal interest never touch a limit. */
    public void principalRepaid(Subject s, long transactionId, BigDecimal principal, LocalDate businessDate) {
        if (!settings.enabled() || !s.linked() || principal == null || principal.signum() <= 0) {
            return;
        }
        enqueue(s, "REPAY", null, principal, businessDate, null, "lend-repay-" + transactionId);
    }

    /** The account reached CLOSED or FORECLOSED: whatever LCM still counts as outstanding is released. */
    public void accountClosed(Subject s, String reason, LocalDate businessDate) {
        if (!settings.enabled() || !s.linked()) {
            return;
        }
        enqueue(s, "CLOSE", null, null, businessDate, reason, "lend-close-" + s.accountId());
    }

    // ------------------------------------------------------------------------------------------- internals

    private void enqueue(Subject s, String operation, String reservationRef, BigDecimal amount, LocalDate businessDate,
                         String reason, String key) {
        Long id = outbox.insert(s.accountId(), operation, s.facilityRef(), reservationRef, amount, s.currency(), s.branch(),
                businessDate, reason, key);
        if (id == null) {
            return; // already queued by an earlier attempt of the same business event
        }
        afterCommit(() -> outboxService.deliverSoon(id));
    }

    private void releaseIfRolledBack(String reservationRef, long accountId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) {
                    return;
                }
                try {
                    client.release(reservationRef, "lend-release-" + reservationRef);
                } catch (RuntimeException e) {
                    log.warn("Could not release LCM reservation {} for account {} after a rollback; it expires on its own: {}",
                            reservationRef, accountId, e.getMessage());
                }
            }
        });
    }

    private void afterCommit(Runnable work) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            safely(work);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                safely(work);
            }
        });
    }

    private static void safely(Runnable work) {
        try {
            work.run();
        } catch (RuntimeException e) {
            log.warn("Limit delivery could not be started (the sweep will pick the row up): {}", e.toString());
        }
    }
}
