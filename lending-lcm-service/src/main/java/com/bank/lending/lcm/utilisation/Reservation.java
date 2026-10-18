package com.bank.lending.lcm.utilisation;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record Reservation(
        long id,
        String reservationRef,
        long facilityId,
        BigDecimal amount,
        BigDecimal confirmedAmount,
        String currencyCode,
        String referenceType,
        String referenceId,
        String status,
        boolean enforced,
        String wouldReject,
        boolean lateConfirm,
        OffsetDateTime expiresAt,
        String createdBy,
        OffsetDateTime createdAt) {
}
