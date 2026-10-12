package com.bank.lending.batch.core.eod;

import com.bank.lending.batch.core.JobParamsUtil;
import com.bank.lending.batch.core.launch.BatchExecutionQueryService;
import com.bank.lending.batch.core.launch.BatchJobLauncherService;
import com.bank.lending.batch.domain.BatchJobConfig;
import com.bank.lending.batch.repository.BatchJobConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/** {@link EodJobGateway} backed by batch_job_config, Spring Batch's job explorer and the launcher. */
@Component
@RequiredArgsConstructor
public class SpringBatchEodJobGateway implements EodJobGateway {

    private static final int HISTORY_WINDOW = 50;

    private final BatchJobConfigRepository configRepository;
    private final BatchExecutionQueryService executionQueryService;
    private final BatchJobLauncherService launcherService;

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
            JobExecution execution = launcherService.launchForEndOfDay(jobName, businessDate);
            String description = execution.getExitStatus().getExitDescription();
            return new EodJobResult(jobName, execution.getStatus().toString(),
                    description == null || description.isBlank()
                            ? "skips=" + execution.getStepExecutions().stream().mapToLong(s -> s.getSkipCount()).sum()
                            : abbreviate(description));
        } catch (Exception e) {
            return new EodJobResult(jobName, "FAILED", abbreviate(String.valueOf(e.getMessage())));
        }
    }

    private static String abbreviate(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 300 ? t.substring(0, 300) + "..." : t;
    }
}
