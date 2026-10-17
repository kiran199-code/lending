package com.bank.lending.accounting.repository;

import com.bank.lending.accounting.dto.AccountingDtos.JournalLineView;
import com.bank.lending.accounting.dto.AccountingDtos.JournalSummary;
import com.bank.lending.accounting.dto.AccountingDtos.TrialBalanceRow;
import com.bank.lending.accounting.model.Defs.DraftJournal;
import com.bank.lending.accounting.model.Defs.DraftLine;
import com.bank.lending.accounting.model.Defs.PostingRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Reads and writes the journal tables. Journals are append-only: this class never updates or deletes one. */
@Repository
public class JournalRepository {

    public record Stored(long journalId, String journalNo, LocalDate businessDate) {
    }

    private static final String SUMMARY_COLUMNS =
            "journal_id, journal_no, event_code, account_id, product_code, source_type, source_id, business_date, "
                    + "value_date, txn_currency, posting_mode, is_backdated, prior_period, reversal_of_journal_id, "
                    + "narrative, template_id, template_version, created_at, branch_code";

    private final JdbcTemplate jdbc;

    public JournalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long nextJournalId() {
        Long id = jdbc.queryForObject("SELECT nextval('acct_journal_seq')", Long.class);
        return id == null ? 0L : id;
    }

    public static String journalNo(long journalId) {
        return String.format("JV%012d", journalId);
    }

    /**
     * Claims the idempotency key for a new journal. Returns false when the key is already taken, in which case the
     * caller must return the existing journal. A concurrent claim of the same key waits for the first transaction.
     */
    public boolean claimKey(String key, long journalId, LocalDate businessDate) {
        int n = jdbc.update("INSERT INTO acct_idempotency(idempotency_key, journal_id, business_date) VALUES (?, ?, ?) "
                + "ON CONFLICT (idempotency_key) DO NOTHING", key, journalId, businessDate);
        return n == 1;
    }

    public Optional<Stored> findByKey(String key) {
        List<Stored> found = jdbc.query(
                "SELECT i.journal_id, i.business_date, j.journal_no FROM acct_idempotency i "
                        + "LEFT JOIN acct_journal j ON j.journal_id = i.journal_id AND j.business_date = i.business_date "
                        + "WHERE i.idempotency_key = ?",
                (rs, n) -> new Stored(rs.getLong("journal_id"),
                        rs.getString("journal_no") == null ? journalNo(rs.getLong("journal_id")) : rs.getString("journal_no"),
                        rs.getObject("business_date", LocalDate.class)), key);
        return found.stream().findFirst();
    }

    public void insert(long journalId, PostingRequest req, DraftJournal draft, String postingMode, boolean backdated,
                       boolean priorPeriod, Long reversalOf, String stage) {
        jdbc.update("INSERT INTO acct_journal(journal_id, journal_no, event_code, template_id, template_version, "
                        + "account_id, product_code, source_type, source_id, source_date, idempotency_key, business_date, "
                        + "value_date, txn_currency, base_currency, posting_mode, is_backdated, prior_period, "
                        + "reversal_of_journal_id, narrative, created_by, branch_code) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                journalId, journalNo(journalId), draft.eventCode(), draft.templateId(), draft.templateVersion(),
                req.accountId(), req.productCode(), req.sourceType(), req.sourceId(), req.sourceDate(),
                req.idempotencyKey(), req.businessDate(), req.valueDate(), draft.txnCurrency(), draft.baseCurrency(),
                postingMode, backdated, priorPeriod, reversalOf, truncate(draft.narrative(), 300),
                req.createdBy(), req.branchCode());
        List<Object[]> batch = new ArrayList<>();
        for (DraftLine l : draft.lines()) {
            batch.add(new Object[]{journalId, req.businessDate(), l.lineSeq(), l.ledgerId(), l.glAccountCode(),
                    l.roleCode(), l.side(), l.amountTxn(), l.currencyTxn(), l.fxRate(), l.fxRateType(), l.amountBase(),
                    l.currencyBase(), req.accountId(), req.productCode(), stage, l.ruleId() == 0L ? null : l.ruleId(),
                    l.rateStale(), truncate(l.narrative(), 300), postingMode, req.branchCode()});
        }
        jdbc.batchUpdate("INSERT INTO acct_journal_line(line_id, journal_id, business_date, line_seq, ledger_id, "
                + "gl_account_code, role_code, side, amount_txn, currency_txn, fx_rate, fx_rate_type, amount_base, "
                + "currency_base, account_id, product_code, npa_stage, rule_id, rate_stale, narrative, posting_mode, branch_code) "
                + "VALUES (nextval('acct_line_seq'), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", batch);
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    // ------------------------------------------------------------------ reads

    public List<JournalSummary> search(Long accountId, String eventCode, String journalNo, LocalDate from, LocalDate to,
                                       String branchCode, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT " + SUMMARY_COLUMNS + " FROM acct_journal WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (accountId != null) {
            sql.append(" AND account_id = ?");
            args.add(accountId);
        }
        if (eventCode != null && !eventCode.isBlank()) {
            sql.append(" AND event_code = ?");
            args.add(eventCode);
        }
        if (journalNo != null && !journalNo.isBlank()) {
            sql.append(" AND journal_no = ?");
            args.add(journalNo);
        }
        if (branchCode != null && !branchCode.isBlank()) {
            sql.append(" AND branch_code = ?");
            args.add(branchCode);
        }
        if (from != null) {
            sql.append(" AND business_date >= ?");
            args.add(from);
        }
        if (to != null) {
            sql.append(" AND business_date <= ?");
            args.add(to);
        }
        sql.append(" ORDER BY business_date DESC, journal_id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), (rs, i) -> summary(rs), args.toArray());
    }

    public Optional<JournalSummary> find(long journalId) {
        return jdbc.query("SELECT " + SUMMARY_COLUMNS + " FROM acct_journal WHERE journal_id = ?",
                (rs, i) -> summary(rs), journalId).stream().findFirst();
    }

    public List<JournalLineView> lines(long journalId, LocalDate businessDate) {
        return jdbc.query("SELECT l.line_id, l.line_seq, l.ledger_id, g.ledger_code, l.gl_account_code, l.role_code, "
                        + "l.side, l.amount_txn, l.currency_txn, l.fx_rate, l.amount_base, l.currency_base, l.rule_id, "
                        + "l.narrative FROM acct_journal_line l JOIN gl_ledger g ON g.ledger_id = l.ledger_id "
                        + "WHERE l.journal_id = ? AND l.business_date = ? ORDER BY l.line_seq",
                (rs, i) -> new JournalLineView(rs.getLong("line_id"), rs.getInt("line_seq"), rs.getInt("ledger_id"),
                        rs.getString("ledger_code"), rs.getString("gl_account_code"), rs.getString("role_code"),
                        rs.getString("side").trim(), rs.getBigDecimal("amount_txn"), trimmed(rs.getString("currency_txn")),
                        rs.getBigDecimal("fx_rate"), rs.getBigDecimal("amount_base"),
                        trimmed(rs.getString("currency_base")), nullableLong(rs, "rule_id"), rs.getString("narrative")),
                journalId, businessDate);
    }

    public Optional<Long> reversedBy(long journalId) {
        return jdbc.query("SELECT reversal_journal_id FROM acct_journal_reversal WHERE original_journal_id = ?",
                (rs, i) -> rs.getLong(1), journalId).stream().findFirst();
    }

    /** Debits minus credits posted to a role for one account in one ledger (positive = net debit). */
    public BigDecimal netDebit(long accountId, int ledgerId, String roleCode) {
        BigDecimal v = jdbc.queryForObject(
                "SELECT COALESCE(SUM(CASE side WHEN 'DR' THEN amount_base ELSE -amount_base END), 0) "
                        + "FROM acct_journal_line WHERE account_id = ? AND ledger_id = ? AND role_code = ? AND posting_mode = 'LIVE'",
                BigDecimal.class, accountId, ledgerId, roleCode);
        return v == null ? BigDecimal.ZERO : v;
    }

    /** How many journals the account has, of any event. */
    public long countForAccount(long accountId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM acct_journal WHERE account_id = ?", Long.class, accountId);
        return n == null ? 0L : n;
    }

    /** Status of the accounting period that contains the date; OPEN when no period row exists for it. */
    public String periodStatus(LocalDate date) {
        List<String> s = jdbc.query("SELECT status FROM acct_period WHERE ? BETWEEN start_date AND end_date",
                (rs, i) -> rs.getString(1), date);
        return s.isEmpty() ? "OPEN" : s.get(0);
    }

    /** Marks a claimed key as a posting that produced no journal (all amounts zero). */
    public void markNoJournal(String key) {
        jdbc.update("UPDATE acct_idempotency SET journal_id = 0 WHERE idempotency_key = ?", key);
    }

    public boolean hasJournal(String idempotencyKey) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM acct_idempotency WHERE idempotency_key = ?",
                Integer.class, idempotencyKey);
        return n != null && n > 0;
    }

    public List<TrialBalanceRow> trialBalance(LocalDate asOf, Integer ledgerId, String branchCode) {
        StringBuilder sql = new StringBuilder(
                "SELECT l.ledger_id, g.ledger_code, l.gl_account_code, a.name, l.currency_base, l.branch_code, "
                        + "SUM(CASE WHEN l.side = 'DR' THEN l.amount_base ELSE 0 END) AS debits, "
                        + "SUM(CASE WHEN l.side = 'CR' THEN l.amount_base ELSE 0 END) AS credits "
                        + "FROM acct_journal_line l "
                        + "JOIN gl_ledger g ON g.ledger_id = l.ledger_id "
                        + "JOIN gl_account a ON a.ledger_id = l.ledger_id AND a.gl_account_code = l.gl_account_code "
                        + "WHERE l.business_date <= ? AND l.posting_mode = 'LIVE'");
        List<Object> args = new ArrayList<>();
        args.add(asOf);
        if (ledgerId != null) {
            sql.append(" AND l.ledger_id = ?");
            args.add(ledgerId);
        }
        if (branchCode != null && !branchCode.isBlank()) {
            sql.append(" AND l.branch_code = ?");
            args.add(branchCode);
        }
        sql.append(" GROUP BY l.ledger_id, g.ledger_code, l.gl_account_code, a.name, l.currency_base, l.branch_code "
                + "ORDER BY l.ledger_id, l.gl_account_code, l.branch_code");
        return jdbc.query(sql.toString(), (rs, i) -> {
            BigDecimal d = rs.getBigDecimal("debits");
            BigDecimal c = rs.getBigDecimal("credits");
            return new TrialBalanceRow(rs.getInt("ledger_id"), rs.getString("ledger_code"),
                    rs.getString("gl_account_code"), rs.getString("name"), trimmed(rs.getString("currency_base")), d, c,
                    d.subtract(c), rs.getString("branch_code"));
        }, args.toArray());
    }

    /** Total debits and credits per ledger for one business date: the integrity check. */
    public List<BigDecimal[]> dayTotals(LocalDate businessDate) {
        return jdbc.query("SELECT ledger_id, currency_base, "
                        + "COALESCE(SUM(CASE WHEN side = 'DR' THEN amount_base END), 0), "
                        + "COALESCE(SUM(CASE WHEN side = 'CR' THEN amount_base END), 0) "
                        + "FROM acct_journal_line WHERE business_date = ? AND posting_mode = 'LIVE' GROUP BY ledger_id, currency_base",
                (rs, i) -> new BigDecimal[]{BigDecimal.valueOf(rs.getInt(1)), rs.getBigDecimal(3), rs.getBigDecimal(4)},
                businessDate);
    }

    private static JournalSummary summary(ResultSet rs) throws SQLException {
        return new JournalSummary(rs.getLong("journal_id"), rs.getString("journal_no"), rs.getString("event_code"),
                nullableLong(rs, "account_id"), rs.getString("product_code"), rs.getString("source_type"),
                nullableLong(rs, "source_id"), rs.getObject("business_date", LocalDate.class),
                rs.getObject("value_date", LocalDate.class), trimmed(rs.getString("txn_currency")),
                rs.getString("posting_mode"), rs.getBoolean("is_backdated"), rs.getBoolean("prior_period"),
                nullableLong(rs, "reversal_of_journal_id"), rs.getString("narrative"), nullableLong(rs, "template_id"),
                rs.getObject("template_version") == null ? null : rs.getInt("template_version"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getString("branch_code"));
    }

    static Long nullableLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    static String trimmed(String s) {
        return s == null ? null : s.trim();
    }
}
