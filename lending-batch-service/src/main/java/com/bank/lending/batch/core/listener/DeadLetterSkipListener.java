package com.bank.lending.batch.core.listener;

import com.bank.lending.batch.domain.BatchJobErrorRecord;
import com.bank.lending.batch.repository.BatchJobErrorRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.step.skip.SkipListenerSupport;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDate;

/**
 * Attached to every chunk step the registry assembles: every skipped read/process/write lands
 * as a row in batch_job_error_records rather than only appearing in a log line, so ops can
 * review and replay it later (see README "Error records: surfacing and replay").
 *
 * <p>Deliberately untyped ({@code Object,Object}) rather than generic over the step's actual
 * item types: {@code BusinessChunkJob<I,O>} lets I and O differ per job, and a skip can occur at
 * read (item is null), process (item is type I), or write (item is type O) — a single listener
 * instance attached uniformly to every step by {@code BatchJobRegistry} is far simpler than
 * threading I/O generics through the registry's job-assembly code, and {@code recordIdentifier}
 * only ever needs {@code toString()} regardless of the item's static type.
 */
@RequiredArgsConstructor
public class DeadLetterSkipListener extends SkipListenerSupport<Object, Object> implements StepExecutionListener {

    private final BatchJobErrorRecordRepository errorRecordRepository;
    private final String jobName;

    private Long jobExecutionId;
    private LocalDate businessDate;

    @Override
    public void beforeStep(StepExecution stepExecution) {
        this.jobExecutionId = stepExecution.getJobExecutionId();
        this.businessDate = stepExecution.getJobParameters().getLocalDate("businessDate");
    }

    @Override
    public void onSkipInRead(Throwable t) {
        record(null, t);
    }

    @Override
    public void onSkipInProcess(Object item, Throwable t) {
        record(item, t);
    }

    @Override
    public void onSkipInWrite(Object item, Throwable t) {
        record(item, t);
    }

    private void record(Object item, Throwable t) {
        StringWriter stackTrace = new StringWriter();
        t.printStackTrace(new PrintWriter(stackTrace));

        BatchJobErrorRecord error = BatchJobErrorRecord.builder()
                .jobName(jobName)
                .jobExecutionId(jobExecutionId)
                .businessDate(businessDate)
                .recordIdentifier(item == null ? "n/a (read failure)" : item.toString())
                .recordPayload(item == null ? null : item.toString())
                .exceptionClass(t.getClass().getName())
                .exceptionMessage(truncate(String.valueOf(t.getMessage()), 4000))
                .stackTrace(truncate(stackTrace.toString(), 8000))
                .replayStatus(BatchJobErrorRecord.ReplayStatus.PENDING_REVIEW)
                .build();
        errorRecordRepository.save(error);
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
