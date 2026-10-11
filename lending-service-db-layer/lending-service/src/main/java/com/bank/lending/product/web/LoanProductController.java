package com.bank.lending.product.web;

import com.bank.lending.product.domain.LoanProduct;
import com.bank.lending.product.dto.LoanProductRequest;
import com.bank.lending.product.dto.LoanProductResponse;
import com.bank.lending.product.service.LoanProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/v1/loan-products")
@RequiredArgsConstructor
@Tag(name = "Loan Products", description = "Product master: default terms and limits applied when an account is created from a product")
public class LoanProductController {

    private final LoanProductService productService;

    @Operation(summary = "List loan products (optionally only ACTIVE or INACTIVE)")
    @GetMapping
    public List<LoanProductResponse> list(@RequestParam(required = false) LoanProduct.Status status) {
        return productService.list(status).stream().map(LoanProductResponse::from).toList();
    }

    @Operation(summary = "Get a loan product by its code")
    @GetMapping("/{productCode}")
    public LoanProductResponse get(@PathVariable String productCode) {
        return LoanProductResponse.from(productService.getByCode(productCode));
    }

    @Operation(summary = "Create a loan product",
            description = "Leave a min/max empty for no limit. Floating products need a benchmarkRateRef.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
            mediaType = "application/json",
            examples = {
                    @ExampleObject(name = "Step-up personal loan, quarterly allowed", value = """
                    {
                      "productCode": "SU02", "productName": "Step-up Personal Loan 2",
                      "accountType": "UNSECURED", "rateType": "FIXED", "scheduleType": "STEP_UP_EMI",
                      "dayCountConvention": "THIRTY_E_360",
                      "defaultInterestRate": 12.0, "stepUpPercent": 5, "maxStepUpPercent": 15,
                      "repaymentFrequency": "MONTHLY", "allowedRepaymentFrequencies": ["MONTHLY", "QUARTERLY"],
                      "minTenureMonths": 12, "maxTenureMonths": 84
                    }
                    """),
                    @ExampleObject(name = "Floating personal loan", value = """
                    {
                      "productCode": "PL02", "productName": "Floating Personal Loan",
                      "accountType": "UNSECURED", "rateType": "FLOATING", "dayCountConvention": "ACT_365",
                      "benchmarkRateRef": "REPO", "defaultInterestRate": 9.5, "defaultSpread": 3.0,
                      "minInterestRate": 8.0, "maxInterestRate": 14.0, "minSpread": 1.0, "maxSpread": 5.0,
                      "minSanctionedAmount": 50000, "maxSanctionedAmount": 3000000,
                      "minTenureMonths": 6, "maxTenureMonths": 84
                    }
                    """)}))
    @PostMapping
    public ResponseEntity<LoanProductResponse> create(@Valid @RequestBody LoanProductRequest request,
                                                      UriComponentsBuilder uriBuilder) {
        LoanProduct product = productService.create(request);
        URI location = uriBuilder.path("/api/v1/loan-products/{code}").buildAndExpand(product.getProductCode()).toUri();
        return ResponseEntity.created(location).body(LoanProductResponse.from(product));
    }

    @Operation(summary = "Replace a loan product's terms (affects accounts created afterwards only)",
            description = "Send the full definition. The product code in the URL identifies the product and cannot change. "
                    + "Set status to INACTIVE to stop new accounts being opened on it.")
    @PutMapping("/{productCode}")
    public LoanProductResponse update(@PathVariable String productCode, @Valid @RequestBody LoanProductRequest request) {
        return LoanProductResponse.from(productService.update(productCode, request));
    }
}
