package com.bank.lending.batch.maintenance;

import com.bank.lending.batch.maintenance.MaintenanceDtos.ClearRequest;
import com.bank.lending.batch.maintenance.MaintenanceDtos.ClearResult;
import com.bank.lending.batch.maintenance.MaintenanceDtos.StatusView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Clear the job execution history in a non-production environment. Secured with the rest of /api/v1/batch-admin
 * (HTTP Basic, ROLE_BATCH_ADMIN); the admin console's Maintenance screen is the caller.
 */
@RestController
@RequestMapping("/api/v1/batch-admin/maintenance")
public class MaintenanceController {

    private final MaintenanceService service;

    public MaintenanceController(MaintenanceService service) {
        this.service = service;
    }

    /** Whether clearing is enabled, whether something is running, and the row counts. */
    @GetMapping("/status")
    public StatusView status() {
        return service.status();
    }

    /**
     * Irreversible. 403 RESET_DISABLED unless BATCH_RESET_ENABLED=true; 400 without {"confirm":"RESET"}; 409 BATCH_BUSY
     * while end of day or a job is running. The job settings and Quartz schedule are kept.
     */
    @PostMapping("/clear-data")
    public ClearResult clear(@RequestBody(required = false) ClearRequest request,
                             @RequestHeader(value = "X-Actor", required = false) String actor) {
        return service.clearAll(request == null ? null : request.confirm(), actor == null || actor.isBlank() ? "batch-admin" : actor);
    }
}
