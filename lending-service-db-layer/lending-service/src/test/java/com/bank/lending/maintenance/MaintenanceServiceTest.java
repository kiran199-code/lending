package com.bank.lending.maintenance;

import com.bank.lending.businessdate.domain.BusinessDateStatus;
import com.bank.lending.businessdate.dto.BusinessDateResponse;
import com.bank.lending.businessdate.service.BusinessDateService;
import com.bank.lending.common.exception.FeatureDisabledException;
import com.bank.lending.common.exception.InvalidAccountStateException;
import com.bank.lending.maintenance.dto.BaseProductsResponse;
import com.bank.lending.maintenance.dto.ClearDataResponse;
import com.bank.lending.maintenance.service.BaseProducts;
import com.bank.lending.maintenance.service.MaintenanceService;
import com.bank.lending.product.dto.LoanProductRequest;
import com.bank.lending.product.repository.LoanProductRepository;
import com.bank.lending.product.service.LoanProductService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Spring-free: the guards around clearing data, and creating the base products. */
class MaintenanceServiceTest {

    private final JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    private final BusinessDateService businessDate = Mockito.mock(BusinessDateService.class);
    private final LoanProductService productService = Mockito.mock(LoanProductService.class);
    private final LoanProductRepository productRepository = Mockito.mock(LoanProductRepository.class);

    private MaintenanceService service(boolean enabled) {
        return new MaintenanceService(jdbc, businessDate, productService, productRepository, enabled);
    }

    private void businessDateIs(BusinessDateStatus status) {
        LocalDate d = LocalDate.of(2026, 10, 12);
        when(businessDate.state()).thenReturn(new BusinessDateResponse(d, d.minusDays(1), d.plusDays(1),
                status, true, d, "test", null));
    }

    @Test
    void clearIsRefusedWhileSwitchedOff() {
        assertThatThrownBy(() -> service(false).clearAllData("RESET", "kiran"))
                .isInstanceOf(FeatureDisabledException.class)
                .hasMessageContaining("LENDING_RESET_ENABLED");
        verify(jdbc, never()).execute(anyString());
    }

    @Test
    void clearNeedsTheConfirmationWord() {
        assertThatThrownBy(() -> service(true).clearAllData("reset", "kiran"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("RESET");
        assertThatThrownBy(() -> service(true).clearAllData(null, "kiran"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(jdbc, never()).execute(anyString());
    }

    @Test
    void clearIsRefusedWhileEndOfDayIsRunning() {
        businessDateIs(BusinessDateStatus.EOD_RUNNING);
        assertThatThrownBy(() -> service(true).clearAllData("RESET", "kiran"))
                .isInstanceOf(InvalidAccountStateException.class).hasMessageContaining("End of day is running");
        verify(jdbc, never()).execute(anyString());
    }

    @Test
    void clearTruncatesEveryTableInOneStatementAndReportsTheCounts() {
        businessDateIs(BusinessDateStatus.OPEN);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(2L);

        ClearDataResponse r = service(true).clearAllData("RESET", "admin");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(1)).execute(sql.capture());
        assertThat(sql.getValue()).startsWith("TRUNCATE TABLE ").endsWith(" RESTART IDENTITY")
                .doesNotContain("CASCADE");
        for (String table : List.of("loan_account", "amortization_schedule", "interest_accrual", "emi_transaction",
                "loan_demand", "payment_allocation",
                "mortgage", "mortgage_valuation_history", "ledger_outbox", "processed_request", "loan_product")) {
            assertThat(sql.getValue()).contains(table);
        }
        // The business date, holidays, benchmark rates and batch tables are never touched.
        assertThat(sql.getValue()).doesNotContain("business_date", "non_working_day", "benchmark_rate");
        assertThat(r.deleted()).hasSize(11);
        assertThat(r.totalRows()).isEqualTo(22L);
        assertThat(r.clearedBy()).isEqualTo("admin");
    }

    @Test
    void baseProductsAreCreatedWhenMissing() {
        when(productRepository.existsByProductCode(anyString())).thenReturn(false);

        BaseProductsResponse r = service(false).createBaseProducts();

        assertThat(r.created()).containsExactly("PL01", "BL01", "EL01", "GL01", "AL01", "HL01",
                "TL01", "BB01", "AB01", "SU01", "EM01");
        assertThat(r.alreadyExisted()).isEmpty();
        verify(productService, times(11)).create(any(LoanProductRequest.class));
    }

    @Test
    void existingBaseProductsAreLeftAlone() {
        when(productRepository.existsByProductCode(anyString())).thenReturn(false);
        when(productRepository.existsByProductCode("PL01")).thenReturn(true);
        when(productRepository.existsByProductCode("HL01")).thenReturn(true);

        BaseProductsResponse r = service(false).createBaseProducts();

        assertThat(r.created()).containsExactly("BL01", "EL01", "GL01", "AL01", "TL01", "BB01", "AB01", "SU01", "EM01");
        assertThat(r.alreadyExisted()).containsExactly("PL01", "HL01");
        verify(productService, never()).create(Mockito.argThat(p -> p.productCode().equals("PL01")));
        verify(productService, never()).update(anyString(), any());
    }

    @Test
    void theBaseProductSetIsTheSeededAndSampleProductsAndEachOneIsValid() {
        List<LoanProductRequest> all = BaseProducts.all();
        Set<String> codes = all.stream().map(LoanProductRequest::productCode).collect(Collectors.toSet());
        assertThat(codes).containsExactlyInAnyOrder("PL01", "BL01", "EL01", "GL01", "AL01", "HL01",
                "TL01", "BB01", "AB01", "SU01", "EM01");
        // The end-to-end scenarios depend on these schedule types.
        assertThat(all).filteredOn(p -> p.productCode().equals("BB01"))
                .extracting(LoanProductRequest::scheduleType)
                .containsExactly(com.bank.lending.account.domain.LoanAccount.ScheduleType.INTEREST_ONLY);
        all.forEach(LoanProductService::validate);
        assertThat(all).filteredOn(p -> p.rateType() == com.bank.lending.account.domain.LoanAccount.RateType.FLOATING)
                .extracting(LoanProductRequest::benchmarkRateRef).containsOnly("REPO");
    }
}
