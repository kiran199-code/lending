package com.bank.lending.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(description = "Several loan accounts to create in one call. Each is processed independently.")
public record BulkCreateLoanAccountsRequest(
        @Schema(description = "Accounts to create, in the same shape as the single-create request. "
                + "Between 1 and " + BulkLimits.MAX_ITEMS + " items.")
        @NotEmpty @Size(max = BulkLimits.MAX_ITEMS)
        List<@NotNull @Valid CreateLoanAccountRequest> accounts
) {
}
