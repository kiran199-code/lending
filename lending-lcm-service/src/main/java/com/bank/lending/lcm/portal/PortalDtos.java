package com.bank.lending.lcm.portal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Customer-facing read models. Deliberately leaner than the internal views: no maker, checker, actors or internal ids. */
public final class PortalDtos {
    private PortalDtos() {}

    public record PortalFacility(String facilityRef, String facilityType, String parentRef, String productCode, String currencyCode,
                                 String status, boolean revolving, BigDecimal sanctionedAmount, BigDecimal utilised, BigDecimal available,
                                 LocalDate expiryDate) {}

    public record PortalCollateral(String collateralRef, String collateralType, String description, String status,
                                   BigDecimal latestValue, LocalDate valuationDate, BigDecimal lendableValue) {}

    public record PortalExposure(long customerId, List<PortalFacility> facilities, List<PortalCollateral> collateral) {}
}
