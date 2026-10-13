package com.bank.lending.admin.service;

import com.bank.lending.admin.common.FeatureDisabledException;
import com.bank.lending.admin.common.UpstreamException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * Loan account operations: create, disburse, post an instalment, part-payment, foreclosure, and the two bulk calls.
 * Every one is done by lending-service through its own validated API; this service never writes to the database
 * itself. Payments carry an Idempotency-Key, so sending the same request twice applies it once.
 */
@Service
public class LendingAccountClient {

    private final String baseUrl;
    private final RestClient client;

    public LendingAccountClient(@Value("${lending.service.base-url:}") String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(120_000); // a sleeping free-tier service needs about a minute to wake; bulk calls take longer
        this.client = enabled() ? RestClient.builder().baseUrl(this.baseUrl).requestFactory(factory).build() : null;
    }

    public boolean enabled() {
        return !baseUrl.isEmpty();
    }

    public JsonNode createAccount(JsonNode body, String actor) {
        return post("/api/v1/loan-accounts", body, actor, null);
    }

    public JsonNode disburse(long accountId, JsonNode body, String actor) {
        return post("/api/v1/loan-accounts/" + accountId + "/disburse", body, actor, null);
    }

    public JsonNode payInstalment(long accountId, JsonNode body, String actor, String idempotencyKey) {
        return post("/api/v1/loan-accounts/" + accountId + "/payments/emi", body, actor, idempotencyKey);
    }

    public JsonNode partPayment(long accountId, JsonNode body, String actor, String idempotencyKey) {
        return post("/api/v1/loan-accounts/" + accountId + "/payments/part-payment", body, actor, idempotencyKey);
    }

    public JsonNode foreclose(long accountId, JsonNode body, String actor, String idempotencyKey) {
        return post("/api/v1/loan-accounts/" + accountId + "/payments/foreclosure", body, actor, idempotencyKey);
    }

    public JsonNode bulkCreate(JsonNode body, String actor) {
        return post("/api/v1/loan-accounts/bulk/create", body, actor, null);
    }

    public JsonNode bulkDisburse(JsonNode body, String actor) {
        return post("/api/v1/loan-accounts/bulk/disburse", body, actor, null);
    }

    private JsonNode post(String path, JsonNode body, String actor, String idempotencyKey) {
        require();
        return call(() -> {
            RestClient.RequestBodySpec spec = client.post().uri(path)
                    .header("X-Actor", actor == null ? "admin-portal" : actor)
                    .contentType(MediaType.APPLICATION_JSON);
            if (idempotencyKey != null) {
                spec = spec.header("Idempotency-Key", idempotencyKey);
            }
            return spec.body(body).retrieve()
                    .onStatus(s -> s.isError(), (req, res) -> { throw upstream(res.getStatusCode().value(), res.getBody()); })
                    .body(JsonNode.class);
        });
    }

    private void require() {
        if (!enabled()) {
            throw new FeatureDisabledException(
                    "Account operations are not set up. Set LENDING_SERVICE_URL on the admin service to enable them.");
        }
    }

    private static <T> T call(Supplier<T> action) {
        try {
            return action.get();
        } catch (UpstreamException e) {
            throw e;
        } catch (RestClientException e) {
            throw new UpstreamException(502, "Could not reach lending-service: " + e.getMessage());
        }
    }

    private static UpstreamException upstream(int status, java.io.InputStream body) {
        String text;
        try {
            text = new String(body.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            text = "";
        }
        String msg = BatchConfigClient.message(text);
        return new UpstreamException(status, msg.isBlank() ? "lending-service returned HTTP " + status : msg);
    }
}
