package com.bank.lending.accounting.repository;

import com.bank.lending.accounting.AccountingSettings;
import com.bank.lending.accounting.engine.AccountingConfig;
import com.bank.lending.accounting.model.Defs.AccountDef;
import com.bank.lending.accounting.model.Defs.EventDef;
import com.bank.lending.accounting.model.Defs.LedgerDef;
import com.bank.lending.accounting.model.Defs.RouteDef;
import com.bank.lending.accounting.model.Defs.RuleDef;
import com.bank.lending.accounting.model.Defs.TemplateDef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Loads the accounting configuration (events, approved templates, ledgers, accounts, routes) into an immutable
 * {@link AccountingConfig}. The snapshot is cached for {@code accounting.config-cache-seconds}; a change made through
 * this instance evicts it at once, a change made on another instance is seen within the cache period.
 */
@Repository
public class AccountingConfigRepository {

    private final JdbcTemplate jdbc;
    private final AccountingSettings settings;
    private volatile AccountingConfig cached;
    private volatile long loadedAtMillis;

    public AccountingConfigRepository(JdbcTemplate jdbc, AccountingSettings settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    public AccountingConfig current() {
        AccountingConfig c = cached;
        long age = System.currentTimeMillis() - loadedAtMillis;
        if (c == null || age > settings.configCacheSeconds() * 1000L) {
            c = load();
            cached = c;
            loadedAtMillis = System.currentTimeMillis();
        }
        return c;
    }

    public void evict() {
        cached = null;
    }

    /** The internal, primary ledger: where sub-ledger balances are derived and reconciled. */
    public Optional<LedgerDef> primaryLedger() {
        return current().ledgers().values().stream()
                .filter(l -> "INTERNAL".equals(l.ledgerType()) && "PRIMARY".equals(l.purpose()) && !"CLOSED".equals(l.status()))
                .findFirst();
    }

    /** A template of any status, with its active rules (used by the simulator and by template validation). */
    public Optional<TemplateDef> template(long templateId) {
        List<TemplateDef> list = jdbc.query(
                "SELECT template_id, event_code, product_code, version, effective_from, effective_to "
                        + "FROM acct_entry_template WHERE template_id = ?",
                (rs, i) -> new TemplateDef(rs.getLong("template_id"), rs.getString("event_code"),
                        rs.getString("product_code"), rs.getInt("version"), date(rs, "effective_from"),
                        date(rs, "effective_to"), List.of()), templateId);
        if (list.isEmpty()) {
            return Optional.empty();
        }
        TemplateDef t = list.get(0);
        return Optional.of(new TemplateDef(t.templateId(), t.eventCode(), t.productCode(), t.version(),
                t.effectiveFrom(), t.effectiveTo(), rules(templateId)));
    }

    public List<RuleDef> rules(long templateId) {
        return jdbc.query(
                "SELECT rule_id, line_seq, side, role_code, amount_source, condition_expr, ledger_scope, currency_basis, "
                        + "narrative FROM acct_entry_rule WHERE template_id = ? AND active ORDER BY line_seq",
                (rs, i) -> rule(rs), templateId);
    }

    private AccountingConfig load() {
        Map<String, Set<String>> tags = new HashMap<>();
        jdbc.query("SELECT event_code, tag_code FROM acct_amount_tag",
                rs -> {
                    tags.computeIfAbsent(rs.getString("event_code"), k -> new HashSet<>()).add(rs.getString("tag_code"));
                });
        Map<String, EventDef> events = new LinkedHashMap<>();
        jdbc.query("SELECT event_code, name, category, blocking_mode, posting_mode, eod_gate, on_missing_template, "
                        + "narrative_pattern, status, fx_rate_type FROM acct_event_def ORDER BY event_code",
                rs -> {
                    String code = rs.getString("event_code");
                    events.put(code, new EventDef(code, rs.getString("name"), rs.getString("category"),
                            rs.getString("blocking_mode"), rs.getString("posting_mode"), rs.getBoolean("eod_gate"),
                            rs.getString("on_missing_template"), rs.getString("narrative_pattern"),
                            rs.getString("status"), rs.getString("fx_rate_type"),
                            tags.getOrDefault(code, Set.of())));
                });

        Map<Long, List<RuleDef>> rulesByTemplate = new HashMap<>();
        jdbc.query("SELECT r.template_id, r.rule_id, r.line_seq, r.side, r.role_code, r.amount_source, r.condition_expr, "
                        + "r.ledger_scope, r.currency_basis, r.narrative FROM acct_entry_rule r "
                        + "JOIN acct_entry_template t ON t.template_id = r.template_id "
                        + "WHERE t.status = 'APPROVED' AND r.active ORDER BY r.template_id, r.line_seq",
                rs -> {
                    rulesByTemplate.computeIfAbsent(rs.getLong("template_id"), k -> new ArrayList<>()).add(rule(rs));
                });
        Map<String, List<TemplateDef>> templates = new HashMap<>();
        jdbc.query("SELECT template_id, event_code, product_code, version, effective_from, effective_to "
                        + "FROM acct_entry_template WHERE status = 'APPROVED'",
                rs -> {
                    long id = rs.getLong("template_id");
                    String event = rs.getString("event_code");
                    templates.computeIfAbsent(event, k -> new ArrayList<>()).add(new TemplateDef(id, event,
                            rs.getString("product_code"), rs.getInt("version"), date(rs, "effective_from"),
                            date(rs, "effective_to"), rulesByTemplate.getOrDefault(id, List.of())));
                });

        Map<Integer, LedgerDef> ledgers = new LinkedHashMap<>();
        jdbc.query("SELECT ledger_id, ledger_code, ledger_type, purpose, base_currency, delivery_mode, status "
                        + "FROM gl_ledger ORDER BY ledger_id",
                rs -> {
                    int id = rs.getInt("ledger_id");
                    ledgers.put(id, new LedgerDef(id, rs.getString("ledger_code"), rs.getString("ledger_type"),
                            rs.getString("purpose"), rs.getString("base_currency"), rs.getString("delivery_mode"),
                            rs.getString("status")));
                });
        Map<String, AccountDef> accounts = new HashMap<>();
        jdbc.query("SELECT ledger_id, gl_account_code, nature, normal_balance, posting_allowed, status, "
                        + "currency_restriction FROM gl_account",
                rs -> {
                    int lid = rs.getInt("ledger_id");
                    String code = rs.getString("gl_account_code");
                    accounts.put(AccountingConfig.accountKey(lid, code), new AccountDef(lid, code, rs.getString("nature"),
                            rs.getString("normal_balance").trim(), rs.getBoolean("posting_allowed"),
                            rs.getString("status"), rs.getString("currency_restriction")));
                });
        List<RouteDef> routes = jdbc.query(
                "SELECT route_id, ledger_id, role_code, product_code, npa_stage, currency, gl_account_code, priority, "
                        + "effective_from, effective_to, branch_code FROM acct_route WHERE status = 'APPROVED'",
                (rs, i) -> new RouteDef(rs.getLong("route_id"), rs.getInt("ledger_id"), rs.getString("role_code"),
                        rs.getString("product_code"), rs.getString("npa_stage"), trimmed(rs.getString("currency")),
                        rs.getString("gl_account_code"), rs.getInt("priority"), date(rs, "effective_from"),
                        date(rs, "effective_to"), rs.getString("branch_code")));
        Map<String, List<Integer>> eventLedgers = new HashMap<>();
        jdbc.query("SELECT event_code, ledger_id FROM acct_event_ledger ORDER BY ledger_id",
                rs -> {
                    eventLedgers.computeIfAbsent(rs.getString("event_code"), k -> new ArrayList<>()).add(rs.getInt("ledger_id"));
                });
        return new AccountingConfig(Map.copyOf(events), Map.copyOf(templates), Map.copyOf(ledgers), Map.copyOf(accounts),
                List.copyOf(routes), Map.copyOf(eventLedgers));
    }

    private static RuleDef rule(ResultSet rs) throws SQLException {
        return new RuleDef(rs.getLong("rule_id"), rs.getInt("line_seq"), rs.getString("side").trim(),
                rs.getString("role_code"), rs.getString("amount_source"), rs.getString("condition_expr"),
                rs.getString("ledger_scope"), rs.getString("currency_basis"), rs.getString("narrative"));
    }

    private static LocalDate date(ResultSet rs, String col) throws SQLException {
        return rs.getObject(col, LocalDate.class);
    }

    private static String trimmed(String s) {
        return s == null ? null : s.trim();
    }
}
