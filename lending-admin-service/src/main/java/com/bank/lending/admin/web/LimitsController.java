package com.bank.lending.admin.web;

import com.bank.lending.admin.service.LcmClient;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Limit and collateral screens: facilities, maker-checker changes and temporary excess, collateral, valuations, allocations,
 * drawing power and monitoring. Everything passes through to lending-lcm-service, which applies the real rules and whose
 * message is returned unchanged; the checks here only catch a malformed reference or status so the user gets a clear message.
 *
 * <p><b>Who acts.</b> LCM's maker-checker compares the names it is given. The console has one login, so the screens ask
 * the person to type the name they are acting as, send it in {@code X-Acting-As}, and it is passed on as {@code X-Actor}
 * (the login name is used when none is given). These names are not authenticated; real separation of duties comes with IAM.
 */
@RestController
@RequestMapping("/api/admin/limits")
@Tag(name = "Limits and collateral", description = "Facilities, collateral, valuations, allocations and monitoring (done by lending-lcm-service)")
public class LimitsController {

    private static final Pattern REF = Pattern.compile("[A-Za-z0-9_.:\\-]{1,64}");
    private static final Pattern WORD = Pattern.compile("[A-Z_]{1,30}");
    private static final Pattern PERSON = Pattern.compile("[A-Za-z0-9._@\\-]{2,40}");

    private final LcmClient client;

    public LimitsController(LcmClient client) {
        this.client = client;
    }

    @Operation(summary = "Whether the limit screens are configured (LCM address and password set)")
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("enabled", client.enabled());
    }

    // ---------------------------------------------------------------- facilities

    @Operation(summary = "Search facilities by customer, status and parent")
    @GetMapping("/facilities")
    public JsonNode facilities(@RequestParam(required = false) Long customerId, @RequestParam(required = false) String status,
                               @RequestParam(required = false) String parentRef) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("customerId", customerId);
        q.put("status", word(status, "status"));
        q.put("parentRef", ref(parentRef, "parentRef"));
        return client.get("/facilities", q);
    }

    @Operation(summary = "Create a facility in DRAFT; activate it with a change that a different person approves")
    @PostMapping("/facilities")
    public JsonNode createFacility(@RequestBody JsonNode body, @RequestHeader(value = "X-Acting-As", required = false) String acting,
                                   Principal user) {
        object(body);
        number(body, "customerId");
        text(body, "facilityType");
        text(body, "currencyCode");
        number(body, "sanctionedAmount");
        if (body.hasNonNull("facilityRef")) {
            ref(body.get("facilityRef").asText(), "facilityRef");
        }
        return client.post("/facilities", Map.of(), body, actor(acting, user));
    }

    @Operation(summary = "One facility")
    @GetMapping("/facilities/{ref}")
    public JsonNode facility(@PathVariable String ref) {
        return client.get("/facilities/" + ref(ref, "ref"), Map.of());
    }

    @Operation(summary = "Limit, utilisation and availability including the whole parent chain")
    @GetMapping("/facilities/{ref}/availability")
    public JsonNode availability(@PathVariable String ref) {
        return client.get("/facilities/" + ref(ref, "ref") + "/availability", Map.of());
    }

    @Operation(summary = "The facility with all its sub-limits and their availability")
    @GetMapping("/facilities/{ref}/tree")
    public JsonNode tree(@PathVariable String ref) {
        return client.get("/facilities/" + ref(ref, "ref") + "/tree", Map.of());
    }

    @Operation(summary = "Ledger of limit movements, newest first")
    @GetMapping("/facilities/{ref}/transactions")
    public JsonNode transactions(@PathVariable String ref, @RequestParam(required = false) Long beforeId,
                                 @RequestParam(defaultValue = "100") int limit) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("beforeId", beforeId);
        q.put("limit", Math.max(1, Math.min(limit, 500)));
        return client.get("/facilities/" + ref(ref, "ref") + "/transactions", q);
    }

    @Operation(summary = "Drawing power of a facility with the contribution of each allocated collateral")
    @GetMapping("/facilities/{ref}/drawing-power")
    public JsonNode drawingPower(@PathVariable String ref) {
        return client.get("/facilities/" + ref(ref, "ref") + "/drawing-power", Map.of());
    }

    @Operation(summary = "Propose ACTIVATE, AMEND_LIMIT, EXTEND_EXPIRY, SUSPEND, REINSTATE, FREEZE or CLOSE")
    @PostMapping("/facilities/{ref}/changes")
    public JsonNode proposeChange(@PathVariable String ref, @RequestBody JsonNode body,
                                  @RequestHeader(value = "X-Acting-As", required = false) String acting, Principal user) {
        object(body);
        text(body, "changeType");
        return client.post("/facilities/" + ref(ref, "ref") + "/changes", Map.of(), body, actor(acting, user));
    }

    @Operation(summary = "Temporary excess history of a facility")
    @GetMapping("/facilities/{ref}/excess")
    public JsonNode excess(@PathVariable String ref) {
        return client.get("/facilities/" + ref(ref, "ref") + "/excess", Map.of());
    }

    @Operation(summary = "Propose a temporary excess over the sanctioned limit")
    @PostMapping("/facilities/{ref}/excess")
    public JsonNode proposeExcess(@PathVariable String ref, @RequestBody JsonNode body,
                                  @RequestHeader(value = "X-Acting-As", required = false) String acting, Principal user) {
        object(body);
        number(body, "amount");
        text(body, "validFrom");
        text(body, "validTo");
        text(body, "reason");
        return client.post("/facilities/" + ref(ref, "ref") + "/excess", Map.of(), body, actor(acting, user));
    }

    @Operation(summary = "Facility changes of all facilities, e.g. status=PENDING")
    @GetMapping("/changes")
    public JsonNode changes(@RequestParam(required = false) String status, @RequestParam(required = false) String facilityRef) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("status", word(status, "status"));
        q.put("facilityRef", ref(facilityRef, "facilityRef"));
        return client.get("/facilities/changes", q);
    }

    @Operation(summary = "Approve a facility change (a different person from the one who proposed it)")
    @PostMapping("/changes/{id}/approve")
    public JsonNode approveChange(@PathVariable long id, @RequestBody(required = false) JsonNode body,
                                  @RequestHeader(value = "X-Acting-As", required = false) String acting, Principal user) {
        return client.post("/facilities/changes/" + id + "/approve", Map.of(), note(body), actor(acting, user));
    }

    @Operation(summary = "Reject a facility change")
    @PostMapping("/changes/{id}/reject")
    public JsonNode rejectChange(@PathVariable long id, @RequestBody(required = false) JsonNode body,
                                 @RequestHeader(value = "X-Acting-As", required = false) String acting, Principal user) {
        return client.post("/facilities/changes/" + id + "/reject", Map.of(), note(body), actor(acting, user));
    }

    @Operation(summary = "Temporary excess requests of all facilities, e.g. status=PENDING")
    @GetMapping("/excess-requests")
    public JsonNode excessRequests(@RequestParam(required = false) String status) {
        return client.get("/facilities/excess-requests", statusQuery(status));
    }

    @Operation(summary = "Approve a temporary excess")
    @PostMapping("/excess/{id}/approve")
    public JsonNode approveExcess(@PathVariable long id, @RequestHeader(value = "X-Acting-As", required = false) String acting,
                                  Principal user) {
        return client.post("/facilities/excess/" + id + "/approve", Map.of(), null, actor(acting, user));
    }

    @Operation(summary = "Reject a temporary excess")
    @PostMapping("/excess/{id}/reject")
    public JsonNode rejectExcess(@PathVariable long id, @RequestHeader(value = "X-Acting-As", required = false) String acting,
                                 Principal user) {
        return client.post("/facilities/excess/" + id + "/reject", Map.of(), null, actor(acting, user));
    }

    // ---------------------------------------------------------------- collateral

    @Operation(summary = "Collateral types with default haircut, LTV, validity and requirements")
    @GetMapping("/collateral-types")
    public JsonNode collateralTypes() {
        return client.get("/collateral-types", Map.of());
    }

    @Operation(summary = "Search collateral by customer and status")
    @GetMapping("/collateral")
    public JsonNode collateral(@RequestParam(required = false) Long customerId, @RequestParam(required = false) String status) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("customerId", customerId);
        q.put("status", word(status, "status"));
        return client.get("/collateral", q);
    }

    @Operation(summary = "Register collateral in DRAFT")
    @PostMapping("/collateral")
    public JsonNode createCollateral(@RequestBody JsonNode body, @RequestHeader(value = "X-Acting-As", required = false) String acting,
                                     Principal user) {
        object(body);
        number(body, "customerId");
        text(body, "collateralType");
        text(body, "description");
        text(body, "currencyCode");
        if (body.hasNonNull("collateralRef")) {
            ref(body.get("collateralRef").asText(), "collateralRef");
        }
        return client.post("/collateral", Map.of(), body, actor(acting, user));
    }

    @Operation(summary = "One collateral with latest valuation, lendable value and what is still missing for activation")
    @GetMapping("/collateral/{ref}")
    public JsonNode oneCollateral(@PathVariable String ref) {
        return client.get("/collateral/" + ref(ref, "ref"), Map.of());
    }

    @Operation(summary = "Valuation history of a collateral")
    @GetMapping("/collateral/{ref}/valuations")
    public JsonNode valuations(@PathVariable String ref) {
        return client.get("/collateral/" + ref(ref, "ref") + "/valuations", Map.of());
    }

    @Operation(summary = "Propose a valuation; a different person approves it")
    @PostMapping("/collateral/{ref}/valuations")
    public JsonNode proposeValuation(@PathVariable String ref, @RequestBody JsonNode body,
                                     @RequestHeader(value = "X-Acting-As", required = false) String acting, Principal user) {
        object(body);
        text(body, "valuationDate");
        number(body, "marketValue");
        return client.post("/collateral/" + ref(ref, "ref") + "/valuations", Map.of(), body, actor(acting, user));
    }

    @Operation(summary = "Valuations of all collateral, e.g. status=PENDING")
    @GetMapping("/valuations")
    public JsonNode valuationsByStatus(@RequestParam(required = false) String status) {
        return client.get("/valuations", statusQuery(status));
    }

    @Operation(summary = "Approve a valuation")
    @PostMapping("/valuations/{id}/approve")
    public JsonNode approveValuation(@PathVariable long id, @RequestHeader(value = "X-Acting-As", required = false) String acting,
                                     Principal user) {
        return client.post("/valuations/" + id + "/approve", Map.of(), null, actor(acting, user));
    }

    @Operation(summary = "Reject a valuation")
    @PostMapping("/valuations/{id}/reject")
    public JsonNode rejectValuation(@PathVariable long id, @RequestHeader(value = "X-Acting-As", required = false) String acting,
                                    Principal user) {
        return client.post("/valuations/" + id + "/reject", Map.of(), null, actor(acting, user));
    }

    @Operation(summary = "Record the legal verification outcome PENDING, VERIFIED or FAILED")
    @PostMapping("/collateral/{ref}/legal-verification")
    public JsonNode legal(@PathVariable String ref, @RequestBody JsonNode body,
                          @RequestHeader(value = "X-Acting-As", required = false) String acting, Principal user) {
        object(body);
        text(body, "status");
        return client.post("/collateral/" + ref(ref, "ref") + "/legal-verification", Map.of(), body, actor(acting, user));
    }

    @Operation(summary = "Record charge registration and any prior charge")
    @PostMapping("/collateral/{ref}/charge")
    public JsonNode charge(@PathVariable String ref, @RequestBody JsonNode body,
                           @RequestHeader(value = "X-Acting-As", required = false) String acting, Principal user) {
        object(body);
        return client.post("/collateral/" + ref(ref, "ref") + "/charge", Map.of(), body, actor(acting, user));
    }

    @Operation(summary = "Record insurance expiry")
    @PostMapping("/collateral/{ref}/insurance")
    public JsonNode insurance(@PathVariable String ref, @RequestBody JsonNode body,
                              @RequestHeader(value = "X-Acting-As", required = false) String acting, Principal user) {
        object(body);
        text(body, "expiry");
        return client.post("/collateral/" + ref(ref, "ref") + "/insurance", Map.of(), body, actor(acting, user));
    }

    @Operation(summary = "Activate once legal, valuation, charge and insurance requirements are met")
    @PostMapping("/collateral/{ref}/activate")
    public JsonNode activate(@PathVariable String ref, @RequestHeader(value = "X-Acting-As", required = false) String acting,
                             Principal user) {
        return client.post("/collateral/" + ref(ref, "ref") + "/activate", Map.of(), null, actor(acting, user));
    }

    @Operation(summary = "Release collateral that has no active allocations")
    @PostMapping("/collateral/{ref}/release")
    public JsonNode release(@PathVariable String ref, @RequestHeader(value = "X-Acting-As", required = false) String acting,
                            Principal user) {
        return client.post("/collateral/" + ref(ref, "ref") + "/release", Map.of(), null, actor(acting, user));
    }

    @Operation(summary = "Allocations of a collateral")
    @GetMapping("/collateral/{ref}/allocations")
    public JsonNode allocations(@PathVariable String ref) {
        return client.get("/collateral/" + ref(ref, "ref") + "/allocations", Map.of());
    }

    @Operation(summary = "Allocate a share of the collateral to a facility")
    @PostMapping("/collateral/{ref}/allocations")
    public JsonNode allocate(@PathVariable String ref, @RequestBody JsonNode body,
                             @RequestHeader(value = "X-Acting-As", required = false) String acting, Principal user) {
        object(body);
        text(body, "facilityRef");
        ref(body.get("facilityRef").asText(), "facilityRef");
        number(body, "allocationPct");
        return client.post("/collateral/" + ref(ref, "ref") + "/allocations", Map.of(), body, actor(acting, user));
    }

    @Operation(summary = "Release an allocation")
    @PostMapping("/allocations/{id}/release")
    public JsonNode releaseAllocation(@PathVariable long id, @RequestHeader(value = "X-Acting-As", required = false) String acting,
                                      Principal user) {
        return client.post("/allocations/" + id + "/release", Map.of(), null, actor(acting, user));
    }

    // ---------------------------------------------------------------- monitoring

    @Operation(summary = "Breaches, e.g. status=OPEN")
    @GetMapping("/breaches")
    public JsonNode breaches(@RequestParam(required = false) String status) {
        return client.get("/monitoring/breaches", statusQuery(status));
    }

    @Operation(summary = "Acknowledge an open breach")
    @PostMapping("/breaches/{id}/acknowledge")
    public JsonNode acknowledge(@PathVariable long id, @RequestHeader(value = "X-Acting-As", required = false) String acting,
                                Principal user) {
        return client.post("/monitoring/breaches/" + id + "/acknowledge", Map.of(), null, actor(acting, user));
    }

    @Operation(summary = "Run the monitoring sweep now: expire reservations and facilities, open and close breaches")
    @PostMapping("/monitoring/sweep")
    public JsonNode sweep(@RequestHeader(value = "X-Acting-As", required = false) String acting, Principal user) {
        return client.post("/monitoring/sweep", Map.of(), null, actor(acting, user));
    }

    @Operation(summary = "Check that every facility counter equals the sum of its ledger rows")
    @GetMapping("/monitoring/integrity")
    public JsonNode integrity() {
        return client.get("/monitoring/integrity", Map.of());
    }

    // ---------------------------------------------------------------- helpers

    /** The name LCM records: the one typed on the screen, else the login. */
    static String actor(String acting, Principal user) {
        if (acting != null && !acting.isBlank()) {
            String a = acting.trim();
            if (!PERSON.matcher(a).matches()) {
                throw new IllegalArgumentException("Acting-as name must be 2 to 40 letters, digits, dot, dash, underscore or @");
            }
            return a;
        }
        return user == null ? "admin-portal" : user.getName();
    }

    private static Map<String, Object> statusQuery(String status) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("status", word(status, "status"));
        return q;
    }

    private static Object note(JsonNode body) {
        return body == null || body.isNull() ? null : body;
    }

    static String ref(String v, String name) {
        if (v == null || v.isBlank()) return null;
        if (!REF.matcher(v).matches()) {
            throw new IllegalArgumentException(name + " may contain only letters, digits and . _ : - (up to 64)");
        }
        return v;
    }

    static String word(String v, String name) {
        if (v == null || v.isBlank()) return null;
        if (!WORD.matcher(v).matches()) {
            throw new IllegalArgumentException(name + " must be upper-case letters, for example PENDING");
        }
        return v;
    }

    private static void object(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("A JSON object body is required");
        }
    }

    private static void text(JsonNode body, String field) {
        if (!body.hasNonNull(field) || body.get(field).asText().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private static void number(JsonNode body, String field) {
        if (!body.hasNonNull(field) || !body.get(field).isNumber()) {
            throw new IllegalArgumentException(field + " must be a number");
        }
    }
}
