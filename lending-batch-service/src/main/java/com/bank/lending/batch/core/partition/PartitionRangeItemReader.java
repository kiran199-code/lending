package com.bank.lending.batch.core.partition;

import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStream;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamReader;

import java.util.function.BiFunction;

/**
 * Adapts a {@code (minId, maxId) -> ItemReader} factory (i.e.
 * {@link com.bank.lending.batch.core.BusinessChunkJob#reader}) into a reader the framework can
 * hand to Spring Batch once per worker-step execution, without relying on {@code @StepScope}
 * CGLIB proxying (which only works cleanly for {@code @Bean}-declared readers, not the
 * dynamically-assembled steps {@code BatchJobRegistry} builds at runtime).
 *
 * <p>Each partition gets its own {@code StepExecution}, so {@code beforeStep} — called once per
 * partition, on that partition's own thread — is exactly the right place to read {@code minValue}
 * / {@code maxValue} out of the partition's {@link ExecutionContext} (written there by
 * {@link ColumnRangePartitioner}) and lazily construct the real, partition-scoped delegate reader.
 * For a non-partitioned step (concurrency &lt;= 1), minValue/maxValue are simply absent and both
 * bounds resolve to {@code null}, which {@code AbstractAccountRangeChunkJob}'s reader treats as
 * "no range restriction".
 */
public class PartitionRangeItemReader<I> implements ItemStreamReader<I>, StepExecutionListener {

    private final BiFunction<Long, Long, org.springframework.batch.item.ItemReader<I>> readerFactory;
    private org.springframework.batch.item.ItemReader<I> delegate;

    public PartitionRangeItemReader(BiFunction<Long, Long, org.springframework.batch.item.ItemReader<I>> readerFactory) {
        this.readerFactory = readerFactory;
    }

    @Override
    public void beforeStep(StepExecution stepExecution) {
        ExecutionContext ctx = stepExecution.getExecutionContext();
        Long minId = ctx.containsKey("minValue") ? ctx.getLong("minValue") : null;
        Long maxId = ctx.containsKey("maxValue") ? ctx.getLong("maxValue") : null;
        this.delegate = readerFactory.apply(minId, maxId);
    }

    @Override
    public I read() throws Exception {
        if (delegate == null) {
            // beforeStep wasn't invoked (e.g. reader used outside a managed step, such as a unit
            // test) — fall back to the unrestricted range rather than NPE.
            delegate = readerFactory.apply(null, null);
        }
        return delegate.read();
    }

    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        if (delegate instanceof ItemStream stream) {
            stream.open(executionContext);
        }
    }

    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        if (delegate instanceof ItemStream stream) {
            stream.update(executionContext);
        }
    }

    @Override
    public void close() throws ItemStreamException {
        if (delegate instanceof ItemStream stream) {
            stream.close();
        }
    }
}
