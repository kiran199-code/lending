package com.bank.lending.batch.core.quartz;

import com.bank.lending.batch.domain.BatchJobConfig;
import com.bank.lending.batch.repository.BatchJobConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

import static org.quartz.CronScheduleBuilder.cronSchedule;
import static org.quartz.JobBuilder.newJob;
import static org.quartz.TriggerBuilder.newTrigger;

/**
 * Reads cron_expression per job from batch_job_config and (re)schedules a Quartz trigger that
 * fires {@link SpringBatchQuartzJob} for it — the only place cron-scheduling logic lives;
 * individual business jobs never touch Quartz directly. Runs once at startup and again whenever
 * BatchAdminController#reloadConfig is invoked, so a cron/enabled change takes effect without a
 * restart.
 *
 * <p>Misfire handling: FIRE_AND_PROCEED — a trigger missed while the scheduler was down fires
 * once immediately on the next tick and then resumes its normal schedule, matching EOD
 * catch-up expectations. Clustering: point every instance at the same JDBC job store
 * (spring.quartz.job-store-type=jdbc, org.quartz.jobStore.isClustered=true — see
 * application-prod.yml) and Quartz's own trigger-acquisition locking keeps only one node firing
 * each trigger.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuartzJobBridge {

    private static final String JOB_GROUP = "lending-batch";

    private final Scheduler scheduler;
    private final BatchJobConfigRepository batchJobConfigRepository;

    @EventListener(ApplicationReadyEvent.class)
    public void scheduleAllOnStartup() {
        rescheduleAll();
    }

    /** Re-reads every enabled, cron-scheduled job from config and (re)schedules its trigger. Safe to call repeatedly. */
    public void rescheduleAll() {
        List<BatchJobConfig> configs = batchJobConfigRepository.findByEnabledTrue();
        int scheduled = 0;
        for (BatchJobConfig config : configs) {
            if (config.getCronExpression() == null || config.getCronExpression().isBlank()) {
                continue; // event-triggered job — no Quartz trigger, only the manual/API path
            }
            try {
                scheduleOrReschedule(config);
                scheduled++;
            } catch (SchedulerException e) {
                log.error("Failed to schedule job={} cron={}", config.getJobName(), config.getCronExpression(), e);
            }
        }
        log.info("QuartzJobBridge: (re)scheduled {} cron-triggered jobs (of {} enabled)", scheduled, configs.size());
    }

    private void scheduleOrReschedule(BatchJobConfig config) throws SchedulerException {
        JobKey jobKey = JobKey.jobKey(config.getJobName(), JOB_GROUP);
        TriggerKey triggerKey = TriggerKey.triggerKey(config.getJobName() + "-trigger", JOB_GROUP);

        JobDetail jobDetail = newJob(SpringBatchQuartzJob.class)
                .withIdentity(jobKey)
                .usingJobData("jobName", config.getJobName())
                .storeDurably()
                .build();

        Trigger trigger = newTrigger()
                .withIdentity(triggerKey)
                .forJob(jobDetail)
                .withSchedule(cronSchedule(config.getCronExpression())
                        .withMisfireHandlingInstructionFireAndProceed())
                .withPriority(resolvePriority(config))
                .build();

        if (scheduler.checkExists(jobKey)) {
            scheduler.addJob(jobDetail, true);
            scheduler.rescheduleJob(triggerKey, trigger);
        } else {
            scheduler.scheduleJob(jobDetail, trigger);
        }
    }

    /** batch_job_config.priority is "lower runs first"; Quartz trigger priority is "higher wins", so invert it. */
    private int resolvePriority(BatchJobConfig config) {
        int configured = config.getPriority() == null ? 100 : config.getPriority();
        return Math.max(1, 1000 - configured);
    }

    public void unscheduleAll() throws SchedulerException {
        for (JobKey key : scheduler.getJobKeys(GroupMatcher.jobGroupEquals(JOB_GROUP))) {
            scheduler.deleteJob(key);
        }
    }
}
