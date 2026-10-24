package com.bank.lending.e2e.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bank.lending.e2e.http.ApiResult;
import com.bank.lending.e2e.http.Http;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class EodRunTest {

    private static ApiResult response(int status, String json) throws IOException {
        JsonNode body = json == null ? null : Http.MAPPER.readTree(json);
        return new ApiResult(status, body, json, 1, null, "POST", "/api/v1/batch-admin/eod/run");
    }

    @Test
    void advancedRunIsReportedAsAdvancedWithItsJobs() throws IOException {
        JsonNode last = Http.MAPPER.readTree("{\"businessDate\":\"2026-11-01\",\"outcome\":\"ADVANCED\",\"newBusinessDate\":\"2026-11-02\","
                + "\"jobs\":[{\"jobName\":\"INTEREST_ACCRUAL_DAILY\",\"status\":\"COMPLETED\"}],\"error\":null}");
        BatchClient.EodRun run = new BatchClient.EodRun(response(202, "{\"started\":true}"), last, false, 5);

        assertTrue(run.started());
        assertTrue(run.advanced());
        assertEquals("ADVANCED", run.outcome());
        assertEquals(1, run.jobs().size());
    }

    @Test
    void failedRunKeepsItsErrorAndIsNotAdvanced() throws IOException {
        JsonNode last = Http.MAPPER.readTree("{\"businessDate\":\"2026-11-01\",\"outcome\":\"FAILED\",\"jobs\":[],\"error\":\"Job X ended FAILED\"}");
        BatchClient.EodRun run = new BatchClient.EodRun(response(202, "{}"), last, false, 5);

        assertFalse(run.advanced());
        assertEquals("FAILED", run.outcome());
        assertEquals("Job X ended FAILED", run.error());
    }

    @Test
    void rejectedStartTimeoutAndMissingResultAreDistinguished() throws IOException {
        assertEquals("REJECTED(409)", new BatchClient.EodRun(response(409, "{\"started\":false}"), null, false, 5).outcome());
        assertEquals("TIMED_OUT", new BatchClient.EodRun(response(202, "{}"), null, true, 5).outcome());
        assertEquals("NO_RESULT", new BatchClient.EodRun(response(202, "{}"), null, false, 5).outcome());
        assertTrue(new BatchClient.EodRun(response(202, "{}"), null, false, 5).jobs().isEmpty());
        assertEquals("", new BatchClient.EodRun(response(202, "{}"), null, false, 5).error());
    }
}
