package com.bank.lending.benchmark.repository;

import com.bank.lending.benchmark.domain.BenchmarkRate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface BenchmarkRateRepository extends JpaRepository<BenchmarkRate, Long> {

    /** The rate in force on a date: the most recent one effective on or before it. */
    Optional<BenchmarkRate> findFirstByBenchmarkRefAndEffectiveDateLessThanEqualOrderByEffectiveDateDesc(
            String benchmarkRef, LocalDate asOf);

    Optional<BenchmarkRate> findByBenchmarkRefAndEffectiveDate(String benchmarkRef, LocalDate effectiveDate);

    List<BenchmarkRate> findByBenchmarkRefOrderByEffectiveDateDesc(String benchmarkRef);

    /** Idempotent publish: re-publishing the same (ref, date) corrects the rate instead of failing. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO benchmark_rate (benchmark_ref, effective_date, rate, source)
            VALUES (:ref, :effectiveDate, :rate, :source)
            ON CONFLICT (benchmark_ref, effective_date)
            DO UPDATE SET rate = EXCLUDED.rate, source = EXCLUDED.source, updated_at = now()
            """, nativeQuery = true)
    int upsert(@Param("ref") String benchmarkRef,
               @Param("effectiveDate") LocalDate effectiveDate,
               @Param("rate") BigDecimal rate,
               @Param("source") String source);
}
