package com.bank.lending.admin.web;

import com.bank.lending.admin.common.UpstreamException;
import com.bank.lending.admin.service.BatchConfigClient;
import com.bank.lending.admin.service.BusinessDateSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * End of day from the console: start or resume a run on lending-batch-service and follow it. The run itself,
 * and its progress, live in the batch service; this controller forwards the two calls and adds the business
 * date as the shared database shows it, so the date is still visible when the batch service is asleep.
 */
@RestController
@RequestMapping("/api/admin/eod")
@Tag(name = "End of day", description = "Start, resume and follow the end-of-day batch (run by lending-batch-service)")
public class EodController {

    private final BatchConfigClient batch;
    private final BusinessDateSource businessDate;

    public EodController(BatchConfigClient batch, BusinessDateSource businessDate) {
        this.batch = batch;
        this.businessDate = businessDate;
    }

    @Operation(summary = "Business date, whether end of day is running, per-job progress and the last run",
            description = "Safe to poll. When the batch service cannot be reached the answer still carries the "
                    + "database's business date, with reachable=false and the reason in error.")
    @GetMapping("/status")
    public JsonNode status() {
        ObjectNode out = JsonNodes.object();
        out.put("configured", batch.enabled());
        out.put("databaseBusinessDate", businessDate.today().toString());
        out.put("databaseStatus", businessDate.status());
        if (!batch.enabled()) {
            out.put("reachable", false);
            out.put("error", "The admin service does not know the batch service address or password. "
                    + "Set BATCH_SERVICE_URL and BATCH_ADMIN_PASSWORD on it.");
            return out;
        }
        try {
            JsonNode s = batch.eodStatus();
            out.put("reachable", true);
            out.set("running", s.path("running"));
            out.set("businessDate", s.path("businessDate"));
            out.set("progress", s.path("progress"));
            out.set("lastRun", s.path("lastRun"));
        } catch (UpstreamException e) {
            out.put("reachable", false);
            out.put("error", e.getMessage());
        }
        return out;
    }

    @Operation(summary = "Start end of day for the current business date, or resume a failed run",
            description = "Body {\"expectedBusinessDate\":\"yyyy-MM-dd\"} is required: it is the date the operator saw on "
                    + "screen. If the business date has moved since, nothing starts (409), so a stale screen cannot close the wrong day. "
                    + "The batch service answers 202 when started and 409 when a run is already in progress.")
    @PostMapping("/run")
    public JsonNode run(@RequestBody(required = false) Map<String, String> body) {
        String expected = body == null ? null : body.get("expectedBusinessDate");
        if (expected == null || expected.isBlank()) {
            throw new IllegalArgumentException("expectedBusinessDate is required: the business date shown on the screen");
        }
        LocalDate expectedDate;
        try {
            expectedDate = LocalDate.parse(expected.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("expectedBusinessDate must look like 2026-11-02");
        }
        // The batch service reads the date from lending-service itself and refuses (409) if it is not the one on screen.
        return batch.eodRun(expectedDate.toString());
    }
}
