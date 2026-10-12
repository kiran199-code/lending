package com.bank.lending.admin.web;

import com.bank.lending.admin.service.BusinessDateSource;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/admin")
@Tag(name = "Business date", description = "The date every screen treats as today (owned by lending-service)")
public class BusinessDateController {

    /**
     * @param businessDate  the current business date
     * @param status        OPEN, EOD_RUNNING, or null when it could not be read
     * @param fromLending   false when the wall-clock fallback was used (lending-service has not created the table yet)
     */
    public record BusinessDateView(LocalDate businessDate, String status, boolean fromLending) {
    }

    private final BusinessDateSource businessDate;

    public BusinessDateController(BusinessDateSource businessDate) {
        this.businessDate = businessDate;
    }

    @Operation(summary = "Current business date and whether end-of-day is running")
    @GetMapping("/business-date")
    public BusinessDateView current() {
        String status = businessDate.status();
        return new BusinessDateView(businessDate.today(), status, status != null);
    }
}
