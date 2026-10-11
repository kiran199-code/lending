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
    void rateOutsideProductBandIsRejected() {
        assertThatThrownBy(() -> accountService.createAccount(new CreateLoanAccountRequest(
                7002L, 7002L, "PL01", null, new BigDecimal("300000"), 24, null, new BigDecimal("25"), null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("interestRate 25 is above the maximum 17");
    }
}
