package com.bank.lending.admin.web;

import com.bank.lending.admin.service.LendingMaintenanceClient;
import com.bank.lending.admin.service.MaintenanceService;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/maintenance")
@Tag(name = "Maintenance", description = "Start from scratch: clear all loan and limit/collateral data, reset the business date to today, create the base products (done by lending-service and lending-lcm-service)")
public class MaintenanceController {

    private final LendingMaintenanceClient client;
    private final MaintenanceService service;

    public MaintenanceController(LendingMaintenanceClient client, MaintenanceService service) {
        this.client = client;
        this.service = service;
    }

    @Operation(summary = "Whether clearing is enabled on lending-service and LCM, row counts of both, the business date, and which base products exist")
    @GetMapping("/status")
    public JsonNode status() {
        return service.status();
    }

    @Operation(summary = "Delete all accounts, their data, all loan products and all limit and collateral data, and set the business date to today (irreversible)",
            description = "Body {\"confirm\":\"RESET\"} is required. lending-service refuses with 403 unless it runs with "
                    + "LENDING_RESET_ENABLED=true, and with 409 while end of day is running; lending-lcm-service needs "
                    + "LCM_RESET_ENABLED=true. LCM is checked first, so a refusal there deletes nothing.")
    @PostMapping("/clear-data")
    public JsonNode clearData(@RequestBody(required = false) Map<String, String> body, Principal user) {
        if (body == null || !"RESET".equals(body.get("confirm"))) {
            throw new IllegalArgumentException("Type RESET to confirm clearing all data");
        }
        return service.clearData(user == null ? null : user.getName());
    }

    @Operation(summary = "Create the eleven base products (existing ones are left unchanged)")
    @PostMapping("/base-products")
    public JsonNode createBaseProducts() {
        return client.createBaseProducts();
    }
}
