package com.bank.lending.iam.events;

import com.bank.lending.iam.audit.AuditService;
import com.bank.lending.iam.common.Hashing;
import com.bank.lending.iam.common.IamException;
import com.bank.lending.iam.common.RequestContext;
import com.bank.lending.iam.common.Validate;
import com.bank.lending.iam.config.IamSettings;
import com.bank.lending.iam.crypto.SecretCrypto;
import com.bank.lending.iam.domain.UrlPolicy;
import com.bank.lending.iam.events.EventModels.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Administration of webhook subscriptions and of the delivery log. */
@Service
public class WebhookService {
    public static final Set<String> EVENT_TYPES = Set.of(EventService.USER_CREATED, EventService.USER_STATUS_CHANGED, EventService.ROLES_CHANGED,
            EventService.CLIENT_CHANGED, EventService.APPROVAL_SUBMITTED, EventService.APPROVAL_APPROVED, EventService.APPROVAL_REJECTED,
            EventService.APPROVAL_CANCELLED, EventService.APPROVAL_EXPIRED, EventService.APPROVAL_CONSUMED, EventService.CUSTOMER_LINK_CHANGED, EventService.CONSENT_CHANGED);

    public record SubscriptionView(long id, String name, String url, String eventTypes, boolean active, String approvalType, Instant createdAt) {}
    public record CreatedSubscription(SubscriptionView subscription, String signingSecret) {}
    public record CreateRequest(String name, String url, List<String> eventTypes) {}
    public record UpdateRequest(String name, String url, List<String> eventTypes, Boolean active) {}
    public record DeliveryView(long id, long eventId, long subscriptionId, String status, int attempts, Instant nextAttemptAt, String lastError, Instant deliveredAt) {}

    private final EventRepository repo;
    private final SecretCrypto crypto;
    private final AuditService audit;
    private final IamSettings settings;
    private final Clock clock;

    public WebhookService(EventRepository repo, SecretCrypto crypto, AuditService audit, IamSettings settings, Clock clock) {
        this.repo = repo;
        this.crypto = crypto;
        this.audit = audit;
        this.settings = settings;
        this.clock = clock;
    }

    static SubscriptionView view(SubscriptionRow s) {
        return new SubscriptionView(s.id(), s.name(), s.url(), s.eventTypes(), s.active(), s.approvalTypeCode(), s.createdAt());
    }

    public List<SubscriptionView> list() {
        return repo.subscriptions().stream().map(WebhookService::view).toList();
    }

    private String eventTypes(List<String> types) {
        if (types == null || types.isEmpty() || types.contains("*")) return "*";
        for (String t : types) if (!EVENT_TYPES.contains(t)) throw IamException.badRequest("UNKNOWN_EVENT_TYPE", "Unknown event type " + t);
        return String.join(",", types);
    }

    private String checkedUrl(String url) {
        String problem = UrlPolicy.problem(url, settings.allowHttpWebhooks);
        if (problem != null) throw IamException.badRequest("URL_NOT_ALLOWED", problem);
        return url.trim();
    }

    @Transactional
    public CreatedSubscription create(CreateRequest r, String idempotencyKey) {
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim();
        if (key != null) {
            if (key.length() > 80) throw IamException.badRequest("VALIDATION_FAILED", "Idempotency-Key is too long");
            var prior = repo.subscriptionByIdempotencyKey(key);
            if (prior.isPresent()) return new CreatedSubscription(view(prior.get()), null);   // the secret was shown the first time only
        }
        String name = Validate.text(r.name(), "name", 80, true);
        String url = checkedUrl(r.url());
        String secret = Hashing.randomToken(32);
        long id = repo.insertSubscription(name, url, eventTypes(r.eventTypes()), crypto.encrypt(secret, "webhook"), null, RequestContext.current().actor(), key);
        audit.record("WEBHOOK_CREATED", "WEBHOOK", String.valueOf(id), "SUCCESS", "name", name, "url", url, "eventTypes", eventTypes(r.eventTypes()));
        return new CreatedSubscription(view(repo.subscription(id).orElseThrow()), secret);
    }

    @Transactional
    public SubscriptionView update(long id, UpdateRequest r) {
        SubscriptionRow s = repo.subscription(id).orElseThrow(() -> IamException.notFound("Webhook", String.valueOf(id)));
        String url = r.url() == null ? s.url() : checkedUrl(r.url());
        String types = r.eventTypes() == null ? s.eventTypes() : eventTypes(r.eventTypes());
        boolean active = r.active() == null ? s.active() : r.active();
        repo.updateSubscription(id, r.name() == null ? s.name() : Validate.text(r.name(), "name", 80, true), url, types, active);
        audit.record("WEBHOOK_UPDATED", "WEBHOOK", String.valueOf(id), "SUCCESS", "url", url, "eventTypes", types, "active", active);
        return view(repo.subscription(id).orElseThrow());
    }

    @Transactional
    public void delete(long id) {
        SubscriptionRow s = repo.subscription(id).orElseThrow(() -> IamException.notFound("Webhook", String.valueOf(id)));
        if (s.approvalTypeCode() != null) throw IamException.conflict("CALLBACK_OWNED_BY_TYPE", "This is the callback of an approval type; change the type instead");
        boolean deleted = repo.deleteOrDeactivate(id);
        audit.record(deleted ? "WEBHOOK_DELETED" : "WEBHOOK_DEACTIVATED", "WEBHOOK", String.valueOf(id), "SUCCESS");
    }

    public List<DeliveryView> deliveries(String status, Integer page, Integer size) {
        return repo.deliveries(status, Validate.page(page), Validate.size(size)).stream()
                .map(d -> new DeliveryView(d.id(), d.eventId(), d.subId(), d.status(), d.attempts(), d.nextAttemptAt(), d.lastError(), d.deliveredAt())).toList();
    }

    @Transactional
    public DeliveryView replay(long deliveryId) {
        DeliveryRow d = repo.delivery(deliveryId).orElseThrow(() -> IamException.notFound("Delivery", String.valueOf(deliveryId)));
        if (!repo.replay(deliveryId, Instant.now(clock))) throw IamException.conflict("INVALID_STATE", "Only a FAILED delivery can be replayed (this one is " + d.status() + ")");
        audit.record("WEBHOOK_REPLAYED", "WEBHOOK_DELIVERY", String.valueOf(deliveryId), "SUCCESS");
        DeliveryRow n = repo.delivery(deliveryId).orElseThrow();
        return new DeliveryView(n.id(), n.eventId(), n.subId(), n.status(), n.attempts(), n.nextAttemptAt(), n.lastError(), n.deliveredAt());
    }

    // ---- the callback of an approval type is a subscription owned by the type
    /** @return the new signing secret when the callback was just created, otherwise null */
    @Transactional
    public String ensureCallback(String typeCode, String url) {
        String checked = checkedUrl(url);
        var existing = repo.callbackOfType(typeCode);
        if (existing.isPresent()) {
            repo.updateSubscription(existing.get().id(), existing.get().name(), checked, existing.get().eventTypes(), true);
            return null;
        }
        String secret = Hashing.randomToken(32);
        repo.insertSubscription("callback:" + typeCode, checked, "APPROVAL_APPROVED,APPROVAL_REJECTED,APPROVAL_EXPIRED,APPROVAL_CANCELLED", crypto.encrypt(secret, "webhook"),
                typeCode, RequestContext.current().actor(), null);
        return secret;
    }

    @Transactional
    public void removeCallback(String typeCode) {
        repo.callbackOfType(typeCode).ifPresent(s -> repo.updateSubscription(s.id(), s.name(), s.url(), s.eventTypes(), false));
    }
}
