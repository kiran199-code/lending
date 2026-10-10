package com.bank.lending.admin.service;

import com.bank.lending.admin.dto.AdminDtos.BucketSummary;
import com.bank.lending.admin.dto.AdminDtos.Dashboard;
import com.bank.lending.admin.dto.AdminDtos.OverdueRow;
import com.bank.lending.admin.dto.AdminDtos.PageResponse;
import com.bank.lending.admin.dto.AdminDtos.StatusCount;
import com.bank.lending.admin.dto.AdminDtos.UpcomingResponse;
import com.bank.lending.admin.repository.OpsRepository;
import com.bank.lending.admin.repository.PaymentRepository;
import com.bank.lending.admin.repository.PortfolioRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Service
public class PortfolioService {

    private static final List<String> LIVE_STATUSES = List.of("ACTIVE", "NPA_SMA0", "NPA_SMA1", "NPA_SMA2",
            "NPA_SUBSTANDARD", "NPA_DOUBTFUL", "NPA_LOSS");

    private final PortfolioRepository portfolio;
    private final PaymentRepository payments;
    private final OpsRepository ops;
    private final Clock clock;

    public PortfolioService(PortfolioRepository portfolio, PaymentRepository payments, OpsRepository ops, Clock clock) {
        this.portfolio = portfolio;
        this.payments = payments;
        this.ops = ops;
        this.clock = clock;
    }

    public Dashboard dashboard() {
        LocalDate today = LocalDate.now(clock);
        LocalDate monthStart = today.withDayOfMonth(1);

        List<StatusCount> byStatus = portfolio.statusCounts();
        long total = byStatus.stream().mapToLong(StatusCount::accounts).sum();
        long live = byStatus.stream().filter(s -> LIVE_STATUSES.contains(s.status())).mapToLong(StatusCount::accounts).sum();
        BigDecimal disbursed = byStatus.stream().map(StatusCount::disbursed).reduce(BigDecimal.ZERO, BigDecimal::add);

        var overdue = portfolio.overdueTotals(today);
        var due7 = portfolio.dueBetween(today, today.plusDays(7));
        var collections = portfolio.collections(monthStart, today);
        LocalDate lastAccrual = portfolio.lastAccrualDate();
        BigDecimal lastAmount = lastAccrual == null ? BigDecimal.ZERO : portfolio.accrualSum(lastAccrual, lastAccrual);
        Map<String, Long> outbox = ops.outboxCounts();

        return new Dashboard(today, total, live, disbursed, portfolio.outstandingPrincipal(), overdue.accounts(),
                overdue.amount(), due7.count(), due7.amount(), collections.amount(), collections.count(),
                lastAccrual, lastAmount, portfolio.accrualSum(monthStart, today), outbox.getOrDefault("PENDING", 0L),
                outbox.getOrDefault("FAILED", 0L), byStatus, payments.accrualDaily(today.minusDays(29), today),
                payments.collectionsDaily(today.minusDays(29), today));
    }

    public UpcomingResponse upcoming(LocalDate from, LocalDate to, Integer days, String product, String status,
                                     int page, int size) {
        LocalDate today = LocalDate.now(clock);
        LocalDate start = from != null ? from : today;
        LocalDate end = to != null ? to : start.plusDays(days == null ? 30 : Math.max(1, Math.min(days, 366)));
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("'to' must not be before 'from'");
        }
        return new UpcomingResponse(portfolio.upcoming(today, start, end, product, status, page, size),
                portfolio.upcomingAmount(start, end, product, status), start, end);
    }

    public PageResponse<OverdueRow> overdue(Integer minDpd, Integer maxDpd, String product, int page, int size) {
        return portfolio.overdue(LocalDate.now(clock), minDpd, maxDpd, product, page, size);
    }

    public List<BucketSummary> overdueBuckets() {
        return portfolio.overdueBuckets(LocalDate.now(clock));
    }
}
