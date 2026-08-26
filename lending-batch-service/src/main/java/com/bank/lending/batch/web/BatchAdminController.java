package com.bank.lending.batch.web;

import com.bank.lending.batch.core.BatchJobRegistry;
import com.bank.lending.batch.core.launch.BatchExecutionQueryService;
import com.bank.lending.batch.core.launch.BatchJobLauncherService;
import com.bank.lending.batch.core.quartz.QuartzJobBridge;
import com.bank.lending.batch.domain.BatchJobConfig;
import com.bank.lending.batch.repository.BatchJobConfigRepository;
import com.bank.lending.batch.repository.BatchJobErrorRecordRepository;
import com.bank.lending.batch.web.dto.AckResponse;
import com.bank.lending.batch.web.dto.ErrorRecordResponse;
import com.bank.lending.batch.web.dto.JobConfigResponse;
import com.bank.lending.batch.web.dto.JobExecutionSummaryResponse;
import com.bank.lending.batch.web.dto.JobStatusResponse;
import com.bank.lending.batch.web.dto.TriggerJobRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.springframework.batch.core.JobExecution;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Admin/ops surface for the batch framework: inspect configured jobs, trigger ad-hoc/backdated
 * runs, review execution history and dead-lettered records, and reload config without a restart.
 * Secured by SecurityConfig (HTTP Basic, ROLE_BATCH_ADMIN) — this is an operator tool, not a
 * customer-facing API.
 */
@RestController
@RequestMapping("/api/v1/batch-admin")
@RequiredArgsConstructor
public class BatchAdminController {

    private static final String QUARTZ_JOB_GROUP = "lending-batch";

    private final BatchJobConfigRepository batchJobConfigRepository;
    private final BatchJobErrorRecordRepository batchJobErrorRecordRepository;
    private final BatchJobRegistry batchJobRegistry;
    private final BatchJobLauncherService batchJobLauncherService;
    private final BatchExecutionQueryService executionQueryService;
    private final QuartzJobBridge quartzJobBridge;
    private final Scheduler scheduler;
    private final ObjectMapper objectMapper;

    @GetMapping("/jobs")
    public List<JobConfigResponse> listJobs() {
        return batchJobConfigRepository.findAll().stream()
                .map(c -> JobConfigResponse.from(c, batchJobRegistry.registeredJobNames().contains(c.getJobName())))
                .toList();
    }

    @GetMapping("/jobs/{jobName}")
    public JobConfigResponse getJob(@PathVariable String jobName) {
        BatchJobConfig config = batchJobConfigRepository.findByJobName(jobName)
                .orElseThrow(() -> new IllegalArgumentException("No such job: " + jobName));
        return JobConfigResponse.from(config, batchJobRegistry.registeredJobNames().contains(jobName));
    }

    /**
     * Ad-hoc run (no body), backdated re-run (businessDate in the body), or an event-triggered
     * job's invocation (jobParams in the body, e.g. {@code {"accountId": 12345}}) — see README
     * "Triggering ad-hoc / backdated runs".
     */
    @PostMapping("/jobs/{jobName}/trigger")
    public JobExecutionSummaryResponse trigger(@PathVariable String jobName,
                                                @RequestBody(required = false) TriggerJobRequest request) throws Exception {
        LocalDate businessDate = request == null ? null : request.businessDate();
        String jobParamsOverride = (request != null && request.jobParams() != null && !request.jobParams().isEmpty())
                ? objectMapper.writeValueAsString(request.jobParams())
                : null;
        JobExecution execution = batchJobLauncherService.launchManual(jobName, businessDate, jobParamsOverride);
        return JobExecutionSummaryResponse.from(execution);
    }

    @GetMapping("/jobs/{jobName}/executions")
    public List<JobExecutionSummaryResponse> executions(@PathVariable String jobName,
                                                          @RequestParam(defaultValue = "20") int limit) {
        return executionQueryService.recentExecutions(jobName, limit).stream()
                .map(JobExecutionSummaryResponse::from)
                .toList();
    }

    @GetMapping("/jobs/{jobName}/errors")
    public List<ErrorRecordResponse> errors(@PathVariable String jobName,
                                             @RequestParam(defaultValue = "50") int limit) {
        return batchJobErrorRecordRepository
                .findByJobNameOrderByCreatedAtDesc(jobName, PageRequest.of(0, limit, Sort.by("createdAt").descending()))
                .map(ErrorRecordResponse::from)
                .getContent();
    }

    @GetMapping("/executions/{jobExecutionId}/errors")
    public List<ErrorRecordResponse> errorsForExecution(@PathVariable Long jobExecutionId,
                                                          @RequestParam(defaultValue = "200") int limit) {
        return batchJobErrorRecordRepository
                .findByJobExecutionId(jobExecutionId, PageRequest.of(0, limit))
                .map(ErrorRecordResponse::from)
                .getContent();
    }

    /** Reloads batch_job_config from the DB, rebuilds every Job, and re-schedules Quartz triggers — no restart needed. */
    @PostMapping("/config/reload")
    public AckResponse reloadConfig() {
        batchJobRegistry.rebuildAll();
        quartzJobBridge.rescheduleAll();
        return AckResponse.of("Config reloaded: " + batchJobRegistry.registeredJobNames().size() + " jobs assembled");
    }

    /** Which jobs are due next, currently running, or last failed — the health/status view. */
    @GetMapping("/status/today")
    public List<JobStatusResponse> statusToday() throws SchedulerException {
        List<JobStatusResponse> statuses = new ArrayList<>();
        for (BatchJobConfig config : batchJobConfigRepository.findAll()) {
            JobExecution last = executionQueryService.latestExecution(config.getJobName());
            boolean running = executionQueryService.isRunning(config.getJobName());
            LocalDateTime nextFire = nextFireTime(config.getJobName());
            statuses.add(new JobStatusResponse(
                    config.getJobName(),
                    config.getJobCategory().name(),
                    Boolean.TRUE.equals(config.getEnabled()),
                    running,
                    last == null ? null : last.getStatus().toString(),
                    last == null ? null : last.getExitStatus().getExitCode(),
                    last == null ? null : last.getStartTime(),
                    nextFire
            ));
        }
        return statuses;
    }

    private LocalDateTime nextFireTime(String jobName) throws SchedulerException {
        JobKey jobKey = JobKey.jobKey(jobName, QUARTZ_JOB_GROUP);
        if (!scheduler.checkExists(jobKey)) {
            return null;
        }
        return scheduler.getTriggersOfJob(jobKey).stream()
                .map(Trigger::getNextFireTime)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .map(date -> LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault()))
                .orElse(null);
    }
}
