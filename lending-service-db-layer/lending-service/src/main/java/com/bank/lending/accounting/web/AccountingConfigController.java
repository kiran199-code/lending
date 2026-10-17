package com.bank.lending.accounting.web;

import com.bank.lending.accounting.dto.AccountingDtos.EventView;
import com.bank.lending.accounting.dto.AccountingDtos.RouteDraftRequest;
import com.bank.lending.accounting.dto.AccountingDtos.RouteView;
import com.bank.lending.accounting.dto.AccountingDtos.TemplateDraftRequest;
import com.bank.lending.accounting.dto.AccountingDtos.TemplateView;
import com.bank.lending.accounting.dto.AccountingDtos.ValidationResult;
import com.bank.lending.accounting.service.AccountingQueryService;
import com.bank.lending.accounting.service.TemplateAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Changing accounting configuration: entry templates, routes and event switches. Every change needs an X-Actor, and a
 * template or route is approved by someone other than the person who drafted it.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Accounting configuration", description = "Entry templates, routes and event switches, with maker-checker approval")
public class AccountingConfigController {

    private static final String ACTOR_HEADER = "X-Actor";

    private final TemplateAdminService admin;
    private final AccountingQueryService query;

    @Operation(summary = "Entry templates, optionally for one event or status")
    @GetMapping("/api/v1/accounting/templates")
    public List<TemplateView> templates(@RequestParam(required = false) String eventCode,
                                        @RequestParam(required = false) String status) {
        return query.templates(eventCode, status);
    }

    @Operation(summary = "One entry template with its rules")
    @GetMapping("/api/v1/accounting/templates/{templateId}")
    public TemplateView template(@PathVariable long templateId) {
        return query.template(templateId);
    }

    @Operation(summary = "Draft a new template version (becomes the next version of its event and product)")
    @PostMapping("/api/v1/accounting/templates")
    public TemplateView create(@RequestBody TemplateDraftRequest request,
                               @RequestHeader(value = ACTOR_HEADER, required = false) String actor) {
        return admin.createDraft(request, actor);
    }

    @Operation(summary = "Edit a DRAFT template")
    @PutMapping("/api/v1/accounting/templates/{templateId}")
    public TemplateView update(@PathVariable long templateId, @RequestBody TemplateDraftRequest request,
                               @RequestHeader(value = ACTOR_HEADER, required = false) String actor) {
        return admin.updateDraft(templateId, request, actor);
    }

    @Operation(summary = "Check a template: expressions, tags, roles, ledger scopes and routes")
    @PostMapping("/api/v1/accounting/templates/{templateId}/validate")
    public ValidationResult validate(@PathVariable long templateId) {
        return admin.validate(templateId);
    }

    @Operation(summary = "Submit a valid DRAFT template for approval")
    @PostMapping("/api/v1/accounting/templates/{templateId}/submit")
    public TemplateView submit(@PathVariable long templateId,
                               @RequestHeader(value = ACTOR_HEADER, required = false) String actor) {
        return admin.submit(templateId, actor);
    }

    @Operation(summary = "Approve a SUBMITTED template (not by its author); it takes effect from its effective-from date")
    @PostMapping("/api/v1/accounting/templates/{templateId}/approve")
    public TemplateView approve(@PathVariable long templateId,
                                @RequestHeader(value = ACTOR_HEADER, required = false) String actor) {
        return admin.approve(templateId, actor);
    }

    @Operation(summary = "Send a SUBMITTED template back to DRAFT")
    @PostMapping("/api/v1/accounting/templates/{templateId}/reject")
    public TemplateView reject(@PathVariable long templateId, @RequestBody(required = false) Map<String, String> body,
                               @RequestHeader(value = ACTOR_HEADER, required = false) String actor) {
        return admin.reject(templateId, actor, body == null ? null : body.get("reason"));
    }

    @Operation(summary = "Retire an APPROVED template")
    @PostMapping("/api/v1/accounting/templates/{templateId}/retire")
    public TemplateView retire(@PathVariable long templateId,
                               @RequestHeader(value = ACTOR_HEADER, required = false) String actor) {
        return admin.retire(templateId, actor);
    }

    @Operation(summary = "Draft a route from an accounting role to a GL account")
    @PostMapping("/api/v1/accounting/routes")
    public RouteView createRoute(@RequestBody RouteDraftRequest request,
                                 @RequestHeader(value = ACTOR_HEADER, required = false) String actor) {
        return admin.createRoute(request, actor);
    }

    @Operation(summary = "Approve a DRAFT route (not by its author)")
    @PostMapping("/api/v1/accounting/routes/{routeId}/approve")
    public RouteView approveRoute(@PathVariable long routeId,
                                  @RequestHeader(value = ACTOR_HEADER, required = false) String actor) {
        return admin.approveRoute(routeId, actor);
    }

    @Operation(summary = "Retire a route")
    @PostMapping("/api/v1/accounting/routes/{routeId}/retire")
    public RouteView retireRoute(@PathVariable long routeId,
                                 @RequestHeader(value = ACTOR_HEADER, required = false) String actor) {
        return admin.retireRoute(routeId, actor);
    }

    @Operation(summary = "Change an event's posting mode (OFF, SHADOW, LIVE), blocking mode or end-of-day gate")
    @PutMapping("/api/v1/accounting/events/{eventCode}")
    public EventView updateEvent(@PathVariable String eventCode, @RequestBody Map<String, Object> body,
                                 @RequestHeader(value = ACTOR_HEADER, required = false) String actor) {
        return admin.updateEvent(eventCode, text(body, "postingMode"), text(body, "blockingMode"),
                body.get("eodGate") instanceof Boolean b ? b : null, actor);
    }

    private static String text(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
