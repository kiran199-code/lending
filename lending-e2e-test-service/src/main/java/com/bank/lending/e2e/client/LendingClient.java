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
