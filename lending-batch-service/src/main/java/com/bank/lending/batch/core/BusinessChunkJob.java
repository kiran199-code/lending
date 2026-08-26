package com.bank.lending.batch.core;

import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;

/**
 * Marker + factory for a chunk-oriented, partitionable business job — bulk record processing
 * (e.g. interest accrual across millions of accounts). {@code BatchJobRegistry} wraps the
 * reader/processor/writer this returns with the shared partitioning + chunking + retry/skip
 * infrastructure built from this job's {@code batch_job_config} row; implementations never
 * construct a {@link org.springframework.batch.core.Step} or {@link org.springframework.batch.core.Job}
 * themselves.
 *
 * <p>Reader implementations should stay partition-range-aware: {@code minId}/{@code maxId} bound
 * the account_id range this partition (one thread of the grid) is responsible for, produced by
 * {@link com.bank.lending.batch.core.partition.ColumnRangePartitioner}. The default account-range
 * reader ({@link #accountRangeReader}) covers the common case (one row of business logic per
 * loan_account row); override {@link #reader} directly for jobs keyed off a different table.
 *
 * <p>To add a new chunk-style job: implement this interface as a {@code @Component}, insert a
 * matching {@code batch_job_config} row with {@code job_type = 'CHUNK'}, and reload config (or
 * restart) — no framework code changes. See README "Adding a new batch job".
 *
 * @param <I> item type read from the source table/range
 * @param <O> item type after business processing, handed to the writer
 */
public interface BusinessChunkJob<I, O> {

    /** Must exactly match the job_name of this job's row in batch_job_config. */
    String getJobName();

    /**
     * Reads items for the given account_id partition range [minId, maxId]. Most account-scoped
     * jobs can rely on the framework's generic account-range reader instead of implementing this
     * directly — see {@code AbstractAccountRangeChunkJob} in the jobs.* packages for the common
     * pattern this interface is designed to support.
     */
    ItemReader<I> reader(Long minId, Long maxId);

    /** The business logic for this job — stubbed with // TODO markers in the initial scaffolding pass. */
    ItemProcessor<I, O> processor();

    /** Persists/side-effects processed items. May be a no-op logging writer in the scaffolding pass. */
    ItemWriter<O> writer();
}
