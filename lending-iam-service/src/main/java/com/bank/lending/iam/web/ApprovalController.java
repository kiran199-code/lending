package com.bank.lending.iam.web;

import com.bank.lending.iam.approval.ApprovalDtos.*;
import com.bank.lending.iam.approval.ApprovalService;
import com.bank.lending.iam.common.Caller;
import com.bank.lending.iam.common.IamException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class ApprovalController {
    private final Guard guard;
    private final ApprovalService approvals;

    public ApprovalController(Guard guard, ApprovalService approvals) {
        this.guard = guard;
        this.approvals = approvals;
    }

    @GetMapping("/approval-types")
    public List<TypeView> types(Authentication auth) {
        guard.require(auth, "iam.approval.read");
        return approvals.types();
    }

    @PostMapping("/approval-types")
    @ResponseStatus(HttpStatus.CREATED)
    public TypeSaved createType(Authentication auth, @Valid @RequestBody CreateTypeRequest r) {
        guard.require(auth, "iam.approval.manage");
        return approvals.createType(r);
    }

    @PutMapping("/approval-types/{code}")
    public TypeSaved updateType(Authentication auth, @PathVariable String code, @Valid @RequestBody UpdateTypeRequest r) {
        guard.require(auth, "iam.approval.manage");
        return approvals.updateType(code, r);
    }

    @PostMapping("/approvals")
    @ResponseStatus(HttpStatus.CREATED)
    public ActionView submit(Authentication auth, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                             @Valid @RequestBody SubmitRequest r) {
        Caller c = guard.require(auth, "iam.approval.submit");
        return approvals.submit(c, r, idempotencyKey);
    }

    @GetMapping("/approvals")
    public PageView search(Authentication auth, @RequestParam(required = false) String status, @RequestParam(required = false) String type,
                           @RequestParam(required = false) String maker, @RequestParam(required = false) String target,
                           @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        guard.require(auth, "iam.approval.read");
        return approvals.search(status, type, maker, target, page, size);
    }

    @GetMapping("/approvals/inbox")
    public List<ActionView> inbox(Authentication auth) {
        Caller c = guard.require(auth, "iam.approval.read");
        return approvals.inbox(c);
    }

    @GetMapping("/approvals/{id}")
    public ActionView get(Authentication auth, @PathVariable String id) {
        guard.require(auth, "iam.approval.read");
        return approvals.get(id);
    }

    @PostMapping("/approvals/{id}/approve")
    public ActionView approve(Authentication auth, @PathVariable String id, @Valid @RequestBody(required = false) DecideRequest r) {
        return decide(auth, id, true, r);
    }

    @PostMapping("/approvals/{id}/reject")
    public ActionView reject(Authentication auth, @PathVariable String id, @Valid @RequestBody(required = false) DecideRequest r) {
        return decide(auth, id, false, r);
    }

    private ActionView decide(Authentication auth, String id, boolean approve, DecideRequest r) {
        Caller c = guard.require(auth, "iam.approval.decide");
        DecisionOutcome o = approvals.decide(c, id, approve, r == null ? null : r.comment());
        // a decision that arrives after the expiry is recorded as an expiry first, then reported as a failure
        if (o.error() != null) throw IamException.conflict(o.error(), "The action expired before this decision");
        return o.action();
    }

    /** Only the maker may cancel; the check is in the service, so any signed-in identity may call it. */
    @PostMapping("/approvals/{id}/cancel")
    public ActionView cancel(Authentication auth, @PathVariable String id) {
        Caller c = guard.authenticated(auth);
        return approvals.cancel(c, id);
    }

    @PostMapping("/approvals/{id}/consume")
    public ActionView consume(Authentication auth, @PathVariable String id, @Valid @RequestBody ConsumeRequest r) {
        Caller c = guard.require(auth, "iam.approval.consume");
        return approvals.consume(c, id, r.reference());
    }
}
