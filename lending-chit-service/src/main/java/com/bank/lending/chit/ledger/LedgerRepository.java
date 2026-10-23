package com.bank.lending.chit.ledger;

import com.bank.lending.chit.ledger.LedgerDtos.AccountBalance;
import com.bank.lending.chit.ledger.LedgerDtos.JournalView;
import com.bank.lending.chit.ledger.LedgerDtos.LineView;
import com.bank.lending.chit.ledger.LedgerDtos.PostingContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** JDBC access to the ledger tables. Every method runs in the caller's transaction. */
@Repository
public class LedgerRepository {

    /** What an earlier journal with the same posting key looks like, to tell a retry from a clash. */
    public record Existing(long id, long groupId, String template, BigDecimal total) {
    }

    private final JdbcTemplate jdbc;

    public LedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates the account if it is not there yet and returns its id. Safe under concurrent callers. */
    public long ensureAccount(long groupId, AccountRef ref) {
        if (ref.ticketId() == null) {
            jdbc.update("INSERT INTO ledger_account (group_id, account_type, normal_side) VALUES (?,?,?) "
                            + "ON CONFLICT (group_id, account_type) WHERE ticket_id IS NULL DO NOTHING",
                    groupId, ref.type().name(), String.valueOf(ref.type().normalSide().code()));
            return jdbc.queryForObject("SELECT id FROM ledger_account WHERE group_id = ? AND account_type = ? AND ticket_id IS NULL",
                    Long.class, groupId, ref.type().name());
        }
        jdbc.update("INSERT INTO ledger_account (group_id, ticket_id, account_type, normal_side) VALUES (?,?,?,?) "
                        + "ON CONFLICT (group_id, account_type, ticket_id) WHERE ticket_id IS NOT NULL DO NOTHING",
                groupId, ref.ticketId(), ref.type().name(), String.valueOf(ref.type().normalSide().code()));
        return jdbc.queryForObject("SELECT id FROM ledger_account WHERE group_id = ? AND account_type = ? AND ticket_id = ?",
                Long.class, groupId, ref.type().name(), ref.ticketId());
    }

    /** Inserts the journal header; returns empty when the posting key already exists. */
    public Optional<Long> insertJournal(JournalDraft draft, PostingContext ctx, Long reversesJournalId) {
        List<Long> ids = jdbc.query(
                "INSERT INTO ledger_journal (group_id, template, posting_key, cycle_id, business_date, reference_type, reference_id, "
                        + "narration, reverses_journal_id, created_by) VALUES (?,?,?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT (posting_key) DO NOTHING RETURNING id",
                (rs, i) -> rs.getLong(1),
                ctx.groupId(), draft.template(), draft.postingKey(), ctx.cycleId(), Date.valueOf(ctx.businessDate()),
                ctx.referenceType(), ctx.referenceId(), draft.narration(), reversesJournalId, ctx.actor());
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    public void insertLine(long journalId, int lineNo, long accountId, Line line) {
        jdbc.update("INSERT INTO ledger_line (journal_id, line_no, account_id, side, amount) VALUES (?,?,?,?,?)",
                journalId, lineNo, accountId, String.valueOf(line.side().code()), line.amount());
    }

    public Optional<Existing> findByPostingKey(String postingKey) {
        List<Existing> rows = jdbc.query(
                "SELECT j.id, j.group_id, j.template, COALESCE(SUM(l.amount) FILTER (WHERE l.side = 'D'), 0) AS total "
                        + "FROM ledger_journal j LEFT JOIN ledger_line l ON l.journal_id = j.id "
                        + "WHERE j.posting_key = ? GROUP BY j.id",
                (rs, i) -> new Existing(rs.getLong("id"), rs.getLong("group_id"), rs.getString("template"), rs.getBigDecimal("total")),
                postingKey);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<JournalView> findJournal(long journalId) {
        List<JournalView> rows = jdbc.query(
                "SELECT id, group_id, template, posting_key, cycle_id, business_date, reference_type, reference_id, narration, "
                        + "reverses_journal_id, created_by, created_at FROM ledger_journal WHERE id = ?",
                (rs, i) -> toView(rs, List.of()), journalId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(withLines(rows.get(0)));
    }

    public List<JournalView> journals(long groupId, long afterId, int limit) {
        List<JournalView> heads = jdbc.query(
                "SELECT id, group_id, template, posting_key, cycle_id, business_date, reference_type, reference_id, narration, "
                        + "reverses_journal_id, created_by, created_at FROM ledger_journal WHERE group_id = ? AND id > ? "
                        + "ORDER BY id LIMIT ?",
                (rs, i) -> toView(rs, List.of()), groupId, afterId, limit);
        List<JournalView> out = new ArrayList<>();
        for (JournalView h : heads) {
            out.add(withLines(h));
        }
        return out;
    }

    /** A journal read back as a draft, with the template and key it was posted under. Used to build its reversal. */
    public JournalDraft loadDraft(long journalId) {
        JournalView v = findJournal(journalId).orElseThrow();
        List<Line> lines = new ArrayList<>();
        for (LineView l : v.lines()) {
            AccountRef ref = l.ticketId() == null ? AccountRef.of(l.accountType()) : AccountRef.of(l.accountType(), l.ticketId());
            lines.add(new Line(ref, Side.ofCode(l.side().charAt(0)), l.amount()));
        }
        return new JournalDraft(v.template(), v.postingKey(), v.narration(), lines);
    }

    public boolean isReversed(long journalId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM ledger_journal WHERE reverses_journal_id = ?", Integer.class, journalId);
        return n != null && n > 0;
    }

    public List<AccountBalance> balances(long groupId) {
        return jdbc.query(
                "SELECT a.id, a.account_type, a.ticket_id, a.normal_side, "
                        + "COALESCE(SUM(l.amount) FILTER (WHERE l.side = 'D'), 0) AS debits, "
                        + "COALESCE(SUM(l.amount) FILTER (WHERE l.side = 'C'), 0) AS credits "
                        + "FROM ledger_account a LEFT JOIN ledger_line l ON l.account_id = a.id "
                        + "WHERE a.group_id = ? GROUP BY a.id ORDER BY a.account_type, a.ticket_id NULLS FIRST",
                (rs, i) -> {
                    BigDecimal debits = rs.getBigDecimal("debits");
                    BigDecimal credits = rs.getBigDecimal("credits");
                    boolean debitNormal = "D".equals(rs.getString("normal_side"));
                    long ticket = rs.getLong("ticket_id");
                    Long ticketId = rs.wasNull() ? null : ticket;
                    return new AccountBalance(rs.getLong("id"), AccountType.valueOf(rs.getString("account_type")),
                            ticketId, debits, credits,
                            debitNormal ? debits.subtract(credits) : credits.subtract(debits));
                }, groupId);
    }

    private JournalView withLines(JournalView h) {
        List<LineView> lines = jdbc.query(
                "SELECT l.line_no, a.account_type, a.ticket_id, l.side, l.amount FROM ledger_line l "
                        + "JOIN ledger_account a ON a.id = l.account_id WHERE l.journal_id = ? ORDER BY l.line_no",
                (rs, i) -> {
                    long ticket = rs.getLong("ticket_id");
                    Long ticketId = rs.wasNull() ? null : ticket;
                    return new LineView(rs.getInt("line_no"), AccountType.valueOf(rs.getString("account_type")), ticketId,
                            rs.getString("side"), rs.getBigDecimal("amount"));
                }, h.id());
        return new JournalView(h.id(), h.groupId(), h.template(), h.postingKey(), h.cycleId(), h.businessDate(),
                h.referenceType(), h.referenceId(), h.narration(), h.reversesJournalId(), h.createdBy(), h.createdAt(), lines);
    }

    private static JournalView toView(java.sql.ResultSet rs, List<LineView> lines) throws java.sql.SQLException {
        long cycle = rs.getLong("cycle_id");
        Long cycleId = rs.wasNull() ? null : cycle;
        long rev = rs.getLong("reverses_journal_id");
        Long reverses = rs.wasNull() ? null : rev;
        LocalDate date = rs.getObject("business_date", LocalDate.class);
        return new JournalView(rs.getLong("id"), rs.getLong("group_id"), rs.getString("template"), rs.getString("posting_key"),
                cycleId, date, rs.getString("reference_type"), rs.getString("reference_id"), rs.getString("narration"),
                reverses, rs.getString("created_by"), rs.getObject("created_at", OffsetDateTime.class), lines);
    }
}
