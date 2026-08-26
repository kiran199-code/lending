package com.bank.lending.batch;

import com.bank.lending.batch.core.BatchJobRegistry;
import com.bank.lending.batch.core.launch.BatchJobLauncherService;
import com.bank.lending.batch.support.FailingTestChunkJob;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof that the shared framework — not any individual job's business logic, which is
 * still stubbed throughout — actually works: config-driven job assembly, partitioned/concurrent
 * execution, the dead-letter path, and the Kafka job-completion event, all against real
 * PostgreSQL and Kafka via Testcontainers rather than mocks.
 *
 * <p>Covers, per the README's framework guarantees:
 * <ol>
 *   <li>A real DAILY chunk job (INTEREST_ACCRUAL_DAILY) and a real MONTHLY chunk job
 *       (EMI_BILLING_GENERATION_MONTHLY) — both from the actual V2 seed data, unmodified — run to
 *       completion purely off {@code batch_job_config}, with no job-specific code in this test.</li>
 *   <li>Partitioning actually fans out across more than one thread: both jobs are seeded with
 *       {@code concurrency=8}, and this test asserts more than one distinct partition
 *       (StepExecution) actually ran.</li>
 *   <li>A deliberately failing record (via the test-only {@link FailingTestChunkJob}) is skipped
 *       rather than failing the job, and lands in {@code batch.batch_job_error_records}.</li>
 * </ol>
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LendingBatchServiceIntegrationTest {

    private static final String KAFKA_TOPIC = "lending.batch.job-events";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("lending")
            .withUsername("lending_batch")
            .withPassword("lending_batch");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&currentSchema=batch,public");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BatchJobRegistry batchJobRegistry;

    @Autowired
    private BatchJobLauncherService batchJobLauncherService;

    @Autowired
    private JobExplorer jobExplorer;

    private KafkaConsumer<String, String> kafkaConsumer;

    @BeforeAll
    void seedBusinessSchemaAndTestJob() {
        // lending-service's own schema isn't part of this module — create just enough of
        // public.loan_account for the account-range reader/partitioner to operate against.
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS public.loan_account (
                    account_id BIGINT PRIMARY KEY,
                    account_status VARCHAR(30) NOT NULL
                )""");
        jdbcTemplate.update("DELETE FROM public.loan_account");
        for (long id = 1; id <= 80; id++) {
            jdbcTemplate.update("INSERT INTO public.loan_account (account_id, account_status) VALUES (?, 'ACTIVE')", id);
        }

        // Test-only job config row (never shipped in V2__seed_batch_job_config.sql) backing
        // FailingTestChunkJob — skip_limit=5 and the exception registered as skippable means the
        // framework dead-letters the poison record instead of failing the whole job. Written via
        // execute() (no PreparedStatement parameter parsing at all) so the literal '?' inside the
        // cron expression can't be misread as a bind placeholder.
        jdbcTemplate.update("DELETE FROM batch.batch_job_config WHERE job_name = ?", "TEST_CHUNK_JOB_WITH_FAILURE");
        String exceptionClassName = FailingTestChunkJob.SimulatedValidationException.class.getName();
        jdbcTemplate.execute("""
                INSERT INTO batch.batch_job_config
                    (job_name, job_category, job_type, cron_expression, enabled, restartable,
                     chunk_size, concurrency, throttle_limit, retry_limit, retryable_exceptions,
                     skip_limit, skippable_exceptions, job_params_json, priority, timeout_minutes)
                VALUES ('TEST_CHUNK_JOB_WITH_FAILURE', 'DAILY', 'CHUNK', '0 0 3 * * ?', true, true,
                        2, 2, 2, 1, '[]'::jsonb,
                        5, '["%s"]'::jsonb, '{}'::jsonb, 900, 5)
                """.formatted(exceptionClassName));

        // The registry only reads config at startup / on explicit reload — this test's row was
        // inserted after startup, so rebuild once before triggering it.
        batchJobRegistry.rebuildAll();

        Map<String, Object> consumerProps = new HashMap<>();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "lending-batch-test-group");
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        kafkaConsumer = new KafkaConsumer<>(consumerProps);
        kafkaConsumer.subscribe(List.of(KAFKA_TOPIC));
    }

    @AfterAll
    void closeConsumer() {
        if (kafkaConsumer != null) {
            kafkaConsumer.close();
        }
    }

    @Test
    void dailyAndMonthlyChunkJobsRunToCompletionWithPartitioning() throws Exception {
        LocalDate businessDate = LocalDate.now();

        JobExecution dailyExecution = batchJobLauncherService.launchManual("INTEREST_ACCRUAL_DAILY", businessDate);
        JobExecution monthlyExecution = batchJobLauncherService.launchManual("EMI_BILLING_GENERATION_MONTHLY", businessDate);

        JobExecution dailyFinal = waitForTerminal(dailyExecution.getId());
        JobExecution monthlyFinal = waitForTerminal(monthlyExecution.getId());

        assertThat(dailyFinal.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(monthlyFinal.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Both jobs are seeded with concurrency=8 (V2__seed_batch_job_config.sql) against 80
        // seeded loan_account rows — proves partitioning actually fanned out to more than one
        // worker StepExecution, not just a single-threaded pass with gridSize ignored.
        assertThat(distinctPartitionStepCount("INTEREST_ACCRUAL_DAILY", dailyFinal.getId())).isGreaterThan(1);
        assertThat(distinctPartitionStepCount("EMI_BILLING_GENERATION_MONTHLY", monthlyFinal.getId())).isGreaterThan(1);

        // MetricsJobExecutionListener -> KafkaJobEventBridge: a completion event for at least one
        // of the two jobs above should have reached the topic.
        Set<String> jobNamesSeen = new HashSet<>();
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(20).toMillis();
        while (jobNamesSeen.isEmpty() && System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = kafkaConsumer.poll(Duration.ofMillis(1000));
            for (ConsumerRecord<String, String> record : records) {
                jobNamesSeen.add(record.key());
            }
        }
        assertThat(jobNamesSeen).containsAnyOf("INTEREST_ACCRUAL_DAILY", "EMI_BILLING_GENERATION_MONTHLY");
    }

    @Test
    void failingRecordIsSkippedAndDeadLettered() throws Exception {
        LocalDate businessDate = LocalDate.now();

        JobExecution execution = batchJobLauncherService.launchManual("TEST_CHUNK_JOB_WITH_FAILURE", businessDate);
        JobExecution finalExecution = waitForTerminal(execution.getId());

        // Skip (not failure): the job as a whole still completes even though one record threw,
        // because SimulatedValidationException is registered as this job's skippable_exceptions.
        assertThat(finalExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        Integer errorRecordCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM batch.batch_job_error_records WHERE job_name = ? AND job_execution_id = ?",
                Integer.class, "TEST_CHUNK_JOB_WITH_FAILURE", finalExecution.getId());
        assertThat(errorRecordCount).isGreaterThanOrEqualTo(1);

        String recordIdentifier = jdbcTemplate.queryForObject(
                "SELECT record_identifier FROM batch.batch_job_error_records WHERE job_name = ? AND job_execution_id = ? LIMIT 1",
                String.class, "TEST_CHUNK_JOB_WITH_FAILURE", finalExecution.getId());
        assertThat(recordIdentifier).contains(String.valueOf(FailingTestChunkJob.POISON_ACCOUNT_ID));
    }

    // Deliberately not BatchStatus#isGreaterThan: its ordering is severity-based for status
    // *upgrades* across steps (COMPLETED sorts before STARTED so a failed step always "wins"
    // when merging statuses), not a "reached terminal state" check — COMPLETED.isGreaterThan
    // (STARTED) is actually false. Terminal-ness is its own, explicit set of end states.
    private static final Set<BatchStatus> TERMINAL_STATUSES = Set.of(
            BatchStatus.COMPLETED, BatchStatus.FAILED, BatchStatus.STOPPED,
            BatchStatus.ABANDONED, BatchStatus.UNKNOWN);

    private JobExecution waitForTerminal(Long jobExecutionId) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            JobExecution refreshed = jobExplorer.getJobExecution(jobExecutionId);
            if (refreshed != null && TERMINAL_STATUSES.contains(refreshed.getStatus())) {
                return refreshed;
            }
            TimeUnit.MILLISECONDS.sleep(200);
        }
        throw new AssertionError("JobExecution " + jobExecutionId + " did not reach a terminal status within 30s");
    }

    private int distinctPartitionStepCount(String jobName, Long jobExecutionId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT step_execution_id) FROM batch.batch_job_audit_log WHERE job_name = ? AND job_execution_id = ?",
                Integer.class, jobName, jobExecutionId);
        return count == null ? 0 : count;
    }
}
