package com.bank.lending.chit.group;

import com.bank.lending.chit.domain.rules.ChitRules;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Reads and writes the rules snapshot stored in {@code chit_group_rules_version.rules_json}. Dates are ISO strings,
 * amounts keep their scale, and unknown fields are ignored on reading so an older snapshot still loads after a field
 * has been removed.
 */
public final class RulesJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private RulesJson() {
    }

    public static String write(ChitRules rules) {
        try {
            return MAPPER.writeValueAsString(rules);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Rules could not be written", e);
        }
    }

    public static ChitRules read(String json) {
        try {
            return MAPPER.readValue(json, ChitRules.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored rules could not be read", e);
        }
    }
}
