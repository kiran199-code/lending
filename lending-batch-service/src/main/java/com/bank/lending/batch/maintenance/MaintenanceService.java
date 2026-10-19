package com.bank.lending.batch.maintenance;

import com.bank.lending.batch.core.eod.EndOfDayCoordinator;
import com.bank.lending.batch.maintenance.MaintenanceDtos.ClearResult;
import com.bank.lending.batch.maintenance.MaintenanceDtos.StatusView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Start-from-scratch for a non-production environment: empties the job execution history, so that after the loans were
 * cleared and the business date went back, a job is not refused with "a job instance already exists and is complete" for
 * a business date it ran for before the clear. Called by the admin console's Maintenance screen after it has checked that
 * this switch is on.
 *
 * <p>Emptied: Spring Batch's own history ({@code batch_job_instance}, {@code batch_job_execution} and its parameters and
 * context, {@code batch_step_execution} and its context) and the two tables that point at those executions
 * ({@code batch_job_audit_log}, {@code batch_job_error_records}). Kept: the job settings ({@code batch_job_config}), the
 * Quartz schedule tables and the schema history, so the jobs stay configured and scheduled. The Spring Batch id sequences
 * keep counting, so a new execution never reuses an old id.
 *
 * <p>Refused unless {@code batch.maintenance.reset-enabled} is true (BATCH_RESET_ENABLED), the caller types the confirmation
 * word, and nothing is running: not end of day, and no job started in the last {@value #RUNNING_WINDOW_MINUTES} minutes and
 * still unfinished. An older execution still marked as running is treated as left over from a crash and is cleared with the rest.
 */
@Service
public class MaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceService.class);

    public static final String CONFIRMATION = "RESET";
    static final int RUNNING_WINDOW_MINUTES = 30;

    /** Every table the clear empties, in the order they are reported (all in the {@code batch} schema). */
    static final List<String> TABLES = List.of(
            "batch_step_execution_context", "batch_step_execution",
            "batch_job_execution_context", "batch_job_execution_params", "batch_job_execution", "batch_job_instance",
            "batch_job_audit_log", "batch_job_error_records");

    private static final String SCHEMA = "batch.";

    private final JdbcTemplate jdbc;
    private final EndOfDayCoordinator endOfDay;
    private final JobExplorer jobExplorer;
    private final boolean resetEnabled;

    public MaintenanceService(JdbcTemplate jdbc, EndOfDayCoordinator endOfDay, JobExplorer jobExplorer,
                              @Value("${batch.maintenance.reset-enabled:false}") boolean resetEnabled) {
        this.jdbc = jdbc;
        this.endOfDay = endOfDay;
        this.jobExplorer = jobExplorer;
        this.resetEnabled = resetEnabled;
    }

    @Transactional(readOnly = true)
    public StatusView status() {
        String busy = busyReason();
        return new StatusView(resetEnabled, busy != null, busy, rowCounts());
    }

    @Transactional
    public ClearResult clearAll(String confirm, String actor) {
        if (!resetEnabled) {
            throw new MaintenanceException(HttpStatus.FORBIDDEN, "RESET_DISABLED",
                    "Clearing data is switched off on this lending-batch-service. Set BATCH_RESET_ENABLED=true to allow it "
                            + "(non-production environments only).");
        }
        if (!CONFIRMATION.equals(confirm)) {
            throw new MaintenanceException(HttpStatus.BAD_REQUEST, "CONFIRMATION_MISSING",
                    "Confirmation missing: send {\"confirm\": \"" + CONFIRMATION + "\"}");
        }
        String busy = busyReason();
        if (busy != null) {
            throw new MaintenanceException(HttpStatus.CONFLICT, "BATCH_BUSY", busy + ". Wait for it to finish before clearing.");
        }
        Map<String, Long> before = rowCounts();
        // One statement naming every table, so no CASCADE is needed: the Spring Batch foreign keys all point inside this
        // list. The id sequences are not owned by the tables and are left alone, which is what keeps ids from being reused.
        jdbc.execute("TRUNCATE TABLE " + String.join(", ", TABLES.stream().map(t -> SCHEMA + t).toList()));
        long total = before.values().stream().mapToLong(Long::longValue).sum();
        String who = actor == null || actor.isBlank() ? "unknown" : actor.trim();
        log.warn("BATCH HISTORY CLEARED by {}: {} rows removed {}", who, total, before);
        return new ClearResult(before, total, who, OffsetDateTime.now(),
                "The batch job history was removed; every job can run again for any business date.");
    }

    /** Why a clear would be refused right now, or null when nothing is running. */
    String busyReason() {
        if (endOfDay.isRunning()) {
            return "End of day is running";
        }
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(RUNNING_WINDOW_MINUTES);
        for (String name : jobExplorer.getJobNames()) {
            for (JobExecution e : jobExplorer.findRunningJobExecutions(name)) {
                LocalDateTime created = e.getCreateTime();
                if (created == null || created.isAfter(cutoff)) {
                    return "Job " + name + " is running";
                }
            }
        }
        return null;
    }

    private Map<String, Long> rowCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : TABLES) {
            Long n = jdbc.queryForObject("SELECT count(*) FROM " + SCHEMA + table, Long.class);
            counts.put(table, n == null ? 0L : n);
        }
        return counts;
    }
}
