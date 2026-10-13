package com.bank.lending.batch.core.partition;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamReader;

/** Regression test: parallel partitions share one reader instance and must each keep their own, opened delegate. */
class PartitionRangeItemReaderTest {

    /** Returns its range label once, and fails like AbstractCursorItemReader if read before open(). */
    private static final class RangeReader implements ItemStreamReader<String> {
        private final String label;
        private boolean open;
        private boolean done;

        RangeReader(Long min, Long max) {
            this.label = min + "-" + max;
        }

        @Override
        public void open(ExecutionContext executionContext) {
            open = true;
        }

        @Override
        public String read() {
            if (!open) {
                throw new IllegalStateException("Reader must be open before it can be read.");
            }
            if (done) {
                return null;
            }
            done = true;
            return label;
        }

        @Override
        public void close() {
            open = false;
        }
    }

    private static StepExecution partition(long min, long max) {
        StepExecution se = new StepExecution("worker", new JobExecution(1L));
        se.getExecutionContext().putLong("minValue", min);
        se.getExecutionContext().putLong("maxValue", max);
        return se;
    }

    @Test
    void twoPartitionsThatStartAtTheSameTimeEachReadTheirOwnRange() throws Exception {
        PartitionRangeItemReader<String> shared = new PartitionRangeItemReader<>(RangeReader::new);
        CountDownLatch bothStarted = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> a = pool.submit(() -> run(shared, partition(1, 10), bothStarted));
            Future<String> b = pool.submit(() -> run(shared, partition(11, 20), bothStarted));
            assertEquals("1-10", a.get(10, TimeUnit.SECONDS));
            assertEquals("11-20", b.get(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    /** The order the step runs on its own thread: beforeStep, open, read, close; the latch forces the two beforeSteps to overlap. */
    private static String run(PartitionRangeItemReader<String> reader, StepExecution se, CountDownLatch bothStarted) throws Exception {
        reader.beforeStep(se);
        bothStarted.countDown();
        bothStarted.await(5, TimeUnit.SECONDS);
        reader.open(se.getExecutionContext());
        try {
            return reader.read();
        } finally {
            reader.close();
        }
    }
}
