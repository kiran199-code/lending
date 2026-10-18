package com.bank.lending.lcm.bridge;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Payloads for loading existing lending data. The same shapes are produced by scripts/bridge_from_lending.sql. */
public final class BridgeDtos {
    private BridgeDtos() {}

    public record MortgageValuation(@NotNull LocalDate valuationDate, @NotNull BigDecimal valuationAmount, String valuerRef) {}

    public record MortgageItem(
            @NotNull Long mortgageId,
            @NotNull Long customerId,
            @NotBlank String propertyType,
            @NotBlank String propertyAddress,
            @NotNull LocalDate valuationDate,
            @NotNull BigDecimal valuationAmount,
            String valuerRef,
            String chargeStatus,
            String cersaiRegistrationRef,
            String legalVerificationStatus,
            String currencyCode,
            List<MortgageValuation> valuationHistory) {}

    public record MortgageImport(@NotEmpty @Valid List<MortgageItem> mortgages) {}

    public record SanctionItem(
            @NotNull Long sanctionRefId,
            @NotNull Long customerId,
            String productCode,
            @NotBlank String currencyCode,
            String branchCode,
            @NotNull @PositiveOrZero BigDecimal sanctionedAmount,
            LocalDate effectiveFrom,
            LocalDate expiryDate) {}

    public record SanctionImport(@NotEmpty @Valid List<SanctionItem> sanctions) {}

    public record PositionItem(
            @NotNull Long sanctionRefId,
            @NotNull Long accountId,
            @NotNull @PositiveOrZero BigDecimal drawnAmount,
            @NotNull @PositiveOrZero BigDecimal outstandingPrincipal) {}

    public record PositionImport(@NotEmpty @Valid List<PositionItem> positions) {}

    public record ItemResult(String key, String outcome, String ref, String message) {}

    public record BridgeResult(int created, int skipped, int failed, List<ItemResult> items) {}
}
