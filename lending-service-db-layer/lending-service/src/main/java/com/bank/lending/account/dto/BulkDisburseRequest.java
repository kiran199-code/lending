package com.bank.lending.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Schema(description = "Several accounts to disburse in one call. Each is processed independently.")
public record BulkDisburseRequest(
        @Schema(description = "First EMI due date used for every item that does not give its own.", example = "2026-11-05")
        LocalDate defaultFirstEmiDueDate,

        @Schema(description = "Accounts to disburse. Between 1 and " + BulkLimits.MAX_ITEMS + " items.")
        @NotEmpty @Size(max = BulkLimits.MAX_ITEMS)
        List<@NotNull @Valid Item> items
) {

    public record Item(
            @Schema(description = "Internal account ID, as returned when the account was created.", example = "1")
            @NotNull Long accountId,

            @Schema(description = "Amount to disburse. Leave out to disburse the full sanctioned amount. "
                    + "A smaller amount is a part-disbursement. It cannot exceed the sanctioned amount.",
                    example = "250000.00")
            @Positive BigDecimal disbursedAmount,

            @Schema(description = "First EMI due date for this account. Leave out to use defaultFirstEmiDueDate.",
                    example = "2026-11-05")
            LocalDate firstEmiDueDate
    ) {
    }
}
