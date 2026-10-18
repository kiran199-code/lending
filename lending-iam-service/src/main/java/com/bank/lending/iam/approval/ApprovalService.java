package com.bank.lending.iam.approval;

import com.bank.lending.iam.access.AccessService;
import com.bank.lending.iam.access.AuthzService;
import com.bank.lending.iam.access.RbacModels.PermissionRow;
import com.bank.lending.iam.approval.ApprovalDtos.*;
import com.bank.lending.iam.approval.ApprovalModels.*;
import com.bank.lending.iam.audit.AuditService;
import com.bank.lending.iam.common.Caller;
import com.bank.lending.iam.common.Hashing;
import com.bank.lending.iam.common.IamException;
import com.bank.lending.iam.common.Json;
import com.bank.lending.iam.common.MetricsSink;
import com.bank.lending.iam.common.Validate;
import com.bank.lending.iam.domain.ApprovalRules;
import com.bank.lending.iam.domain.AuthzDecisionEngine;
import com.bank.lending.iam.events.EventService;
import com.bank.lending.iam.events.WebhookService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Generic maker-checker: pending actions, approvals by different people, once-only consumption, expiry. */
@Service
public class ApprovalService {
    private static final int MAX_PAYLOAD = 65_536;

    private final ApprovalRepository repo;
    private final AccessService access;
    private final AuthzService authz;
    private final AuditService audit;
    private final EventService events;
    private final WebhookService webhooks;
    private final MetricsSink metrics;
    private final Clock clock;

    public ApprovalService(ApprovalRepository repo, AccessService access, AuthzService authz, AuditService audit, EventService events,
                           WebhookService webhooks, MetricsSink metrics, Clock clock) {
        this.repo = repo;
        this.access = access;
        this.authz = authz;
        this.audit = audit;
        this.events = events;
        this.webhooks = webhooks;
        this.metrics = metrics;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ types
    private static TypeView view(TypeRow t) {
        return new TypeView(t.code(), t.name(), t.makerPermission(), t.checkerPermission(), t.approvalsRequired(), t.pendingHours(), t.consumeHours(), t.callbackUrl(), t.active());
    }

    public List<TypeView> types() {
        return repo.types().stream().map(ApprovalService::view).toList();
    }

    private void requireCataloguePermission(String code, String field) {
        if (!authz.permissions().containsKey(code)) throw IamException.unprocessable("UNKNOWN_PERMISSION", field + " is not a permission of the catalogue");
    }

    private static void checkBounds(Integer approvals, Integer pending, Integer consume) {
        if (approvals != null && approvals != 1 && approvals != 2) throw IamException.badRequest("VALIDATION_FAILED", "approvalsRequired must be 1 or 2");
        if (pending != null && (pending < 1 || pending > 24 * 30)) throw IamException.badRequest("VALIDATION_FAILED", "pendingHours must be 1 to 720");
        if (consume != null && (consume < 1 || consume > 24 * 30)) throw IamException.badRequest("VALIDATION_FAILED", "consumeHours must be 1 to 720");
    }

    @Transactional
    public TypeSaved createType(CreateTypeRequest r) {
        String code = Validate.code(r.code(), "code").toUpperCase();
        if (repo.type(code).isPresent()) throw IamException.conflict("TYPE_EXISTS", "An approval type with this code exists");
        requireCataloguePermission(r.makerPermission(), "makerPermission");
        requireCataloguePermission(r.checkerPermission(), "checkerPermission");
        if (r.makerPermission().equals(r.checkerPermission())) throw IamException.unprocessable("SAME_PERMISSION", "Maker and checker permission must differ");
        checkBounds(r.approvalsRequired(), r.pendingHours(), r.consumeHours());
        TypeRow t = new TypeRow(code, Validate.text(r.name(), "name", 120, true), r.makerPermission(), r.checkerPermission(),
                r.approvalsRequired() == null ? 1 : r.approvalsRequired(), r.pendingHours() == null ? 72 : r.pendingHours(),
                r.consumeHours() == null ? 24 : r.consumeHours(), blankToNull(r.callbackUrl()), true);
        repo.insertType(t);
        String secret = t.callbackUrl() == null ? null : webhooks.ensureCallback(t.code(), t.callbackUrl());
        audit.record("APPROVAL_TYPE_CREATED", "APPROVAL_TYPE", code, "SUCCESS", "maker", t.makerPermission(), "checker", t.checkerPermission(), "approvals", t.approvalsRequired());
        return new TypeSaved(view(t), secret);
    }

    @Transactional
    public TypeSaved updateType(String code, UpdateTypeRequest r) {
        TypeRow old = repo.type(code).orElseThrow(() -> IamException.notFound("Approval type", code));
        checkBounds(r.approvalsRequired(), r.pendingHours(), r.consumeHours());
        String cb = blankToNull(r.callbackUrl());
        repo.updateType(code, Validate.text(r.name(), "name", 120, true), r.approvalsRequired(), r.pendingHours(), r.consumeHours(), cb, r.active());
        String secret = cb == null ? null : webhooks.ensureCallback(code, cb);
        if (cb == null) webhooks.removeCallback(code);
        audit.record("APPROVAL_TYPE_UPDATED", "APPROVAL_TYPE", code, "SUCCESS",
                "before", java.util.Map.of("approvals", old.approvalsRequired(), "pendingHours", old.pendingHours(), "consumeHours", old.consumeHours(), "active", old.active(), "callback", old.callbackUrl() == null ? "none" : "set"),
                "after", java.util.Map.of("approvals", r.approvalsRequired(), "pendingHours", r.pendingHours(), "consumeHours", r.consumeHours(), "active", r.active(), "callback", cb == null ? "none" : "set"));
        return new TypeSaved(view(repo.type(code).orElseThrow()), secret);
    }

    // ------------------------------------------------------------------ views
    private ActionView toView(ActionRow a) {
        List<DecisionView> ds = repo.decisions(a.id()).stream().map(d -> new DecisionView(d.approver(), d.decision(), d.comment(), d.decidedAt())).toList();
        int given = (int) ds.stream().filter(d -> "APPROVE".equals(d.decision())).count();
        return new ActionView(a.id(), a.typeCode(), a.targetRef(), a.summary(), a.payload(), a.payloadHash(), a.amount(), a.currency(), a.branch(), a.product(),
                a.status(), a.maker(), a.submittedBy(), a.approvalsNeeded(), given, a.createdAt(), a.expiresAt(), a.decidedAt(), a.consumeDeadline(),
                a.consumedAt(), a.consumedBy(), a.consumerRef(), a.decisionComment(), ds);
    }

    public ActionView get(String id) {
        return toView(repo.action(validUuid(id)).orElseThrow(() -> IamException.notFound("Approval", id)));
    }

    public PageView search(String status, String type, String maker, String target, Integer page, Integer size) {
        int p = Validate.page(page), s = Validate.size(size);
        ApprovalRepository.Page r = repo.search(status, type, maker, target, p, s);
        return new PageView(r.rows().stream().map(this::toView).toList(), r.total(), p, s);
    }

    /** Actions the caller could decide: checker permission held, within scope and ceiling, not their own, not already decided by them. */
    public List<ActionView> inbox(Caller caller) {
        Optional<AccessService.Snapshot> snap = access.forUser(caller.name());
        if (snap.isEmpty() || !snap.get().active() || !snap.get().permissions().contains("iam.approval.decide")) return List.of();
        List<TypeRow> mine = repo.types().stream().filter(t -> t.active() && snap.get().permissions().contains(t.checkerPermission())).toList();
        Instant now = Instant.now(clock);
        List<ActionRow> candidates = repo.inbox(mine.stream().map(TypeRow::code).toList(), caller.name(), now, 200);
        List<ActionView> out = new ArrayList<>();
        for (ActionRow a : candidates) {
            TypeRow t = mine.stream().filter(x -> x.code().equals(a.typeCode())).findFirst().orElseThrow();
            AuthzDecisionEngine.Decision d = authz.engine().decide(snap.get().toSubject(), checkFor(t.checkerPermission(), a, now), now, today());
            if (d.allowed() || AuthzDecisionEngine.STEP_UP_REQUIRED.equals(d.code())) out.add(toView(a));
        }
        return out;
    }

    // ------------------------------------------------------------------ submit
    @Transactional
    public ActionView submit(Caller caller, SubmitRequest r, String idempotencyKey) {
        TypeRow type = repo.type(Validate.code(r.type(), "type").toUpperCase()).orElseThrow(() -> IamException.notFound("Approval type", r.type()));
        if (!type.active()) throw IamException.unprocessable("TYPE_INACTIVE", "This approval type is not active");
        String idem = blankToNull(idempotencyKey);
        if (idem != null) {
            if (idem.length() > 80) throw IamException.badRequest("VALIDATION_FAILED", "Idempotency-Key is too long");
            Optional<ActionRow> prior = repo.byIdempotencyKey(type.code(), idem);
            if (prior.isPresent()) return toView(prior.get());
        }
        if (r.payload() == null || r.payload().length() > MAX_PAYLOAD) throw IamException.badRequest("PAYLOAD_INVALID", "payload is required and at most " + MAX_PAYLOAD + " characters");
        String canonical;
        try {
            canonical = Json.canonical(r.payload());
        } catch (IllegalArgumentException e) {
            throw IamException.badRequest("PAYLOAD_INVALID", "payload must be valid JSON");
        }
        String targetRef = Validate.ref(r.targetRef(), "targetRef");
        String summary = Validate.text(r.summary(), "summary", 300, true);
        String currency = blankToNull(r.currency()) == null ? null : Validate.currency(r.currency());
        if (r.amount() != null && currency == null) throw IamException.badRequest("VALIDATION_FAILED", "currency is required with an amount");
        if (r.amount() != null && r.amount().signum() < 0) throw IamException.badRequest("VALIDATION_FAILED", "amount cannot be negative");

        // who is the maker?
        String maker;
        Instant makerMfaAt;
        if (blankToNull(r.onBehalfOf()) != null) {
            if (!caller.isService() || !caller.has("iam.approval.onbehalf")) {
                throw IamException.forbidden("NOT_ALLOWED_ON_BEHALF", "Only a service account with iam.approval.onbehalf may submit for another user");
            }
            maker = access.forUser(Validate.username(r.onBehalfOf())).map(AccessService.Snapshot::subject)
                    .orElseThrow(() -> IamException.unprocessable("MAKER_UNKNOWN", "The named user does not exist"));
            makerMfaAt = Instant.now(clock);   // the calling service vouches for the user's session
        } else {
            maker = caller.name();
            makerMfaAt = caller.mfaAt();
        }
        Instant now = Instant.now(clock);
        Optional<AccessService.Snapshot> msnap = caller.isService() && blankToNull(r.onBehalfOf()) == null ? access.forClient(maker) : access.forUser(maker);
        if (msnap.isEmpty() || !msnap.get().active()) throw IamException.forbidden("NOT_A_MAKER", "The maker is not active");
        AuthzDecisionEngine.Decision d = authz.engine().decide(msnap.get().toSubject(),
                new AuthzDecisionEngine.Check(type.makerPermission(), blankToNull(r.branch()), blankToNull(r.product()), currency, r.amount(), null, null, makerMfaAt), now, today());
        if (!d.allowed()) {
            String code = AuthzDecisionEngine.NOT_GRANTED.equals(d.code()) ? "NOT_A_MAKER" : d.code();
            throw IamException.forbidden(code, "The maker may not submit this action (" + d.code() + ")");
        }

        String id = UUID.randomUUID().toString();
        String hash = Hashing.sha256Hex(canonical);
        boolean inserted = repo.insertAction(id, type, targetRef, summary, r.payload(), hash, r.amount(), currency, blankToNull(r.branch()), blankToNull(r.product()), maker, caller.name(),
                idem, now, now.plus(Duration.ofHours(type.pendingHours())));
        if (!inserted) {
            // two submits with one idempotency key at once: the other won (ON CONFLICT leaves the transaction usable, unlike a caught constraint error)
            return toView(repo.byIdempotencyKey(type.code(), idem)
                    .orElseThrow(() -> IamException.conflict("IDEMPOTENCY_CONFLICT", "A request with this Idempotency-Key is being processed; retry")));
        }
        audit.record("APPROVAL_SUBMITTED", "APPROVAL", id, "SUCCESS", "type", type.code(), "targetRef", targetRef, "maker", maker, "submittedBy", caller.name(),
                "amount", r.amount() == null ? null : r.amount().toPlainString(), "currency", currency, "payloadHash", hash);
        events.publish(EventService.APPROVAL_SUBMITTED, "approval:" + id, "actionId", id, "type", type.code(), "targetRef", targetRef, "maker", maker, "payloadHash", hash, "status", "PENDING");
        metrics.count("iam.approvals", "status", "PENDING");
        return get(id);
    }

    // ------------------------------------------------------------------ decide
    // a refusal here happens before anything is written, and the failure counter and audit row must survive it
    @Transactional(noRollbackFor = IamException.class)
    public DecisionOutcome decide(Caller caller, String id, boolean approve, String comment) {
        if (!caller.isStaff()) throw IamException.forbidden("NOT_A_CHECKER", "Only a person can approve or reject");
        String cleanComment = Validate.text(comment, "comment", 500, false);
        if (!approve && cleanComment == null) throw IamException.badRequest("COMMENT_REQUIRED", "A rejection needs a comment");
        ActionRow a = repo.actionForUpdate(validUuid(id)).orElseThrow(() -> IamException.notFound("Approval", id));
        TypeRow type = repo.type(a.typeCode()).orElseThrow();
        Instant now = Instant.now(clock);
        Set<String> earlier = new HashSet<>();
        for (DecisionRow d : repo.decisions(a.id())) earlier.add(d.approver());

        String block = ApprovalRules.blockDecision(new ApprovalRules.ActionView(a.status(), a.maker(), a.expiresAt(), a.consumeDeadline(), a.approvalsNeeded()), caller.name(), earlier, now);
        if ("ACTION_EXPIRED".equals(block)) {
            expireOne(a, now);   // the late decision is recorded as EXPIRED, and then reported as a failure by the caller
            return new DecisionOutcome(get(a.id()), "ACTION_EXPIRED");
        }
        if (block != null) {
            throw "MAKER_CANNOT_APPROVE".equals(block) ? IamException.forbidden(block, "The maker cannot decide their own action")
                    : IamException.conflict(block, "The action cannot be decided: " + block);
        }

        AccessService.Snapshot snap = access.forUser(caller.name()).filter(AccessService.Snapshot::active)
                .orElseThrow(() -> IamException.forbidden("NOT_A_CHECKER", "The approver is not active"));
        if (!snap.permissions().contains("iam.approval.decide") || !snap.permissions().contains(type.checkerPermission())) {
            throw IamException.forbidden("NOT_A_CHECKER", "The approver does not hold the checker permission of this type");
        }
        if (snap.permissions().contains(type.makerPermission())) {
            throw IamException.conflict("SOD_CONFLICT", "The approver also holds the maker permission of this type");
        }
        AuthzDecisionEngine.Decision d = authz.engine().decide(snap.toSubject(), checkFor(type.checkerPermission(), a, now).withMfa(caller.mfaAt()), now, today());
        if (!d.allowed()) {
            String code = AuthzDecisionEngine.NOT_GRANTED.equals(d.code()) ? "NOT_A_CHECKER" : d.code();
            audit.record("APPROVAL_DECISION_DENIED", "APPROVAL", a.id(), "DENIED", "code", d.code());
            throw IamException.forbidden(code, "The approver may not decide this action (" + d.code() + ")");
        }

        if (!repo.insertDecision(a.id(), caller.name(), approve ? "APPROVE" : "REJECT", cleanComment, now)) {
            throw IamException.conflict("ALREADY_DECIDED", "You have already decided this action");
        }
        String newStatus;
        if (!approve) {
            repo.decide(a.id(), ApprovalRules.REJECTED, now, null, cleanComment);
            newStatus = ApprovalRules.REJECTED;
        } else if (ApprovalRules.isApproved((int) repo.approvalCount(a.id()), a.approvalsNeeded())) {
            repo.decide(a.id(), ApprovalRules.APPROVED, now, ApprovalRules.consumeDeadline(now, type.consumeHours()), cleanComment);
            newStatus = ApprovalRules.APPROVED;
        } else {
            newStatus = ApprovalRules.PENDING;
        }
        audit.record(approve ? "APPROVAL_APPROVED" : "APPROVAL_REJECTED", "APPROVAL", a.id(), "SUCCESS", "type", a.typeCode(), "resultingStatus", newStatus, "comment", cleanComment);
        if (!ApprovalRules.PENDING.equals(newStatus)) {
            events.publish(approve ? EventService.APPROVAL_APPROVED : EventService.APPROVAL_REJECTED, "approval:" + a.id(), "actionId", a.id(), "type", a.typeCode(),
                    "targetRef", a.targetRef(), "maker", a.maker(), "status", newStatus, "payloadHash", a.payloadHash(), "decidedBy", caller.name(), "comment", cleanComment);
            metrics.count("iam.approvals", "status", newStatus);
        }
        return new DecisionOutcome(get(a.id()), null);
    }

    @Transactional
    public ActionView cancel(Caller caller, String id) {
        ActionRow a = repo.actionForUpdate(validUuid(id)).orElseThrow(() -> IamException.notFound("Approval", id));
        if (!a.maker().equalsIgnoreCase(caller.name())) throw IamException.forbidden("NOT_THE_MAKER", "Only the maker can cancel the action");
        if (!ApprovalRules.PENDING.equals(a.status())) throw IamException.conflict("ACTION_NOT_PENDING", "Only a PENDING action can be cancelled");
        cancelRow(a, "Cancelled by the maker");
        return get(a.id());
    }

    private void cancelRow(ActionRow a, String reason) {
        Instant now = Instant.now(clock);
        if (!repo.decide(a.id(), ApprovalRules.CANCELLED, now, null, reason)) return;
        audit.record("APPROVAL_CANCELLED", "APPROVAL", a.id(), "SUCCESS", "type", a.typeCode(), "reason", reason);
        events.publish(EventService.APPROVAL_CANCELLED, "approval:" + a.id(), "actionId", a.id(), "type", a.typeCode(), "targetRef", a.targetRef(), "maker", a.maker(), "status", "CANCELLED");
        metrics.count("iam.approvals", "status", "CANCELLED");
    }

    /** Withdraws every PENDING action a user made (offboarding). */
    @Transactional
    public int cancelAllOf(String maker, String reason) {
        List<ActionRow> rows = repo.pendingOfMaker(maker);
        for (ActionRow a : rows) cancelRow(a, reason);
        return rows.size();
    }

    // ------------------------------------------------------------------ consume
    @Transactional
    public ActionView consume(Caller caller, String id, String reference) {
        String ref = Validate.text(reference, "reference", 120, true);
        ActionRow a = repo.actionForUpdate(validUuid(id)).orElseThrow(() -> IamException.notFound("Approval", id));
        Instant now = Instant.now(clock);
        if (ApprovalRules.CONSUMED.equals(a.status())) {
            // a repeat of the same consume is answered with the first result; another reference is a second use
            if (ref.equals(a.consumerRef())) return get(a.id());
            throw IamException.conflict("ALREADY_CONSUMED", "The action was already consumed");
        }
        if (!ApprovalRules.APPROVED.equals(a.status())) throw IamException.conflict("ACTION_NOT_APPROVED", "The action is " + a.status() + ", not APPROVED");
        if (a.consumeDeadline() != null && !now.isBefore(a.consumeDeadline())) throw IamException.conflict("ACTION_EXPIRED", "The consume deadline has passed");
        if (!repo.consume(a.id(), caller.name(), ref, now)) throw IamException.conflict("ALREADY_CONSUMED", "The action was already consumed");
        audit.record("APPROVAL_CONSUMED", "APPROVAL", a.id(), "SUCCESS", "type", a.typeCode(), "by", caller.name(), "reference", ref);
        events.publish(EventService.APPROVAL_CONSUMED, "approval:" + a.id(), "actionId", a.id(), "type", a.typeCode(), "targetRef", a.targetRef(), "status", "CONSUMED", "reference", ref);
        metrics.count("iam.approvals", "status", "CONSUMED");
        return get(a.id());
    }

    // ------------------------------------------------------------------ expiry sweep
    /** Expires pending actions past their time and approved actions past their consume deadline. */
    @Transactional
    public int expireDue() {
        Instant now = Instant.now(clock);
        int n = 0;
        for (String id : repo.expiredIds(now, 500)) {
            ActionRow a = repo.action(id).orElse(null);
            if (a != null && expireOne(a, now)) n++;
        }
        return n;
    }

    private boolean expireOne(ActionRow a, Instant now) {
        if (!repo.expire(a.id(), now)) return false;
        audit.recordAs("SYSTEM", "approval-sweep", "APPROVAL_EXPIRED", "APPROVAL", a.id(), "SUCCESS", "type", a.typeCode(), "was", a.status());
        events.publish(EventService.APPROVAL_EXPIRED, "approval:" + a.id(), "actionId", a.id(), "type", a.typeCode(), "targetRef", a.targetRef(), "maker", a.maker(), "status", "EXPIRED");
        metrics.count("iam.approvals", "status", "EXPIRED");
        return true;
    }

    // ------------------------------------------------------------------ helpers
    private AuthzDecisionEngine.Check checkFor(String permission, ActionRow a, Instant now) {
        return new AuthzDecisionEngine.Check(permission, a.branch(), a.product(), a.currency(), a.amount(), null, null, null);
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String validUuid(String id) {
        try {
            return UUID.fromString(id).toString();
        } catch (IllegalArgumentException | NullPointerException e) {
            throw IamException.notFound("Approval", String.valueOf(id));
        }
    }
}
