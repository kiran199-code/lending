package com.bank.lending.iam.web;

import com.bank.lending.iam.events.WebhookService;
import com.bank.lending.iam.events.WebhookService.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/webhooks")
public class WebhookController {
    private final Guard guard;
    private final WebhookService webhooks;

    public WebhookController(Guard guard, WebhookService webhooks) {
        this.guard = guard;
        this.webhooks = webhooks;
    }

    @GetMapping
    public List<SubscriptionView> list(Authentication auth) {
        guard.require(auth, "iam.webhook.manage");
        return webhooks.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedSubscription create(Authentication auth, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                      @RequestBody CreateRequest r) {
        guard.require(auth, "iam.webhook.manage");
        return webhooks.create(r, idempotencyKey);
    }

    @PutMapping("/{id}")
    public SubscriptionView update(Authentication auth, @PathVariable long id, @RequestBody UpdateRequest r) {
        guard.require(auth, "iam.webhook.manage");
        return webhooks.update(id, r);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication auth, @PathVariable long id) {
        guard.require(auth, "iam.webhook.manage");
        webhooks.delete(id);
    }

    @GetMapping("/deliveries")
    public List<DeliveryView> deliveries(Authentication auth, @RequestParam(required = false) String status,
                                         @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        guard.require(auth, "iam.webhook.manage");
        return webhooks.deliveries(status, page, size);
    }

    @PostMapping("/deliveries/{id}/replay")
    public DeliveryView replay(Authentication auth, @PathVariable long id) {
        guard.require(auth, "iam.webhook.manage");
        return webhooks.replay(id);
    }
}
