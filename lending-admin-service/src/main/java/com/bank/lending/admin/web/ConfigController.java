package com.bank.lending.admin.web;

import com.bank.lending.admin.dto.AdminDtos.ApplyResult;
import com.bank.lending.admin.dto.AdminDtos.BatchConfigResponse;
import com.bank.lending.admin.dto.AdminDtos.BatchJobConfigUpdate;
import com.bank.lending.admin.dto.AdminDtos.ConfigOverview;
import com.bank.lending.admin.service.ConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/config")
@Tag(name = "Configuration", description = "Settings of all services; batch job settings can be edited")
public class ConfigController {

    private final ConfigService service;

    public ConfigController(ConfigService service) {
        this.service = service;
    }

    @Operation(summary = "Settings reference for all services plus the fixed business rules (secrets are never returned)")
    @GetMapping
    public ConfigOverview overview() {
        return service.overview();
    }

    @Operation(summary = "All batch job configurations, read live from the batch schema")
    @GetMapping("/batch-jobs")
    public BatchConfigResponse batchJobs() {
        return service.batchJobs();
    }

    @Operation(summary = "Edit one batch job (saved by lending-batch-service after its own validation)",
            description = "Send only the fields to change. expectedVersion guards against concurrent edits (409). "
                    + "apply=true also reloads the batch scheduler so the change takes effect.")
    @PutMapping("/batch-jobs/{jobName}")
    public ApplyResult update(@PathVariable String jobName, @RequestBody BatchJobConfigUpdate update,
                              @RequestParam(defaultValue = "true") boolean apply) {
        return service.updateBatchJob(jobName, update, apply);
    }

    @Operation(summary = "Apply saved batch job changes: reload the batch scheduler")
    @PostMapping("/batch-jobs/apply")
    public Map<String, String> apply() {
        return Map.of("message", service.applyBatchChanges());
    }
}
