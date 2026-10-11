package com.bank.lending.admin.service;

import com.bank.lending.admin.common.FeatureDisabledException;
import com.bank.lending.admin.common.UpstreamException;
import com.bank.lending.admin.dto.AdminDtos.ProductInput;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Saves loan products through lending-service's own validated API (/api/v1/loan-products); this
 * service never writes to the database itself. A saved change applies to accounts opened afterwards
 * and, for a floating rate reset, to an individual account when it is rescheduled. Existing
 * accounts are not touched.
 */
@Service
public class LendingProductClient {

    private final String baseUrl;
    private final RestClient client;

    public LendingProductClient(@Value("${lending.service.base-url:}") String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(90_000); // a sleeping free-tier service needs about a minute to wake
        this.client = enabled() ? RestClient.builder().baseUrl(this.baseUrl).requestFactory(factory).build() : null;
    }

    public boolean enabled() {
        return !baseUrl.isEmpty();
    }

    public void create(ProductInput input) {
        require();
        call(() -> client.post().uri("/api/v1/loan-products")
                .contentType(MediaType.APPLICATION_JSON).body(input)
                .retrieve().onStatus(s -> s.isError(), (req, res) -> { throw upstream(res.getStatusCode().value(), res.getBody()); })
                .toBodilessEntity());
    }

    public void update(String productCode, ProductInput input) {
        require();
        call(() -> client.put().uri("/api/v1/loan-products/{code}", productCode)
                .contentType(MediaType.APPLICATION_JSON).body(input)
                .retrieve().onStatus(s -> s.isError(), (req, res) -> { throw upstream(res.getStatusCode().value(), res.getBody()); })
                .toBodilessEntity());
    }

    private void require() {
        if (!enabled()) {
            throw new FeatureDisabledException(
                    "Editing is not set up. Set LENDING_SERVICE_URL on the admin service to enable it.");
        }
    }

    private static void call(Runnable action) {
        try {
            action.run();
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
