package com.bank.lending.refdata;

import com.bank.lending.refdata.ReferenceDtos.BranchView;
import com.bank.lending.refdata.ReferenceDtos.CurrencyView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** The currency and branch reference tables (V18). */
@Repository
public class ReferenceDataRepository {

    private final JdbcTemplate jdbc;

    public ReferenceDataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** All currencies; {@code base} is filled in by the service, which knows the base currency. */
    public List<CurrencyView> currencies(String baseCurrency) {
        return jdbc.query("SELECT currency_code, currency_name, minor_units, active FROM currency ORDER BY currency_code",
                (rs, i) -> new CurrencyView(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getBoolean(4),
                        rs.getString(1).equals(baseCurrency)));
    }

    public Optional<CurrencyView> currency(String code, String baseCurrency) {
        return jdbc.query("SELECT currency_code, currency_name, minor_units, active FROM currency WHERE currency_code = ?",
                (rs, i) -> new CurrencyView(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getBoolean(4),
                        rs.getString(1).equals(baseCurrency)), code).stream().findFirst();
    }

    public void insertCurrency(String code, String name, int minorUnits, boolean active) {
        jdbc.update("INSERT INTO currency(currency_code, currency_name, minor_units, active) VALUES (?, ?, ?, ?)",
                code, name, minorUnits, active);
    }

    public void updateCurrency(String code, String name, Integer minorUnits, boolean active) {
        jdbc.update("UPDATE currency SET currency_name = COALESCE(?, currency_name), minor_units = COALESCE(?, minor_units), "
                + "active = ?, updated_at = now() WHERE currency_code = ?", name, minorUnits, active, code);
    }

    public List<BranchView> branches(String defaultBranch) {
        return jdbc.query("SELECT branch_code, branch_name, active FROM branch ORDER BY branch_code",
                (rs, i) -> new BranchView(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getString(1).equals(defaultBranch)));
    }

    public Optional<BranchView> branch(String code, String defaultBranch) {
        return jdbc.query("SELECT branch_code, branch_name, active FROM branch WHERE branch_code = ?",
                (rs, i) -> new BranchView(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getString(1).equals(defaultBranch)),
                code).stream().findFirst();
    }

    public void insertBranch(String code, String name, boolean active) {
        jdbc.update("INSERT INTO branch(branch_code, branch_name, active) VALUES (?, ?, ?)", code, name, active);
    }

    public void updateBranch(String code, String name, boolean active) {
        jdbc.update("UPDATE branch SET branch_name = COALESCE(?, branch_name), active = ?, updated_at = now() "
                + "WHERE branch_code = ?", name, active, code);
    }

    public long accountsInCurrency(String code) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM loan_account WHERE currency_code = ?", Long.class, code);
        return n == null ? 0L : n;
    }

    public long accountsInBranch(String code) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM loan_account WHERE branch_code = ?", Long.class, code);
        return n == null ? 0L : n;
    }
}
