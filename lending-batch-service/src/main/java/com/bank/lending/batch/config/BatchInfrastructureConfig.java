package com.bank.lending.batch.config;

import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

/**
 * Adds an async JobLauncher and makes it the one that gets injected, in place of Spring Boot's
 * default synchronous one. It is registered under its own bean name ("asyncJobLauncher") because
 * Boot's auto-configuration already defines a bean called "jobLauncher" and bean overriding is
 * disabled, so reusing that name fails startup. {@code @Primary} makes {@code BatchJobLauncherService}
 * receive this one. The point of it: every launch path
 * (Quartz trigger, ad-hoc/backdated REST trigger) returns as soon as the run has started rather
 * than blocking the calling thread — a Quartz worker thread, or an HTTP request thread — until a
 * potentially hours-long batch job finishes. Callers poll BatchAdminController's execution
 * history endpoint for status instead.
 */
@Configuration
public class BatchInfrastructureConfig {

    @Bean
    @Primary
    public JobLauncher asyncJobLauncher(JobRepository jobRepository) throws Exception {
        TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(jobRepository);
        launcher.setTaskExecutor(new SimpleAsyncTaskExecutor("batch-launcher-"));
        launcher.afterPropertiesSet();
        return launcher;
    }
}
