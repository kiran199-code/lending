package com.bank.lending.admin.repository;

import com.bank.lending.admin.dto.AdminDtos.AccrualRow;
import com.bank.lending.admin.dto.AdminDtos.DailyAmount;
import com.bank.lending.admin.dto.AdminDtos.PageResponse;
import com.bank.lending.admin.dto.AdminDtos.PaymentRow;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** emi_transaction and interest_accrual reads. */
@Repository
public class PaymentRepository {

    public record PaymentFilter(LocalDate from, LocalDate to, String status, String type, String channel,
                                String accountNumber) {
    }

    public record AccrualTotals(BigDecimal total, LocalDate lastDate) {
    }

    private static final RowMapper<PaymentRow> PAYMENT = (rs, i) -> new PaymentRow(
            rs.getLong("transaction_id"), Sql.date(rs, "value_date"), rs.getLong("account_id"),
            rs.getString("account_number"), Sql.intOrNull(rs, "installment_number"),
            rs.getString("transaction_type"), rs.getBigDecimal("amount"), rs.getBigDecimal("principal_paid"),
            rs.getBigDecimal("interest_paid"), rs.getBigDecimal("penal_paid"), rs.getString("payment_channel"), rs.getString("payment_status"),
            rs.getString("idempotency_key"));

    private static final String PAYMENT_COLS = """
            t.transaction_id, t.value_date, t.account_id, a.account_number, t.installment_number,
            t.transaction_type, t.amount, t.principal_paid, t.interest_paid, t.penal_paid, t.payment_channel,
            t.payment_status, t.idempotency_key
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public PaymentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<PaymentRow> forAccount(long accountId) {
        return jdbc.query("SELECT " + PAYMENT_COLS + """
                FROM emi_transaction t JOIN loan_account a ON a.account_id = t.account_id
                WHERE t.account_id = :id
                ORDER BY t.value_date DESC, t.transaction_id DESC
                """, new MapSqlParameterSource("id", accountId), PAYMENT);
    }

    public PageResponse<PaymentRow> search(PaymentFilter f, int page, int size) {
        page = Sql.clampPage(page);
        size = Sql.clampSize(size);
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        MapSqlParameterSource p = new MapSqlParameterSource();
        if (f.from() != null) {
            where.append(" AND t.value_date >= :from");
            p.addValue("from", f.from());
        }
        if (f.to() != null) {
            where.append(" AND t.value_date <= :to");
            p.addValue("to", f.to());
        }
        if (notBlank(f.status())) {
            where.append(" AND t.payment_status = :status");
            p.addValue("status", f.status());
        }
        if (notBlank(f.type())) {
            where.append(" AND t.transaction_type = :type");
            p.addValue("type", f.type());
        }
        if (notBlank(f.channel())) {
            where.append(" AND t.payment_channel = :channel");
            p.addValue("channel", f.channel());
        }
        if (notBlank(f.accountNumber())) {
            where.append(" AND a.account_number ILIKE :acc");
            p.addValue("acc", "%" + f.accountNumber().trim() + "%");
        }
        String from = " FROM emi_transaction t JOIN loan_account a ON a.account_id = t.account_id";
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from + where, p, Long.class);
        p.addValue("limit", size).addValue("offset", (long) page * size);
        List<PaymentRow> rows = jdbc.query("SELECT " + PAYMENT_COLS + from + where
                + " ORDER BY t.value_date DESC, t.transaction_id DESC LIMIT :limit OFFSET :offset", p, PAYMENT);
        return PageResponse.of(rows, page, size, total == null ? 0 : total);
    }

    // ---------------------------------------------------------------- accruals

    private static final RowMapper<AccrualRow> ACCRUAL = (rs, i) -> new AccrualRow(Sql.date(rs, "business_date"),
            rs.getLong("account_id"), rs.getString("account_number"), rs.getBigDecimal("accrued_amount"),
            rs.getBigDecimal("outstanding_principal"));

    public List<AccrualRow> accrualsForAccount(long accountId, LocalDate from, LocalDate to) {
        MapSqlParameterSource p = new MapSqlParameterSource("id", accountId);
        StringBuilder where = new StringBuilder(" WHERE i.account_id = :id");
        if (from != null) {
            where.append(" AND i.business_date >= :from");
            p.addValue("from", from);
        }
        if (to != null) {
            where.append(" AND i.business_date <= :to");
            p.addValue("to", to);
        }
        return jdbc.query("""
                SELECT i.business_date, i.account_id, a.account_number, i.accrued_amount, i.outstanding_principal
                FROM interest_accrual i JOIN loan_account a ON a.account_id = i.account_id
                """ + where + " ORDER BY i.business_date DESC", p, ACCRUAL);
    }

    public AccrualTotals accrualTotals(long accountId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(accrued_amount), 0) AS total, MAX(business_date) AS last_date
                FROM interest_accrual WHERE account_id = :id
                """, new MapSqlParameterSource("id", accountId),
                (rs, i) -> new AccrualTotals(rs.getBigDecimal("total"), Sql.date(rs, "last_date")));
    }

    /** Every account's accrual for one business date (the output of one accrual run). */
    public PageResponse<AccrualRow> accrualsForDate(LocalDate date, int page, int size) {
        page = Sql.clampPage(page);
        size = Sql.clampSize(size);
        MapSqlParameterSource p = new MapSqlParameterSource("d", date);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM interest_accrual WHERE business_date = :d", p,
                Long.class);
        p.addValue("limit", size).addValue("offset", (long) page * size);
        List<AccrualRow> rows = jdbc.query("""
                SELECT i.business_date, i.account_id, a.account_number, i.accrued_amount, i.outstanding_principal
                FROM interest_accrual i JOIN loan_account a ON a.account_id = i.account_id
                WHERE i.business_date = :d ORDER BY a.account_number LIMIT :limit OFFSET :offset
                """, p, ACCRUAL);
        return PageResponse.of(rows, page, size, total == null ? 0 : total);
    }

    public List<DailyAmount> accrualDaily(LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT business_date AS d, SUM(accrued_amount) AS amt, COUNT(*) AS n
                FROM interest_accrual WHERE business_date BETWEEN :from AND :to
                GROUP BY business_date ORDER BY business_date
                """, new MapSqlParameterSource("from", from).addValue("to", to),
                (rs, i) -> new DailyAmount(Sql.date(rs, "d"), rs.getBigDecimal("amt"), rs.getLong("n")));
    }

    public List<DailyAmount> collectionsDaily(LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT value_date AS d, SUM(amount) AS amt, COUNT(*) AS n
                FROM emi_transaction
                WHERE payment_status = 'SUCCESS' AND value_date BETWEEN :from AND :to
                GROUP BY value_date ORDER BY value_date
                """, new MapSqlParameterSource("from", from).addValue("to", to),
                (rs, i) -> new DailyAmount(Sql.date(rs, "d"), rs.getBigDecimal("amt"), rs.getLong("n")));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
