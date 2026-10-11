package com.bank.lending.benchmark;

import com.bank.lending.benchmark.domain.BenchmarkRate;
import com.bank.lending.benchmark.repository.BenchmarkRateRepository;
import com.bank.lending.benchmark.service.BenchmarkRateService;
import com.bank.lending.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spring-free: publishing and looking up benchmark rates. */
class BenchmarkRateServiceTest {

    private final BenchmarkRateRepository repository = Mockito.mock(BenchmarkRateRepository.class);
    private final BenchmarkRateService service = new BenchmarkRateService(repository);

    private static BenchmarkRate rate(String ref, LocalDate date, String value) {
        return BenchmarkRate.builder().benchmarkRef(ref).effectiveDate(date).rate(new BigDecimal(value)).build();
    }

    @Test
    void publishUpsertsAndReturnsTheStoredRow() {
        LocalDate d = LocalDate.of(2026, 10, 1);
        Mockito.when(repository.findByBenchmarkRefAndEffectiveDate("MCLR_1Y", d))
                .thenReturn(Optional.of(rate("MCLR_1Y", d, "8.7500")));

        BenchmarkRate stored = service.publish("MCLR_1Y", d, new BigDecimal("8.75"), "RBI");

        Mockito.verify(repository).upsert("MCLR_1Y", d, new BigDecimal("8.75"), "RBI");
        assertThat(stored.getRate()).isEqualByComparingTo("8.75");
    }

    @Test
    void theRateInForceIsTheMostRecentOnOrBeforeTheDate() {
        LocalDate asOf = LocalDate.of(2026, 10, 20);
        Mockito.when(repository.findFirstByBenchmarkRefAndEffectiveDateLessThanEqualOrderByEffectiveDateDesc("MCLR_1Y", asOf))
                .thenReturn(Optional.of(rate("MCLR_1Y", LocalDate.of(2026, 10, 1), "8.75")));

        assertThat(service.getRateAsOf("MCLR_1Y", asOf).getEffectiveDate()).isEqualTo(LocalDate.of(2026, 10, 1));
    }

    @Test
    void aBenchmarkWithNoPublishedRateIsNotFound() {
        Mockito.when(repository.findFirstByBenchmarkRefAndEffectiveDateLessThanEqualOrderByEffectiveDateDesc(
                Mockito.anyString(), Mockito.any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getRateAsOf("TBILL_91D", LocalDate.of(2026, 10, 20)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("TBILL_91D");
    }
}
