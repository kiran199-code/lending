package com.bank.lending.lcm.facility;

import java.math.BigDecimal;
import java.time.LocalDate;

public record Facility(
        long id,
        String facilityRef,
        long customerId,
        Long parentId,
        String facilityType,
        String productCode,
        String currencyCode,
        String branchCode,
        boolean revolving,
        boolean dpGoverned,
        BigDecimal sanctionedAmount,
        BigDecimal maxSingleAmount,
        FacilityStatus status,
        LocalDate effectiveFrom,
        LocalDate expiryDate,
        Long sanctionRefId,
        String source,
        BigDecimal utilised,
        BigDecimal drawnTotal,
        BigDecimal reserved,
        String createdBy,
        String approvedBy,
        long version) {
}
