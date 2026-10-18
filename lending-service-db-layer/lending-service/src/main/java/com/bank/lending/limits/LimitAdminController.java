package com.bank.lending.limits;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Operations view of the link to the Limits and Collateral Management service. */
@RestController
@RequestMapping("/api/v1/limits")
@Tag(name = "Limits (LCM link)")
public class LimitAdminController {

    private final LimitSettings settings;
    private final LimitOutboxRepository outbox;
    private final LimitOutboxService outboxService;

    public LimitAdminController(LimitSettings settings, LimitOutboxRepository outbox, LimitOutboxService outboxService) {
        this.settings = settings;
        this.outbox = outbox;
        this.outboxService = outboxService;
    }

    @GetMapping("/status")
    @Operation(summary = "Mode of the limit integration and the state of the queue of calls still owed to LCM")
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", settings.mode().name().toLowerCase());
        m.put("baseUrl", settings.baseUrl());
        m.put("failOpen", settings.failOpen());
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String st : List.of("PENDING", "SENT", "SKIPPED", "DEAD")) {
            counts.put(st, outbox.count(st));
        }
        m.put("outbox", counts);
        return m;
    }

    @GetMapping("/outbox")
    @Operation(summary = "Calls queued for LCM, newest first. status = PENDING, SENT, SKIPPED or DEAD (omit for all).")
    public List<LimitOutboxRepository.Row> list(@RequestParam(required = false) String status,
                                                @RequestParam(defaultValue = "100") int limit) {
        return outbox.list(status, Math.max(1, Math.min(limit, 500)));
    }

    @PostMapping("/outbox/retry")
    @Operation(summary = "Deliver what is due now instead of waiting for the next sweep")
    public Map<String, Object> retryNow() {
        return Map.of("processed", outboxService.retryDue());
    }

    @PostMapping("/outbox/{id}/requeue")
    @Operation(summary = "Put a DEAD call back in the queue once its cause is fixed")
    public ResponseEntity<Map<String, Object>> requeue(@PathVariable long id) {
        if (outbox.get(id) == null) {
            return ResponseEntity.notFound().build();
        }
        boolean ok = outbox.requeue(id);
        return ok ? ResponseEntity.ok(Map.of("id", id, "status", "PENDING"))
                : ResponseEntity.status(409).body(Map.of("id", id, "message", "Only a DEAD call can be requeued"));
    }
}
