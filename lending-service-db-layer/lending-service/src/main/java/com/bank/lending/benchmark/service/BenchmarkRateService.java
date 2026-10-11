package com.bank.lending.benchmark.service;

import com.bank.lending.benchmark.domain.BenchmarkRate;
import com.bank.lending.benchmark.repository.BenchmarkRateRepository;
import com.bank.lending.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Source of truth for benchmark rates used by floating-rate resets. Publishing a rate does not
 * touch any account: accounts pick the rate up individually, when their own reset runs.
 */
@Service
@RequiredArgsConstructor
public class BenchmarkRateService {

    private final BenchmarkRateRepository repository;

    @Transactional
    public BenchmarkRate publish(String benchmarkRef, LocalDate effectiveDate, BigDecimal rate, String source) {
        repository.upsert(benchmarkRef, effectiveDate, rate, source);
        return repository.findByBenchmarkRefAndEffectiveDate(benchmarkRef, effectiveDate).orElseThrow();
    }

    /** The rate in force on {@code asOf}; 404 when nothing has been published on or before that date. */
    @Transactional(readOnly = true)
    public BenchmarkRate getRateAsOf(String benchmarkRef, LocalDate asOf) {
        return repository
                .findFirstByBenchmarkRefAndEffectiveDateLessThanEqualOrderByEffectiveDateDesc(benchmarkRef, asOf)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No rate published for benchmark " + benchmarkRef + " on or before " + asOf));
    }

    @Transactional(readOnly = true)
    public List<BenchmarkRate> history(String benchmarkRef) {
        return repository.findByBenchmarkRefOrderByEffectiveDateDesc(benchmarkRef);
    }
}
