package com.bank.lending.common.web;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(info = @Info(
        title = "Lending Service API",
        version = "v1",
        description = "Retail Unsecured and Mortgage loan account lifecycle: creation, disbursement, "
                + "amortization scheduling, interest accrual, EMI/part-payment/foreclosure servicing, "
                + "mortgage collateral, and ledger outbox operations. Phase 1 — no auth layer yet."
))
public class OpenApiConfig {
}
