package com.bank.lending.admin.web;

import com.bank.lending.admin.service.ChitClient;
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
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Chit fund screens: create a group, enrol tickets, verify security, activate, cancel, and look at the cycles. Everything passes
 * through to lending-chit-service, which applies the real rules; its message (with the error code) is returned unchanged. The checks
 * here only catch a malformed reference so the user gets a clear message.
 *
 * <p><b>Who acts.</b> As on the limit screens, the person types the name they are acting as; it is sent in {@code X-Acting-As} and
 * passed on as {@code X-Actor} (the login name is used when none is given). The chit service refuses to let the person who recorded
 * a ticket's security verify it. The names are not authenticated.
 *
 * <p><b>Idempotency.</b> The browser sends one {@code Idempotency-Key} per distinct request, so a double click or a retry after a lost
 * answer is applied once; when none is sent a fresh one is generated (which gives no replay protection).
 */
@RestController
@RequestMapping("/api/admin/chit")
@Tag(name = "Chit funds", description = "Groups, tickets, activation and cycles (done by lending-chit-service)")
public class ChitController {

    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_.:\\-]{1,64}");
    private static final Pattern WORD = Pattern.compile("[A-Z_]{1,30}");

    private final ChitClient client;

    public ChitController(ChitClient client) {
        this.client = client;
    }

    @Operation(summary = "Whether the chit screens are configured (chit service address and password set)")
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("enabled", client.enabled());
    }

    // ---------------------------------------------------------------- groups

    @Operation(summary = "Groups, optionally by status")
    @GetMapping("/groups")
    public JsonNode groups(@RequestParam(required = false) String status, @RequestParam(required = false) Integer limit,
                           @RequestParam(required = false) Integer offset) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("status", word(status, "status"));
        q.put("limit", limit);
        q.put("offset", offset);
        return client.get("/groups", q);
    }

    @Operation(summary = "Create a group in DRAFT")
    @PostMapping("/groups")
    public JsonNode create(@RequestBody JsonNode body, @RequestHeader(value = "Idempotency-Key", required = false) String key,
                           @RequestHeader(value = "X-Acting-As", required = false) String acting, Principal user) {
        object(body);
        text(body, "groupCode");
        code(body.get("groupCode").asText(), "groupCode");
        text(body, "name");
        text(body, "foremanRef");
        return client.post("/groups", body, actor(acting, user), key(key));
    }

    @Operation(summary = "Check rules and see the schedule they would give, without saving anything")
    @PostMapping("/groups/preview")
    public JsonNode preview(@RequestBody JsonNode body) {
        object(body);
        return client.post("/groups/preview", body, null, null);
    }

    @Operation(summary = "One group")
    @GetMapping("/groups/{code}")
    public JsonNode group(@PathVariable String code) {
        return client.get("/groups/" + code(code, "code"), Map.of());
    }

    @Operation(summary = "The group's current rules")
    @GetMapping("/groups/{code}/rules")
    public JsonNode rules(@PathVariable String code) {
        return client.get("/groups/" + code(code, "code") + "/rules", Map.of());
    }

    @Operation(summary = "Open enrolment (DRAFT to ENROLLING)")
    @PostMapping("/groups/{code}/open-enrolment")
    public JsonNode openEnrolment(@PathVariable String code, @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                  @RequestHeader(value = "X-Acting-As", required = false) String acting, Principal user) {
        return client.post("/groups/" + code(code, "code") + "/open-enrolment", null, actor(acting, user), key(key));
    }

    @Operation(summary = "What a new ticket owes at enrolment")
    @GetMapping("/groups/{code}/enrolment-quote")
    public JsonNode quote(@PathVariable String code) {
        return client.get("/groups/" + code(code, "code") + "/enrolment-quote", Map.of());
    }

    @Operation(summary = "Can the group be activated, and if not, why")
    @GetMapping("/groups/{code}/activation-check")
    public JsonNode activationCheck(@PathVariable String code) {
        return client.get("/groups/" + code(code, "code") + "/activation-check", Map.of());
    }

    @Operation(summary = "Activate the group: freezes the rules, activates the tickets and lays out the cycles")
    @PostMapping("/groups/{code}/activate")
    public JsonNode activate(@PathVariable String code, @RequestHeader(value = "Idempotency-Key", required = false) String key,
                             @RequestHeader(value = "X-Acting-As", required = false) String acting, Principal user) {
        return client.post("/groups/" + code(code, "code") + "/activate", null, actor(acting, user), key(key));
    }

    @Operation(summary = "Cancel a group that has not been activated; refunds everything received")
    @PostMapping("/groups/{code}/cancel")
    public JsonNode cancel(@PathVariable String code, @RequestBody JsonNode body,
                           @RequestHeader(value = "Idempotency-Key", required = false) String key,
                           @RequestHeader(value = "X-Acting-As", required = false) String acting, Principal user) {
        object(body);
        text(body, "reason");
        return client.post("/groups/" + code(code, "code") + "/cancel", body, actor(acting, user), key(key));
    }

    @Operation(summary = "The cycle calendar (empty until activation)")
    @GetMapping("/groups/{code}/cycles")
    public JsonNode cycles(@PathVariable String code) {
        return client.get("/groups/" + code(code, "code") + "/cycles", Map.of());
    }

    // ---------------------------------------------------------------- tickets

    @Operation(summary = "Tickets of a group")
    @GetMapping("/groups/{code}/tickets")
    public JsonNode tickets(@PathVariable String code, @RequestParam(required = false) Boolean includeExited) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("includeExited", includeExited);
        return client.get("/groups/" + code(code, "code") + "/tickets", q);
    }

    @Operation(summary = "Enrol a customer into the next free seat")
    @PostMapping("/groups/{code}/tickets")
    public JsonNode enrol(@PathVariable String code, @RequestBody JsonNode body,
                          @RequestHeader(value = "Idempotency-Key", required = false) String key,
                          @RequestHeader(value = "X-Acting-As", required = false) String acting, Principal user) {
        object(body);
        number(body, "customerId");
        return client.post("/groups/" + code(code, "code") + "/tickets", body, actor(acting, user), key(key));
    }

    @Operation(summary = "Withdraw a ticket before activation")
    @PostMapping("/groups/{code}/tickets/{no}/withdraw")
    public JsonNode withdraw(@PathVariable String code, @PathVariable int no, @RequestBody(required = false) JsonNode body,
                             @RequestHeader(value = "Idempotency-Key", required = false) String key,
                             @RequestHeader(value = "X-Acting-As", required = false) String acting, Principal user) {
        return client.post("/groups/" + code(code, "code") + "/tickets/" + no + "/withdraw", body, actor(acting, user), key(key));
    }

    @Operation(summary = "Verify a ticket's security; the person who recorded it cannot verify it")
    @PostMapping("/groups/{code}/tickets/{no}/security/verify")
    public JsonNode verifySecurity(@PathVariable String code, @PathVariable int no,
                                   @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                   @RequestHeader(value = "X-Acting-As", required = false) String acting, Principal user) {
        return client.post("/groups/" + code(code, "code") + "/tickets/" + no + "/security/verify", null, actor(acting, user), key(key));
    }

    // ---------------------------------------------------------------- collection (Phase 4)

    @Operation(summary = "Dues across groups: UPCOMING (not yet raised), OVERDUE, or every OPEN demand")
    @GetMapping("/dues")
    public JsonNode dues(@RequestParam(required = false) String view, @RequestParam(required = false) String groupCode,
                         @RequestParam(required = false) Integer withinDays, @RequestParam(required = false) Integer limit,
                         @RequestParam(required = false) Integer offset) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("view", word(view, "view"));
        q.put("groupCode", groupCode == null || groupCode.isBlank() ? null : code(groupCode, "groupCode"));
        q.put("withinDays", withinDays);
        q.put("limit", limit);
        q.put("offset", offset);
        return client.get("/dues", q);
    }

    @Operation(summary = "A ticket's account: owed, advance held, demands, payments and any default case")
    @GetMapping("/groups/{code}/tickets/{no}/statement")
    public JsonNode ticketStatement(@PathVariable String code, @PathVariable int no) {
        return client.get("/groups/" + code(code, "code") + "/tickets/" + no + "/statement", Map.of());
    }

    @Operation(summary = "What would accrue as late fee over the next days if nothing is paid")
    @GetMapping("/groups/{code}/tickets/{no}/late-fee-preview")
    public JsonNode lateFeePreview(@PathVariable String code, @PathVariable int no, @RequestParam(required = false) Integer days) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("days", days);
        return client.get("/groups/" + code(code, "code") + "/tickets/" + no + "/late-fee-preview", q);
    }

    @Operation(summary = "Cycle by cycle collection for a group")
    @GetMapping("/groups/{code}/statement")
    public JsonNode groupStatement(@PathVariable String code) {
        return client.get("/groups/" + code(code, "code") + "/statement", Map.of());
    }

    @Operation(summary = "Demands of a group, optionally for one ticket or status")
    @GetMapping("/groups/{code}/demands")
    public JsonNode demands(@PathVariable String code, @RequestParam(required = false) Integer ticketNo,
                            @RequestParam(required = false) String status) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("ticketNo", ticketNo);
        q.put("status", word(status, "status"));
        return client.get("/groups/" + code(code, "code") + "/demands", q);
    }

    @Operation(summary = "Payments of a group, newest first, optionally for one ticket")
    @GetMapping("/groups/{code}/payments")
    public JsonNode payments(@PathVariable String code, @RequestParam(required = false) Integer ticketNo) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("ticketNo", ticketNo);
        return client.get("/groups/" + code(code, "code") + "/payments", q);
    }

    @Operation(summary = "One payment with every allocation line (the receipt)")
    @GetMapping("/groups/{code}/payments/{ref}")
    public JsonNode payment(@PathVariable String code, @PathVariable String ref) {
        return client.get("/groups/" + code(code, "code") + "/payments/" + code(ref, "ref"), Map.of());
    }

    @Operation(summary = "Record money received from a ticket holder")
    @PostMapping("/groups/{code}/tickets/{no}/payments")
    public JsonNode recordPayment(@PathVariable String code, @PathVariable int no, @RequestBody JsonNode body,
                                  @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                  @RequestHeader(value = "X-Acting-As", required = false) String acting, Principal user) {
        object(body);
        number(body, "amount");
        if (body.get("amount").decimalValue().signum() <= 0) {
            throw new IllegalArgumentException("amount must be more than zero");
        }
        text(body, "channel");
        if (word(body.get("channel").asText(), "channel") == null) {
            throw new IllegalArgumentException("channel is required");
        }
        return client.post("/groups/" + code(code, "code") + "/tickets/" + no + "/payments", body, actor(acting, user), key(key));
    }

    @Operation(summary = "Reverse a payment (a bounced cheque or a wrong entry)")
    @PostMapping("/groups/{code}/payments/{ref}/reverse")
    public JsonNode reversePayment(@PathVariable String code, @PathVariable String ref, @RequestBody JsonNode body,
                                   @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                   @RequestHeader(value = "X-Acting-As", required = false) String acting, Principal user) {
        object(body);
        text(body, "reason");
        return client.post("/groups/" + code(code, "code") + "/payments/" + code(ref, "ref") + "/reverse", body, actor(acting, user),
                key(key));
    }

    @Operation(summary = "Default cases of a group, optionally by status")
    @GetMapping("/groups/{code}/default-cases")
    public JsonNode defaultCases(@PathVariable String code, @RequestParam(required = false) String status) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("status", word(status, "status"));
        return client.get("/groups/" + code(code, "code") + "/default-cases", q);
    }

    @Operation(summary = "Run today's demand job now (raises instalments that have fallen due); safe to repeat")
    @PostMapping("/batch/demand-run")
    public JsonNode demandRun(@RequestHeader(value = "X-Acting-As", required = false) String acting, Principal user) {
        return client.post("/batch/demand-run", null, actor(acting, user), null);
    }

    @Operation(summary = "Run today's late-fee job now; safe to repeat, a day is never charged twice")
    @PostMapping("/batch/late-fee-run")
    public JsonNode lateFeeRun(@RequestHeader(value = "X-Acting-As", required = false) String acting, Principal user) {
        return client.post("/batch/late-fee-run", null, actor(acting, user), null);
    }

    @Operation(summary = "Run today's default check now; safe to repeat")
    @PostMapping("/batch/default-run")
    public JsonNode defaultRun(@RequestHeader(value = "X-Acting-As", required = false) String acting, Principal user) {
        return client.post("/batch/default-run", null, actor(acting, user), null);
    }

    // ---------------------------------------------------------------- helpers

    private static String key(String sent) {
        return sent == null || sent.isBlank() ? UUID.randomUUID().toString() : sent.trim();
    }

    /** The name the chit service records: the one typed on the screen, else the login. */
    static String actor(String acting, Principal user) {
        return LimitsController.actor(acting, user);
    }

    static String code(String v, String name) {
        if (v == null || !CODE.matcher(v).matches()) {
            throw new IllegalArgumentException(name + " may contain only letters, digits and . _ : - (up to 64)");
        }
        return v;
    }

    static String word(String v, String name) {
        if (v == null || v.isBlank()) return null;
        if (!WORD.matcher(v).matches()) {
            throw new IllegalArgumentException(name + " must be upper-case letters, for example ACTIVE");
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
