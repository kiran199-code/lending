package com.bank.lending.batch.web;

import com.bank.lending.batch.core.eod.EndOfDayCoordinator;
import com.bank.lending.batch.core.eod.EodRunSummary;
import com.bank.lending.batch.lending.LendingServiceClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Run end of day on demand and see where it stands. Secured with the rest of /api/v1/batch-admin
 * (HTTP Basic, ROLE_BATCH_ADMIN). Starting a run returns immediately (202); poll {@code /eod/status}.
 */
@RestController
@RequestMapping("/api/v1/batch-admin/eod")
@RequiredArgsConstructor
public class EndOfDayController {

    private final EndOfDayCoordinator coordinator;

    /** Starts end of day for the current business date, or resumes a failed one. 409 if a run is already in progress. */
    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> run() {
        boolean started = coordinator.trigger("batch-admin");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("started", started);
        body.put("message", started ? "End of day started; poll /api/v1/batch-admin/eod/status"
                : "End of day is already running");
        return ResponseEntity.status(started ? HttpStatus.ACCEPTED : HttpStatus.CONFLICT).body(body);
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        LendingServiceClient.BusinessDateInfo date = coordinator.businessDate();
        EodRunSummary last = coordinator.lastRun();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("running", coordinator.isRunning());
        body.put("businessDate", date);
        body.put("lastRun", last);
        return body;
    }
}
