package com.bank.lending.e2e.client;

import com.bank.lending.e2e.http.ApiResult;
import com.bank.lending.e2e.http.Http;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/** Writes to the system under test go ONLY through lending-service's public REST API. */
public final class LendingClient {

    private final Http http;

    public LendingClient(Http http) {
        this.http = http;
    }

    public ApiResult listProducts() {
        return http.get("/api/v1/loan-products");
    }

    public ApiResult createAccount(ObjectNode body) {
        return http.post("/api/v1/loan-accounts", body);
    }

    public ApiResult disburse(long accountId, BigDecimal amount, LocalDate firstEmiDueDate) {
        ObjectNode b = Http.MAPPER.createObjectNode();
        b.put("disbursedAmount", amount);
        b.put("firstEmiDueDate", firstEmiDueDate.toString());
        return http.post("/api/v1/loan-accounts/" + accountId + "/disburse", b);
    }

    public ApiResult getAccount(long accountId) {
        return http.get("/api/v1/loan-accounts/" + accountId);
    }

    public ApiResult schedule(long accountId) {
        return http.get("/api/v1/loan-accounts/" + accountId + "/schedule");
    }

    public ApiResult payHistory(long accountId) {
        return http.get("/api/v1/loan-accounts/" + accountId + "/payments");
    }

    public ApiResult payEmi(long accountId, LocalDate valueDate, BigDecimal amount, String channel, String idempotencyKey) {
        return http.post("/api/v1/loan-accounts/" + accountId + "/payments/emi", payment(valueDate, amount, channel),
                Map.of("Idempotency-Key", idempotencyKey));
    }

    public ApiResult partPayment(long accountId, LocalDate valueDate, BigDecimal amount, String channel, String idempotencyKey) {
        return http.post("/api/v1/loan-accounts/" + accountId + "/payments/part-payment", payment(valueDate, amount, channel),
                Map.of("Idempotency-Key", idempotencyKey));
    }

    public ApiResult foreclose(long accountId, LocalDate date, String channel, String idempotencyKey) {
        ObjectNode b = Http.MAPPER.createObjectNode();
        b.put("foreclosureDate", date.toString());
        b.put("channel", channel);
        return http.post("/api/v1/loan-accounts/" + accountId + "/payments/foreclosure", b, Map.of("Idempotency-Key", idempotencyKey));
    }

    /** Opt-in alternative to the batch path (e2e.accrual.path=API); see README. */
    public ApiResult runAccrualDirect(LocalDate businessDate) {
        return http.post("/api/v1/interest-accruals/run?businessDate=" + businessDate, null);
    }

    /** Opt-in alternative to the batch path (e2e.dpd.path=API); see README. */
    public ApiResult classifyDirect(LocalDate asOf) {
        ObjectNode b = Http.MAPPER.createObjectNode();
        b.put("asOfDate", asOf.toString());
        return http.post("/api/v1/loan-accounts/delinquency-classifications", b);
    }

    // ---- business date (owned by lending-service) ----

    public ApiResult businessDate() {
        return http.get("/api/v1/business-date");
    }

    /** Sets the business date (forward-only unless lending-service allows backward moves). */
    public ApiResult setBusinessDate(LocalDate date, String reason) {
        ObjectNode b = Http.MAPPER.createObjectNode();
        b.put("date", date.toString());
        b.put("reason", reason);
        return http.put("/api/v1/business-date", b);
    }

    /** OPEN to EOD_RUNNING. Only used by e2e.eod.mode=JOBS_THEN_ADVANCE; EOD_RUN lets the batch service do it. */
    public ApiResult startEndOfDay() {
        return http.post("/api/v1/business-date/eod/start", null, Map.of("X-Actor", "e2e-suite"));
    }

    /** Closes {@code expected} and opens the next day. Idempotent on the expected date. */
    public ApiResult advanceBusinessDate(LocalDate expected) {
        ObjectNode b = Http.MAPPER.createObjectNode();
        b.put("expectedBusinessDate", expected.toString());
        return http.post("/api/v1/business-date/advance", b, Map.of("X-Actor", "e2e-suite"));
    }

    public Http http() {
        return http;
    }

    private static ObjectNode payment(LocalDate valueDate, BigDecimal amount, String channel) {
        ObjectNode b = Http.MAPPER.createObjectNode();
        b.put("valueDate", valueDate.toString());
        b.put("amount", amount);
        b.put("channel", channel);
        return b;
    }
}
