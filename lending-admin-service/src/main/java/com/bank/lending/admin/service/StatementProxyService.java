package com.bank.lending.admin.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * The customer PDF statement is produced by lending-service. This service has read-only database
 * access only, so for the "Download statement" button it fetches that PDF over HTTP.
 */
@Service
public class StatementProxyService {

    private final String baseUrl;

    public StatementProxyService(@Value("${lending.service.base-url:}") String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
    }

    public boolean enabled() {
        return !baseUrl.isEmpty();
    }

    public ResponseEntity<byte[]> fetch(long accountId) {
        return RestClient.create(baseUrl).get()
                .uri("/api/v1/loan-accounts/{id}/statement", accountId)
                .retrieve()
                .toEntity(byte[].class);
    }
}
