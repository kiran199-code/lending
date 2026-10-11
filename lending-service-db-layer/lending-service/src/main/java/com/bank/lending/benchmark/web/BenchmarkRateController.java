package com.bank.lending.benchmark.web;

import com.bank.lending.benchmark.dto.BenchmarkRateResponse;
import com.bank.lending.benchmark.dto.PublishBenchmarkRateRequest;
import com.bank.lending.benchmark.service.BenchmarkRateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/benchmark-rates")
@RequiredArgsConstructor
@Tag(name = "Benchmark Rates", description = "Published benchmark rates (MCLR, T-bill, repo, ...) consumed by floating-rate resets")
public class BenchmarkRateController {

    private final BenchmarkRateService benchmarkRateService;

    @Operation(summary = "Publish (or correct) the rate for a benchmark effective from a date; idempotent")
    @PutMapping("/{benchmarkRef}/{effectiveDate}")
    public BenchmarkRateResponse publish(@PathVariable String benchmarkRef,
                                         @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveDate,
                                         @Valid @RequestBody PublishBenchmarkRateRequest request) {
        return BenchmarkRateResponse.from(
                benchmarkRateService.publish(benchmarkRef, effectiveDate, request.rate(), request.source()));
    }

    @Operation(summary = "The rate in force for a benchmark on a date (defaults to today)")
    @GetMapping("/{benchmarkRef}")
    public BenchmarkRateResponse getAsOf(@PathVariable String benchmarkRef,
                                         @RequestParam(required = false)
                                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return BenchmarkRateResponse.from(
                benchmarkRateService.getRateAsOf(benchmarkRef, asOf != null ? asOf : LocalDate.now()));
    }

    @Operation(summary = "All published rates for a benchmark, newest first")
    @GetMapping("/{benchmarkRef}/history")
    public List<BenchmarkRateResponse> history(@PathVariable String benchmarkRef) {
        return benchmarkRateService.history(benchmarkRef).stream().map(BenchmarkRateResponse::from).toList();
    }
}
