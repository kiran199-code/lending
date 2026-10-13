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
    /**
     * One instance of this class is shared by every partition's worker-step execution of a job (the registry builds a single
     * worker step), and the partitions run in parallel on different threads. The delegate therefore must not be a plain field:
     * partition B's {@code beforeStep} would replace partition A's delegate before A's {@code open()} ran, so A opened B's reader
     * and then read from its own, never-opened one ("ReaderNotOpenException: Reader must be open before it can be read").
     * Each partition runs {@code beforeStep}, {@code open}, {@code read}/{@code update} and {@code close} on its own thread,
     * so a thread-local holds exactly that partition's delegate.
     */
    private final ThreadLocal<org.springframework.batch.item.ItemReader<I>> delegateHolder = new ThreadLocal<>();

    public PartitionRangeItemReader(BiFunction<Long, Long, org.springframework.batch.item.ItemReader<I>> readerFactory) {
        this.readerFactory = readerFactory;
    }

    @Override
    public void beforeStep(StepExecution stepExecution) {
        ExecutionContext ctx = stepExecution.getExecutionContext();
        Long minId = ctx.containsKey("minValue") ? ctx.getLong("minValue") : null;
        Long maxId = ctx.containsKey("maxValue") ? ctx.getLong("maxValue") : null;
        delegateHolder.set(readerFactory.apply(minId, maxId));
    }

    @Override
    public I read() throws Exception {
        org.springframework.batch.item.ItemReader<I> delegate = delegateHolder.get();
        if (delegate == null) {
            // beforeStep wasn't invoked (e.g. reader used outside a managed step, such as a unit
            // test) — fall back to the unrestricted range rather than NPE.
            delegate = readerFactory.apply(null, null);
            delegateHolder.set(delegate);
        }
        return delegate.read();
    }

    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        if (delegateHolder.get() instanceof ItemStream stream) {
            stream.open(executionContext);
        }
    }

    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        if (delegateHolder.get() instanceof ItemStream stream) {
            stream.update(executionContext);
        }
    }

    @Override
    public void close() throws ItemStreamException {
        try {
            if (delegateHolder.get() instanceof ItemStream stream) {
                stream.close();
            }
        } finally {
            delegateHolder.remove();
        }
    }
}
