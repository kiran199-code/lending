package com.bank.lending.lcm.collateral;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Row types for the collateral tables. */
public final class CollateralModels {
    private CollateralModels() {}

    public record TypeConfig(String typeCode, String description, BigDecimal defaultHaircutPct, BigDecimal maxLtvPct,
                             int validityDays, boolean requiresCharge, boolean requiresInsurance, boolean active) {}

    public record Collateral(long id, String collateralRef, long customerId, String collateralType, String description,
                             String currencyCode, String status, String legalStatus, boolean chargeRegistered, String chargeRef,
                             LocalDate chargeRegisteredOn, BigDecimal priorChargeAmount, LocalDate insuranceExpiry,
                             BigDecimal haircutOverride, BigDecimal ltvOverride, Long lendingMortgageId, String source, String createdBy) {}

    public record Valuation(long id, long collateralId, LocalDate valuationDate, BigDecimal marketValue, BigDecimal forcedSaleValue,
                            String valuerRef, String source, String status, String maker, String checker) {
        /** The value lending is based on: forced-sale value when the valuer gave one, else market value. */
        public BigDecimal baseValue() {
            return forcedSaleValue != null ? forcedSaleValue : marketValue;
        }
    }

    public record Allocation(long id, long collateralId, long facilityId, BigDecimal allocationPct, String status) {}

    /** One ACTIVE allocation of ACTIVE collateral that covers a facility (directly or through a parent). */
    public record CoverRow(String collateralRef, String facilityRef, BigDecimal allocationPct) {}
}
