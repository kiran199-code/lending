package com.bank.lending.lcm.portal;

import com.bank.lending.lcm.collateral.CollateralDtos.CollateralView;
import com.bank.lending.lcm.collateral.CollateralService;
import com.bank.lending.lcm.common.LcmException;
import com.bank.lending.lcm.facility.FacilityDtos.AvailabilityView;
import com.bank.lending.lcm.facility.FacilityDtos.FacilityView;
import com.bank.lending.lcm.facility.FacilityService;
import com.bank.lending.lcm.portal.PortalDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Read-only views for the customer self-service UI. The customer BFF must pass the customerId of the signed-in
 * customer (never a value from the browser). A facility that belongs to another customer is reported as not found.
 */
@RestController
@RequestMapping("/api/v1/lcm/portal")
@Tag(name = "Portal", description = "Customer-facing read models (limits, availability, collateral)")
public class PortalController {

    private static final Set<String> VISIBLE_FACILITY = Set.of("ACTIVE", "SUSPENDED", "FROZEN", "EXPIRED");
    private static final Set<String> VISIBLE_COLLATERAL = Set.of("ACTIVE", "SUSPENDED");

    private final FacilityService facilities;
    private final CollateralService collateral;

    public PortalController(FacilityService facilities, CollateralService collateral) {
        this.facilities = facilities;
        this.collateral = collateral;
    }

    @GetMapping("/customers/{customerId}/exposure")
    @Transactional(readOnly = true)
    @Operation(summary = "Facilities with availability and pledged collateral of one customer (lcm.portal.read)")
    public PortalExposure exposure(@PathVariable("customerId") long customerId) {
        List<PortalFacility> fs = facilities.search(customerId, null, null).stream()
                .filter(f -> VISIBLE_FACILITY.contains(f.status()))
                .map(this::toPortal).collect(Collectors.toList());
        List<PortalCollateral> cs = collateral.search(customerId, null).stream()
                .filter(c -> VISIBLE_COLLATERAL.contains(c.status()))
                .map(this::toPortal).collect(Collectors.toList());
        return new PortalExposure(customerId, fs, cs);
    }

    @GetMapping("/customers/{customerId}/facilities/{ref}")
    @Transactional(readOnly = true)
    @Operation(summary = "One facility of the customer (lcm.portal.read)")
    public PortalFacility facility(@PathVariable("customerId") long customerId, @PathVariable("ref") String ref) {
        FacilityView f = facilities.get(ref);
        if (f.customerId() != customerId || !VISIBLE_FACILITY.contains(f.status())) {
            throw LcmException.notFound("Facility", ref);
        }
        return toPortal(f);
    }

    private PortalFacility toPortal(FacilityView f) {
        AvailabilityView a = facilities.availability(f.facilityRef(), null);
        return new PortalFacility(f.facilityRef(), f.facilityType(), f.parentRef(), f.productCode(), f.currencyCode(), f.status(),
                f.revolving(), f.sanctionedAmount(), f.utilised(), a.effectiveAvailable(), f.expiryDate());
    }

    private PortalCollateral toPortal(CollateralView c) {
        var v = c.latestValuation();
        return new PortalCollateral(c.collateralRef(), c.collateralType(), c.description(), c.status(),
                v == null ? null : (v.forcedSaleValue() != null ? v.forcedSaleValue() : v.marketValue()),
                v == null ? null : v.valuationDate(), c.lendableValue());
    }
}
