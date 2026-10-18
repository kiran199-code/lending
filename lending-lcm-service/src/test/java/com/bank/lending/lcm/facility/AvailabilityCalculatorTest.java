package com.bank.lending.lcm.facility;

import com.bank.lending.lcm.facility.AvailabilityCalculator.Level;
import com.bank.lending.lcm.facility.AvailabilityCalculator.Rejection;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AvailabilityCalculatorTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 18);

    static BigDecimal d(String v) { return new BigDecimal(v); }

    static Facility fac(String ref, String sanctioned, String utilised, String drawn, String reserved, boolean revolving, FacilityStatus status) {
        return new Facility(1, ref, 7, null, "OVERALL", null, "INR", null, revolving, false, d(sanctioned), null, status,
                null, LocalDate.of(2027, 12, 31), null, "MANUAL", d(utilised), d(drawn), d(reserved), "tester", null, 0);
    }

    static Facility withBranch(Facility f, String branch, String maxSingle) {
        return new Facility(f.id(), f.facilityRef(), f.customerId(), f.parentId(), f.facilityType(), f.productCode(), f.currencyCode(), branch,
                f.revolving(), f.dpGoverned(), f.sanctionedAmount(), maxSingle == null ? null : d(maxSingle), f.status(), f.effectiveFrom(),
                f.expiryDate(), f.sanctionRefId(), f.source(), f.utilised(), f.drawnTotal(), f.reserved(), f.createdBy(), f.approvedBy(), f.version());
    }

    static Facility dpGoverned(Facility f) {
        return new Facility(f.id(), f.facilityRef(), f.customerId(), f.parentId(), f.facilityType(), f.productCode(), f.currencyCode(), f.branchCode(),
                f.revolving(), true, f.sanctionedAmount(), f.maxSingleAmount(), f.status(), f.effectiveFrom(),
                f.expiryDate(), f.sanctionRefId(), f.source(), f.utilised(), f.drawnTotal(), f.reserved(), f.createdBy(), f.approvedBy(), f.version());
    }

    static Level lvl(Facility f) { return new Level(f, d("0"), null); }

    @Test
    void availableIsLimitMinusConsumedMinusReserved() {
        Level l = lvl(fac("F1", "1000.00", "300.00", "300.00", "100.00", true, FacilityStatus.ACTIVE));
        assertThat(AvailabilityCalculator.available(l)).isEqualByComparingTo("600.00");
    }

    @Test
    void revolvingRestoresLimitOnRepaymentButNonRevolvingDoesNot() {
        // 1000 drawn in total, 400 repaid: outstanding 600
        Level revolving = lvl(fac("R", "1000.00", "600.00", "1000.00", "0.00", true, FacilityStatus.ACTIVE));
        Level term = lvl(fac("T", "1000.00", "600.00", "1000.00", "0.00", false, FacilityStatus.ACTIVE));
        assertThat(AvailabilityCalculator.available(revolving)).isEqualByComparingTo("400.00");
        assertThat(AvailabilityCalculator.available(term)).isEqualByComparingTo("0.00");
    }

    @Test
    void activeExcessRaisesTheLimit() {
        Level l = new Level(fac("F1", "1000.00", "1000.00", "1000.00", "0.00", true, FacilityStatus.ACTIVE), d("250.00"), null);
        assertThat(AvailabilityCalculator.effectiveLimit(l)).isEqualByComparingTo("1250.00");
        assertThat(AvailabilityCalculator.available(l)).isEqualByComparingTo("250.00");
    }

    @Test
    void drawingPowerCapsTheLimitWhenGoverned() {
        Facility f = dpGoverned(fac("F1", "1000.00", "0.00", "0.00", "0.00", true, FacilityStatus.ACTIVE));
        assertThat(AvailabilityCalculator.effectiveLimit(new Level(f, d("0"), d("600.00")))).isEqualByComparingTo("600.00");
        assertThat(AvailabilityCalculator.effectiveLimit(new Level(f, d("0"), d("5000.00")))).isEqualByComparingTo("1000.00");
        assertThat(AvailabilityCalculator.effectiveLimit(new Level(f, d("0"), null))).isEqualByComparingTo("0.00");
    }

    @Test
    void effectiveAvailableIsTheTightestLevelOfTheChain() {
        Level child = lvl(fac("CHILD", "500.00", "100.00", "100.00", "0.00", true, FacilityStatus.ACTIVE));   // 400 free
        Level parent = lvl(fac("PARENT", "1000.00", "900.00", "900.00", "50.00", true, FacilityStatus.ACTIVE)); // 50 free
        assertThat(AvailabilityCalculator.effectiveAvailable(List.of(child, parent))).isEqualByComparingTo("50.00");
    }

    @Test
    void overLimitFacilityHasNegativeAvailableButNeverNegativeEffectiveAvailable() {
        Level l = lvl(fac("F1", "100.00", "130.00", "130.00", "0.00", true, FacilityStatus.ACTIVE));
        assertThat(AvailabilityCalculator.available(l)).isEqualByComparingTo("-30.00");
        assertThat(AvailabilityCalculator.effectiveAvailable(List.of(l))).isEqualByComparingTo("0.00");
    }

    @Test
    void drawdownExactlyUpToTheLimitIsAllowedOneRupeeMoreIsNot() {
        Level l = lvl(fac("F1", "1000.00", "400.00", "400.00", "0.00", true, FacilityStatus.ACTIVE));
        assertThat(AvailabilityCalculator.firstRejection(List.of(l), d("600.00"), "INR", null, TODAY)).isNull();
        Rejection r = AvailabilityCalculator.firstRejection(List.of(l), d("600.01"), "INR", null, TODAY);
        assertThat(r.code()).isEqualTo("LIMIT_EXCEEDED");
    }

    @Test
    void parentLimitRejectsEvenWhenTheSubLimitHasRoom() {
        Level child = lvl(fac("CHILD", "500.00", "0.00", "0.00", "0.00", true, FacilityStatus.ACTIVE));
        Level parent = lvl(fac("PARENT", "1000.00", "900.00", "900.00", "0.00", true, FacilityStatus.ACTIVE));
        Rejection r = AvailabilityCalculator.firstRejection(List.of(child, parent), d("200.00"), "INR", null, TODAY);
        assertThat(r.code()).isEqualTo("LIMIT_EXCEEDED");
        assertThat(r.facilityRef()).isEqualTo("PARENT");
    }

    @Test
    void statusExpiryCurrencyBranchAndSingleCapRejections() {
        Facility suspended = fac("S", "1000.00", "0.00", "0.00", "0.00", true, FacilityStatus.SUSPENDED);
        assertThat(AvailabilityCalculator.firstRejection(List.of(lvl(suspended)), d("1.00"), "INR", null, TODAY).code()).isEqualTo("FACILITY_NOT_ACTIVE");

        Facility active = fac("A", "1000.00", "0.00", "0.00", "0.00", true, FacilityStatus.ACTIVE);
        assertThat(AvailabilityCalculator.firstRejection(List.of(lvl(active)), d("1.00"), "INR", null, LocalDate.of(2028, 1, 1)).code()).isEqualTo("FACILITY_EXPIRED");
        assertThat(AvailabilityCalculator.firstRejection(List.of(lvl(active)), d("1.00"), "USD", null, TODAY).code()).isEqualTo("CURRENCY_MISMATCH");
        assertThat(AvailabilityCalculator.firstRejection(List.of(lvl(withBranch(active, "MUM01", null))), d("1.00"), "INR", "DEL01", TODAY).code()).isEqualTo("BRANCH_NOT_ALLOWED");
        assertThat(AvailabilityCalculator.firstRejection(List.of(lvl(withBranch(active, "MUM01", null))), d("1.00"), "INR", "mum01", TODAY)).isNull();
        assertThat(AvailabilityCalculator.firstRejection(List.of(lvl(withBranch(active, null, "50.00"))), d("50.01"), "INR", null, TODAY).code()).isEqualTo("EXCEEDS_SINGLE_LIMIT");
    }

    @Test
    void notYetEffectiveFacilityIsRejected() {
        Facility f = fac("F", "1000.00", "0.00", "0.00", "0.00", true, FacilityStatus.ACTIVE);
        Facility future = new Facility(f.id(), f.facilityRef(), f.customerId(), null, "OVERALL", null, "INR", null, true, false, f.sanctionedAmount(), null,
                FacilityStatus.ACTIVE, LocalDate.of(2026, 11, 1), f.expiryDate(), null, "MANUAL", f.utilised(), f.drawnTotal(), f.reserved(), "t", null, 0);
        assertThat(AvailabilityCalculator.firstRejection(List.of(lvl(future)), d("1.00"), "INR", null, TODAY).code()).isEqualTo("FACILITY_NOT_EFFECTIVE");
    }

    @Test
    void drawingPowerShortfallIsReportedWhenCollateralIsTheBindingConstraint() {
        Facility f = dpGoverned(fac("F", "1000.00", "500.00", "500.00", "0.00", true, FacilityStatus.ACTIVE));
        Rejection r = AvailabilityCalculator.firstRejection(List.of(new Level(f, d("0"), d("600.00"))), d("200.00"), "INR", null, TODAY);
        assertThat(r.code()).isEqualTo("DRAWING_POWER_SHORTFALL");
        // when the sanctioned limit is the tighter one, it is a plain limit breach
        Rejection r2 = AvailabilityCalculator.firstRejection(List.of(new Level(f, d("0"), d("5000.00"))), d("600.00"), "INR", null, TODAY);
        assertThat(r2.code()).isEqualTo("LIMIT_EXCEEDED");
    }
}
