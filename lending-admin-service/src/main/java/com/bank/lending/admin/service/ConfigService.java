package com.bank.lending.admin.service;

import com.bank.lending.admin.common.NotFoundException;
import com.bank.lending.admin.dto.AdminDtos.ApplyResult;
import com.bank.lending.admin.dto.AdminDtos.BatchConfigResponse;
import com.bank.lending.admin.dto.AdminDtos.BatchJobConfigRow;
import com.bank.lending.admin.dto.AdminDtos.BatchJobConfigUpdate;
import com.bank.lending.admin.dto.AdminDtos.ConfigOverview;
import com.bank.lending.admin.repository.OpsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ConfigService {

    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);

    private final ConfigCatalog catalog;
    private final OpsRepository ops;
    private final BatchConfigClient batch;

    public ConfigService(ConfigCatalog catalog, OpsRepository ops, BatchConfigClient batch) {
        this.catalog = catalog;
        this.ops = ops;
        this.batch = batch;
    }

    public ConfigOverview overview() {
        return new ConfigOverview(catalog.services(), catalog.fixedRules(), batch.enabled());
    }

    public BatchConfigResponse batchJobs() {
        String note = batch.enabled() ? null
                : "Viewing only: set BATCH_SERVICE_URL and BATCH_ADMIN_PASSWORD on this service to enable editing.";
        try {
            return new BatchConfigResponse(ops.batchJobConfigs(), batch.enabled(), note);
        } catch (RuntimeException e) {
            log.warn("Batch schema not readable: {}", e.getMessage());
            return new BatchConfigResponse(List.of(), false,
                    "The batch schema is not available in this database (lending-batch-service has not run yet).");
        }
    }

    /**
     * Saves the edit through the batch service and, when {@code apply} is true, reloads its scheduler so
     * the change takes effect. If the save works but the reload fails, the result says so rather than failing.
     */
    public ApplyResult updateBatchJob(String jobName, BatchJobConfigUpdate update, boolean apply) {
        if (ops.batchJobConfig(jobName).isEmpty()) {
            throw new NotFoundException("No such batch job: " + jobName);
        }
        batch.update(jobName, update);
        boolean applied = false;
        String message = "Saved. The scheduler keeps the old settings until you apply (reload) them.";
        if (apply) {
            try {
                message = "Saved and applied: " + batch.reload();
                applied = true;
            } catch (RuntimeException e) {
                message = "Saved, but applying failed: " + e.getMessage() + " Use Apply changes to retry.";
            }
        }
        BatchJobConfigRow row = ops.batchJobConfig(jobName).orElseThrow(() -> new NotFoundException("No such batch job: " + jobName));
        return new ApplyResult(row, applied, message);
    }

    public String applyBatchChanges() {
        return batch.reload();
    }
}
