package com.bank.lending.batch.core;

import com.bank.lending.batch.core.listener.AuditStepExecutionListener;
import com.bank.lending.batch.core.listener.BatchJobContextStepListener;
import com.bank.lending.batch.core.listener.DeadLetterSkipListener;
import com.bank.lending.batch.core.listener.MetricsJobExecutionListener;
import com.bank.lending.batch.core.partition.ColumnRangePartitioner;
import com.bank.lending.batch.core.partition.PartitionRangeItemReader;
import com.bank.lending.batch.core.validation.BatchJobConfigValidator;
import com.bank.lending.batch.domain.BatchJobConfig;
import com.bank.lending.batch.repository.BatchJobAuditLogRepository;
import com.bank.lending.batch.repository.BatchJobConfigRepository;
import com.bank.lending.batch.repository.BatchJobErrorRecordRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.SkipListener;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.partition.support.TaskExecutorPartitionHandler;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.FaultTolerantStepBuilder;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * The heart of the framework: turns every {@code batch_job_config} row into a fully-assembled
 * Spring Batch {@link Job} — reader/processor/writer from the matching {@link BusinessChunkJob}
 * or {@link BusinessBatchTasklet} bean, chunking/partitioning/retry/skip/timeout all pulled from
 * config, and the shared cross-cutting listeners (metrics, audit, dead-letter, businessDate
 * context) attached uniformly. No job-specific Java wiring exists anywhere else in this codebase
 * — adding a job is "write the business component + insert a config row", never "write a
 * Job/Step bean".
 *
 * <p>Built once at startup ({@link #buildAllOnStartup}) and rebuilt in full on every admin
 * config reload ({@link #rebuildAll}); {@link #getJob} is what {@code BatchJobLauncherService}
 * calls to resolve a job_name to a runnable {@link Job}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchJobRegistry {

    private static final int DEFAULT_CHUNK_SIZE = 100;
    private static final String PARTITION_TABLE = "public.loan_account";
    private static final String PARTITION_ID_COLUMN = "account_id";

    private final ApplicationContext applicationContext;
    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final DataSource dataSource;
    private final BatchJobConfigRepository batchJobConfigRepository;
    private final BatchJobAuditLogRepository batchJobAuditLogRepository;
    private final BatchJobErrorRecordRepository batchJobErrorRecordRepository;
    private final BatchJobConfigValidator configValidator;
    private final MeterRegistry meterRegistry;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    private final Map<String, Job> jobsByName = new ConcurrentHashMap<>();
    /** Per-job partition thread pools, tracked so #shutdown can drain them gracefully on SIGTERM. */
    private final List<ThreadPoolTaskExecutor> managedExecutors = new CopyOnWriteArrayList<>();

    @EventListener(ApplicationReadyEvent.class)
    public void buildAllOnStartup() {
        rebuildAll();
    }

    /** Rebuilds every Job from the current batch_job_config rows. Called at startup and by the admin reload endpoint. */
    @SuppressWarnings("rawtypes")
    public synchronized void rebuildAll() {
        Map<String, BusinessChunkJob> chunkJobs = applicationContext.getBeansOfType(BusinessChunkJob.class).values().stream()
                .collect(Collectors.toMap(BusinessChunkJob::getJobName, j -> j, (a, b) -> a));
        Map<String, BusinessBatchTasklet> taskletJobs = applicationContext.getBeansOfType(BusinessBatchTasklet.class).values().stream()
                .collect(Collectors.toMap(BusinessBatchTasklet::getJobName, j -> j, (a, b) -> a));

        shutdownManagedExecutors();

        List<BatchJobConfig> configs = batchJobConfigRepository.findAll();
        Map<String, Job> built = new ConcurrentHashMap<>();
        for (BatchJobConfig config : configs) {
            try {
                configValidator.validate(config);
                Job job = config.getJobType() == BatchJobConfig.JobType.CHUNK
                        ? buildChunkJob(config, requireChunkJob(chunkJobs, config))
                        : buildTaskletJob(config, requireTaskletJob(taskletJobs, config));
                built.put(config.getJobName(), job);
            } catch (Exception e) {
                log.error("Skipping job '{}' — failed to assemble: {}", config.getJobName(), e.getMessage());
            }
        }
        jobsByName.clear();
        jobsByName.putAll(built);
        log.info("BatchJobRegistry: assembled {}/{} configured jobs", built.size(), configs.size());
    }

    public Job getJob(String jobName) {
        Job job = jobsByName.get(jobName);
        if (job == null) {
            throw new IllegalArgumentException("No assembled Job for '" + jobName
                    + "' — check it has a matching BusinessChunkJob/BusinessBatchTasklet bean and a valid, enabled batch_job_config row");
        }
        return job;
    }

    public Set<String> registeredJobNames() {
        return jobsByName.keySet();
    }

    @SuppressWarnings("rawtypes")
    private BusinessChunkJob<?, ?> requireChunkJob(Map<String, BusinessChunkJob> chunkJobs, BatchJobConfig config) {
        BusinessChunkJob<?, ?> job = chunkJobs.get(config.getJobName());
        if (job == null) {
            throw new IllegalStateException("job_type=CHUNK but no BusinessChunkJob bean registered for job_name=" + config.getJobName());
        }
        return job;
    }

    private BusinessBatchTasklet requireTaskletJob(Map<String, BusinessBatchTasklet> taskletJobs, BatchJobConfig config) {
        BusinessBatchTasklet tasklet = taskletJobs.get(config.getJobName());
        if (tasklet == null) {
            throw new IllegalStateException("job_type=TASKLET but no BusinessBatchTasklet bean registered for job_name=" + config.getJobName());
        }
        return tasklet;
    }

    @SuppressWarnings("unchecked")
    private <I, O> Job buildChunkJob(BatchJobConfig config, BusinessChunkJob<?, ?> rawJob) {
        BusinessChunkJob<I, O> businessJob = (BusinessChunkJob<I, O>) rawJob;
        int chunkSize = config.getChunkSize() != null ? config.getChunkSize() : DEFAULT_CHUNK_SIZE;
        int concurrency = config.getConcurrency() != null ? config.getConcurrency() : 1;
        int throttleLimit = config.getThrottleLimit() != null ? config.getThrottleLimit() : Math.max(1, concurrency);
        int skipLimit = config.getSkipLimit() != null ? config.getSkipLimit() : 0;
        int retryLimit = config.getRetryLimit() != null ? config.getRetryLimit() : 0;

        PartitionRangeItemReader<I> reader = new PartitionRangeItemReader<>(businessJob::reader);
        ItemProcessor<I, O> processor = businessJob.processor();
        ItemWriter<O> writer = businessJob.writer();
        DeadLetterSkipListener skipListener = new DeadLetterSkipListener(batchJobErrorRecordRepository, config.getJobName());

        // Configured with separate statements (the builder mutates itself) instead of one fluent
        // chain: several listener(...) overloads return the plain SimpleStepBuilder, which would
        // lose the fault-tolerant type needed for skip()/retry() below. The casts pick one
        // overload explicitly for classes that implement more than one listener interface.
        FaultTolerantStepBuilder<I, O> faultTolerantBuilder = new StepBuilder(config.getJobName() + "-worker", jobRepository)
                .<I, O>chunk(chunkSize, transactionManager)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .faultTolerant();
        faultTolerantBuilder.skipLimit(skipLimit);
        faultTolerantBuilder.retryLimit(retryLimit);
        faultTolerantBuilder.listener((StepExecutionListener) reader);
        faultTolerantBuilder.listener((StepExecutionListener) new AuditStepExecutionListener(batchJobAuditLogRepository, config.getJobName()));
        faultTolerantBuilder.listener((StepExecutionListener) new BatchJobContextStepListener());
        faultTolerantBuilder.listener((StepExecutionListener) skipListener);
        faultTolerantBuilder.listener((SkipListener<Object, Object>) skipListener);

        for (Class<? extends Throwable> ex : resolveExceptionClasses(config.getSkippableExceptions())) {
            faultTolerantBuilder.skip(ex);
        }
        for (Class<? extends Throwable> ex : resolveExceptionClasses(config.getRetryableExceptions())) {
            faultTolerantBuilder.retry(ex);
        }

        Step workerStep = faultTolerantBuilder.build();

        Step step;
        if (concurrency > 1) {
            ColumnRangePartitioner partitioner = new ColumnRangePartitioner(
                    dataSource, PARTITION_TABLE, PARTITION_ID_COLUMN, null);

            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(throttleLimit);
            executor.setMaxPoolSize(throttleLimit);
            executor.setThreadNamePrefix("batch-" + config.getJobName() + "-");
            executor.setWaitForTasksToCompleteOnShutdown(true);
            executor.setAwaitTerminationSeconds(120);
            executor.initialize();
            managedExecutors.add(executor);

            TaskExecutorPartitionHandler partitionHandler = new TaskExecutorPartitionHandler();
            partitionHandler.setTaskExecutor(executor);
            partitionHandler.setStep(workerStep);
            partitionHandler.setGridSize(concurrency);

            step = new StepBuilder(config.getJobName(), jobRepository)
                    .partitioner(workerStep.getName(), partitioner)
                    .partitionHandler(partitionHandler)
                    .build();
        } else {
            step = workerStep;
        }

        return new JobBuilder(config.getJobName(), jobRepository)
                .start(step)
                .listener(new MetricsJobExecutionListener(meterRegistry, eventPublisher))
                .build();
    }

    private Job buildTaskletJob(BatchJobConfig config, BusinessBatchTasklet businessTasklet) {
        var tasklet = businessTasklet.buildTasklet();
        Integer timeoutMinutes = config.getTimeoutMinutes();
        var effectiveTasklet = (timeoutMinutes != null && timeoutMinutes > 0)
                ? new TimeoutTaskletDecorator(tasklet, timeoutMinutes)
                : tasklet;

        Step step = new StepBuilder(config.getJobName(), jobRepository)
                .tasklet(effectiveTasklet, transactionManager)
                .listener(new AuditStepExecutionListener(batchJobAuditLogRepository, config.getJobName()))
                .listener(new BatchJobContextStepListener())
                .build();

        return new JobBuilder(config.getJobName(), jobRepository)
                .start(step)
                .listener(new MetricsJobExecutionListener(meterRegistry, eventPublisher))
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<Class<? extends Throwable>> resolveExceptionClasses(String jsonArray) {
        if (jsonArray == null || jsonArray.isBlank()) {
            return List.of();
        }
        List<Class<? extends Throwable>> classes = new ArrayList<>();
        try {
            List<String> classNames = objectMapper.readValue(jsonArray, new TypeReference<List<String>>() {
            });
            for (String className : classNames) {
                try {
                    Class<?> clazz = Class.forName(className);
                    if (Throwable.class.isAssignableFrom(clazz)) {
                        classes.add((Class<? extends Throwable>) clazz);
                    } else {
                        log.warn("Configured exception class {} does not extend Throwable — ignoring", className);
                    }
                } catch (ClassNotFoundException e) {
                    log.warn("Configured exception class {} not found on classpath — ignoring", className);
                }
            }
        } catch (Exception e) {
            log.warn("Could not parse exception list JSON '{}': {}", jsonArray, e.getMessage());
        }
        return classes;
    }

    /** Graceful shutdown: give every in-flight partition executor time to finish/checkpoint before the JVM exits. */
    @PreDestroy
    public void shutdownManagedExecutors() {
        for (ThreadPoolTaskExecutor executor : managedExecutors) {
            log.info("Shutting down partition executor {} (waiting for in-flight partitions to complete)",
                    executor.getThreadNamePrefix());
            executor.shutdown();
        }
        managedExecutors.clear();
    }
}
