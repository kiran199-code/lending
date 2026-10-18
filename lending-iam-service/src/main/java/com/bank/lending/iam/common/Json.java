package com.bank.lending.iam.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small JSON helper for audit detail, event payloads and canonical payload text. */
public final class Json {
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(SerializationFeature.INDENT_OUTPUT, false);

    private Json() {}

    /** Builds a JSON object from alternating key, value arguments; null values are left out. */
    public static String obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            Object v = kv[i + 1];
            if (v != null) m.put(String.valueOf(kv[i]), v instanceof BigDecimal b ? b.toPlainString() : v);
        }
        return write(m);
    }

    public static String write(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Canonical text of a JSON document: object keys in alphabetical order at every level, no white space.
     * The payload hash of an approval is the SHA-256 of this text, so two callers that send the same
     * content with a different key order or spacing get the same hash.
     * @throws IllegalArgumentException when the text is not valid JSON
     */
    public static String canonical(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node == null || node.isMissingNode()) throw new IllegalArgumentException("empty JSON");
            Object tree = MAPPER.treeToValue(node, Object.class);
            return CANONICAL.writeValueAsString(tree);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("payload is not valid JSON");
        }
    }
}
