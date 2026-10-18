package com.bank.lending.admin.web;

import com.bank.lending.admin.service.LendingAccountClient;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.UUID;

/**
 * Account operations from the console. The checks here only catch an empty or malformed form so the user gets a
 * clear message quickly; lending-service applies the real business rules and its message is passed back unchanged.
 */
@RestController
@RequestMapping("/api/admin/operations")
@Tag(name = "Account operations", description = "Create, disburse, pay an instalment, part-pay, foreclose and the bulk calls (done by lending-service)")
public class AccountOperationsController {

    static final int BULK_MAX = 200;
    static final Set<String> CHANNELS = Set.of("NACH", "MANUAL", "ONLINE");

    private final LendingAccountClient client;

    public AccountOperationsController(LendingAccountClient client) {
        this.client = client;
    }

    @Operation(summary = "Create a loan account (status PENDING_DISBURSEMENT)")
    @PostMapping("/accounts")
    public JsonNode create(@RequestBody JsonNode body, Principal user) {
        requireObject(body);
        requireNumber(body, "customerId");
        requireNumber(body, "sanctionRefId");
        requireText(body, "productCode");
        requirePositive(body, "sanctionedAmount");
        requirePositive(body, "tenureMonths");
        return client.createAccount(body, actor(user));
    }

    @Operation(summary = "Attach, change or clear the limit facility of an account that is still pending disbursement")
    @PutMapping("/accounts/{accountId}/limit-facility")
    public JsonNode linkLimitFacility(@PathVariable long accountId, @RequestBody JsonNode body, Principal user) {
        requireObject(body);
        return client.linkLimitFacility(accountId, body, actor(user));
    }

    @Operation(summary = "Disburse a sanctioned account, in full or in part, and generate its schedule")
    @PostMapping("/accounts/{accountId}/disburse")
    public JsonNode disburse(@PathVariable long accountId, @RequestBody JsonNode body, Principal user) {
        requireObject(body);
        requirePositive(body, "disbursedAmount");
        requireDate(body, "firstEmiDueDate");
        return client.disburse(accountId, body, actor(user));
    }

    @Operation(summary = "Post the instalment that is due (the amount must equal it exactly)")
    @PostMapping("/accounts/{accountId}/payments/emi")
    public JsonNode payInstalment(@PathVariable long accountId, @RequestBody JsonNode body,
                                  @RequestHeader(value = "Idempotency-Key", required = false) String key, Principal user) {
        payment(body, "valueDate");
        return client.payInstalment(accountId, body, actor(user), keyOrNew(key));
    }

    @Operation(summary = "What is payable on an account on a date: open demands oldest first, overdue, due today, advance")
    @GetMapping("/accounts/{accountId}/amount-payable")
    public JsonNode amountPayable(@PathVariable long accountId, @RequestParam(required = false) String asOf) {
        if (asOf != null && !asOf.isBlank()) {
            try {
                LocalDate.parse(asOf);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("asOf must be a date in the form yyyy-MM-dd");
            }
        }
        return client.amountPayable(accountId, asOf);
    }

    @Operation(summary = "Show how a payment would be applied (penal interest, interest, principal, advance) without posting it")
    @PostMapping("/accounts/{accountId}/payments/preview")
    public JsonNode previewPayment(@PathVariable long accountId, @RequestBody JsonNode body, Principal user) {
        requireObject(body);
        requireDate(body, "valueDate");
        requirePositive(body, "amount");
        return client.previewPayment(accountId, body, actor(user));
    }

    @Operation(summary = "Post a principal part-payment; the remaining schedule is regenerated")
    @PostMapping("/accounts/{accountId}/payments/part-payment")
    public JsonNode partPayment(@PathVariable long accountId, @RequestBody JsonNode body,
                                @RequestHeader(value = "Idempotency-Key", required = false) String key, Principal user) {
        payment(body, "valueDate");
        return client.partPayment(accountId, body, actor(user), keyOrNew(key));
    }

    @Operation(summary = "Pay off outstanding principal and accrued interest and close the account")
    @PostMapping("/accounts/{accountId}/payments/foreclosure")
    public JsonNode foreclose(@PathVariable long accountId, @RequestBody JsonNode body,
                              @RequestHeader(value = "Idempotency-Key", required = false) String key, Principal user) {
        requireObject(body);
        requireDate(body, "foreclosureDate");
        requireChannel(body);
        return client.foreclose(accountId, body, actor(user), keyOrNew(key));
    }

    @Operation(summary = "Create up to 200 accounts; each item succeeds or fails on its own")
    @PostMapping("/accounts/bulk/create")
    public JsonNode bulkCreate(@RequestBody JsonNode body, Principal user) {
        requireObject(body);
        requireItems(body, "accounts");
        return client.bulkCreate(body, actor(user));
    }

    @Operation(summary = "Disburse up to 200 accounts; each item succeeds or fails on its own")
    @PostMapping("/accounts/bulk/disburse")
    public JsonNode bulkDisburse(@RequestBody JsonNode body, Principal user) {
        requireObject(body);
        requireItems(body, "items");
        return client.bulkDisburse(body, actor(user));
    }

    // ---- checks

    private static void payment(JsonNode body, String dateField) {
        requireObject(body);
        requireDate(body, dateField);
        requirePositive(body, "amount");
        requireChannel(body);
    }

    private static void requireObject(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("A request body is required");
        }
    }

    private static void requireText(JsonNode body, String field) {
        JsonNode v = body.get(field);
        if (v == null || v.isNull() || v.asText().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private static void requireNumber(JsonNode body, String field) {
        JsonNode v = body.get(field);
        if (v == null || v.isNull() || !(v.isNumber() || v.asText().matches("\\d+"))) {
            throw new IllegalArgumentException(field + " must be a number");
        }
    }

    private static void requirePositive(JsonNode body, String field) {
        JsonNode v = body.get(field);
        if (v == null || v.isNull()) {
            throw new IllegalArgumentException(field + " is required");
        }
        try {
            if (new BigDecimal(v.asText()).signum() <= 0) {
                throw new IllegalArgumentException(field + " must be greater than zero");
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " must be a number");
        }
    }

    private static void requireDate(JsonNode body, String field) {
        JsonNode v = body.get(field);
        if (v == null || v.isNull() || v.asText().isBlank()) {
            throw new IllegalArgumentException(field + " is required (yyyy-MM-dd)");
        }
        try {
            LocalDate.parse(v.asText());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " must be a date in the form yyyy-MM-dd");
        }
    }

    private static void requireChannel(JsonNode body) {
        JsonNode v = body.get("channel");
        if (v == null || !CHANNELS.contains(v.asText())) {
            throw new IllegalArgumentException("channel must be one of " + String.join(", ", new java.util.TreeSet<>(CHANNELS)));
        }
    }

    private static void requireItems(JsonNode body, String field) {
        JsonNode v = body.get(field);
        if (v == null || !v.isArray() || v.isEmpty()) {
            throw new IllegalArgumentException(field + " must list at least one item");
        }
        if (v.size() > BULK_MAX) {
            throw new IllegalArgumentException("At most " + BULK_MAX + " items per call; this has " + v.size());
        }
    }

    private static String actor(Principal user) {
        return user == null ? null : user.getName();
    }

    private static String keyOrNew(String key) {
        return key == null || key.isBlank() ? UUID.randomUUID().toString() : key.trim();
    }
}
