package com.bank.lending.admin.web;

import com.bank.lending.admin.dto.AdminDtos.AccrualRow;
import com.bank.lending.admin.dto.AdminDtos.BucketSummary;
import com.bank.lending.admin.dto.AdminDtos.DailyAmount;
import com.bank.lending.admin.dto.AdminDtos.Dashboard;
import com.bank.lending.admin.dto.AdminDtos.OverdueRow;
import com.bank.lending.admin.dto.AdminDtos.PageResponse;
import com.bank.lending.admin.dto.AdminDtos.PaymentRow;
import com.bank.lending.admin.dto.AdminDtos.UpcomingResponse;
import com.bank.lending.admin.repository.PaymentRepository;
import com.bank.lending.admin.service.PortfolioService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/admin")
@Tag(name = "Portfolio", description = "Dashboard, upcoming and overdue payments, payments and accruals across accounts")
public class PortfolioController {

    private final PortfolioService portfolio;
    private final PaymentRepository payments;
    private final Clock clock;

    public PortfolioController(PortfolioService portfolio, PaymentRepository payments, Clock clock) {
        this.portfolio = portfolio;
        this.payments = payments;
        this.clock = clock;
    }

    @Operation(summary = "Headline numbers, status mix and 30-day accrual / collection series")
    @GetMapping("/dashboard")
    public Dashboard dashboard() {
        return portfolio.dashboard();
    }

    @Operation(summary = "Upcoming instalments across accounts (default: next 30 days)")
    @GetMapping("/upcoming-payments")
    public UpcomingResponse upcoming(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer days,
            @RequestParam(required = false) String product,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return portfolio.upcoming(from, to, days, product, status, page, size);
    }

    @Operation(summary = "Overdue instalments with days past due; filter by DPD range")
    @GetMapping("/overdue")
    public PageResponse<OverdueRow> overdue(
            @RequestParam(required = false) Integer minDpd,
            @RequestParam(required = false) Integer maxDpd,
            @RequestParam(required = false) String product,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return portfolio.overdue(minDpd, maxDpd, product, page, size);
    }

    @Operation(summary = "Overdue accounts and amount per days-past-due bucket")
    @GetMapping("/overdue/buckets")
    public List<BucketSummary> buckets() {
        return portfolio.overdueBuckets();
    }

    @Operation(summary = "Payments across all accounts, filterable")
    @GetMapping("/payments")
    public PageResponse<PaymentRow> payments(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String accountNumber,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return payments.search(new PaymentRepository.PaymentFilter(from, to, status, type, channel, accountNumber),
                page, size);
    }

    @Operation(summary = "Accrual totals per business date over a range (default: last 30 days)")
    @GetMapping("/accruals/summary")
    public List<DailyAmount> accrualSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now(clock);
        LocalDate start = from != null ? from : end.minusDays(29);
        return payments.accrualDaily(start, end);
    }

    @Operation(summary = "Each account's accrual for one business date (the output of one accrual run)")
    @GetMapping("/accruals")
    public PageResponse<AccrualRow> accrualsForDate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return payments.accrualsForDate(date, page, size);
    }
}
