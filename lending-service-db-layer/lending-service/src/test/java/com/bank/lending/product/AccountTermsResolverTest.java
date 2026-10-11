package com.bank.lending.product;

import com.bank.lending.account.domain.LoanAccount;
import com.bank.lending.account.dto.CreateLoanAccountRequest;
import com.bank.lending.common.exception.InvalidAccountStateException;
import com.bank.lending.common.exception.ResourceNotFoundException;
import com.bank.lending.product.domain.LoanProduct;
import com.bank.lending.product.repository.LoanProductRepository;
import com.bank.lending.product.service.AccountTermsResolver;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spring-free: product defaults and limit checks on account creation. */
class AccountTermsResolverTest {

    private final LoanProductRepository repository = Mockito.mock(LoanProductRepository.class);
    private final AccountTermsResolver resolver = new AccountTermsResolver(repository);

    private LoanProduct fixedProduct() {
        return LoanProduct.builder().productId(1L).productCode("PL01").productName("Personal Loan")
                .accountType(LoanAccount.AccountType.UNSECURED).rateType(LoanAccount.RateType.FIXED)
                .dayCountConvention(LoanAccount.DayCountConvention.ACT_365)
                .defaultInterestRate(new BigDecimal("10.5")).minInterestRate(new BigDecimal("8")).maxInterestRate(new BigDecimal("17"))
                .minSanctionedAmount(new BigDecimal("50000")).maxSanctionedAmount(new BigDecimal("5000000"))
                .minTenureMonths(6).maxTenureMonths(84).build();
    }

    private LoanProduct floatingProduct() {
        return LoanProduct.builder().productId(2L).productCode("HL01").productName("Home Loan")
                .accountType(LoanAccount.AccountType.MORTGAGE).rateType(LoanAccount.RateType.FLOATING)
                .dayCountConvention(LoanAccount.DayCountConvention.ACT_365).benchmarkRateRef("REPO")
                .defaultInterestRate(new BigDecimal("8.75")).defaultSpread(new BigDecimal("2.5"))
                .minSpread(new BigDecimal("1")).maxSpread(new BigDecimal("5")).build();
    }

    private CreateLoanAccountRequest request(String code, BigDecimal amount, int tenure, LoanAccount.RateType rateType,
                                             BigDecimal rate, String benchmark, BigDecimal spread) {
        return new CreateLoanAccountRequest(1L, 1L, code, null, amount, tenure, rateType, rate, benchmark, spread, null);
    }

    @Test
    void omittedFieldsAreDefaultedFromFixedProduct() {
        Mockito.when(repository.findByProductCode("PL01")).thenReturn(Optional.of(fixedProduct()));

        var t = resolver.resolve(request("PL01", new BigDecimal("100000"), 12, null, null, null, null));

        assertThat(t.accountType()).isEqualTo(LoanAccount.AccountType.UNSECURED);
        assertThat(t.rateType()).isEqualTo(LoanAccount.RateType.FIXED);
        assertThat(t.interestRate()).isEqualByComparingTo("10.5");
        assertThat(t.benchmarkRateRef()).isNull();
        assertThat(t.spread()).isNull();
    }

    @Test
    void floatingProductDefaultsBenchmarkAndSpread() {
        Mockito.when(repository.findByProductCode("HL01")).thenReturn(Optional.of(floatingProduct()));

        var t = resolver.resolve(request("HL01", new BigDecimal("6000000"), 240, null, null, null, null));

        assertThat(t.benchmarkRateRef()).isEqualTo("REPO");
        assertThat(t.spread()).isEqualByComparingTo("2.5");
        assertThat(t.interestRate()).isEqualByComparingTo("8.75");
    }

    @Test
    void overrideInsideBandIsAccepted() {
        Mockito.when(repository.findByProductCode("PL01")).thenReturn(Optional.of(fixedProduct()));

        var t = resolver.resolve(request("PL01", new BigDecimal("100000"), 12, null, new BigDecimal("12.25"), null, null));

        assertThat(t.interestRate()).isEqualByComparingTo("12.25");
    }

    @Test
    void allViolationsAreReportedTogether() {
        Mockito.when(repository.findByProductCode("PL01")).thenReturn(Optional.of(fixedProduct()));

        assertThatThrownBy(() -> resolver.resolve(
                request("PL01", new BigDecimal("1000"), 120, null, new BigDecimal("30"), null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("interestRate 30 is above the maximum 17")
                .hasMessageContaining("sanctionedAmount 1000 is below the minimum 50000")
                .hasMessageContaining("tenureMonths 120 is above the maximum 84");
    }

    @Test
    void rateTypeMustMatchProduct() {
        Mockito.when(repository.findByProductCode("PL01")).thenReturn(Optional.of(fixedProduct()));

        assertThatThrownBy(() -> resolver.resolve(
                request("PL01", new BigDecimal("100000"), 12, LoanAccount.RateType.FLOATING, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rateType FLOATING does not match product PL01");
    }

    @Test
    void spreadOutsideBandIsRejected() {
        Mockito.when(repository.findByProductCode("HL01")).thenReturn(Optional.of(floatingProduct()));

        assertThatThrownBy(() -> resolver.resolve(
                request("HL01", new BigDecimal("6000000"), 240, null, null, null, new BigDecimal("7"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("spread 7 is above the maximum 5");
    }

    @Test
    void spreadOnFixedProductIsRejected() {
        Mockito.when(repository.findByProductCode("PL01")).thenReturn(Optional.of(fixedProduct()));

        assertThatThrownBy(() -> resolver.resolve(
                request("PL01", new BigDecimal("100000"), 12, null, null, null, new BigDecimal("2"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("apply to FLOATING products only");
    }

    private LoanProduct scheduleProduct(LoanAccount.ScheduleType type) {
        LoanProduct p = fixedProduct();
        p.setProductCode("SP01");
        p.setScheduleType(type);
        p.setAllowedRepaymentFrequencies("MONTHLY,QUARTERLY");
        p.setMaxMoratoriumMonths(12);
        return p;
    }

    private CreateLoanAccountRequest schedule(int tenure, LoanAccount.RepaymentFrequency frequency, Integer moratorium,
                                              BigDecimal balloon, BigDecimal stepUp) {
        return new CreateLoanAccountRequest(1L, 1L, "SP01", null, new BigDecimal("100000"), tenure, null, null, null, null,
                null, frequency, moratorium, balloon, stepUp);
    }

    @Test
    void scheduleParametersAreDefaultedFromTheProduct() {
        LoanProduct p = scheduleProduct(LoanAccount.ScheduleType.BALLOON);
        p.setBalloonPercent(new BigDecimal("30"));
        p.setMaxBalloonPercent(new BigDecimal("50"));
        p.setMoratoriumMonths(6);
        Mockito.when(repository.findByProductCode("SP01")).thenReturn(Optional.of(p));

        var t = resolver.resolve(schedule(36, null, null, null, null));

        assertThat(t.scheduleType()).isEqualTo(LoanAccount.ScheduleType.BALLOON);
        assertThat(t.repaymentFrequency()).isEqualTo(LoanAccount.RepaymentFrequency.MONTHLY);
        assertThat(t.moratoriumMonths()).isEqualTo(6);
        assertThat(t.balloonPercent()).isEqualByComparingTo("30");
        assertThat(t.stepUpPercent()).isEqualByComparingTo("0");
    }

    @Test
    void scheduleOverridesInsideTheProductLimitsAreAccepted() {
        LoanProduct p = scheduleProduct(LoanAccount.ScheduleType.BALLOON);
        p.setBalloonPercent(new BigDecimal("30"));
        p.setMaxBalloonPercent(new BigDecimal("50"));
        Mockito.when(repository.findByProductCode("SP01")).thenReturn(Optional.of(p));

        var t = resolver.resolve(schedule(36, LoanAccount.RepaymentFrequency.QUARTERLY, 6, new BigDecimal("40"), null));

        assertThat(t.repaymentFrequency()).isEqualTo(LoanAccount.RepaymentFrequency.QUARTERLY);
        assertThat(t.moratoriumMonths()).isEqualTo(6);
        assertThat(t.balloonPercent()).isEqualByComparingTo("40");
    }

    @Test
    void scheduleOverridesOutsideTheProductLimitsAreAllReported() {
        LoanProduct p = scheduleProduct(LoanAccount.ScheduleType.BALLOON);
        p.setBalloonPercent(new BigDecimal("30"));
        p.setMaxBalloonPercent(new BigDecimal("50"));
        Mockito.when(repository.findByProductCode("SP01")).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> resolver.resolve(
                schedule(36, LoanAccount.RepaymentFrequency.YEARLY, 24, new BigDecimal("60"), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repaymentFrequency YEARLY is not allowed")
                .hasMessageContaining("moratoriumMonths 24 is above the maximum 12")
                .hasMessageContaining("balloonPercent 60 is above the maximum 50");
    }

    @Test
    void tenureMustSuitTheRepaymentFrequency() {
        LoanProduct p = scheduleProduct(LoanAccount.ScheduleType.EMI);
        Mockito.when(repository.findByProductCode("SP01")).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> resolver.resolve(schedule(37, LoanAccount.RepaymentFrequency.QUARTERLY, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenureMonths 37 must be a multiple of 3");
    }

    @Test
    void balloonOnAnEmiProductIsRejected() {
        LoanProduct p = scheduleProduct(LoanAccount.ScheduleType.EMI);
        Mockito.when(repository.findByProductCode("SP01")).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> resolver.resolve(schedule(36, null, null, new BigDecimal("10"), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("balloonPercent applies to BALLOON schedules only");
    }

    @Test
    void unknownProductIsNotFound() {
        Mockito.when(repository.findByProductCode("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolve(request("NOPE", new BigDecimal("100000"), 12, null, null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void inactiveProductIsRejected() {
        LoanProduct p = fixedProduct();
        p.setStatus(LoanProduct.Status.INACTIVE);
        Mockito.when(repository.findByProductCode("PL01")).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> resolver.resolve(request("PL01", new BigDecimal("100000"), 12, null, null, null, null)))
                .isInstanceOf(InvalidAccountStateException.class);
    }
}
