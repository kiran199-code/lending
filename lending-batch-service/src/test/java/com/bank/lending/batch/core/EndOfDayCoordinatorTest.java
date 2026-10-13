package com.bank.lending.batch.core;

import com.bank.lending.batch.core.eod.EndOfDayCoordinator;
import com.bank.lending.batch.core.eod.EodProgress;
import com.bank.lending.batch.lending.LendingServiceClient;
import com.bank.lending.batch.lending.LendingServiceException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The coordinator's two guards: no start on a stale date, and a status read that never waits for lending-service. */
class EndOfDayCoordinatorTest {

    // The job gateway is never reached in these cases, so null is fine.
    private static EndOfDayCoordinator coordinator(LendingServiceClient lending) {
        return new EndOfDayCoordinator(lending, null);
    }

    @Test
    void aStartForADateThatIsNotTheCurrentBusinessDateIsRefusedAndNothingStarts() {
        EndOfDayServiceTest.FakeLending lending = new EndOfDayServiceTest.FakeLending();
        EndOfDayCoordinator c = coordinator(lending);

        try {
            c.trigger("batch-admin", lending.date.plusDays(1));
            assertTrue(false, "expected a stale-date refusal");
        } catch (EndOfDayCoordinator.StaleBusinessDateException e) {
            assertTrue(e.getMessage().contains(lending.date.toString()));
        }

        assertFalse(c.isRunning());
        assertEquals(EodProgress.State.IDLE, c.progress().state());
        assertFalse(lending.calls.stream().anyMatch(x -> x.startsWith("start")));
    }

    @Test
    void theStatusDateIsReadInTheBackgroundAndNeverBlocksOnLendingService() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        EndOfDayServiceTest.FakeLending lending = new EndOfDayServiceTest.FakeLending() {
            @Override
            public BusinessDateInfo businessDateOnce() {
                try {
                    release.await(10, TimeUnit.SECONDS); // a lending-service that is still waking up
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return businessDate();
            }
        };
        EndOfDayCoordinator c = coordinator(lending);

        long t0 = System.nanoTime();
        assertNull(c.businessDate()); // returns at once with nothing yet
        assertTrue(System.nanoTime() - t0 < TimeUnit.SECONDS.toNanos(2));

        release.countDown();
        LendingServiceClient.BusinessDateInfo seen = null;
        for (int i = 0; i < 100 && seen == null; i++) {
            Thread.sleep(50);
            seen = c.businessDate();
        }
        assertNotNull(seen);
        assertEquals(lending.date.toString(), seen.businessDate());
    }

    @Test
    void whenLendingServiceCannotBeReachedTheStatusDateStaysEmptyInsteadOfFailing() throws Exception {
        EndOfDayServiceTest.FakeLending lending = new EndOfDayServiceTest.FakeLending() {
            @Override
            public BusinessDateInfo businessDateOnce() {
                throw new LendingServiceException("GET /api/v1/business-date -> HTTP 502", 502, null);
            }
        };
        EndOfDayCoordinator c = coordinator(lending);

        assertNull(c.businessDate());
        Thread.sleep(200);
        assertNull(c.businessDate());
    }
}
