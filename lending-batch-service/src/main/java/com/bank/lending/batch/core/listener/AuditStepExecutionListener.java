package com.bank.lending.batch.core.listener;

import com.bank.lending.batch.domain.BatchJobAuditLog;
import com.bank.lending.batch.repository.BatchJobAuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Attached to every step the registry assembles: writes a step-level summary row to
 * batch_job_audit_log after each step finishes (read/write/skip/retry counts, exit status,
 * timing) — the "how did this run go" view backing BatchAdminController's execution history.
 * Per-item failure detail lives separately in batch_job_error_records (see DeadLetterSkipListener).
 */
@RequiredArgsConstructor
public class AuditStepExecutionListener implements StepExecutionListener {

    private final BatchJobAuditLogRepository auditLogRepository;
    private final String jobName;

    @Override
    public void beforeStep(StepExecution stepExecution) {
        // No-op: this listener only records the outcome, not the start; start time comes from
        // StepExecution.getStartTime() at afterStep time.
    }

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        LocalDate businessDate = stepExecution.getJobParameters().getLocalDate("businessDate");
        Long durationMillis = (stepExecution.getStartTime() != null && stepExecution.getEndTime() != null)
                ? java.time.Duration.between(stepExecution.getStartTime(), stepExecution.getEndTime()).toMillis()
                : null;

        BatchJobAuditLog audit = BatchJobAuditLog.builder()
                .jobName(jobName)
                .stepName(stepExecution.getStepName())
                .jobExecutionId(stepExecution.getJobExecutionId())
                .stepExecutionId(stepExecution.getId())
                .businessDate(businessDate)
                .readCount(stepExecution.getReadCount())
                .writeCount(stepExecution.getWriteCount())
                .skipCount(stepExecution.getSkipCount())
                .retryCount((long) stepExecution.getRollbackCount())
                .commitCount(stepExecution.getCommitCount())
                .rollbackCount((long) stepExecution.getRollbackCount())
                .exitStatus(stepExecution.getExitStatus().getExitCode())
                .exitDescription(truncate(stepExecution.getExitStatus().getExitDescription(), 4000))
                .startTime(stepExecution.getStartTime() == null ? null : stepExecution.getStartTime().atOffset(ZoneOffset.UTC))
                .endTime(stepExecution.getEndTime() == null ? null : stepExecution.getEndTime().atOffset(ZoneOffset.UTC))
                .durationMillis(durationMillis)
                .build();
        auditLogRepository.save(audit);

        return stepExecution.getExitStatus();
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
