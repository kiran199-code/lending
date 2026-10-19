package com.bank.lending.lcm.maintenance;

import com.bank.lending.lcm.common.LcmException;
import com.bank.lending.lcm.maintenance.MaintenanceDtos.ClearResult;
import com.bank.lending.lcm.maintenance.MaintenanceDtos.StatusView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Start-from-scratch for a non-production environment: empties every limit and collateral table so that a
 * clear of lending-service's loans does not leave facilities, utilisation and collateral behind. Called by the
 * admin console's Maintenance screen after it has checked that this switch is on.
 *
 * <p>Emptied: facilities and their changes, excess, utilisation positions, reservations, the limit ledger, the
 * idempotency keys, the event feed, the audit log, collateral with its valuations and allocations, and breaches.
 * Kept: {@code collateral_type_config} (the collateral types and their rules, seeded by migration V2) and the
 * schema history. Refused unless {@code lcm.maintenance.reset-enabled} is true (LCM_RESET_ENABLED) and the caller
 * types the confirmation word.
 */
@Service
public class MaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceService.class);

    public static final String CONFIRMATION = "RESET";

    /** Every table the clear empties, in the order they are reported. */
    static final List<String> TABLES = List.of(
            "facility", "facility_change", "facility_excess", "utilisation_position", "reservation", "limit_txn",
            "idempotency_key", "lcm_event", "audit_log",
            "collateral", "collateral_valuation", "collateral_allocation", "breach");

    private final JdbcTemplate jdbc;
    private final boolean resetEnabled;

    public MaintenanceService(JdbcTemplate jdbc, @Value("${lcm.maintenance.reset-enabled:false}") boolean resetEnabled) {
        this.jdbc = jdbc;
        this.resetEnabled = resetEnabled;
    }

    @Transactional(readOnly = true)
    public StatusView status() {
        return new StatusView(resetEnabled, rowCounts());
    }

    @Transactional
    public ClearResult clearAll(String confirm, String actor) {
        if (!resetEnabled) {
            throw LcmException.forbidden("RESET_DISABLED",
                    "Clearing data is switched off on this lending-lcm-service. Set LCM_RESET_ENABLED=true to allow it "
                            + "(non-production environments only).");
        }
        if (!CONFIRMATION.equals(confirm)) {
            throw LcmException.badRequest("CONFIRMATION_MISSING", "Confirmation missing: send {\"confirm\": \"" + CONFIRMATION + "\"}");
        }
        Map<String, Long> before = rowCounts();
        // One statement naming every table, so no CASCADE is needed (the facility and collateral foreign keys all point
        // inside this list; collateral_type_config is only referenced). RESTART IDENTITY puts the ids back to 1.
        jdbc.execute("TRUNCATE TABLE " + String.join(", ", TABLES) + " RESTART IDENTITY");
        long total = before.values().stream().mapToLong(Long::longValue).sum();
        String who = actor == null || actor.isBlank() ? "unknown" : actor.trim();
        log.warn("LCM DATA CLEARED by {}: {} rows removed {}", who, total, before);
        return new ClearResult(before, total, who, OffsetDateTime.now(),
                "All facilities, utilisation and collateral were removed.");
    }

    private Map<String, Long> rowCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : TABLES) {
            Long n = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
            counts.put(table, n == null ? 0L : n);
        }
        return counts;
    }
}
