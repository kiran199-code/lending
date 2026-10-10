package com.bank.lending.admin.web;

import com.bank.lending.admin.dto.AdminDtos.BatchOverview;
import com.bank.lending.admin.dto.AdminDtos.OutboxResponse;
import com.bank.lending.admin.repository.OpsRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
@Tag(name = "Operations", description = "Ledger outbox and batch job visibility")
public class OperationsController {

    private static final Logger log = LoggerFactory.getLogger(OperationsController.class);

    private final OpsRepository ops;

    public OperationsController(OpsRepository ops) {
        this.ops = ops;
    }

    @Operation(summary = "Ledger outbox entries with status counts")
    @GetMapping("/ledger-outbox")
    public OutboxResponse outbox(@RequestParam(required = false) String status,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "20") int size) {
        return new OutboxResponse(ops.outbox(status, page, size), ops.outboxCounts());
    }

    @Operation(summary = "Configured batch jobs and their recent executions (from the batch schema)")
    @GetMapping("/batch")
    public BatchOverview batch(@RequestParam(defaultValue = "30") int limit) {
        try {
            var jobs = ops.batchJobs();
            try {
                return new BatchOverview(jobs, ops.batchExecutions(limit), null);
            } catch (RuntimeException e) {
                log.warn("Batch execution tables not readable: {}", e.getMessage());
                return new BatchOverview(jobs, List.of(), "No execution history available yet.");
            }
        } catch (RuntimeException e) {
            log.warn("Batch schema not readable: {}", e.getMessage());
            return new BatchOverview(List.of(), List.of(),
                    "The batch schema is not available in this database (lending-batch-service has not run yet).");
        }
    }
}
