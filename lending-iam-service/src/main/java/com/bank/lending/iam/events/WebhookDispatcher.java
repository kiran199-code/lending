package com.bank.lending.iam.events;

import com.bank.lending.iam.common.Hashing;
import com.bank.lending.iam.common.Json;
import com.bank.lending.iam.config.IamSettings;
import com.bank.lending.iam.crypto.SecretCrypto;
import com.bank.lending.iam.events.EventModels.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * After commit: copies each outbox event to a delivery per interested subscription, then sends the due deliveries.
 * Delivery is at least once. Events with the same key are delivered in order per subscription; a delivery that has
 * run out of attempts is FAILED and holds back later events of the same key until an administrator replays it.
 */
@Service
public class WebhookDispatcher {
    private static final Set<String> CALLBACK_EVENTS = Set.of(EventService.APPROVAL_APPROVED, EventService.APPROVAL_REJECTED,
            EventService.APPROVAL_EXPIRED, EventService.APPROVAL_CANCELLED);

    private final EventRepository repo;
    private final HttpSender sender;
    private final SecretCrypto crypto;
    private final IamSettings settings;
    private final Clock clock;

    public WebhookDispatcher(EventRepository repo, HttpSender sender, SecretCrypto crypto, IamSettings settings, Clock clock) {
        this.repo = repo;
        this.sender = sender;
        this.crypto = crypto;
        this.settings = settings;
        this.clock = clock;
    }

    /** @return how many events were fanned out */
    @Transactional
    public int fanOut() {
        List<EventRow> events = repo.unfannedEvents(200);
        if (events.isEmpty()) return 0;
        List<SubscriptionRow> subs = repo.activeSubscriptions();
        for (EventRow e : events) {
            Map<String, Object> payload = Json.readMap(e.payload());
            for (SubscriptionRow s : subs) {
                if (!s.wants(e.type())) continue;
                if (s.approvalTypeCode() != null && !(CALLBACK_EVENTS.contains(e.type()) && s.approvalTypeCode().equals(payload.get("type")))) continue;
                repo.insertDelivery(e.id(), s.id());
            }
            repo.markFannedOut(e.id());
        }
        return events.size();
    }

    /**
     * Sends what is due. Not one transaction: each result is stored on its own so a slow receiver never holds a lock.
     * Events of one subject are delivered in order, one at a time per subscription, so a burst for one subject
     * (submitted, then approved) needs several rounds: it repeats while a round delivered something, up to MAX_ROUNDS.
     */
    public int deliverDue() {
        int total = 0;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            int sent = deliverRound();
            total += sent;
            if (sent == 0) break;
        }
        return total;
    }

    private static final int MAX_ROUNDS = 5;

    private int deliverRound() {
        Instant now = Instant.now(clock);
        int sent = 0;
        for (DueDelivery d : repo.due(now, 50)) {
            String body = body(d);
            String secret;
            try {
                secret = crypto.decrypt(d.secretEnc(), "webhook");
            } catch (IllegalStateException e) {
                repo.markAttemptFailed(d.deliveryId(), true, now, "signing secret cannot be read");
                continue;
            }
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/json");
            headers.put("X-IAM-Event", d.eventType());
            headers.put("X-IAM-Event-Id", String.valueOf(d.eventId()));
            headers.put("X-IAM-Delivery", String.valueOf(d.deliveryId()));
            headers.put("X-IAM-Signature", "sha256=" + Hashing.hmacSha256Hex(secret, body));
            HttpSender.Response r = sender.post(d.url(), body, headers, Duration.ofSeconds(settings.webhookTimeoutSeconds));
            Instant after = Instant.now(clock);
            if (r.ok()) {
                repo.markDelivered(d.deliveryId(), after);
                sent++;
            } else {
                int attempts = d.attempts() + 1;
                boolean giveUp = attempts >= settings.webhookMaxAttempts;
                repo.markAttemptFailed(d.deliveryId(), giveUp, after.plus(backoff(attempts)), r.error());
            }
        }
        return sent;
    }

    /** 30 s, 1 min, 2 min, ... capped at 1 hour. */
    static Duration backoff(int attempts) {
        long seconds = 30L * (1L << Math.min(Math.max(attempts - 1, 0), 7));
        return Duration.ofSeconds(Math.min(seconds, 3600));
    }

    private static String body(DueDelivery d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventId", d.eventId());
        m.put("type", d.eventType());
        m.put("occurredAt", d.eventCreatedAt().toString());
        m.put("data", Json.readMap(d.payload()));
        return Json.write(m);
    }
}
