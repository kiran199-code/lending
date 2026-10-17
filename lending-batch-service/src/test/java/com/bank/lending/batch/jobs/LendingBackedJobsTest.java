package com.bank.lending.batch.jobs;

import com.bank.lending.batch.core.BatchJobContext;
import com.bank.lending.batch.jobs.daily.DemandRaiseBatchJob;
import com.bank.lending.batch.jobs.daily.DpdUpdateBatchJob;
import com.bank.lending.batch.jobs.daily.GlPostingReconciliationBatchJob;
import com.bank.lending.batch.jobs.daily.InterestAccrualBatchJob;
import com.bank.lending.batch.jobs.daily.NpaClassificationCheckBatchJob;
import com.bank.lending.batch.jobs.daily.RateResetCheckDailyBatchJob;
import com.bank.lending.batch.jobs.monthly.MonthEndGlCloseBatchJob;
import com.bank.lending.batch.jobs.monthly.RateResetMonthlyBatchJob;
import com.bank.lending.batch.jobs.periodic.ForeclosurePrepaymentProcessingBatchJob;
import com.bank.lending.batch.jobs.periodic.QuarterlyRateResetBatchJob;
import com.bank.lending.batch.lending.LendingServiceClient;
import com.bank.lending.batch.lending.LendingServiceException;
import com.bank.lending.batch.lending.LendingServiceSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spring-free: each lending-backed job's processor/tasklet against a hand-written fake client. */
class LendingBackedJobsTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 12);

    /** Records every call; outcomes are scripted by the fields below. */
    static class FakeLending extends LendingServiceClient {
        final List<String> calls = new ArrayList<>();
        String accrualOutcome = "POSTED";
        boolean statusChanged = true;
        String rateOutcome = "RESET";
        int demandsCreated = 2;
        final Deque<Integer> pendingCounts = new ArrayDeque<>();
        boolean failAll;

        FakeLending() {
            super(new LendingServiceSettings("http://unused", null, null, null, null, 1, Duration.ofMillis(1)),
                    new ObjectMapper());
        }

        @Override
        public AccrualResult accrueInterest(long accountId, LocalDate businessDate) {
            calls.add("accrue " + accountId + " " + businessDate);
            if (failAll) {
                throw new LendingServiceException("down", 503, null);
            }
            return new AccrualResult(accountId, accrualOutcome, BigDecimal.ONE, null);
        }

        @Override
        public DemandRaiseResult raiseDemands(long accountId, LocalDate businessDate) {
            calls.add("demands " + accountId + " " + businessDate);
            if (failAll) {
                throw new LendingServiceException("down", 503, null);
            }
            return new DemandRaiseResult(accountId, demandsCreated);
        }

        @Override
        public DelinquencyResult classifyDelinquency(long accountId, LocalDate asOfDate) {
            calls.add("classify " + accountId + " " + asOfDate);
            return new DelinquencyResult(accountId, 40, "ACTIVE", "NPA_SMA1", statusChanged, false);
        }

        @Override
        public RateResetResult resetRate(long accountId, LocalDate businessDate, String benchmarkRateRef) {
            calls.add("reset " + accountId + " " + businessDate + " " + benchmarkRateRef);
            return new RateResetResult(accountId, rateOutcome, BigDecimal.ONE, BigDecimal.TEN, null);
        }

        @Override
        public void pushLedgerOutbox(int batchSize) {
            calls.add("push " + batchSize);
        }

        @Override
        public int countPendingLedgerEntries(int limit) {
            calls.add("pending " + limit);
            return pendingCounts.isEmpty() ? 0 : pendingCounts.poll();
        }

        @Override
        public JsonNode foreclose(long accountId, LocalDate foreclosureDate, String channel, String idempotencyKey) {
            calls.add("foreclose " + accountId + " " + foreclosureDate + " " + channel + " " + idempotencyKey);
            return new ObjectMapper().createObjectNode();
        }
    }

    private static void context(String paramsJson) {
        BatchJobContext.set(DATE, paramsJson);
    }

    @Test
    void accrualPostsPerAccountForTheBusinessDateAndFiltersNonPostings() throws Exception {
        FakeLending lending = new FakeLending();
        InterestAccrualBatchJob job = new InterestAccrualBatchJob(null, lending);
        context("{}");
        assertEquals(Long.valueOf(42L), job.processor().process(42L));
        lending.accrualOutcome = "ALREADY_POSTED";
        assertEquals(null, job.processor().process(43L));
        assertEquals(List.of("accrue 42 2026-10-12", "accrue 43 2026-10-12"), lending.calls);
    }

    @Test
    void demandRaiseCallsLendingPerAccountAndOnlyPassesOnAccountsThatGotANewDemand() throws Exception {
        FakeLending lending = new FakeLending();
        DemandRaiseBatchJob job = new DemandRaiseBatchJob(null, lending);
        context("{}");
        assertEquals(Long.valueOf(9L), job.processor().process(9L));
        lending.demandsCreated = 0;
        assertEquals(null, job.processor().process(10L));
        assertEquals(List.of("demands 9 2026-10-12", "demands 10 2026-10-12"), lending.calls);
        assertEquals("DEMAND_RAISE_DAILY", job.getJobName());
    }

    @Test
    void aLendingServiceFailureSurfacesAsASkippableIllegalStateException() {
        FakeLending lending = new FakeLending();
        lending.failAll = true;
        InterestAccrualBatchJob job = new InterestAccrualBatchJob(null, lending);
        context("{}");
        assertThrows(IllegalStateException.class, () -> job.processor().process(1L));
    }

    @Test
    void dpdAndNpaJobsClassifyAndOnlyPassOnAccountsThatChangedBucket() throws Exception {
        FakeLending lending = new FakeLending();
        context("{}");
        assertEquals(Long.valueOf(5L), new DpdUpdateBatchJob(null, lending).processor().process(5L));
        assertEquals(Long.valueOf(6L), new NpaClassificationCheckBatchJob(null, lending).processor().process(6L));
        lending.statusChanged = false;
        assertEquals(null, new DpdUpdateBatchJob(null, lending).processor().process(7L));
        assertEquals(List.of("classify 5 2026-10-12", "classify 6 2026-10-12", "classify 7 2026-10-12"), lending.calls);
    }

    @Test
    void rateResetJobsPassTheirConfiguredBenchmark() throws Exception {
        FakeLending lending = new FakeLending();
        context("{\"benchmarkRateRef\": \"MCLR_1Y\"}");
        assertEquals(Long.valueOf(1L), new RateResetMonthlyBatchJob(null, lending).processor().process(1L));
        assertEquals(Long.valueOf(2L), new RateResetCheckDailyBatchJob(null, lending).processor().process(2L));
        assertEquals(Long.valueOf(3L), new QuarterlyRateResetBatchJob(null, lending).processor().process(3L));
        assertEquals("reset 1 2026-10-12 MCLR_1Y", lending.calls.get(0));
        lending.rateOutcome = "UNCHANGED";
        assertEquals(null, new RateResetMonthlyBatchJob(null, lending).processor().process(4L));
    }

    @Test
    void rateResetWithoutABenchmarkFailsInsteadOfRepricingEveryFloatingAccount() {
        FakeLending lending = new FakeLending();
        context("{}");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new RateResetCheckDailyBatchJob(null, lending).processor().process(1L));
        assertTrue(e.getMessage().contains("benchmarkRateRef"));
        assertTrue(lending.calls.isEmpty());
    }

    @Test
    void glPostingPushesUntilTheOutboxIsEmpty() throws Exception {
        FakeLending lending = new FakeLending();
        lending.pendingCounts.addAll(List.of(200, 80, 0)); // before, after round 1, after round 2
        context("{}");
        new GlPostingReconciliationBatchJob(lending).buildTasklet().execute(null, null);
        assertEquals(List.of("pending 200", "push 200", "pending 200", "push 200", "pending 200"), lending.calls);
    }

    @Test
    void glPostingFailsWhenEntriesStayPending() {
        FakeLending lending = new FakeLending();
        for (int i = 0; i < 10; i++) {
            lending.pendingCounts.add(5);
        }
        context("{\"batchSize\": 10, \"maxRounds\": 3}");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new GlPostingReconciliationBatchJob(lending).buildTasklet().execute(null, null));
        assertTrue(e.getMessage().contains("3 push round"));
        assertEquals(3, lending.calls.stream().filter(c -> c.startsWith("push")).count());
    }

    @Test
    void glPostingDoesNothingWhenNothingIsPending() throws Exception {
        FakeLending lending = new FakeLending();
        context("{}");
        new GlPostingReconciliationBatchJob(lending).buildTasklet().execute(null, null);
        assertEquals(List.of("pending 200"), lending.calls);
    }

    @Test
    void monthEndCloseOnlyChecksAndBlocksOnPendingEntries() throws Exception {
        FakeLending lending = new FakeLending();
        context("{}");
        new MonthEndGlCloseBatchJob(lending).buildTasklet().execute(null, null);
        lending.pendingCounts.add(1);
        assertThrows(IllegalStateException.class, () -> new MonthEndGlCloseBatchJob(lending).buildTasklet().execute(null, null));
        assertTrue(lending.calls.stream().noneMatch(c -> c.startsWith("push")));
    }

    @Test
    void foreclosureUsesTheRequestParamsAndADeterministicIdempotencyKey() throws Exception {
        FakeLending lending = new FakeLending();
        context("{\"accountId\": 77, \"foreclosureDate\": \"2026-10-15\", \"channel\": \"ONLINE\"}");
        new ForeclosurePrepaymentProcessingBatchJob(lending).buildTasklet().execute(null, null);
        assertEquals(List.of("foreclose 77 2026-10-15 ONLINE FORECLOSURE-77-2026-10-15"), lending.calls);
    }

    @Test
    void foreclosureDefaultsToTheBusinessDateAndManualChannelAndRequiresAnAccount() throws Exception {
        FakeLending lending = new FakeLending();
        context("{\"accountId\": \"78\"}");
        new ForeclosurePrepaymentProcessingBatchJob(lending).buildTasklet().execute(null, null);
        assertEquals(List.of("foreclose 78 2026-10-12 MANUAL FORECLOSURE-78-2026-10-12"), lending.calls);

        context("{}");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new ForeclosurePrepaymentProcessingBatchJob(lending).buildTasklet().execute(null, null));
        assertTrue(e.getMessage().contains("accountId"));
    }
}
