package com.bank.lending.iam.auth;

import com.bank.lending.iam.approval.ApprovalService;
import com.bank.lending.iam.common.RequestContext;
import com.bank.lending.iam.events.WebhookDispatcher;
import com.bank.lending.iam.keys.KeyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** The once-a-minute housekeeping: expiry of approvals, retired key clean-up, outbox fan-out and webhook delivery. */
@Service
public class SweepService {
    private static final Logger log = LoggerFactory.getLogger(SweepService.class);

    public record Result(int expiredActions, int droppedKeys, int fannedOut, int delivered, int purgedAuthorizations) {}

    private final ApprovalService approvals;
    private final KeyService keys;
    private final WebhookDispatcher dispatcher;
    private final RevocationService revocation;
    private final java.time.Clock clock;

    public SweepService(ApprovalService approvals, KeyService keys, WebhookDispatcher dispatcher, RevocationService revocation, java.time.Clock clock) {
        this.approvals = approvals;
        this.keys = keys;
        this.dispatcher = dispatcher;
        this.revocation = revocation;
        this.clock = clock;
    }

    @Scheduled(cron = "${iam.sweep.cron:0 * * * * *}")
    public void scheduled() {
        try {
            RequestContext.runAsSystem("sweep", () -> run());
        } catch (RuntimeException e) {
            log.error("Sweep failed: {}", e.toString());
        }
    }

    /** Each step stands alone: a failure in one is logged and does not stop the others. */
    public Result run() {
        int expired = 0, dropped = 0, fanned = 0, delivered = 0, purged = 0;
        try { expired = approvals.expireDue(); } catch (RuntimeException e) { log.error("Approval expiry failed: {}", e.toString()); }
        try { dropped = keys.dropExpired(); } catch (RuntimeException e) { log.error("Key clean-up failed: {}", e.toString()); }
        try { fanned = dispatcher.fanOut(); } catch (RuntimeException e) { log.error("Outbox fan-out failed: {}", e.toString()); }
        try { delivered = dispatcher.deliverDue(); } catch (RuntimeException e) { log.error("Webhook delivery failed: {}", e.toString()); }
        try { purged = revocation.purgeExpired(java.time.Instant.now(clock).minus(java.time.Duration.ofDays(1))); } catch (RuntimeException e) { log.error("Authorization clean-up failed: {}", e.toString()); }
        return new Result(expired, dropped, fanned, delivered, purged);
    }
}
