package com.bank.lending.db;

import com.bank.lending.account.domain.LoanAccount;
import com.bank.lending.account.dto.CreateLoanAccountRequest;
import com.bank.lending.account.service.LoanAccountService;
import com.bank.lending.product.domain.LoanProduct;
import com.bank.lending.product.repository.LoanProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** End to end: migration-seeded products drive account defaults and limits. */
@Transactional
class LoanProductIntegrationTest extends AbstractIntegrationTest {

    @Autowired private LoanProductRepository productRepository;
    @Autowired private LoanAccountService accountService;
    @Autowired private com.bank.lending.schedule.service.AmortizationScheduleService scheduleService;

    @Test
    void standardProductsAreSeeded() {
        assertThat(productRepository.findAllByOrderByProductCode())
                .extracting(LoanProduct::getProductCode)
                .contains("PL01", "BL01", "EL01", "GL01", "AL01", "HL01");
    }

    @Test
    void floatingProductWithoutBenchmarkIsRejectedByTheDatabase() {
        LoanProduct p = LoanProduct.builder()
                .productCode("BAD1").productName("Bad").accountType(LoanAccount.AccountType.UNSECURED)
                .rateType(LoanAccount.RateType.FLOATING).defaultInterestRate(new BigDecimal("9")).version(0).build();
        assertThatThrownBy(() -> productRepository.saveAndFlush(p))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void accountCreatedFromProductGetsItsDefaults() {
        LoanAccount a = accountService.createAccount(new CreateLoanAccountRequest(
                7001L, 7001L, "HL01", null, new BigDecimal("6000000"), 240, null, null, null, null, null));

        assertThat(a.getProductId()).isNotNull();
        assertThat(a.getAccountType()).isEqualTo(LoanAccount.AccountType.MORTGAGE);
        assertThat(a.getRateType()).isEqualTo(LoanAccount.RateType.FLOATING);
        assertThat(a.getBenchmarkRateRef()).isEqualTo("REPO");
        assertThat(a.getSpread()).isEqualByComparingTo("2.5");
        assertThat(a.getInterestRate()).isEqualByComparingTo("8.75");
        assertThat(a.getDayCountConvention()).isEqualTo(LoanAccount.DayCountConvention.ACT_365);
        assertThat(a.getScheduleType()).isEqualTo(LoanAccount.ScheduleType.EMI);
    }

    @Test
    void newScheduleProductsAreSeeded() {
        assertThat(productRepository.findAllByOrderByProductCode())
                .extracting(LoanProduct::getProductCode)
                .contains("TL01", "BB01", "AB01", "SU01", "EM01");
        LoanProduct balloon = productRepository.findByProductCode("AB01").orElseThrow();
        assertThat(balloon.getScheduleType()).isEqualTo(LoanAccount.ScheduleType.BALLOON);
        assertThat(balloon.getBalloonPercent()).isEqualByComparingTo("30");
        LoanProduct equalPrincipal = productRepository.findByProductCode("TL01").orElseThrow();
        assertThat(equalPrincipal.getDayCountConvention()).isEqualTo(LoanAccount.DayCountConvention.THIRTY_E_360);
        assertThat(equalPrincipal.allowedFrequencies()).contains(LoanAccount.RepaymentFrequency.QUARTERLY);
    }

    @Test
    void moratoriumProductGivesInterestOnlyInstalmentsFirst() {
        LoanAccount created = accountService.createAccount(new CreateLoanAccountRequest(
                7003L, 7003L, "EM01", null, new BigDecimal("1200000"), 60, null, null, null, null, null));
        assertThat(created.getMoratoriumMonths()).isEqualTo(12);
        assertThat(created.getDayCountConvention()).isEqualTo(LoanAccount.DayCountConvention.THIRTY_360);

        accountService.disburse(created.getAccountId(), new BigDecimal("1200000"), java.time.LocalDate.of(2026, 11, 5));
        var schedule = scheduleService.getCurrentSchedule(created.getAccountId());

        assertThat(schedule).hasSize(60);
        assertThat(schedule.subList(0, 12)).allSatisfy(e -> assertThat(e.getPrincipalComponent()).isEqualByComparingTo("0"));
        assertThat(schedule.get(12).getPrincipalComponent()).isGreaterThan(BigDecimal.ZERO);
        assertThat(schedule.get(59).getOutstandingBalance()).isEqualByComparingTo("0");
    }

    @Test
    void balloonScheduleEndsWithTheBalloon() {
        LoanAccount created = accountService.createAccount(new CreateLoanAccountRequest(
                7004L, 7004L, "AB01", null, new BigDecimal("900000"), 48, null, null, null, null, null,
                null, null, new BigDecimal("40"), null));
        accountService.disburse(created.getAccountId(), new BigDecimal("900000"), java.time.LocalDate.of(2026, 11, 5));
        var schedule = scheduleService.getCurrentSchedule(created.getAccountId());

        assertThat(schedule).hasSize(48);
        var last = schedule.get(47);
        assertThat(last.getPrincipalComponent().add(last.getInterestComponent()))
                .isGreaterThan(new BigDecimal("360000"));      // 40% of 900,000 plus a normal instalment
    }

    @Test
    void rateOutsideProductBandIsRejected() {
        assertThatThrownBy(() -> accountService.createAccount(new CreateLoanAccountRequest(
                7002L, 7002L, "PL01", null, new BigDecimal("300000"), 24, null, new BigDecimal("25"), null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("interestRate 25 is above the maximum 17");
    }
}
