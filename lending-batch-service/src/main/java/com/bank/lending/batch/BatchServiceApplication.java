package com.bank.lending.batch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Standalone deployable for all loan-servicing batch jobs (daily EOD, monthly, and periodic).
 * Deliberately NOT part of the lending-service web app — this runs as its own JAR/process so
 * batch load never competes with, or is coupled to the deploy cadence of, the servicing API.
 *
 * <p>Job execution itself is never triggered by Spring Boot's default "run every discovered
 * Job at startup" behavior (disabled via spring.batch.job.enabled=false) — jobs are launched
 * exclusively via {@link com.bank.lending.batch.core.quartz.QuartzJobBridge} on their configured
 * cron schedule, or on-demand via {@link com.bank.lending.batch.web.BatchAdminController}.
 */
@SpringBootApplication
@EnableScheduling
public class BatchServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(BatchServiceApplication.class, args);
    }
}
