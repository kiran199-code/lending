package com.bank.lending.chit.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Audit log and event-feed writers shared by all features. Both run inside the caller's transaction. */
@Component
public class Audit {
    private final JdbcTemplate jdbc;

    public Audit(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void log(String entityType, String entityRef, String action, String actor, String detail) {
        jdbc.update("INSERT INTO audit_log (entity_type, entity_ref, action, actor, detail) VALUES (?,?,?,?,?)",
                entityType, entityRef, action, actor, detail);
    }

    public void event(String eventType, String aggregateType, String aggregateRef, String payloadJson) {
        jdbc.update("INSERT INTO chit_event (event_type, aggregate_type, aggregate_ref, payload) VALUES (?,?,?,?)",
                eventType, aggregateType, aggregateRef, payloadJson);
    }
}
