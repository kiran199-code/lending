package com.bank.lending.e2e.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

/** Outcome of one HTTP call. Never throws for 4xx/5xx: a rejection is data the tests assert on. */
public record ApiResult(int status, JsonNode json, String rawBody, long elapsedMs, String transportError,
                        String method, String url) {

    public boolean ok() {
        return transportError == null && status >= 200 && status < 300;
    }

    public boolean clientError() {
        return status >= 400 && status < 500;
    }

    public JsonNode body() {
        return json == null ? MissingNode.getInstance() : json;
    }

    /** One-line description for audit trail / defects. */
    public String brief() {
        if (transportError != null) {
            return method + " " + url + " -> TRANSPORT " + transportError;
        }
        String b = rawBody == null ? "" : rawBody.replaceAll("\\s+", " ");
        return method + " " + url + " -> " + status + (b.length() > 300 ? " " + b.substring(0, 300) + "..." : " " + b);
    }
}
