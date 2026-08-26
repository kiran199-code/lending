package com.bank.lending.batch.core.quartz;

import com.bank.lending.batch.core.launch.BatchJobLauncherService;
import lombok.extern.slf4j.Slf4j;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Every Quartz trigger points at this one class — never at a job-specific Quartz Job
 * implementation — with the target business job's name carried in the trigger's JobDataMap
 * under "jobName" (see QuartzJobBridge). Spring Boot's Quartz autoconfiguration installs an
 * autowiring-capable JobFactory, so {@code @Autowired} below is populated even though Quartz,
 * not Spring, instantiates this class fresh on every fire.
 */
@Slf4j
public class SpringBatchQuartzJob implements Job {

    @Autowired
    private BatchJobLauncherService batchJobLauncherService;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        String jobName = context.getJobDetail().getJobDataMap().getString("jobName");
        try {
            batchJobLauncherService.launchScheduled(jobName);
        } catch (Exception e) {
            log.error("Quartz-triggered launch failed for job={}", jobName, e);
            throw new JobExecutionException(e);
        }
    }
}
