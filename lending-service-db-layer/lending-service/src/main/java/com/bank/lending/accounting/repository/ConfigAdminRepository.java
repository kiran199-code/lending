package com.bank.lending.accounting.repository;

import com.bank.lending.accounting.dto.AccountingDtos.EventView;
import com.bank.lending.accounting.dto.AccountingDtos.GlAccountView;
import com.bank.lending.accounting.dto.AccountingDtos.LedgerView;
import com.bank.lending.accounting.dto.AccountingDtos.RouteView;
import com.bank.lending.accounting.dto.AccountingDtos.RuleInput;
import com.bank.lending.accounting.dto.AccountingDtos.RuleView;
import com.bank.lending.accounting.dto.AccountingDtos.TemplateView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Reads and writes accounting configuration rows for the admin APIs (templates, rules, routes, event switches). */
@Repository
public class ConfigAdminRepository {

    /** The header facts the lifecycle checks need. */
    public record TemplateHead(long templateId, String eventCode, String productCode, int version, String status,
                               LocalDate effectiveFrom, LocalDate effectiveTo, String createdBy) {
    }

    public record RouteHead(long routeId, String status, String createdBy) {
    }

    private final JdbcTemplate jdbc;

    public ConfigAdminRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------------------------------------ audit

    public void audit(String entity, String key, String action, String actor, String detail) {
        jdbc.update("INSERT INTO acct_config_audit(entity, entity_key, action, actor, detail) VALUES (?, ?, ?, ?, ?)",
                entity, key, action, actor, detail == null ? null : detail.substring(0, Math.min(detail.length(), 500)));
    }

    // ------------------------------------------------------------------------------------------------ templates

    public int nextVersion(String eventCode, String productCode) {
        Integer v = jdbc.queryForObject("SELECT COALESCE(MAX(version), 0) + 1 FROM acct_entry_template "
                + "WHERE event_code = ? AND COALESCE(product_code, '') = COALESCE(?, '')", Integer.class, eventCode,
                productCode);
        return v == null ? 1 : v;
    }

    public long insertTemplate(String eventCode, String productCode, int version, LocalDate from, LocalDate to,
                               String description, String createdBy) {
        Long id = jdbc.queryForObject("INSERT INTO acct_entry_template(event_code, product_code, version, status, "
                + "effective_from, effective_to, description, created_by) VALUES (?, ?, ?, 'DRAFT', "
                + "COALESCE(?, DATE '2000-01-01'), ?, ?, ?) RETURNING template_id", Long.class, eventCode,
                productCode, version, from, to, description, createdBy);
        return id == null ? 0L : id;
    }

    public void updateTemplateHeader(long templateId, LocalDate from, LocalDate to, String description) {
        jdbc.update("UPDATE acct_entry_template SET effective_from = COALESCE(?, effective_from), effective_to = ?, "
                + "description = ? WHERE template_id = ?", from, to, description, templateId);
    }

    public void replaceRules(long templateId, List<RuleInput> rules) {
        jdbc.update("DELETE FROM acct_entry_rule WHERE template_id = ?", templateId);
        int seq = 0;
        List<Object[]> batch = new ArrayList<>();
        for (RuleInput r : rules) {
            seq++;
            batch.add(new Object[]{templateId, r.lineSeq() == null ? seq * 10 : r.lineSeq(), r.side(), r.roleCode(),
                    r.amountSource(), blankToNull(r.condition()), blankToNull(r.ledgerScope()),
                    r.currencyBasis() == null ? "TXN" : r.currencyBasis(), r.narrative()});
        }
        jdbc.batchUpdate("INSERT INTO acct_entry_rule(template_id, line_seq, side, role_code, amount_source, "
                + "condition_expr, ledger_scope, currency_basis, narrative) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", batch);
    }

    public Optional<TemplateHead> templateHead(long templateId) {
        return jdbc.query("SELECT template_id, event_code, product_code, version, status, effective_from, "
                        + "effective_to, created_by FROM acct_entry_template WHERE template_id = ?",
                (rs, i) -> new TemplateHead(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                        rs.getString(5), rs.getObject(6, LocalDate.class), rs.getObject(7, LocalDate.class),
                        rs.getString(8)), templateId).stream().findFirst();
    }

    public void setTemplateStatus(long templateId, String status, String approvedBy) {
        jdbc.update("UPDATE acct_entry_template SET status = ?, approved_by = ?, "
                        + "approved_at = CASE WHEN ? = 'APPROVED' THEN now() ELSE approved_at END WHERE template_id = ?",
                status, approvedBy, status, templateId);
    }

    /**
     * When a template is approved from {@code newFrom}, the earlier approved version of the same event and product
     * that is still open (or runs past that date) ends the day before.
     */
    public int endPreviousVersions(long newTemplateId, String eventCode, String productCode, LocalDate newFrom) {
        return jdbc.update("UPDATE acct_entry_template SET effective_to = ? WHERE status = 'APPROVED' "
                + "AND template_id <> ? AND event_code = ? AND COALESCE(product_code, '') = COALESCE(?, '') "
                + "AND effective_from < ? AND (effective_to IS NULL OR effective_to >= ?)",
                newFrom.minusDays(1), newTemplateId, eventCode, productCode, newFrom, newFrom);
    }

    public List<TemplateView> templates(String eventCode, String status) {
        return templates(null, eventCode, status);
    }

    private List<TemplateView> templates(Long templateId, String eventCode, String status) {
        StringBuilder sql = new StringBuilder("SELECT template_id, event_code, product_code, version, status, "
                + "effective_from, effective_to, created_by, approved_by FROM acct_entry_template WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (templateId != null) {
            sql.append(" AND template_id = ?");
            args.add(templateId);
        }
        if (eventCode != null && !eventCode.isBlank()) {
            sql.append(" AND event_code = ?");
            args.add(eventCode);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY event_code, COALESCE(product_code, ''), version DESC");
        Map<Long, List<RuleView>> rules = new HashMap<>();
        jdbc.query("SELECT r.template_id, r.rule_id, r.line_seq, r.side, r.role_code, r.amount_source, "
                        + "r.condition_expr, r.ledger_scope, r.currency_basis, r.narrative FROM acct_entry_rule r "
                        + "JOIN acct_entry_template t ON t.template_id = r.template_id WHERE r.active"
                        + (templateId != null ? " AND r.template_id = ?" : "")
                        + (eventCode != null && !eventCode.isBlank() ? " AND t.event_code = ?" : "")
                        + " ORDER BY r.template_id, r.line_seq",
                rs -> {
                    rules.computeIfAbsent(rs.getLong("template_id"), k -> new ArrayList<>()).add(ruleView(rs));
                }, ruleArgs(templateId, eventCode));
        return jdbc.query(sql.toString(), (rs, i) -> new TemplateView(rs.getLong("template_id"),
                rs.getString("event_code"), rs.getString("product_code"), rs.getInt("version"), rs.getString("status"),
                rs.getObject("effective_from", LocalDate.class), rs.getObject("effective_to", LocalDate.class),
                rs.getString("created_by"), rs.getString("approved_by"),
                rules.getOrDefault(rs.getLong("template_id"), List.of())), args.toArray());
    }

    public Optional<TemplateView> template(long templateId) {
        return templates(templateId, null, null).stream().findFirst();
    }

    private static Object[] ruleArgs(Long templateId, String eventCode) {
        List<Object> a = new ArrayList<>();
        if (templateId != null) {
            a.add(templateId);
        }
        if (eventCode != null && !eventCode.isBlank()) {
            a.add(eventCode);
        }
        return a.toArray();
    }

    // ------------------------------------------------------------------------------------------------ routes

    public long insertRoute(int ledgerId, String roleCode, String productCode, String npaStage, String currency,
                            String glAccountCode, int priority, LocalDate from, LocalDate to, String createdBy,
                            String branchCode) {
        Long id = jdbc.queryForObject("INSERT INTO acct_route(ledger_id, role_code, product_code, npa_stage, currency, "
                + "gl_account_code, priority, effective_from, effective_to, status, created_by, branch_code) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, COALESCE(?, DATE '2000-01-01'), ?, 'DRAFT', ?, ?) RETURNING route_id",
                Long.class, ledgerId, roleCode, blankToNull(productCode), blankToNull(npaStage), blankToNull(currency),
                glAccountCode, priority, from, to, createdBy, blankToNull(branchCode));
        return id == null ? 0L : id;
    }

    /** Whether the branch exists in the branch reference table (any status; a route may be drafted for a closed branch). */
    public boolean branchExists(String branchCode) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM branch WHERE branch_code = ?", Integer.class, branchCode);
        return n != null && n > 0;
    }

    public Optional<RouteHead> routeHead(long routeId) {
        return jdbc.query("SELECT route_id, status, created_by FROM acct_route WHERE route_id = ?",
                (rs, i) -> new RouteHead(rs.getLong(1), rs.getString(2), rs.getString(3)), routeId).stream().findFirst();
    }

    public void setRouteStatus(long routeId, String status, String approvedBy) {
        jdbc.update("UPDATE acct_route SET status = ?, approved_by = ? WHERE route_id = ?", status, approvedBy, routeId);
    }

    public List<RouteView> routes(Integer ledgerId, String status) {
        StringBuilder sql = new StringBuilder("SELECT route_id, ledger_id, role_code, product_code, npa_stage, currency, "
                + "gl_account_code, priority, effective_from, effective_to, status, branch_code FROM acct_route WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (ledgerId != null) {
            sql.append(" AND ledger_id = ?");
            args.add(ledgerId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY ledger_id, role_code, route_id");
        return jdbc.query(sql.toString(), (rs, i) -> new RouteView(rs.getLong("route_id"), rs.getInt("ledger_id"),
                rs.getString("role_code"), rs.getString("product_code"), rs.getString("npa_stage"),
                rs.getString("currency") == null ? null : rs.getString("currency").trim(),
                rs.getString("gl_account_code"), rs.getInt("priority"), rs.getObject("effective_from", LocalDate.class),
                rs.getObject("effective_to", LocalDate.class), rs.getString("status"), rs.getString("branch_code")), args.toArray());
    }

    public boolean accountExists(int ledgerId, String glAccountCode) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM gl_account WHERE ledger_id = ? AND gl_account_code = ?",
                Integer.class, ledgerId, glAccountCode);
        return n != null && n > 0;
    }

    public boolean roleExists(String roleCode) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM acct_role WHERE role_code = ?", Integer.class, roleCode);
        return n != null && n > 0;
    }

    // ------------------------------------------------------------------------------------------------ events, ledgers

    public void updateEvent(String eventCode, String postingMode, String blockingMode, Boolean eodGate) {
        jdbc.update("UPDATE acct_event_def SET posting_mode = COALESCE(?, posting_mode), "
                + "blocking_mode = COALESCE(?, blocking_mode), eod_gate = COALESCE(?, eod_gate), updated_at = now() "
                + "WHERE event_code = ?", postingMode, blockingMode, eodGate, eventCode);
    }

    public List<EventView> events() {
        Map<String, List<String>> tags = new HashMap<>();
        jdbc.query("SELECT event_code, tag_code FROM acct_amount_tag ORDER BY event_code, tag_code",
                rs -> {
                    tags.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
                });
        Map<String, List<Integer>> ledgers = new HashMap<>();
        jdbc.query("SELECT event_code, ledger_id FROM acct_event_ledger ORDER BY event_code, ledger_id",
                rs -> {
                    ledgers.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getInt(2));
                });
        return jdbc.query("SELECT event_code, name, category, blocking_mode, posting_mode, eod_gate, status "
                        + "FROM acct_event_def ORDER BY category, event_code",
                (rs, i) -> new EventView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getBoolean(6), rs.getString(7),
                        tags.getOrDefault(rs.getString(1), List.of()), ledgers.getOrDefault(rs.getString(1), List.of())));
    }

    public List<LedgerView> ledgers() {
        return jdbc.query("SELECT ledger_id, ledger_code, name, ledger_type, purpose, base_currency, delivery_mode, status "
                        + "FROM gl_ledger ORDER BY ledger_id",
                (rs, i) -> new LedgerView(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6).trim(), rs.getString(7), rs.getString(8)));
    }

    public List<GlAccountView> accounts(int ledgerId) {
        return jdbc.query("SELECT ledger_id, gl_account_code, name, nature, normal_balance, status, posting_allowed "
                        + "FROM gl_account WHERE ledger_id = ? ORDER BY gl_account_code",
                (rs, i) -> new GlAccountView(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5).trim(), rs.getString(6), rs.getBoolean(7)), ledgerId);
    }

    private static RuleView ruleView(ResultSet rs) throws SQLException {
        return new RuleView(rs.getLong("rule_id"), rs.getInt("line_seq"), rs.getString("side").trim(),
                rs.getString("role_code"), rs.getString("amount_source"), rs.getString("condition_expr"),
                rs.getString("ledger_scope"), rs.getString("currency_basis"), rs.getString("narrative"));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
