package com.bank.lending.product;

import com.bank.lending.account.domain.LoanAccount;
import com.bank.lending.common.exception.InvalidAccountStateException;
import com.bank.lending.product.domain.LoanProduct;
import com.bank.lending.product.dto.LoanProductRequest;
import com.bank.lending.product.repository.LoanProductRepository;
import com.bank.lending.product.service.LoanProductService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spring-free: product update rules. */
class LoanProductServiceTest {

    private final LoanProductRepository repository = Mockito.mock(LoanProductRepository.class);
    private final LoanProductService service = new LoanProductService(repository);

    private LoanProduct existing() {
        return LoanProduct.builder().productId(1L).productCode("PL01").productName("Personal Loan")
                .accountType(LoanAccount.AccountType.UNSECURED).rateType(LoanAccount.RateType.FIXED)
                .defaultInterestRate(new BigDecimal("10.5")).version(3).build();
    }

    private LoanProductRequest update(String rate, Integer expectedVersion) {
        return new LoanProductRequest("PL01", "Personal Loan", null, LoanAccount.AccountType.UNSECURED,
                LoanAccount.RateType.FIXED, null, null, null, new BigDecimal(rate), null,
                null, null, null, null, null, null, null, null, null, expectedVersion);
    }

    @Test
    void updateWithCurrentVersionChangesTheProduct() {
        LoanProduct p = existing();
        Mockito.when(repository.findByProductCode("PL01")).thenReturn(Optional.of(p));
        Mockito.when(repository.saveAndFlush(p)).thenReturn(p);

        service.update("PL01", update("11.25", 3));

        assertThat(p.getDefaultInterestRate()).isEqualByComparingTo("11.25");
    }

    @Test
    void updateWithStaleVersionIsRefused() {
        Mockito.when(repository.findByProductCode("PL01")).thenReturn(Optional.of(existing()));

        assertThatThrownBy(() -> service.update("PL01", update("11.25", 2)))
                .isInstanceOf(InvalidAccountStateException.class)
                .hasMessageContaining("changed by someone else");
    }
}
