package com.bank.lending.e2e.client;

import com.bank.lending.e2e.http.ApiResult;
import com.bank.lending.e2e.http.Http;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Set;

/**
 * Drives the REAL batch service through its operator API (the same path an operator uses for a backdated
 * run). It triggers, then POLLS the execution record until a terminal status - it never sleeps for a fixed
 * time and never assumes success.
 */
public final class BatchClient {

    private static final Set<String> TERMINAL = Set.of("COMPLETED", "FAILED", "STOPPED", "ABANDONED");

    private final Http http;
    private final Duration pollTimeout;
    private final Duration pollInterval;

    public BatchClient(Http http, Duration pollTimeout, Duration pollInterval) {
        this.http = http;
        this.pollTimeout = pollTimeout;
        this.pollInterval = pollInterval;
    }

    public ApiResult listJobs() {
        return http.get("/api/v1/batch-admin/jobs");
    }

    public ApiResult statusToday() {
        return http.get("/api/v1/batch-admin/status/today");
    }

    public ApiResult executions(String job, int limit) {
        return http.get("/api/v1/batch-admin/jobs/" + job + "/executions?limit=" + limit);
    }

    public ApiResult errors(String job, int limit) {
        return http.get("/api/v1/batch-admin/jobs/" + job + "/errors?limit=" + limit);
    }

    /** Result of trigger + poll. {@code triggerCall} is the raw trigger response (rejections are data). */
    public record Run(String job, LocalDate businessDate, ApiResult triggerCall, JsonNode finalExecution,
                      boolean timedOut, long elapsedMs) {

        public boolean triggered() {
            return triggerCall.ok();
        }

        public String status() {
            if (finalExecution != null && finalExecution.hasNonNull("status")) {
                return finalExecution.get("status").asText();
            }
            return triggerCall.ok() ? "UNKNOWN" : "REJECTED(" + triggerCall.status() + ")";
        }

        public long readCount() {
            return finalExecution == null ? -1 : finalExecution.path("readCount").asLong(-1);
        }

        public long writeCount() {
            return finalExecution == null ? -1 : finalExecution.path("writeCount").asLong(-1);
        }

        public long skipCount() {
            return finalExecution == null ? -1 : finalExecution.path("skipCount").asLong(-1);
        }

        public Long executionId() {
            return finalExecution != null && finalExecution.hasNonNull("jobExecutionId") ? finalExecution.get("jobExecutionId").asLong() : null;
        }
    }

    public Run triggerAndWait(String job, LocalDate businessDate) {
        long t0 = System.nanoTime();
        ObjectNode body = Http.MAPPER.createObjectNode();
        body.put("businessDate", businessDate.toString());
        ApiResult trig = http.post("/api/v1/batch-admin/jobs/" + job + "/trigger", body);
        if (!trig.ok() || !trig.body().hasNonNull("jobExecutionId")) {
            return new Run(job, businessDate, trig, null, false, (System.nanoTime() - t0) / 1_000_000);
        }
        JsonNode current = trig.body();
        long execId = current.get("jobExecutionId").asLong();
        long deadline = System.nanoTime() + pollTimeout.toNanos();
        boolean timedOut = false;
        while (!TERMINAL.contains(current.path("status").asText(""))) {
            if (System.nanoTime() > deadline) {
                timedOut = true;
                break;
            }
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            ApiResult list = executions(job, 10);
            if (list.ok() && list.body().isArray()) {
                for (JsonNode e : list.body()) {
                    if (e.path("jobExecutionId").asLong(-1) == execId) {
                        current = e;
                    }
                }
            }
        }
        return new Run(job, businessDate, trig, current, timedOut, (System.nanoTime() - t0) / 1_000_000);
    }
}
