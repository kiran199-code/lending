package com.bank.lending.accounting.service;

import com.bank.lending.accounting.dto.AccountingDtos.EventView;
import com.bank.lending.accounting.dto.AccountingDtos.RouteDraftRequest;
import com.bank.lending.accounting.dto.AccountingDtos.RouteView;
import com.bank.lending.accounting.dto.AccountingDtos.TemplateDraftRequest;
import com.bank.lending.accounting.dto.AccountingDtos.TemplateView;
import com.bank.lending.accounting.dto.AccountingDtos.ValidationResult;
import com.bank.lending.accounting.engine.AccountingConfig;
import com.bank.lending.accounting.engine.Expr;
import com.bank.lending.accounting.engine.JournalAssembler;
import com.bank.lending.accounting.model.ConfigConflictException;
import com.bank.lending.accounting.model.Defs.AccountDef;
import com.bank.lending.accounting.model.Defs.EventDef;
import com.bank.lending.accounting.model.Defs.LedgerDef;
import com.bank.lending.accounting.repository.AccountingConfigRepository;
import com.bank.lending.accounting.repository.ConfigAdminRepository;
import com.bank.lending.accounting.repository.ConfigAdminRepository.RouteHead;
import com.bank.lending.accounting.repository.ConfigAdminRepository.TemplateHead;
import com.bank.lending.common.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The lifecycle of accounting configuration: templates and routes are drafted by one person and approved by another,
 * and only approved ones are used by the engine. Every change is written to the configuration audit trail.
 *
 * <p>Template states: DRAFT, SUBMITTED (waiting for a checker), APPROVED, RETIRED. A new approved version ends the
 * earlier one the day before it takes effect, so history is kept and a date always selects one template.
 */
@Service
public class TemplateAdminService {

    /** Names an expression may use besides the event's amount tags (supplied by the poster or the engine). */
    static final Set<String> CONTEXT_NAMES = Set.of("npa_stage", "product_code", "currency", "is_backdated",
            "account_status");

    private static final Set<String> PURPOSES = Set.of("PRIMARY", "MANAGEMENT", "REGULATORY", "MEMO");

    private final ConfigAdminRepository admin;
    private final AccountingConfigRepository configRepository;

    public TemplateAdminService(ConfigAdminRepository admin, AccountingConfigRepository configRepository) {
        this.admin = admin;
        this.configRepository = configRepository;
    }

    // ------------------------------------------------------------------------------------------------ templates

    @Transactional
    public TemplateView createDraft(TemplateDraftRequest req, String actor) {
        requireActor(actor);
        if (req.eventCode() == null || req.eventCode().isBlank()) {
            throw new IllegalArgumentException("eventCode is required");
        }
        if (!configRepository.current().events().containsKey(req.eventCode())) {
            throw new ResourceNotFoundException("Unknown accounting event " + req.eventCode());
        }
        if (req.rules() == null || req.rules().isEmpty()) {
            throw new IllegalArgumentException("A template needs at least one rule");
        }
        requireRoles(req.rules());
        int version = admin.nextVersion(req.eventCode(), req.productCode());
        long id = admin.insertTemplate(req.eventCode(), blankToNull(req.productCode()), version, req.effectiveFrom(),
                req.effectiveTo(), req.description(), actor);
        admin.replaceRules(id, req.rules());
        admin.audit("TEMPLATE", String.valueOf(id), "CREATE", actor,
                req.eventCode() + " v" + version + (req.productCode() == null ? "" : " product " + req.productCode()));
        return view(id);
    }

    @Transactional
    public TemplateView updateDraft(long templateId, TemplateDraftRequest req, String actor) {
        requireActor(actor);
        TemplateHead head = head(templateId);
        if (!"DRAFT".equals(head.status())) {
            throw new ConfigConflictException("Template " + templateId + " is " + head.status() + "; only a DRAFT can be edited");
        }
        if (req.rules() == null || req.rules().isEmpty()) {
            throw new IllegalArgumentException("A template needs at least one rule");
        }
        requireRoles(req.rules());
        admin.updateTemplateHeader(templateId, req.effectiveFrom(), req.effectiveTo(), req.description());
        admin.replaceRules(templateId, req.rules());
        admin.audit("TEMPLATE", String.valueOf(templateId), "EDIT", actor, null);
        return view(templateId);
    }

    @Transactional(readOnly = true)
    public ValidationResult validate(long templateId) {
        TemplateHead head = head(templateId);
        TemplateView t = view(templateId);
        AccountingConfig cfg = configRepository.current();
        EventDef ev = cfg.events().get(head.eventCode());
        List<String> problems = new ArrayList<>();
        if (ev == null) {
            problems.add("Event " + head.eventCode() + " does not exist");
            return new ValidationResult(false, problems);
        }
        if (t.effectiveTo() != null && t.effectiveTo().isBefore(t.effectiveFrom())) {
            problems.add("effectiveTo is before effectiveFrom");
        }
        Set<String> allowed = new HashSet<>(ev.tags());
        allowed.addAll(CONTEXT_NAMES);
        Set<Integer> seqs = new HashSet<>();
        boolean hasDr = false;
        boolean hasCr = false;
        for (var r : t.rules()) {
            String where = "Line " + r.lineSeq() + ": ";
            if (!seqs.add(r.lineSeq())) {
                problems.add(where + "line number is used twice");
            }
            if ("DR".equals(r.side())) {
                hasDr = true;
            } else if ("CR".equals(r.side())) {
                hasCr = true;
            } else {
                problems.add(where + "side must be DR or CR");
            }
            if (!admin.roleExists(r.roleCode())) {
                problems.add(where + "unknown accounting role " + r.roleCode());
            }
            checkExpression(where + "amount", r.amountSource(), allowed, problems);
            if (r.condition() != null && !r.condition().isBlank()) {
                checkExpression(where + "condition", r.condition(), allowed, problems);
            }
            if (r.ledgerScope() != null && !scopeKnown(r.ledgerScope(), cfg)) {
                problems.add(where + "ledger scope " + r.ledgerScope() + " matches no ledger code, purpose or TYPE:x");
            }
            if (!"TXN".equals(r.currencyBasis()) && !"BASE".equals(r.currencyBasis())) {
                problems.add(where + "currency basis must be TXN or BASE");
            }
            // every ledger the event posts to needs a route for this role
            for (Integer ledgerId : cfg.eventLedgers().getOrDefault(ev.eventCode(), List.of())) {
                LedgerDef ledger = cfg.ledgers().get(ledgerId);
                if (ledger == null || "CLOSED".equals(ledger.status()) || !JournalAssembler.applies(r.ledgerScope(), ledger)) {
                    continue;
                }
                boolean routed = cfg.routes().stream()
                        .anyMatch(rt -> rt.ledgerId() == ledgerId && rt.roleCode().equals(r.roleCode()));
                if (!routed) {
                    problems.add(where + "role " + r.roleCode() + " has no approved route in ledger " + ledger.ledgerCode());
                }
            }
        }
        if (!t.rules().isEmpty() && !(hasDr && hasCr)) {
            problems.add("A template needs at least one debit and one credit line");
        }
        return new ValidationResult(problems.isEmpty(), problems);
    }

    @Transactional
    public TemplateView submit(long templateId, String actor) {
        requireActor(actor);
        TemplateHead head = head(templateId);
        if (!"DRAFT".equals(head.status())) {
            throw new ConfigConflictException("Template " + templateId + " is " + head.status() + "; only a DRAFT can be submitted");
        }
        ValidationResult v = validate(templateId);
        if (!v.valid()) {
            throw new IllegalArgumentException("Template " + templateId + " is not valid: " + String.join("; ", v.problems()));
        }
        admin.setTemplateStatus(templateId, "SUBMITTED", null);
        admin.audit("TEMPLATE", String.valueOf(templateId), "SUBMIT", actor, null);
        return view(templateId);
    }

    @Transactional
    public TemplateView approve(long templateId, String actor) {
        requireActor(actor);
        TemplateHead head = head(templateId);
        if (!"SUBMITTED".equals(head.status())) {
            throw new ConfigConflictException("Template " + templateId + " is " + head.status() + "; only a SUBMITTED template can be approved");
        }
        if (head.createdBy() != null && head.createdBy().equals(actor)) {
            throw new ConfigConflictException("The person who drafted a template cannot approve it (maker-checker)");
        }
        ValidationResult v = validate(templateId);
        if (!v.valid()) {
            throw new IllegalArgumentException("Template " + templateId + " is no longer valid: " + String.join("; ", v.problems()));
        }
        admin.endPreviousVersions(templateId, head.eventCode(), head.productCode(), head.effectiveFrom());
        admin.setTemplateStatus(templateId, "APPROVED", actor);
        admin.audit("TEMPLATE", String.valueOf(templateId), "APPROVE", actor, null);
        configRepository.evict();
        return view(templateId);
    }

    @Transactional
    public TemplateView reject(long templateId, String actor, String reason) {
        requireActor(actor);
        TemplateHead head = head(templateId);
        if (!"SUBMITTED".equals(head.status())) {
            throw new ConfigConflictException("Template " + templateId + " is " + head.status() + "; only a SUBMITTED template can be rejected");
        }
        admin.setTemplateStatus(templateId, "DRAFT", null);
        admin.audit("TEMPLATE", String.valueOf(templateId), "REJECT", actor, reason);
        return view(templateId);
    }

    @Transactional
    public TemplateView retire(long templateId, String actor) {
        requireActor(actor);
        TemplateHead head = head(templateId);
        if (!"APPROVED".equals(head.status())) {
            throw new ConfigConflictException("Template " + templateId + " is " + head.status() + "; only an APPROVED template can be retired");
        }
        EventDef ev = configRepository.current().events().get(head.eventCode());
        boolean defaultTemplate = head.productCode() == null;
        boolean otherDefault = admin.templates(head.eventCode(), "APPROVED").stream()
                .anyMatch(t -> t.templateId() != templateId && t.productCode() == null);
        if (defaultTemplate && !otherDefault && ev != null && !"OFF".equals(ev.postingMode())) {
            throw new ConfigConflictException("Template " + templateId + " is the only default template of live event "
                    + head.eventCode() + "; switch the event OFF first or approve its replacement");
        }
        admin.setTemplateStatus(templateId, "RETIRED", null);
        admin.audit("TEMPLATE", String.valueOf(templateId), "RETIRE", actor, null);
        configRepository.evict();
        return view(templateId);
    }

    // ------------------------------------------------------------------------------------------------ routes

    @Transactional
    public RouteView createRoute(RouteDraftRequest req, String actor) {
        requireActor(actor);
        if (req.ledgerId() == null || req.roleCode() == null || req.glAccountCode() == null) {
            throw new IllegalArgumentException("ledgerId, roleCode and glAccountCode are required");
        }
        if (!admin.roleExists(req.roleCode())) {
            throw new ResourceNotFoundException("Unknown accounting role " + req.roleCode());
        }
        AccountDef account = configRepository.current().account(req.ledgerId(), req.glAccountCode()).orElseThrow(
                () -> new ResourceNotFoundException("GL account " + req.glAccountCode() + " does not exist in ledger " + req.ledgerId()));
        if (!account.postingAllowed() || !"ACTIVE".equals(account.status())) {
            throw new ConfigConflictException("GL account " + req.glAccountCode() + " is not open for posting");
        }
        String branch = req.branchCode() == null || req.branchCode().isBlank() ? null : req.branchCode().trim().toUpperCase();
        if (branch != null && !admin.branchExists(branch)) {
            throw new IllegalArgumentException("Unknown branch " + branch + " (see /api/v1/reference/branches)");
        }
        long id = admin.insertRoute(req.ledgerId(), req.roleCode(), req.productCode(), req.npaStage(), req.currency(),
                req.glAccountCode(), req.priority() == null ? 0 : req.priority(), req.effectiveFrom(), req.effectiveTo(),
                actor, branch);
        admin.audit("ROUTE", String.valueOf(id), "CREATE", actor,
                req.roleCode() + " -> " + req.glAccountCode() + " in ledger " + req.ledgerId()
                        + (branch == null ? "" : " for branch " + branch));
        return route(id);
    }

    @Transactional
    public RouteView approveRoute(long routeId, String actor) {
        requireActor(actor);
        RouteHead head = admin.routeHead(routeId).orElseThrow(() -> new ResourceNotFoundException("Route " + routeId + " not found"));
        if (!"DRAFT".equals(head.status())) {
            throw new ConfigConflictException("Route " + routeId + " is " + head.status() + "; only a DRAFT route can be approved");
        }
        if (head.createdBy() != null && head.createdBy().equals(actor)) {
            throw new ConfigConflictException("The person who drafted a route cannot approve it (maker-checker)");
        }
        admin.setRouteStatus(routeId, "APPROVED", actor);
        admin.audit("ROUTE", String.valueOf(routeId), "APPROVE", actor, null);
        configRepository.evict();
        return route(routeId);
    }

    @Transactional
    public RouteView retireRoute(long routeId, String actor) {
        requireActor(actor);
        RouteHead head = admin.routeHead(routeId).orElseThrow(() -> new ResourceNotFoundException("Route " + routeId + " not found"));
        if ("RETIRED".equals(head.status())) {
            return route(routeId);
        }
        admin.setRouteStatus(routeId, "RETIRED", null);
        admin.audit("ROUTE", String.valueOf(routeId), "RETIRE", actor, null);
        configRepository.evict();
        return route(routeId);
    }

    // ------------------------------------------------------------------------------------------------ events

    /** Changes an event's operating switches. Posting mode OFF/SHADOW/LIVE is the cutover control. */
    @Transactional
    public EventView updateEvent(String eventCode, String postingMode, String blockingMode, Boolean eodGate, String actor) {
        requireActor(actor);
        if (!configRepository.current().events().containsKey(eventCode)) {
            throw new ResourceNotFoundException("Unknown accounting event " + eventCode);
        }
        if (postingMode != null && !Set.of("OFF", "SHADOW", "LIVE").contains(postingMode)) {
            throw new IllegalArgumentException("postingMode must be OFF, SHADOW or LIVE");
        }
        if (blockingMode != null && !Set.of("BLOCKING", "NON_BLOCKING").contains(blockingMode)) {
            throw new IllegalArgumentException("blockingMode must be BLOCKING or NON_BLOCKING");
        }
        admin.updateEvent(eventCode, postingMode, blockingMode, eodGate);
        admin.audit("EVENT", eventCode, "UPDATE", actor,
                "postingMode=" + postingMode + " blockingMode=" + blockingMode + " eodGate=" + eodGate);
        configRepository.evict();
        return admin.events().stream().filter(e -> e.eventCode().equals(eventCode)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private void requireRoles(List<com.bank.lending.accounting.dto.AccountingDtos.RuleInput> rules) {
        for (var r : rules) {
            if (r.roleCode() == null || !admin.roleExists(r.roleCode())) {
                throw new IllegalArgumentException("Unknown accounting role " + r.roleCode());
            }
            if (!"DR".equals(r.side()) && !"CR".equals(r.side())) {
                throw new IllegalArgumentException("A rule's side must be DR or CR, not " + r.side());
            }
        }
    }

    private TemplateHead head(long id) {
        return admin.templateHead(id).orElseThrow(() -> new ResourceNotFoundException("Template " + id + " not found"));
    }

    private TemplateView view(long id) {
        return admin.template(id).orElseThrow(() -> new ResourceNotFoundException("Template " + id + " not found"));
    }

    private RouteView route(long id) {
        return admin.routes(null, null).stream().filter(r -> r.routeId() == id).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Route " + id + " not found"));
    }

    private static void checkExpression(String label, String text, Set<String> allowed, List<String> problems) {
        if (text == null || text.isBlank()) {
            problems.add(label + " expression is empty");
            return;
        }
        try {
            Set<String> ids = Expr.parse(text).identifiers();
            for (String id : ids) {
                if (!allowed.contains(id)) {
                    problems.add(label + " uses " + id + ", which is neither a tag of the event nor a context value");
                }
            }
        } catch (Expr.ExprException e) {
            problems.add(label + " cannot be read: " + e.getMessage());
        }
    }

    private static boolean scopeKnown(String scope, AccountingConfig cfg) {
        return scope.startsWith("TYPE:") || PURPOSES.contains(scope)
                || cfg.ledgers().values().stream().anyMatch(l -> l.ledgerCode().equals(scope));
    }

    private static void requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("The X-Actor header is required for configuration changes");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
