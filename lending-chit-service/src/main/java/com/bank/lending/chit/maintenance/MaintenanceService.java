package com.bank.lending.chit.maintenance;

import com.bank.lending.chit.common.ChitException;
import com.bank.lending.chit.maintenance.MaintenanceDtos.ClearResult;
import com.bank.lending.chit.maintenance.MaintenanceDtos.StatusView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Start-from-scratch for a non-production environment: empties every chit table, so that a clear of lending-service's
 * loans does not leave chit groups, tickets and journals behind. Called by the admin console's Maintenance screen after
 * it has checked that this switch is on.
 *
 * <p>Refused unless {@code chit.maintenance.reset-enabled} is true (CHIT_RESET_ENABLED) and the caller types the
 * confirmation word. The schema history is kept. The ledger is append-only for normal use; TRUNCATE is not a row
 * operation, so it is the one sanctioned way to empty it, and only behind this switch.
 */
@Service
public class MaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceService.class);

    public static final String CONFIRMATION = "RESET";

    private final JdbcTemplate jdbc;
    private final boolean resetEnabled;

    public MaintenanceService(JdbcTemplate jdbc, @Value("${chit.maintenance.reset-enabled:false}") boolean resetEnabled) {
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
            throw ChitException.forbidden("RESET_DISABLED",
                    "Clearing data is switched off on this lending-chit-service. Set CHIT_RESET_ENABLED=true to allow it "
                            + "(non-production environments only).");
        }
        if (!CONFIRMATION.equals(confirm)) {
            throw ChitException.badRequest("CONFIRMATION_MISSING", "Confirmation missing: send {\"confirm\": \"" + CONFIRMATION + "\"}");
        }
        Map<String, Long> before = rowCounts();
        // One statement naming every table, so no CASCADE is needed (all the foreign keys point inside this list).
        // RESTART IDENTITY puts the ids back to 1.
        jdbc.execute("TRUNCATE TABLE " + String.join(", ", ResetTables.TABLES) + " RESTART IDENTITY");
        long total = before.values().stream().mapToLong(Long::longValue).sum();
        String who = actor == null || actor.isBlank() ? "unknown" : actor.trim();
        log.warn("CHIT DATA CLEARED by {}: {} rows removed {}", who, total, before);
        return new ClearResult(before, total, who, OffsetDateTime.now(),
                "All chit groups, tickets, payments and journals were removed.");
    }

    private Map<String, Long> rowCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : ResetTables.TABLES) {
            Long n = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
            counts.put(table, n == null ? 0L : n);
        }
        return counts;
    }
}
