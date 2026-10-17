package com.bank.lending.accounting.repository;

import com.bank.lending.accounting.dto.AccountingDtos.ReconciliationRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Queries and results of the daily balance snapshot and the reconciliation to the loan sub-ledger. */
@Repository
public class ReconciliationRepository {

    private final JdbcTemplate jdbc;

    public ReconciliationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Net debit (debits minus credits) per account and role in one ledger, for the given roles, on journals up to
     * the date. Journals of accounts that are not loan accounts (account_id null) are not included.
     */
    public Map<Long, Map<String, BigDecimal>> netDebitByAccountAndRole(int ledgerId, LocalDate asOf, List<String> roles) {
        Map<Long, Map<String, BigDecimal>> out = new HashMap<>();
        String in = String.join(",", roles.stream().map(r -> "?").toList());
        Object[] args = new Object[roles.size() + 2];
        args[0] = ledgerId;
        args[1] = asOf;
        for (int i = 0; i < roles.size(); i++) {
            args[i + 2] = roles.get(i);
        }
        jdbc.query("SELECT account_id, role_code, SUM(CASE side WHEN 'DR' THEN amount_base ELSE -amount_base END) "
                        + "FROM acct_journal_line WHERE ledger_id = ? AND business_date <= ? AND account_id IS NOT NULL AND posting_mode = 'LIVE' "
                        + "AND role_code IN (" + in + ") GROUP BY account_id, role_code",
                rs -> {
                    out.computeIfAbsent(rs.getLong(1), k -> new HashMap<>()).put(rs.getString(2), rs.getBigDecimal(3));
                }, args);
        return out;
    }

    /** Debits and credits per ledger, currency and branch over all journals up to the date. */
    public List<Object[]> ledgerTotals(LocalDate asOf) {
        return jdbc.query("SELECT ledger_id, currency_base, "
                        + "COALESCE(SUM(CASE WHEN side = 'DR' THEN amount_base END), 0), "
                        + "COALESCE(SUM(CASE WHEN side = 'CR' THEN amount_base END), 0), branch_code "
                        + "FROM acct_journal_line WHERE business_date <= ? AND posting_mode = 'LIVE' "
                        + "GROUP BY ledger_id, currency_base, branch_code ORDER BY ledger_id, currency_base, branch_code",
                (rs, i) -> new Object[]{rs.getInt(1), rs.getString(2).trim(), rs.getBigDecimal(3), rs.getBigDecimal(4),
                        rs.getString(5)},
                asOf);
    }

    /**
     * Accounts that have a live journal posted in a different currency or branch from the one on the account itself.
     * Journals take both from the account, so any row here means an entry was posted to the wrong place.
     */
    public List<Long> accountsPostedInWrongCurrencyOrBranch() {
        return jdbc.query("SELECT j.account_id FROM acct_journal j JOIN loan_account a ON a.account_id = j.account_id "
                        + "WHERE j.posting_mode = 'LIVE' AND (j.branch_code <> a.branch_code OR j.txn_currency <> a.currency_code) "
                        + "GROUP BY j.account_id ORDER BY j.account_id",
                (rs, i) -> rs.getLong(1));
    }

    public void save(LocalDate businessDate, String checkCode, boolean pass, BigDecimal expected, BigDecimal actual,
                     BigDecimal difference, String detail) {
        jdbc.update("INSERT INTO acct_reconciliation(business_date, check_code, status, expected_amount, actual_amount, "
                        + "difference, detail) VALUES (?, ?, ?, ?, ?, ?, ?)", businessDate, checkCode,
                pass ? "PASS" : "FAIL", expected, actual, difference,
                detail == null ? null : detail.substring(0, Math.min(detail.length(), 500)));
    }

    /** The most recent run for a date: the latest row of each check. */
    public List<ReconciliationRow> latest(LocalDate businessDate) {
        return jdbc.query("SELECT DISTINCT ON (check_code) check_code, status, expected_amount, actual_amount, "
                        + "difference, detail, business_date, run_at FROM acct_reconciliation WHERE business_date = ? "
                        + "ORDER BY check_code, run_at DESC, recon_id DESC",
                (rs, i) -> new ReconciliationRow(rs.getString(1), rs.getString(2), rs.getBigDecimal(3),
                        rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getString(6), rs.getObject(7, LocalDate.class),
                        rs.getObject(8, OffsetDateTime.class)), businessDate);
    }

    /**
     * Writes the day's balance snapshot (opening, debits, credits, closing as net debit) for every GL account that
     * has journals up to the date. Re-running for a date replaces that date's rows.
     */
    public int snapshot(LocalDate businessDate) {
        jdbc.update("DELETE FROM acct_balance_daily WHERE business_date = ?", businessDate);
        return jdbc.update("INSERT INTO acct_balance_daily(ledger_id, gl_account_code, currency, branch_code, business_date, "
                + "opening, debits, credits, closing) "
                + "SELECT ledger_id, gl_account_code, currency_base, branch_code, ?, "
                + "COALESCE(SUM(CASE WHEN business_date < ? THEN CASE side WHEN 'DR' THEN amount_base ELSE -amount_base END END), 0), "
                + "COALESCE(SUM(CASE WHEN business_date = ? AND side = 'DR' THEN amount_base END), 0), "
                + "COALESCE(SUM(CASE WHEN business_date = ? AND side = 'CR' THEN amount_base END), 0), "
                + "SUM(CASE side WHEN 'DR' THEN amount_base ELSE -amount_base END) "
                + "FROM acct_journal_line WHERE business_date <= ? AND posting_mode = 'LIVE' GROUP BY ledger_id, gl_account_code, currency_base, branch_code",
                businessDate, businessDate, businessDate, businessDate, businessDate);
    }
}
