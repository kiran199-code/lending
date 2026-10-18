package com.bank.lending.lcm.utilisation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public final class UtilisationDtos {
    private UtilisationDtos() {}

    public record CheckRequest(
            @NotBlank String facilityRef,
            @NotNull @Positive BigDecimal amount,
            @NotBlank String currency,
            String branchCode,
            LocalDate businessDate,
            Long customerId) {
        /** Without a customer (older callers): no customer comparison. */
        public CheckRequest(String facilityRef, BigDecimal amount, String currency, String branchCode, LocalDate businessDate) {
            this(facilityRef, amount, currency, branchCode, businessDate, null);
        }
    }

    public record CheckResponse(
            boolean allowed, String rejectionCode, String message, String limitingFacilityRef, BigDecimal effectiveAvailable) {}

    public record ReserveRequest(
            @NotBlank String facilityRef,
            @NotNull @Positive BigDecimal amount,
            @NotBlank String currency,
            String branchCode,
            @NotBlank String referenceType,
            @NotBlank String referenceId,
            Integer ttlMinutes,
            Boolean enforce,
            LocalDate businessDate) {}

    public record ConfirmRequest(@Positive BigDecimal amount) {}

    public record ReleaseRequest(String reason) {}

    public record RepayRequest(
            @NotBlank String referenceType,
            @NotBlank String referenceId,
            @NotNull @Positive BigDecimal amount) {}

    public record CloseRequest(
            @NotBlank String referenceType,
            @NotBlank String referenceId,
            String reason) {}

    public record ReservationView(
            String reservationRef, String facilityRef, BigDecimal amount, BigDecimal confirmedAmount, String currency,
            String referenceType, String referenceId, String status, boolean enforced, String wouldReject,
            boolean lateConfirm, OffsetDateTime expiresAt, OffsetDateTime createdAt, boolean replayed) {

        public ReservationView asReplay() {
            return new ReservationView(reservationRef, facilityRef, amount, confirmedAmount, currency, referenceType, referenceId,
                    status, enforced, wouldReject, lateConfirm, expiresAt, createdAt, true);
        }
    }

    public record PositionView(
            String facilityRef, String referenceType, String referenceId, BigDecimal drawn, BigDecimal repaid,
            BigDecimal writtenOff, BigDecimal outstanding, String status) {}

    public record UtilisationResult(
            String operation, String facilityRef, String referenceType, String referenceId, BigDecimal amount,
            BigDecimal outstanding, BigDecimal facilityUtilised, BigDecimal facilityAvailable, boolean replayed) {}
}
