package com.bank.lending.admin.repository;

import com.bank.lending.admin.dto.AdminDtos.BucketSummary;
import com.bank.lending.admin.dto.AdminDtos.OverdueRow;
import com.bank.lending.admin.dto.AdminDtos.PageResponse;
import com.bank.lending.admin.dto.AdminDtos.StatusCount;
import com.bank.lending.admin.dto.AdminDtos.UpcomingPaymentRow;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Cross-account views: dashboard numbers, upcoming dues, overdue instalments. */
@Repository
public class PortfolioRepository {

    public record DueTotals(long count, BigDecimal amount) {
    }

    public record OverdueTotals(long accounts, long instalments, BigDecimal amount) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public PortfolioRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<StatusCount> statusCounts() {
        return jdbc.query("""
                SELECT account_status, COUNT(*) AS n, COALESCE(SUM(sanctioned_amount), 0) AS sanctioned,
                       COALESCE(SUM(disbursed_amount), 0) AS disbursed
                FROM loan_account GROUP BY account_status ORDER BY COUNT(*) DESC
                """, new MapSqlParameterSource(),
                (rs, i) -> new StatusCount(rs.getString("account_status"), rs.getLong("n"),
                        rs.getBigDecimal("sanctioned"), rs.getBigDecimal("disbursed")));
    }

    /** Sum over live accounts of the principal still owed (balance before the first unpaid instalment). */
    public BigDecimal outstandingPrincipal() {
        BigDecimal v = jdbc.queryForObject("WITH " + Sql.CURRENT_SCHEDULE + """
                SELECT COALESCE(SUM(x.outstanding), 0) FROM (
                    SELECT DISTINCT ON (c.account_id) c.account_id, c.outstanding_balance + c.principal_component AS outstanding
                    FROM cur c JOIN loan_account a ON a.account_id = c.account_id
                    WHERE c.installment_status <> 'PAID' AND """ + Sql.LIVE_ACCOUNT + """

                    ORDER BY c.account_id, c.installment_number
                ) x
                """, new MapSqlParameterSource(), BigDecimal.class);
        return v == null ? BigDecimal.ZERO : v;
    }

    public OverdueTotals overdueTotals(LocalDate today) {
        return jdbc.queryForObject("WITH " + Sql.CURRENT_SCHEDULE + """
                SELECT COUNT(DISTINCT c.account_id) AS accounts, COUNT(*) AS n,
                       COALESCE(SUM(c.principal_component + c.interest_component), 0) AS amt
                FROM cur c JOIN loan_account a ON a.account_id = c.account_id
                WHERE c.installment_status <> 'PAID' AND c.due_date < :today AND """ + Sql.LIVE_ACCOUNT,
                new MapSqlParameterSource("today", today),
                (rs, i) -> new OverdueTotals(rs.getLong("accounts"), rs.getLong("n"), rs.getBigDecimal("amt")));
    }

    public DueTotals dueBetween(LocalDate from, LocalDate to) {
        return jdbc.queryForObject("WITH " + Sql.CURRENT_SCHEDULE + """
                SELECT COUNT(*) AS n, COALESCE(SUM(c.principal_component + c.interest_component), 0) AS amt
                FROM cur c JOIN loan_account a ON a.account_id = c.account_id
                WHERE c.installment_status <> 'PAID' AND c.due_date BETWEEN :from AND :to AND """ + Sql.LIVE_ACCOUNT,
                new MapSqlParameterSource("from", from).addValue("to", to),
                (rs, i) -> new DueTotals(rs.getLong("n"), rs.getBigDecimal("amt")));
    }

    public DueTotals collections(LocalDate from, LocalDate to) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) AS n, COALESCE(SUM(amount), 0) AS amt FROM emi_transaction
                WHERE payment_status = 'SUCCESS' AND value_date BETWEEN :from AND :to
                """, new MapSqlParameterSource("from", from).addValue("to", to),
                (rs, i) -> new DueTotals(rs.getLong("n"), rs.getBigDecimal("amt")));
    }

    public LocalDate lastAccrualDate() {
        return jdbc.queryForObject("SELECT MAX(business_date) FROM interest_accrual", new MapSqlParameterSource(),
                LocalDate.class);
    }

    public BigDecimal accrualSum(LocalDate from, LocalDate to) {
        BigDecimal v = jdbc.queryForObject("SELECT COALESCE(SUM(accrued_amount), 0) FROM interest_accrual"
                + " WHERE business_date BETWEEN :from AND :to",
                new MapSqlParameterSource("from", from).addValue("to", to), BigDecimal.class);
        return v == null ? BigDecimal.ZERO : v;
    }

    // ---------------------------------------------------------------- upcoming payments

    public PageResponse<UpcomingPaymentRow> upcoming(LocalDate today, LocalDate from, LocalDate to, String product,
                                                     String status, int page, int size) {
        page = Sql.clampPage(page);
        size = Sql.clampSize(size);
        StringBuilder where = new StringBuilder(
                " WHERE c.installment_status <> 'PAID' AND c.due_date BETWEEN :from AND :to AND " + Sql.LIVE_ACCOUNT);
        MapSqlParameterSource p = new MapSqlParameterSource("from", from).addValue("to", to);
        if (product != null && !product.isBlank()) {
            where.append(" AND a.product_code = :product");
            p.addValue("product", product);
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND a.account_status = :status");
            p.addValue("status", status);
        }
        String base = " FROM cur c JOIN loan_account a ON a.account_id = c.account_id";
        Long total = jdbc.queryForObject("WITH " + Sql.CURRENT_SCHEDULE + "SELECT COUNT(*)" + base + where, p,
                Long.class);
        p.addValue("limit", size).addValue("offset", (long) page * size);
        List<UpcomingPaymentRow> rows = jdbc.query("WITH " + Sql.CURRENT_SCHEDULE + """
                SELECT a.account_id, a.account_number, a.customer_id, a.product_code, a.account_status,
                       c.installment_number, c.due_date, c.principal_component, c.interest_component
                """ + base + where + " ORDER BY c.due_date, a.account_number LIMIT :limit OFFSET :offset", p,
                (rs, i) -> {
                    LocalDate due = Sql.date(rs, "due_date");
                    BigDecimal pr = rs.getBigDecimal("principal_component");
                    BigDecimal in = rs.getBigDecimal("interest_component");
                    return new UpcomingPaymentRow(rs.getLong("account_id"), rs.getString("account_number"),
                            rs.getLong("customer_id"), rs.getString("product_code"), rs.getString("account_status"),
                            rs.getInt("installment_number"), due, pr, in, pr.add(in),
                            (int) ChronoUnit.DAYS.between(today, due));
                });
        return PageResponse.of(rows, page, size, total == null ? 0 : total);
    }

    public BigDecimal upcomingAmount(LocalDate from, LocalDate to, String product, String status) {
        StringBuilder where = new StringBuilder(
                " WHERE c.installment_status <> 'PAID' AND c.due_date BETWEEN :from AND :to AND " + Sql.LIVE_ACCOUNT);
        MapSqlParameterSource p = new MapSqlParameterSource("from", from).addValue("to", to);
        if (product != null && !product.isBlank()) {
            where.append(" AND a.product_code = :product");
            p.addValue("product", product);
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND a.account_status = :status");
            p.addValue("status", status);
        }
        BigDecimal v = jdbc.queryForObject("WITH " + Sql.CURRENT_SCHEDULE
                + "SELECT COALESCE(SUM(c.principal_component + c.interest_component), 0)"
                + " FROM cur c JOIN loan_account a ON a.account_id = c.account_id" + where, p, BigDecimal.class);
        return v == null ? BigDecimal.ZERO : v;
    }

    // ---------------------------------------------------------------- overdue

    public PageResponse<OverdueRow> overdue(LocalDate today, Integer minDpd, Integer maxDpd, String product,
                                            int page, int size) {
        page = Sql.clampPage(page);
        size = Sql.clampSize(size);
        StringBuilder where = new StringBuilder(
                " WHERE c.installment_status <> 'PAID' AND c.due_date < :today AND " + Sql.LIVE_ACCOUNT);
        MapSqlParameterSource p = new MapSqlParameterSource("today", today);
        if (minDpd != null) {
            where.append(" AND c.due_date <= :latest");
            p.addValue("latest", today.minusDays(minDpd));
        }
        if (maxDpd != null) {
            where.append(" AND c.due_date >= :earliest");
            p.addValue("earliest", today.minusDays(maxDpd));
        }
        if (product != null && !product.isBlank()) {
            where.append(" AND a.product_code = :product");
            p.addValue("product", product);
        }
        String base = " FROM cur c JOIN loan_account a ON a.account_id = c.account_id";
        Long total = jdbc.queryForObject("WITH " + Sql.CURRENT_SCHEDULE + "SELECT COUNT(*)" + base + where, p,
                Long.class);
        p.addValue("limit", size).addValue("offset", (long) page * size);
        List<OverdueRow> rows = jdbc.query("WITH " + Sql.CURRENT_SCHEDULE + """
                SELECT a.account_id, a.account_number, a.customer_id, a.product_code, a.account_status,
                       c.installment_number, c.due_date, c.principal_component, c.interest_component
                """ + base + where + " ORDER BY c.due_date, a.account_number LIMIT :limit OFFSET :offset", p,
                (rs, i) -> {
                    LocalDate due = Sql.date(rs, "due_date");
                    BigDecimal pr = rs.getBigDecimal("principal_component");
                    BigDecimal in = rs.getBigDecimal("interest_component");
                    return new OverdueRow(rs.getLong("account_id"), rs.getString("account_number"),
                            rs.getLong("customer_id"), rs.getString("product_code"), rs.getString("account_status"),
                            rs.getInt("installment_number"), due, pr, in, pr.add(in), Sql.dpd(due, today));
                });
        return PageResponse.of(rows, page, size, total == null ? 0 : total);
    }

    /** Accounts per days-past-due bucket, using each account's oldest unpaid instalment. */
    public List<BucketSummary> overdueBuckets(LocalDate today) {
        return jdbc.query("WITH " + Sql.CURRENT_SCHEDULE + """
                , per_account AS (
                    SELECT c.account_id, MIN(c.due_date) AS oldest,
                           SUM(c.principal_component + c.interest_component) AS amt
                    FROM cur c JOIN loan_account a ON a.account_id = c.account_id
                    WHERE c.installment_status <> 'PAID' AND c.due_date < CAST(:today AS date) AND """ + Sql.LIVE_ACCOUNT + """

                    GROUP BY c.account_id
                )
                SELECT CASE WHEN CAST(:today AS date) - oldest <= 30 THEN '1-30'
                            WHEN CAST(:today AS date) - oldest <= 60 THEN '31-60'
                            WHEN CAST(:today AS date) - oldest <= 90 THEN '61-90'
                            ELSE '90+' END AS bucket,
                       COUNT(*) AS accounts, COALESCE(SUM(amt), 0) AS amount
                FROM per_account GROUP BY 1
                """, new MapSqlParameterSource("today", today),
                (rs, i) -> new BucketSummary(rs.getString("bucket"), rs.getLong("accounts"),
                        rs.getBigDecimal("amount")));
    }
}
