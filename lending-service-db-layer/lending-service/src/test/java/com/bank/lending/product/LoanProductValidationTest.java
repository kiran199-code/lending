package com.bank.lending.product;

import com.bank.lending.account.domain.LoanAccount;
import com.bank.lending.product.dto.LoanProductRequest;
import com.bank.lending.product.service.LoanProductService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spring-free: product definition rules. */
class LoanProductValidationTest {

    private static LoanProductRequest request(LoanAccount.RateType type, String benchmark, String defRate,
                                              String minRate, String maxRate, String defSpread) {
        return new LoanProductRequest("T1", "Test", null, LoanAccount.AccountType.UNSECURED, type, null, null,
                benchmark, new BigDecimal(defRate), defSpread == null ? null : new BigDecimal(defSpread),
                minRate == null ? null : new BigDecimal(minRate), maxRate == null ? null : new BigDecimal(maxRate),
                null, null, null, null, null, null, null, null);
    }

    private static void validate(LoanProductRequest r) {
        LoanProductService.validate(r);
    }

    @Test
    void validFixedProductPasses() {
        validate(request(LoanAccount.RateType.FIXED, null, "10", "8", "17", null));
    }

    @Test
    void floatingNeedsBenchmark() {
        assertThatThrownBy(() -> validate(request(LoanAccount.RateType.FLOATING, null, "10", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("benchmarkRateRef is required");
    }

    @Test
    void fixedMustNotCarryBenchmark() {
        assertThatThrownBy(() -> validate(request(LoanAccount.RateType.FIXED, "REPO", "10", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("FLOATING products only");
    }

    @Test
    void defaultRateMustBeInsideBand() {
        assertThatThrownBy(() -> validate(request(LoanAccount.RateType.FIXED, null, "20", "8", "17", null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("defaultInterestRate must be inside");
    }

    @Test
    void minAboveMaxIsRejected() {
        assertThatThrownBy(() -> validate(request(LoanAccount.RateType.FIXED, null, "10", "17", "8", null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must not exceed the maximum");
    }

    private static LoanProductRequest withSchedule(LoanAccount.ScheduleType type, LoanAccount.RepaymentFrequency frequency,
                                                   java.util.List<LoanAccount.RepaymentFrequency> allowed,
                                                   Integer moratorium, String balloon, String stepUp) {
        return new LoanProductRequest("T1", "Test", null, LoanAccount.AccountType.UNSECURED, LoanAccount.RateType.FIXED,
                type, null, null, new BigDecimal("10"), null, null, null, null, null, null, null, null, null,
                frequency, allowed, moratorium, null,
                balloon == null ? null : new BigDecimal(balloon), null,
                stepUp == null ? null : new BigDecimal(stepUp), null, null, null);
    }

    @Test
    void balloonProductNeedsABalloonPercent() {
        assertThatThrownBy(() -> validate(withSchedule(LoanAccount.ScheduleType.BALLOON, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("balloonPercent must be above 0");
        validate(withSchedule(LoanAccount.ScheduleType.BALLOON, null, null, null, "30", null));
    }

    @Test
    void stepUpProductNeedsAStepUpPercent() {
        assertThatThrownBy(() -> validate(withSchedule(LoanAccount.ScheduleType.STEP_UP_EMI, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("stepUpPercent must be above 0");
        validate(withSchedule(LoanAccount.ScheduleType.STEP_UP_EMI, null, null, null, null, "5"));
    }

    @Test
    void defaultFrequencyMustBeInTheAllowedList() {
        assertThatThrownBy(() -> validate(withSchedule(LoanAccount.ScheduleType.EMI, LoanAccount.RepaymentFrequency.MONTHLY,
                java.util.List.of(LoanAccount.RepaymentFrequency.QUARTERLY), null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("allowed frequencies");
    }

    @Test
    void interestOnlyProductMustNotDefaultAMoratorium() {
        assertThatThrownBy(() -> validate(withSchedule(LoanAccount.ScheduleType.INTEREST_ONLY, null, null, 3, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("INTEREST_ONLY");
    }

    private static LoanProductRequest withPenal(String rate, Integer grace, String cap) {
        return new LoanProductRequest("T1", "Test", null, LoanAccount.AccountType.UNSECURED, LoanAccount.RateType.FIXED,
                null, null, null, new BigDecimal("10"), null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                Boolean.TRUE, rate == null ? null : new BigDecimal(rate), grace, cap == null ? null : new BigDecimal(cap),
                null, null);
    }

    @Test
    void penalInterestSettingsAreChecked() {
        validate(withPenal("24", 0, null));
        validate(withPenal("18.5", 5, "10"));
        assertThatThrownBy(() -> validate(withPenal("100", 0, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("penalInterestRate");
        assertThatThrownBy(() -> validate(withPenal("-1", 0, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("penalInterestRate");
        assertThatThrownBy(() -> validate(withPenal("24", -1, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("penalGraceDays");
        assertThatThrownBy(() -> validate(withPenal("24", 0, "0")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("penalCapPercent");
    }
}
