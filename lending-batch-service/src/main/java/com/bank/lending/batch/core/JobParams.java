package com.bank.lending.batch.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Typed, null-safe access to a job run's {@code job_params_json} (the batch_job_config default,
 * or the per-launch override passed to the trigger API). Parse errors surface as
 * {@link IllegalStateException} so a malformed config fails the job loudly.
 */
public final class JobParams {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonNode root;

    private JobParams(JsonNode root) {
        this.root = root;
    }

    /** Params of the job currently executing on this thread (see {@link BatchJobContext}). */
    public static JobParams current() {
        return parse(BatchJobContext.jobParamsJson());
    }

    public static JobParams parse(String json) {
        if (json == null || json.isBlank()) {
            return new JobParams(MAPPER.createObjectNode());
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            return new JobParams(node != null && node.isObject() ? node : MAPPER.createObjectNode());
        } catch (Exception e) {
            throw new IllegalStateException("job_params_json is not valid JSON: " + e.getMessage(), e);
        }
    }

    public String string(String key) {
        JsonNode n = root.get(key);
        return n == null || n.isNull() || n.asText().isBlank() ? null : n.asText();
    }

    public String requireString(String key) {
        String v = string(key);
        if (v == null) {
            throw new IllegalStateException("job_params_json is missing required parameter '" + key + "'");
        }
        return v;
    }

    public Long longValue(String key) {
        JsonNode n = root.get(key);
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isNumber()) {
            return n.asLong();
        }
        try {
            return Long.parseLong(n.asText().trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("job_params_json parameter '" + key + "' is not a number: " + n.asText());
        }
    }

    public long requireLong(String key) {
        Long v = longValue(key);
        if (v == null) {
            throw new IllegalStateException("job_params_json is missing required parameter '" + key + "'");
        }
        return v;
    }

    public int intValue(String key, int defaultValue) {
        Long v = longValue(key);
        return v == null ? defaultValue : Math.toIntExact(v);
    }
}
