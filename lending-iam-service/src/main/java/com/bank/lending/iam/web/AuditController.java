package com.bank.lending.iam.web;

import com.bank.lending.iam.audit.AuditService;
import com.bank.lending.iam.common.Caller;
import com.bank.lending.iam.common.Validate;
import com.bank.lending.iam.web.ApiDtos.AuditPageView;
import com.bank.lending.iam.web.ApiDtos.AuditRowView;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** Reading the audit trail is itself audited. */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {
    private final Guard guard;
    private final AuditService audit;

    public AuditController(Guard guard, AuditService audit) {
        this.guard = guard;
        this.audit = audit;
    }

    @GetMapping
    public AuditPageView search(Authentication auth, @RequestParam(required = false) String actor, @RequestParam(required = false) String action,
                                @RequestParam(required = false) String targetType, @RequestParam(required = false) String targetId,
                                @RequestParam(required = false) String outcome,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        Caller c = guard.require(auth, "iam.audit.read");
        int p = Validate.page(page), s = Validate.size(size);
        AuditService.Page r = audit.search(actor, action, targetType, targetId, outcome, from, to, p, s);
        audit.record("AUDIT_READ", "AUDIT", null, "SUCCESS", "reader", c.name(), "actor", actor, "action", action, "rows", r.rows().size());
        return new AuditPageView(r.rows().stream().map(a -> new AuditRowView(a.id(), a.occurredAt(), a.actorType(), a.actor(), a.action(), a.targetType(),
                a.targetId(), a.outcome(), a.ip(), a.correlationId(), a.detail(), a.rowHash())).toList(), r.total(), p, s);
    }

    @GetMapping("/verify")
    public AuditService.VerifyResult verify(Authentication auth,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        Caller c = guard.require(auth, "iam.audit.read");
        AuditService.VerifyResult r = audit.verify(from, to);
        audit.record("AUDIT_VERIFIED", "AUDIT", null, r.ok() ? "SUCCESS" : "FAILURE", "reader", c.name(), "rows", r.rowsChecked());
        return r;
    }
}
