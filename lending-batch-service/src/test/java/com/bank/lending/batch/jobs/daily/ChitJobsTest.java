package com.bank.lending.batch.jobs.daily;

import com.bank.lending.batch.chit.ChitServiceClient;
import com.bank.lending.batch.chit.ChitServiceClient.GroupFailure;
import com.bank.lending.batch.chit.ChitServiceClient.RunResult;
import com.bank.lending.batch.chit.ChitServiceSettings;
import com.bank.lending.batch.core.BatchJobContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.repeat.RepeatStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The three chit jobs against a hand-written fake client. */
class ChitJobsTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 6);

    /** Records the calls and answers with a canned result. */
    static class FakeChit extends ChitServiceClient {
        final boolean configured;
        final RunResult result;
        final List<String> calls = new ArrayList<>();

        FakeChit(boolean configured, RunResult result) {
            super(new ChitServiceSettings(configured ? "http://chit" : "", null, null, null, null, 1, null), new ObjectMapper());
            this.configured = configured;
            this.result = result;
        }

        @Override
        public boolean configured() {
            return configured;
        }

        @Override
        public RunResult runDemands(LocalDate d) {
            calls.add("demands " + d);
            return result;
        }

        @Override
        public RunResult runLateFees(LocalDate d) {
            calls.add("fees " + d);
            return result;
        }

        @Override
        public RunResult runDefaultCheck(LocalDate d) {
            calls.add("defaults " + d);
            return result;
        }
    }

    private static RunResult clean() {
        return new RunResult("X", DAY.toString(), 2, 0, Map.of("demandsRaised", new BigDecimal("8")), List.of());
    }

    private static RunResult broken() {
        return new RunResult("X", DAY.toString(), 1, 1, Map.of(), List.of(new GroupFailure("G-9", "DATA_CONFLICT", "changed")));
    }

    @AfterEach
    void clear() {
        BatchJobContext.clear();
    }

    private static void within(String paramsJson) {
        BatchJobContext.set(DAY, paramsJson);
    }

    @Test
    void jobNamesMatchTheSeededRows() {
        FakeChit f = new FakeChit(true, clean());
        assertEquals("CHIT_DEMAND_RAISE_DAILY", new ChitDemandRaiseBatchJob(f).getJobName());
        assertEquals("CHIT_LATE_FEE_DAILY", new ChitLateFeeBatchJob(f).getJobName());
        assertEquals("CHIT_DEFAULT_CHECK_DAILY", new ChitDefaultCheckBatchJob(f).getJobName());
    }

    @Test
    void eachJobCallsItsOwnEndpointForTheBusinessDate() throws Exception {
        FakeChit f = new FakeChit(true, clean());
        within("{}");
        assertEquals(RepeatStatus.FINISHED, new ChitDemandRaiseBatchJob(f).buildTasklet().execute(null, null));
        assertEquals(RepeatStatus.FINISHED, new ChitLateFeeBatchJob(f).buildTasklet().execute(null, null));
        assertEquals(RepeatStatus.FINISHED, new ChitDefaultCheckBatchJob(f).buildTasklet().execute(null, null));
        assertEquals(List.of("demands 2026-11-06", "fees 2026-11-06", "defaults 2026-11-06"), f.calls);
    }

    @Test
    void aFailedGroupFailsTheJobAndNamesIt() {
        FakeChit f = new FakeChit(true, broken());
        within("{}");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new ChitLateFeeBatchJob(f).buildTasklet().execute(null, null));
        assertTrue(e.getMessage().contains("G-9"), e.getMessage());
        assertTrue(e.getMessage().contains("CHIT_LATE_FEE_DAILY"), e.getMessage());
    }

    @Test
    void withNoChitServiceTheJobSkipsAndCallsNothing() throws Exception {
        FakeChit f = new FakeChit(false, clean());
        within("{}");
        assertEquals(RepeatStatus.FINISHED, new ChitDemandRaiseBatchJob(f).buildTasklet().execute(null, null));
        assertTrue(f.calls.isEmpty());
    }

    @Test
    void theRequireParameterTurnsTheSkipIntoAFailure() {
        FakeChit f = new FakeChit(false, clean());
        within("{\"requireChitService\":true}");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new ChitDemandRaiseBatchJob(f).buildTasklet().execute(null, null));
        assertTrue(e.getMessage().contains("CHIT_SERVICE_URL"));
        assertTrue(f.calls.isEmpty());
    }
}
