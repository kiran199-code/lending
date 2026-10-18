package com.bank.lending.lcm.facility;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class FacilityDtos {
    private FacilityDtos() {}

    public record CreateFacilityRequest(
            @NotNull Long customerId,
            String facilityRef,
            @NotBlank String facilityType,
            String parentRef,
            String productCode,
            @NotBlank @Size(min = 3, max = 3) String currencyCode,
            String branchCode,
            boolean revolving,
            boolean dpGoverned,
            @NotNull @PositiveOrZero BigDecimal sanctionedAmount,
            @Positive BigDecimal maxSingleAmount,
            LocalDate effectiveFrom,
            LocalDate expiryDate,
            Long sanctionRefId) {}

    public record ProposeChangeRequest(
            @NotBlank String changeType,
            BigDecimal newAmount,
            LocalDate newExpiry,
            String reason) {}

    public record DecisionRequest(String note) {}

    public record ExcessRequest(
            @NotNull @Positive BigDecimal amount,
            @NotNull LocalDate validFrom,
            @NotNull LocalDate validTo,
            @NotBlank String reason) {}

    public record FacilityView(
            String facilityRef, long customerId, String parentRef, String facilityType, String productCode,
            String currencyCode, String branchCode, boolean revolving, boolean dpGoverned,
            BigDecimal sanctionedAmount, BigDecimal maxSingleAmount, String status,
            LocalDate effectiveFrom, LocalDate expiryDate, Long sanctionRefId, String source,
            BigDecimal utilised, BigDecimal drawnTotal, BigDecimal reserved,
            String createdBy, String approvedBy) {}

    public record ChangeView(
            long id, String facilityRef, String changeType, String payload, String reason, String status,
            String maker, String checker, String decisionNote, OffsetDateTime createdAt, OffsetDateTime decidedAt) {}

    public record ExcessView(
            long id, String facilityRef, BigDecimal amount, LocalDate validFrom, LocalDate validTo, String reason,
            String status, String maker, String checker, OffsetDateTime createdAt, OffsetDateTime decidedAt) {}

    public record AvailabilityView(
            String facilityRef, String status, String currencyCode, boolean revolving, boolean dpGoverned,
            BigDecimal sanctionedAmount, BigDecimal activeExcess, BigDecimal drawingPower,
            BigDecimal effectiveLimit, BigDecimal utilised, BigDecimal drawnTotal, BigDecimal reserved,
            BigDecimal consumed, BigDecimal available, BigDecimal effectiveAvailable, boolean overLimit) {}

    public record TreeNode(AvailabilityView facility, List<TreeNode> children) {}

    public record TxnView(
            long id, String facilityRef, String originFacilityRef, String txnType, BigDecimal amount,
            BigDecimal dReserved, BigDecimal dUtilised, BigDecimal dDrawnTotal,
            String referenceType, String referenceId, String actor, OffsetDateTime createdAt) {}
}
