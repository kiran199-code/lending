package com.bank.lending.admin.repository;

import com.bank.lending.admin.dto.AdminDtos.BatchExecutionRow;
import com.bank.lending.admin.dto.AdminDtos.BatchJobConfigRow;
import com.bank.lending.admin.dto.AdminDtos.BatchJobRow;
import com.bank.lending.admin.dto.AdminDtos.OutboxRow;
import com.bank.lending.admin.dto.AdminDtos.PageResponse;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Ledger outbox and the batch service's tables (schema "batch"), both read-only. */
@Repository
public class OpsRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public OpsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Long> outboxCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("PENDING", 0L);
        counts.put("POSTED", 0L);
        counts.put("FAILED", 0L);
        jdbc.query("SELECT posting_status, COUNT(*) AS n FROM ledger_outbox GROUP BY posting_status",
                new MapSqlParameterSource(), rs -> {
                    counts.put(rs.getString("posting_status"), rs.getLong("n"));
                });
        return counts;
    }

    public PageResponse<OutboxRow> outbox(String status, int page, int size) {
        page = Sql.clampPage(page);
        size = Sql.clampSize(size);
        MapSqlParameterSource p = new MapSqlParameterSource();
        String where = "";
        if (status != null && !status.isBlank()) {
            where = " WHERE o.posting_status = :status";
            p.addValue("status", status);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM ledger_outbox o" + where, p, Long.class);
        p.addValue("limit", size).addValue("offset", (long) page * size);
        List<OutboxRow> rows = jdbc.query("""
                SELECT o.outbox_id, o.account_id, a.account_number, o.event_type, o.posting_status,
                       o.retry_count, o.last_attempted_at, o.created_at
                FROM ledger_outbox o JOIN loan_account a ON a.account_id = o.account_id
                """ + where + " ORDER BY o.created_at DESC, o.outbox_id DESC LIMIT :limit OFFSET :offset", p,
                (rs, i) -> new OutboxRow(rs.getLong("outbox_id"), rs.getLong("account_id"),
                        rs.getString("account_number"), rs.getString("event_type"), rs.getString("posting_status"),
                        rs.getInt("retry_count"), Sql.ts(rs, "last_attempted_at"), Sql.ts(rs, "created_at")));
        return PageResponse.of(rows, page, size, total == null ? 0 : total);
    }

    public List<BatchJobRow> batchJobs() {
        return jdbc.query("""
                SELECT job_name, job_category, job_type, cron_expression, enabled, priority, timeout_minutes
                FROM batch.batch_job_config ORDER BY priority, job_name
                """, new MapSqlParameterSource(),
                (rs, i) -> new BatchJobRow(rs.getString("job_name"), rs.getString("job_category"),
                        rs.getString("job_type"), rs.getString("cron_expression"), rs.getBoolean("enabled"),
                        Sql.intOrNull(rs, "priority"), Sql.intOrNull(rs, "timeout_minutes")));
    }

    public List<BatchExecutionRow> batchExecutions(int limit) {
        return jdbc.query("""
                SELECT e.job_execution_id, i.job_name, e.status, e.exit_code, e.start_time, e.end_time
                FROM batch.batch_job_execution e JOIN batch.batch_job_instance i ON i.job_instance_id = e.job_instance_id
                ORDER BY e.job_execution_id DESC LIMIT :limit
                """, new MapSqlParameterSource("limit", Math.max(1, Math.min(limit, 200))),
                (rs, i) -> new BatchExecutionRow(rs.getLong("job_execution_id"), rs.getString("job_name"),
                        rs.getString("status"), rs.getString("exit_code"),
                        rs.getObject("start_time", LocalDateTime.class), rs.getObject("end_time", LocalDateTime.class)));
    }

    private static final String JOB_CONFIG_SELECT = """
            SELECT config_id, job_name, job_category, job_type, cron_expression, enabled, restartable, chunk_size,
                   concurrency, throttle_limit, retry_limit, skip_limit, priority, timeout_minutes, version,
                   CAST(retryable_exceptions AS text) AS retryable_exceptions,
                   CAST(skippable_exceptions AS text) AS skippable_exceptions,
                   CAST(job_params_json AS text) AS job_params_json, updated_at
            FROM batch.batch_job_config
            """;

    private static BatchJobConfigRow jobConfig(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new BatchJobConfigRow(rs.getLong("config_id"), rs.getString("job_name"), rs.getString("job_category"),
                rs.getString("job_type"), rs.getString("cron_expression"), rs.getBoolean("enabled"),
                rs.getBoolean("restartable"), Sql.intOrNull(rs, "chunk_size"), Sql.intOrNull(rs, "concurrency"),
                Sql.intOrNull(rs, "throttle_limit"), rs.getInt("retry_limit"), rs.getInt("skip_limit"),
                rs.getInt("priority"), Sql.intOrNull(rs, "timeout_minutes"), rs.getInt("version"),
                rs.getString("retryable_exceptions"), rs.getString("skippable_exceptions"),
                rs.getString("job_params_json"), Sql.ts(rs, "updated_at"));
    }

    /** Every batch job's full configuration (all 36 rows), in run order. */
    public List<BatchJobConfigRow> batchJobConfigs() {
        return jdbc.query(JOB_CONFIG_SELECT + " ORDER BY priority, job_name", new MapSqlParameterSource(),
                OpsRepository::jobConfig);
    }

    public java.util.Optional<BatchJobConfigRow> batchJobConfig(String jobName) {
        return jdbc.query(JOB_CONFIG_SELECT + " WHERE job_name = :n", new MapSqlParameterSource("n", jobName),
                OpsRepository::jobConfig).stream().findFirst();
    }
}
