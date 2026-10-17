package com.bank.lending.admin.repository;

import com.bank.lending.admin.dto.AdminDtos.AccountDetail;
import com.bank.lending.admin.dto.AdminDtos.AccountSummary;
import com.bank.lending.admin.dto.AdminDtos.MortgageDetail;
import com.bank.lending.admin.dto.AdminDtos.PageResponse;
import com.bank.lending.admin.dto.AdminDtos.ValuationRow;
import com.bank.lending.admin.service.BusinessDateSource;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class AccountRepository {

    public record Filter(String q, String status, String type, String product, Long customerId) {
    }

    private static final Map<String, String> SORTS = Map.of(
            "accountNumber", "a.account_number",
            "customerId", "a.customer_id",
            "status", "a.account_status",
            "product", "a.product_code",
            "sanctionedAmount", "a.sanctioned_amount",
            "createdAt", "a.created_at",
            "nextDue", "nd.next_due");

    private final NamedParameterJdbcTemplate jdbc;
    private final BusinessDateSource businessDate;

    public AccountRepository(NamedParameterJdbcTemplate jdbc, BusinessDateSource businessDate) {
        this.jdbc = jdbc;
        this.businessDate = businessDate;
    }

    public PageResponse<AccountSummary> search(Filter f, int page, int size, String sort, boolean desc) {
        page = Sql.clampPage(page);
        size = Sql.clampSize(size);
        LocalDate today = businessDate.today();

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        MapSqlParameterSource p = new MapSqlParameterSource();
        if (f.q() != null && !f.q().isBlank()) {
            where.append(" AND (a.account_number ILIKE :like OR CAST(a.customer_id AS TEXT) = :exact"
                    + " OR CAST(a.sanction_ref_id AS TEXT) = :exact OR CAST(a.account_id AS TEXT) = :exact)");
            p.addValue("like", "%" + f.q().trim() + "%");
            p.addValue("exact", f.q().trim());
        }
        if (f.status() != null && !f.status().isBlank()) {
            where.append(" AND a.account_status = :status");
            p.addValue("status", f.status());
        }
        if (f.type() != null && !f.type().isBlank()) {
            where.append(" AND a.account_type = :type");
            p.addValue("type", f.type());
        }
        if (f.product() != null && !f.product().isBlank()) {
            where.append(" AND a.product_code = :product");
            p.addValue("product", f.product());
        }
        if (f.customerId() != null) {
            where.append(" AND a.customer_id = :customerId");
            p.addValue("customerId", f.customerId());
        }

        String from = """
                FROM loan_account a
                LEFT JOIN LATERAL (
                    SELECT MIN(s.due_date) AS next_due FROM amortization_schedule s
                    WHERE s.account_id = a.account_id AND s.installment_status <> 'PAID'
                      AND s.schedule_version = (SELECT MAX(m.schedule_version) FROM amortization_schedule m
                                                WHERE m.account_id = a.account_id)
                ) nd ON TRUE
                """;
        Long total = jdbc.queryForObject("SELECT COUNT(*) " + from + where, p, Long.class);

        String orderCol = SORTS.getOrDefault(sort == null ? "" : sort, "a.account_id");
        String order = " ORDER BY " + orderCol + (desc ? " DESC" : " ASC") + ", a.account_id DESC";
        p.addValue("limit", size);
        p.addValue("offset", (long) page * size);

        RowMapper<AccountSummary> mapper = (rs, i) -> {
            LocalDate nextDue = Sql.date(rs, "next_due");
            String status = rs.getString("account_status");
            boolean live = !List.of("PENDING_DISBURSEMENT", "CLOSED", "FORECLOSED", "WRITTEN_OFF").contains(status);
            return new AccountSummary(rs.getLong("account_id"), rs.getString("account_number"),
                    rs.getLong("customer_id"), rs.getLong("sanction_ref_id"), rs.getString("product_code"),
                    rs.getString("account_type"), status, rs.getBigDecimal("sanctioned_amount"),
                    rs.getBigDecimal("disbursed_amount"), rs.getBigDecimal("interest_rate"),
                    rs.getInt("tenure_months"), rs.getBigDecimal("emi_amount"), nextDue,
                    live ? Sql.dpd(nextDue, today) : 0, Sql.ts(rs, "created_at"),
                    rs.getString("currency_code"), rs.getString("branch_code"));
        };
        List<AccountSummary> rows = jdbc.query("""
                SELECT a.account_id, a.account_number, a.customer_id, a.sanction_ref_id, a.product_code,
                       a.account_type, a.account_status, a.sanctioned_amount, a.disbursed_amount,
                       a.interest_rate, a.tenure_months, a.emi_amount, a.created_at, a.currency_code, a.branch_code, nd.next_due
                """ + from + where + order + " LIMIT :limit OFFSET :offset", p, mapper);
        return PageResponse.of(rows, page, size, total == null ? 0 : total);
    }

    /** Account without derived figures; the service layer adds those. */
    public Optional<AccountDetail> findById(long accountId) {
        return jdbc.query("SELECT * FROM loan_account WHERE account_id = :id",
                new MapSqlParameterSource("id", accountId), this::detail).stream().findFirst();
    }

    public Optional<Long> findIdByNumber(String accountNumber) {
        return jdbc.query("SELECT account_id FROM loan_account WHERE account_number = :n",
                new MapSqlParameterSource("n", accountNumber), (rs, i) -> rs.getLong(1)).stream().findFirst();
    }

    private AccountDetail detail(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new AccountDetail(rs.getLong("account_id"), rs.getString("account_number"), rs.getLong("customer_id"),
                rs.getLong("sanction_ref_id"), rs.getString("product_code"), rs.getString("account_type"),
                rs.getString("account_status"), rs.getBigDecimal("sanctioned_amount"),
                rs.getBigDecimal("disbursed_amount"), rs.getInt("tenure_months"), rs.getString("rate_type"),
                rs.getBigDecimal("interest_rate"), rs.getString("benchmark_rate_ref"), rs.getBigDecimal("spread"),
                rs.getString("day_count_convention"), rs.getBigDecimal("emi_amount"), Sql.date(rs, "emi_start_date"),
                Sql.longOrNull(rs, "mortgage_id"), rs.getInt("version"), Sql.ts(rs, "created_at"),
                Sql.ts(rs, "updated_at"), null, rs.getString("currency_code"), rs.getString("branch_code"));
    }

    public Optional<MortgageDetail> findMortgageByAccount(long accountId) {
        Optional<MortgageDetail> base = jdbc.query("SELECT * FROM mortgage WHERE account_id = :id",
                new MapSqlParameterSource("id", accountId), (rs, i) -> new MortgageDetail(
                        rs.getLong("mortgage_id"), rs.getLong("account_id"), rs.getString("property_type"),
                        rs.getString("property_address"), rs.getBigDecimal("valuation_amount"),
                        Sql.date(rs, "valuation_date"), rs.getString("valuer_ref"), rs.getBigDecimal("ltv_ratio"),
                        rs.getString("charge_status"), rs.getString("cersai_registration_ref"),
                        rs.getString("mortgage_deed_ref"), rs.getString("insurance_policy_ref"),
                        rs.getString("legal_verification_status"), List.of())).stream().findFirst();
        return base.map(m -> {
            List<ValuationRow> history = jdbc.query("""
                    SELECT valuation_amount, valuation_date, valuer_ref, valuation_type
                    FROM mortgage_valuation_history WHERE mortgage_id = :mid
                    ORDER BY valuation_date DESC, valuation_id DESC
                    """, new MapSqlParameterSource("mid", m.mortgageId()),
                    (rs, i) -> new ValuationRow(rs.getBigDecimal("valuation_amount"), Sql.date(rs, "valuation_date"),
                            rs.getString("valuer_ref"), rs.getString("valuation_type")));
            return new MortgageDetail(m.mortgageId(), m.accountId(), m.propertyType(), m.propertyAddress(),
                    m.valuationAmount(), m.valuationDate(), m.valuerRef(), m.ltvRatio(), m.chargeStatus(),
                    m.cersaiRegistrationRef(), m.mortgageDeedRef(), m.insurancePolicyRef(),
                    m.legalVerificationStatus(), history);
        });
    }
}
