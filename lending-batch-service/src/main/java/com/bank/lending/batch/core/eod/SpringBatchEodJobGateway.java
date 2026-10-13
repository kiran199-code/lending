package com.bank.lending.batch.core.eod;

import com.bank.lending.batch.core.JobParamsUtil;
import com.bank.lending.batch.core.launch.BatchExecutionQueryService;
import com.bank.lending.batch.core.launch.BatchJobLauncherService;
import com.bank.lending.batch.domain.BatchJobConfig;
import com.bank.lending.batch.repository.BatchJobConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/** {@link EodJobGateway} backed by batch_job_config, Spring Batch's job explorer and the launcher. */
@Component
@RequiredArgsConstructor
public class SpringBatchEodJobGateway implements EodJobGateway {

    private static final int HISTORY_WINDOW = 50;

    private final BatchJobConfigRepository configRepository;
    private final BatchExecutionQueryService executionQueryService;
    private final BatchJobLauncherService launcherService;
    private final JobExplorer jobExplorer;

    /** How long end of day waits for one job to reach a final state before it gives up on the chain. */
    @Value("${batch.eod.job-timeout-minutes:180}")
    private long jobTimeoutMinutes;

    @Value("${batch.eod.poll-interval-ms:1000}")
    private long pollIntervalMs;

    @Override
    public List<String> dailyJobNames() {
        return configRepository.findByJobCategory(BatchJobConfig.JobCategory.DAILY).stream()
                .filter(c -> Boolean.TRUE.equals(c.getEnabled()))
                .sorted(Comparator.comparing(BatchJobConfig::getPriority, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(BatchJobConfig::getJobName))
                .map(BatchJobConfig::getJobName)
                .toList();
    }

    @Override
    public boolean alreadyCompleted(String jobName, LocalDate businessDate) {
        return executionQueryService.recentExecutions(jobName, HISTORY_WINDOW).stream()
                .anyMatch(e -> e.getStatus() == BatchStatus.COMPLETED
                        && businessDate.equals(JobParamsUtil.businessDate(e.getJobParameters())));
    }

    @Override
    public EodJobResult run(String jobName, LocalDate businessDate) {
        try {
            JobExecution launched = launcherService.launchForEndOfDay(jobName, businessDate);
            // The job launcher is asynchronous: launch returns while the job is still STARTING/STARTED. Wait for the
            // job to finish before judging it, otherwise end of day would stop after the first job it launches.
            JobExecution execution = awaitTerminal(launched, id -> jobExplorer.getJobExecution(id),
                    Duration.ofMinutes(Math.max(1, jobTimeoutMinutes)), Duration.ofMillis(Math.max(10, pollIntervalMs)));
            if (execution.getStatus().isRunning()) {
                return new EodJobResult(jobName, "TIMED_OUT", "still " + execution.getStatus() + " after " + jobTimeoutMinutes
                        + " min (batch.eod.job-timeout-minutes); end of day stops here, rerun it once the job has finished");
            }
            String description = execution.getExitStatus().getExitDescription();
            return new EodJobResult(jobName, execution.getStatus().toString(),
                    description == null || description.isBlank()
                            ? "skips=" + execution.getStepExecutions().stream().mapToLong(s -> s.getSkipCount()).sum()
                            : abbreviate(description));
        } catch (Exception e) {
            return new EodJobResult(jobName, "FAILED", abbreviate(String.valueOf(e.getMessage())));
        }
    }

    /**
     * Polls {@code reload} until the execution is no longer STARTING/STARTED/STOPPING, or the timeout passes
     * (then the last state seen is returned and is still "running"). Package-private for unit testing.
     */
    static JobExecution awaitTerminal(JobExecution launched, Function<Long, JobExecution> reload, Duration timeout, Duration poll)
            throws InterruptedException {
        JobExecution current = launched;
        long deadline = System.nanoTime() + timeout.toNanos();
        while (current.getStatus().isRunning() && System.nanoTime() < deadline) {
            Thread.sleep(poll.toMillis());
            JobExecution fresh = reload.apply(launched.getId());
            if (fresh != null) {
                current = fresh;
            }
        }
        return current;
    }

    private static String abbreviate(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 300 ? t.substring(0, 300) + "..." : t;
    }
}
