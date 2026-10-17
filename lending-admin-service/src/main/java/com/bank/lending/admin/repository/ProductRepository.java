package com.bank.lending.admin.repository;

import com.bank.lending.admin.dto.AdminDtos.ProductRow;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Loan product master (table loan_product), read-only. */
@Repository
public class ProductRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ProductRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ProductRow> products() {
        return jdbc.query("""
                SELECT p.*, (SELECT COUNT(*) FROM loan_account a WHERE a.product_id = p.product_id) AS account_count
                FROM loan_product p
                ORDER BY p.product_code
                """, new MapSqlParameterSource(), (rs, i) -> new ProductRow(
                rs.getLong("product_id"), rs.getString("product_code"), rs.getString("product_name"),
                rs.getString("description"), rs.getString("account_type"), rs.getString("rate_type"),
                rs.getString("schedule_type"), rs.getString("day_count_convention"),
                rs.getString("benchmark_rate_ref"), rs.getBigDecimal("default_interest_rate"),
                rs.getBigDecimal("default_spread"), rs.getBigDecimal("min_interest_rate"),
                rs.getBigDecimal("max_interest_rate"), rs.getBigDecimal("min_spread"),
                rs.getBigDecimal("max_spread"), rs.getBigDecimal("min_sanctioned_amount"),
                rs.getBigDecimal("max_sanctioned_amount"), Sql.intOrNull(rs, "min_tenure_months"),
                Sql.intOrNull(rs, "max_tenure_months"),
                rs.getString("repayment_frequency"),
                List.of(rs.getString("allowed_repayment_frequencies").split(",")),
                rs.getInt("moratorium_months"), Sql.intOrNull(rs, "max_moratorium_months"),
                rs.getBigDecimal("balloon_percent"), rs.getBigDecimal("max_balloon_percent"),
                rs.getBigDecimal("step_up_percent"), rs.getBigDecimal("max_step_up_percent"),
                rs.getBoolean("penal_interest_enabled"), rs.getBigDecimal("penal_interest_rate"),
                rs.getInt("penal_grace_days"), rs.getBigDecimal("penal_cap_percent"),
                rs.getString("status"), rs.getLong("account_count"), rs.getInt("version")));
    }
}
