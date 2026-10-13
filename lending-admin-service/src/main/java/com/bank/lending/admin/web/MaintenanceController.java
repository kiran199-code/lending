package com.bank.lending.admin.web;

import com.bank.lending.admin.service.LendingMaintenanceClient;
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
@Tag(name = "Maintenance", description = "Start from scratch: clear all data and create the base products (done by lending-service)")
public class MaintenanceController {

    private final LendingMaintenanceClient client;

    public MaintenanceController(LendingMaintenanceClient client) {
        this.client = client;
    }

    @Operation(summary = "Whether clearing is enabled on lending-service, row counts, and which base products exist")
    @GetMapping("/status")
    public JsonNode status() {
        return client.status();
    }

    @Operation(summary = "Delete all accounts, their data and all loan products (irreversible)",
            description = "Body {\"confirm\":\"RESET\"} is required. lending-service refuses with 403 unless it runs with "
                    + "LENDING_RESET_ENABLED=true, and with 409 while end of day is running.")
    @PostMapping("/clear-data")
    public JsonNode clearData(@RequestBody(required = false) Map<String, String> body, Principal user) {
        if (body == null || !"RESET".equals(body.get("confirm"))) {
            throw new IllegalArgumentException("Type RESET to confirm clearing all data");
        }
        return client.clearData(user == null ? null : user.getName());
    }

    @Operation(summary = "Create the eleven base products (existing ones are left unchanged)")
    @PostMapping("/base-products")
    public JsonNode createBaseProducts() {
        return client.createBaseProducts();
    }
}
