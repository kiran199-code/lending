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
                null, null, null, null, null, null, null);
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
}
