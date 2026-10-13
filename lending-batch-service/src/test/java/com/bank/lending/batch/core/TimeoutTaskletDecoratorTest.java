package com.bank.lending.batch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.repeat.RepeatStatus;

/** The timeout guard runs the tasklet on another thread; the thread-local job context must follow it. */
class TimeoutTaskletDecoratorTest {

    @AfterEach
    void clean() {
        BatchJobContext.clear();
    }

    @Test
    void theTaskletSeesTheBusinessDateAndParamsOfTheCallingStep() throws Exception {
        AtomicReference<LocalDate> seenDate = new AtomicReference<>();
        AtomicReference<String> seenParams = new AtomicReference<>();
        BatchJobContext.set(LocalDate.of(2026, 11, 1), "{\"maxRounds\":3}");

        RepeatStatus status = new TimeoutTaskletDecorator((contribution, chunkContext) -> {
            seenDate.set(BatchJobContext.businessDate());
            seenParams.set(BatchJobContext.jobParamsJson());
            return RepeatStatus.FINISHED;
        }, 1).execute(null, null);

        assertEquals(RepeatStatus.FINISHED, status);
        assertEquals(LocalDate.of(2026, 11, 1), seenDate.get());
        assertEquals("{\"maxRounds\":3}", seenParams.get());
        // the calling thread's own context is untouched
        assertEquals(LocalDate.of(2026, 11, 1), BatchJobContext.businessDate());
    }

    @Test
    void aFailureInsideTheTaskletIsRethrownAsItself() {
        BatchJobContext.set(LocalDate.of(2026, 11, 1), "{}");
        assertThrows(IllegalStateException.class, () -> new TimeoutTaskletDecorator((c, ctx) -> {
            throw new IllegalStateException("boom");
        }, 1).execute(null, null));
    }
}
