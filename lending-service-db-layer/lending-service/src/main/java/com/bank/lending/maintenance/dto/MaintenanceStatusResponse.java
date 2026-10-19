package com.bank.lending.maintenance.dto;

import com.bank.lending.businessdate.domain.BusinessDateStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record MaintenanceStatusResponse(
        boolean resetEnabled,
        LocalDate businessDate,
        BusinessDateStatus businessDateStatus,
        Map<String, Long> rowCounts,
        List<BaseProductStatus> baseProducts
) {
    public record BaseProductStatus(String productCode, String productName, boolean exists) {
    }
}
