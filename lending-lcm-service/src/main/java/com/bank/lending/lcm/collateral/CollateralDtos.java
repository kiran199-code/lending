package com.bank.lending.lcm.collateral;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class CollateralDtos {
    private CollateralDtos() {}

    public record CreateCollateralRequest(
            @NotNull Long customerId,
            String collateralRef,
            @NotBlank String collateralType,
            @NotBlank String description,
            @NotBlank @Size(min = 3, max = 3) String currencyCode,
            @DecimalMin("0.00") @DecimalMax("99.99") BigDecimal haircutPct,
            @DecimalMin("0.01") @DecimalMax("100.00") BigDecimal maxLtvPct,
            @PositiveOrZero BigDecimal priorChargeAmount) {}

    public record ValuationRequest(
            @NotNull LocalDate valuationDate,
            @NotNull @Positive BigDecimal marketValue,
            @Positive BigDecimal forcedSaleValue,
            String valuerRef) {}

    public record LegalRequest(@NotBlank String status) {}

    public record ChargeRequest(String chargeRef, LocalDate registeredOn, @PositiveOrZero BigDecimal priorChargeAmount) {}

    public record InsuranceRequest(@NotNull LocalDate expiry) {}

    public record AllocationRequest(
            @NotBlank String facilityRef,
            @NotNull @DecimalMin("0.01") @DecimalMax("100.00") BigDecimal allocationPct) {}

    public record CollateralView(
            String collateralRef, long customerId, String collateralType, String description, String currencyCode, String status,
            String legalStatus, boolean chargeRegistered, String chargeRef, LocalDate chargeRegisteredOn, BigDecimal priorChargeAmount,
            LocalDate insuranceExpiry, BigDecimal haircutPct, BigDecimal maxLtvPct, Long lendingMortgageId, String source,
            ValuationView latestValuation, BigDecimal eligibleValue, BigDecimal lendableValue, List<String> unmetForActivation) {}

    public record ValuationView(
            long id, String collateralRef, LocalDate valuationDate, BigDecimal marketValue, BigDecimal forcedSaleValue,
            String valuerRef, String source, String status, String maker, String checker, OffsetDateTime createdAt) {}

    public record AllocationView(
            long id, String collateralRef, String facilityRef, BigDecimal allocationPct, String status, String createdBy,
            OffsetDateTime createdAt, OffsetDateTime releasedAt) {}

    public record DrawingPowerView(
            String facilityRef, LocalDate asOf, boolean dpGoverned, BigDecimal drawingPower,
            List<ValuationCalculator.Contribution> contributions) {}

    public record TypeConfigView(
            String typeCode, String description, BigDecimal defaultHaircutPct, BigDecimal maxLtvPct, int valuationValidityDays,
            boolean requiresCharge, boolean requiresInsurance) {}

    /** What backs a facility: collateral of the same customer that is ACTIVE and ACTIVE-allocated to the facility or a parent. */
    public record CoverItem(String collateralRef, String collateralType, String allocatedTo, BigDecimal allocationPct, BigDecimal lendableValue,
                            BigDecimal coveredValue) {}

    public record CoverView(String facilityRef, long customerId, boolean covered, BigDecimal coveredValue, List<CoverItem> items) {}
}
