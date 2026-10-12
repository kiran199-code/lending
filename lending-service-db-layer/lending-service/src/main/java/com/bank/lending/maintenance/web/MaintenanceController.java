package com.bank.lending.maintenance.web;

import com.bank.lending.maintenance.dto.BaseProductsResponse;
import com.bank.lending.maintenance.dto.ClearDataRequest;
import com.bank.lending.maintenance.dto.ClearDataResponse;
import com.bank.lending.maintenance.dto.MaintenanceStatusResponse;
import com.bank.lending.maintenance.service.MaintenanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/maintenance")
@RequiredArgsConstructor
@Tag(name = "Maintenance", description = "Start from scratch in a non-production environment: clear all data, create the base products")
public class MaintenanceController {

    private final MaintenanceService maintenanceService;

    @Operation(summary = "Whether clearing is enabled, the row counts, and which base products exist")
    @GetMapping("/status")
    public MaintenanceStatusResponse status() {
        return maintenanceService.status();
    }

    @Operation(summary = "Delete all accounts, their data and all loan products",
            description = "Irreversible. Refused (403) unless LENDING_RESET_ENABLED=true, (400) without {\"confirm\":\"RESET\"}, "
                    + "and (409) while end of day is running. The business date, holidays, benchmark rates and the batch "
                    + "service's tables are kept.")
    @PostMapping("/clear-data")
    public ClearDataResponse clearData(@RequestBody(required = false) ClearDataRequest request,
                                       @RequestHeader(value = "X-Actor", required = false) String actor) {
        return maintenanceService.clearAllData(request == null ? null : request.confirm(), actor);
    }

    @Operation(summary = "Create the six base products (PL01, BL01, EL01, GL01, AL01, HL01)",
            description = "Products that already exist are left unchanged, so it is safe to call repeatedly.")
    @PostMapping("/base-products")
    public BaseProductsResponse createBaseProducts() {
        return maintenanceService.createBaseProducts();
    }
}
