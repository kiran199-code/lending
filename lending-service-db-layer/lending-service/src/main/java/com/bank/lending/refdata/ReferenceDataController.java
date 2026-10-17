package com.bank.lending.refdata;

import com.bank.lending.refdata.ReferenceDtos.ActiveRequest;
import com.bank.lending.refdata.ReferenceDtos.BranchRequest;
import com.bank.lending.refdata.ReferenceDtos.BranchView;
import com.bank.lending.refdata.ReferenceDtos.CurrencyRequest;
import com.bank.lending.refdata.ReferenceDtos.CurrencyView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Tag(name = "Reference data", description = "Currencies and branches that accounts and accounting entries are posted in")
public class ReferenceDataController {

    private final ReferenceDataService service;

    public ReferenceDataController(ReferenceDataService service) {
        this.service = service;
    }

    @Operation(summary = "Currencies (INR out of the box); the base currency is the one the books are kept in")
    @GetMapping("/api/v1/reference/currencies")
    public List<CurrencyView> currencies() {
        return service.currencies();
    }

    @Operation(summary = "Add a currency. It is created inactive unless active=true; only the base currency can be active until foreign-currency accounting is enabled")
    @PostMapping("/api/v1/reference/currencies")
    @ResponseStatus(HttpStatus.CREATED)
    public CurrencyView addCurrency(@RequestBody CurrencyRequest request) {
        return service.addCurrency(request);
    }

    @Operation(summary = "Activate or deactivate a currency for new accounts (existing accounts are not affected)")
    @PutMapping("/api/v1/reference/currencies/{code}/active")
    public CurrencyView setCurrencyActive(@PathVariable String code, @RequestBody ActiveRequest request) {
        if (request == null || request.active() == null) {
            throw new IllegalArgumentException("active is required");
        }
        return service.setCurrencyActive(code, request.active());
    }

    @Operation(summary = "Branches (ONLINE out of the box)")
    @GetMapping("/api/v1/reference/branches")
    public List<BranchView> branches() {
        return service.branches();
    }

    @Operation(summary = "Add a branch (active unless active=false)")
    @PostMapping("/api/v1/reference/branches")
    @ResponseStatus(HttpStatus.CREATED)
    public BranchView addBranch(@RequestBody BranchRequest request) {
        return service.addBranch(request);
    }

    @Operation(summary = "Rename a branch or open/close it for new accounts (existing accounts are not affected)")
    @PutMapping("/api/v1/reference/branches/{code}")
    public BranchView updateBranch(@PathVariable String code, @RequestBody BranchRequest request) {
        return service.updateBranch(code, request == null ? null : request.branchName(), request == null ? null : request.active());
    }
}
