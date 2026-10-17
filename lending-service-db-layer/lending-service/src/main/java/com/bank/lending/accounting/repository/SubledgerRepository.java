package com.bank.lending.accounting.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Read-only queries over the loan sub-ledger (demands, accruals, accounts). The accounting module reads these tables
 * to true up accruals and to reconcile; it never writes them.
 */
@Repository
public class SubledgerRepository {

    /** The sub-ledger position of one account, in the buckets the general ledger tracks. */
    public record Position(long accountId, String productCode, String status, BigDecimal principalNotDue,
                           BigDecimal principalDue, BigDecimal interestAccrued, BigDecimal interestDue,
                           BigDecimal penal, BigDecimal advance, String currencyCode, String branchCode) {
    }

    private final JdbcTemplate jdbc;

    public SubledgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Interest accrued on dates after {@code after}, as the accrual table holds it, rounded to paise. */
    public BigDecimal accruedAfter(long accountId, LocalDate after) {
        BigDecimal v = jdbc.queryForObject("SELECT COALESCE(SUM(accrued_amount), 0) FROM interest_accrual "
                + "WHERE account_id = ? AND business_date > ?", BigDecimal.class, accountId, after);
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }

    /** Interest accrued on dates in (from, to], rounded to paise. */
    public BigDecimal accruedBetween(long accountId, LocalDate afterExclusive, LocalDate toInclusive) {
        BigDecimal v = jdbc.queryForObject("SELECT COALESCE(SUM(accrued_amount), 0) FROM interest_accrual "
                + "WHERE account_id = ? AND business_date > ? AND business_date <= ?", BigDecimal.class, accountId,
                afterExclusive, toInclusive);
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * The positions of the accounts that could hold a balance, for reconciliation and the opening-balance job.
     * Accrued interest is the accrual table summed after the last demand's due date (or after the start when there is
     * no demand), rounded to paise; the other buckets come straight from the demand rows.
     */
    public List<Position> positions(Long onlyAccountId) {
        String sql = "SELECT a.account_id, a.product_code, a.account_status, a.advance_balance, a.currency_code, a.branch_code, "
                + "CASE WHEN a.account_status IN ('CLOSED','FORECLOSED','WRITTEN_OFF') THEN 0 ELSE COALESCE((SELECT SUM(s.principal_component) FROM amortization_schedule s "
                + "  WHERE s.account_id = a.account_id AND s.schedule_version = "
                + "  (SELECT MAX(v.schedule_version) FROM amortization_schedule v WHERE v.account_id = a.account_id) "
                + "  AND s.installment_status <> 'PAID' AND NOT EXISTS (SELECT 1 FROM loan_demand d "
                + "  WHERE d.account_id = s.account_id AND d.installment_number = s.installment_number)), 0) END AS p_not_due, "
                + "COALESCE((SELECT SUM(d.principal_due - d.principal_paid) FROM loan_demand d "
                + "  WHERE d.account_id = a.account_id), 0) AS p_due, "
                + "COALESCE((SELECT SUM(d.interest_due - d.interest_paid) FROM loan_demand d "
                + "  WHERE d.account_id = a.account_id), 0) AS i_due, "
                + "COALESCE((SELECT SUM(GREATEST(ROUND(d.penal_accrued, 2) - d.penal_paid, 0)) FROM loan_demand d "
                + "  WHERE d.account_id = a.account_id), 0) AS penal, "
                + "CASE WHEN a.account_status IN ('CLOSED','FORECLOSED','WRITTEN_OFF') THEN 0 ELSE COALESCE((SELECT SUM(i.accrued_amount) FROM interest_accrual i WHERE i.account_id = a.account_id "
                + "  AND i.business_date > COALESCE((SELECT MAX(d.due_date) FROM loan_demand d "
                + "  WHERE d.account_id = a.account_id), DATE '0001-01-01')), 0) END AS i_accr "
                + "FROM loan_account a WHERE a.account_status NOT IN ('PENDING_DISBURSEMENT') "
                + (onlyAccountId == null ? "" : "AND a.account_id = ? ")
                + "ORDER BY a.account_id";
        Object[] args = onlyAccountId == null ? new Object[0] : new Object[]{onlyAccountId};
        return jdbc.query(sql, (rs, i) -> new Position(rs.getLong("account_id"), rs.getString("product_code"),
                rs.getString("account_status"), rs.getBigDecimal("p_not_due"), rs.getBigDecimal("p_due"),
                rs.getBigDecimal("i_accr").setScale(2, RoundingMode.HALF_UP), rs.getBigDecimal("i_due"),
                rs.getBigDecimal("penal"), rs.getBigDecimal("advance_balance"), rs.getString("currency_code"),
                rs.getString("branch_code")), args);
    }

    /** All interest ever accrued on the account (raw, four decimals): the starting point of its rounding carry. */
    public BigDecimal accrualTotal(long accountId) {
        BigDecimal v = jdbc.queryForObject("SELECT COALESCE(SUM(accrued_amount), 0) FROM interest_accrual "
                + "WHERE account_id = ?", BigDecimal.class, accountId);
        return v == null ? BigDecimal.ZERO : v;
    }

    /** Penal interest charged so far per demand (four decimals): the starting point of each demand's rounding carry. */
    public List<BigDecimal[]> penalByDemand(long accountId) {
        return jdbc.query("SELECT demand_id, penal_accrued FROM loan_demand WHERE account_id = ? AND penal_accrued <> 0",
                (rs, i) -> new BigDecimal[]{BigDecimal.valueOf(rs.getLong(1)), rs.getBigDecimal(2)}, accountId);
    }
}
