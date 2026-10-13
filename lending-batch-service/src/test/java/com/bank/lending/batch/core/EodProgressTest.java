package com.bank.lending.batch.core;

import com.bank.lending.batch.core.eod.EndOfDayService;
import com.bank.lending.batch.core.eod.EodJobResult;
import com.bank.lending.batch.core.eod.EodProgress;
import com.bank.lending.batch.core.eod.EodRunSummary;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The live view of an end-of-day run, fed by the real {@link EndOfDayService}. */
class EodProgressTest {

    @Test
    void beforeAnyRunTheStateIsIdle() {
        EodProgress.View v = new EodProgress().snapshot();
        assertEquals(EodProgress.State.IDLE, v.state());
        assertEquals(0, v.total());
    }

    @Test
    void beginShowsRunningBeforeTheJobsAreKnown() {
        EodProgress p = new EodProgress();
        p.begin("batch-admin");
        EodProgress.View v = p.snapshot();
        assertEquals(EodProgress.State.RUNNING, v.state());
        assertEquals("batch-admin", v.actor());
        assertNull(v.businessDate());
        assertTrue(v.jobs().isEmpty());
    }

    @Test
    void aCleanRunEndsAdvancedWithEveryJobCompleted() {
        EndOfDayServiceTest.FakeLending lending = new EndOfDayServiceTest.FakeLending();
        EndOfDayServiceTest.FakeJobs jobs = new EndOfDayServiceTest.FakeJobs(List.of("A", "B", "C"), Map.of());
        EodProgress p = new EodProgress();
        p.begin("t");

        new EndOfDayService(lending, jobs).run("t", p);

        EodProgress.View v = p.snapshot();
        assertEquals(EodProgress.State.ADVANCED, v.state());
        assertEquals("2026-10-12", v.businessDate());
        assertEquals("2026-10-13", v.newBusinessDate());
        assertEquals(3, v.total());
        assertEquals(3, v.done());
        assertNull(v.currentJob());
        assertEquals(List.of("COMPLETED", "COMPLETED", "COMPLETED"), v.jobs().stream().map(EodProgress.JobRow::status).toList());
        assertTrue(v.message().contains("2026-10-13"));
    }

    @Test
    void theViewChangesWhileJobsRun() {
        EndOfDayServiceTest.FakeLending lending = new EndOfDayServiceTest.FakeLending();
        List<EodProgress.View> seen = new ArrayList<>();
        EodProgress p = new EodProgress();
        EndOfDayServiceTest.FakeJobs jobs = new EndOfDayServiceTest.FakeJobs(List.of("A", "B"), Map.of()) {
            @Override
            public EodJobResult run(String jobName, LocalDate businessDate) {
                seen.add(p.snapshot()); // what a status call would see while this job is running
                return super.run(jobName, businessDate);
            }
        };

        new EndOfDayService(lending, jobs).run("t", p);

        assertEquals("A", seen.get(0).currentJob());
        assertEquals(0, seen.get(0).done());
        assertEquals(List.of("RUNNING", "PENDING"), seen.get(0).jobs().stream().map(EodProgress.JobRow::status).toList());
        assertEquals("B", seen.get(1).currentJob());
        assertEquals(1, seen.get(1).done());
        assertEquals(EodProgress.State.RUNNING, seen.get(1).state());
    }

    @Test
    void aFailedJobEndsFailedAndLaterJobsStayPending() {
        EndOfDayServiceTest.FakeLending lending = new EndOfDayServiceTest.FakeLending();
        EndOfDayServiceTest.FakeJobs jobs = new EndOfDayServiceTest.FakeJobs(List.of("A", "B", "C"), Map.of("B", "FAILED"));
        EodProgress p = new EodProgress();

        EodRunSummary r = new EndOfDayService(lending, jobs).run("t", p);

        EodProgress.View v = p.snapshot();
        assertEquals(EodRunSummary.Outcome.FAILED, r.outcome());
        assertEquals(EodProgress.State.FAILED, v.state());
        assertEquals(List.of("COMPLETED", "FAILED", "PENDING"), v.jobs().stream().map(EodProgress.JobRow::status).toList());
        assertEquals(1, v.done());
        assertTrue(v.message().contains("B"));
        assertNull(v.newBusinessDate());
    }

    @Test
    void aResumedRunMarksAlreadyCompletedJobsSkippedAndSaysItIsAResume() {
        EndOfDayServiceTest.FakeLending lending = new EndOfDayServiceTest.FakeLending();
        lending.status = "EOD_RUNNING";
        EndOfDayServiceTest.FakeJobs jobs = new EndOfDayServiceTest.FakeJobs(List.of("A", "B"), Map.of());
        jobs.alreadyDone.add("A");
        EodProgress p = new EodProgress();

        new EndOfDayService(lending, jobs).run("t", p);

        EodProgress.View v = p.snapshot();
        assertTrue(v.resumed());
        assertEquals(List.of("SKIPPED", "COMPLETED"), v.jobs().stream().map(EodProgress.JobRow::status).toList());
        assertEquals(2, v.done());
    }

    @Test
    void aRunThatCannotStartEndsFailedWithTheReason() {
        EndOfDayServiceTest.FakeLending lending = new EndOfDayServiceTest.FakeLending();
        lending.failStart = true;
        EodProgress p = new EodProgress();
        p.begin("t");

        new EndOfDayService(lending, new EndOfDayServiceTest.FakeJobs(List.of("A"), Map.of())).run("t", p);

        EodProgress.View v = p.snapshot();
        assertEquals(EodProgress.State.FAILED, v.state());
        assertTrue(v.message().contains("409"));
        assertTrue(v.jobs().isEmpty());
    }
}
