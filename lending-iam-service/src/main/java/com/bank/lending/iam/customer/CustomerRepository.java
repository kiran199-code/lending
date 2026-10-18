package com.bank.lending.iam.customer;

import com.bank.lending.iam.common.Db;
import com.bank.lending.iam.customer.CustomerModels.*;
import com.bank.lending.iam.domain.AuthzDecisionEngine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class CustomerRepository {
    private static final String LINK_COLS = "link_id, user_id, customer_id, link_type, account_ref, relationship, status, valid_from, valid_to, created_by, created_at, approved_by, approved_at, revoked_by, revoked_at, reason";
    private static final RowMapper<LinkRow> LINK = (rs, i) -> new LinkRow(rs.getLong("link_id"), rs.getLong("user_id"), rs.getString("customer_id"), rs.getString("link_type"),
            rs.getString("account_ref"), rs.getString("relationship"), rs.getString("status"), Db.date(rs, "valid_from"), Db.date(rs, "valid_to"), rs.getString("created_by"),
            Db.instant(rs, "created_at"), rs.getString("approved_by"), Db.instant(rs, "approved_at"), rs.getString("revoked_by"), Db.instant(rs, "revoked_at"), rs.getString("reason"));
    private static final RowMapper<PurposeRow> PURPOSE = (rs, i) -> new PurposeRow(rs.getString("purpose_code"), rs.getString("description"), rs.getInt("version"),
            rs.getBoolean("mandatory"), rs.getBoolean("active"));

    private final JdbcTemplate jdbc;

    public CustomerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ links
    public long insertLink(long userId, String customerId, String linkType, String accountRef, String relationship, String status, LocalDate from, LocalDate to, String createdBy) {
        return Db.scalar(jdbc, "INSERT INTO customer_link (user_id, customer_id, link_type, account_ref, relationship, status, valid_from, valid_to, created_by) "
                + "VALUES (?,?,?,?,?,?,?,?,?) RETURNING link_id", Long.class, userId, customerId, linkType, accountRef, relationship, status, from == null ? LocalDate.now() : from, to, createdBy);
    }

    public Optional<LinkRow> link(long id) {
        return Db.one(jdbc, "SELECT " + LINK_COLS + " FROM customer_link WHERE link_id = ?", LINK, id);
    }

    public Optional<LinkRow> linkForUpdate(long id) {
        return Db.one(jdbc, "SELECT " + LINK_COLS + " FROM customer_link WHERE link_id = ? FOR UPDATE", LINK, id);
    }

    public List<LinkRow> linksOf(long userId) {
        return jdbc.query("SELECT " + LINK_COLS + " FROM customer_link WHERE user_id = ? ORDER BY link_id DESC", LINK, userId);
    }

    public List<LinkRow> pending() {
        return jdbc.query("SELECT " + LINK_COLS + " FROM customer_link WHERE status = 'PENDING_APPROVAL' ORDER BY link_id", LINK);
    }

    public boolean approve(long id, String by) {
        return jdbc.update("UPDATE customer_link SET status = 'ACTIVE', approved_by = ?, approved_at = now() WHERE link_id = ? AND status = 'PENDING_APPROVAL'", by, id) == 1;
    }

    public boolean reject(long id, String by, String reason) {
        return jdbc.update("UPDATE customer_link SET status = 'REJECTED', approved_by = ?, approved_at = now(), reason = ? WHERE link_id = ? AND status = 'PENDING_APPROVAL'", by, reason, id) == 1;
    }

    public boolean revoke(long id, String by, String reason) {
        return jdbc.update("UPDATE customer_link SET status = 'REVOKED', revoked_by = ?, revoked_at = now(), reason = ? WHERE link_id = ? AND status IN ('ACTIVE','PENDING_APPROVAL')", by, reason, id) == 1;
    }

    public int revokeAll(long userId, String by, String reason) {
        return jdbc.update("UPDATE customer_link SET status = 'REVOKED', revoked_by = ?, revoked_at = now(), reason = ? WHERE user_id = ? AND status IN ('ACTIVE','PENDING_APPROVAL')", by, reason, userId);
    }

    /** The links as the decision engine wants them (it decides whether each is effective today). */
    public List<AuthzDecisionEngine.Link> engineLinks(long userId) {
        return jdbc.query("SELECT customer_id, link_type, account_ref, status, valid_from, valid_to FROM customer_link WHERE user_id = ? AND status = 'ACTIVE'",
                (rs, i) -> new AuthzDecisionEngine.Link(rs.getString("link_type"), rs.getString("customer_id"), rs.getString("account_ref"),
                        rs.getString("status"), Db.date(rs, "valid_from"), Db.date(rs, "valid_to")), userId);
    }

    // ------------------------------------------------------------------ consent
    public List<PurposeRow> purposes() {
        return jdbc.query("SELECT purpose_code, description, version, mandatory, active FROM consent_purpose ORDER BY purpose_code", PURPOSE);
    }

    public Optional<PurposeRow> purpose(String code) {
        return Db.one(jdbc, "SELECT purpose_code, description, version, mandatory, active FROM consent_purpose WHERE purpose_code = ?", PURPOSE, code);
    }

    public void insertConsent(long userId, String purpose, int version, boolean granted, String clientId, String ip) {
        jdbc.update("INSERT INTO consent_record (user_id, purpose_code, version, granted, client_id, ip) VALUES (?,?,?,?,?,?)", userId, purpose, version, granted, clientId, ip);
    }

    /** The latest record of each purpose. */
    public Map<String, ConsentState> latestConsents(long userId) {
        Map<String, ConsentState> m = new LinkedHashMap<>();
        jdbc.query("SELECT DISTINCT ON (purpose_code) purpose_code, version, granted, recorded_at FROM consent_record WHERE user_id = ? ORDER BY purpose_code, record_id DESC", (rs, i) -> {
            m.put(rs.getString("purpose_code"), new ConsentState(rs.getString("purpose_code"), rs.getInt("version"), rs.getBoolean("granted"), Db.instant(rs, "recorded_at")));
            return null;
        }, userId);
        return m;
    }

    public List<ConsentRecord> consentHistory(long userId) {
        return jdbc.query("SELECT record_id, purpose_code, version, granted, recorded_at, client_id, ip FROM consent_record WHERE user_id = ? ORDER BY record_id DESC",
                (rs, i) -> new ConsentRecord(rs.getLong("record_id"), rs.getString("purpose_code"), rs.getInt("version"), rs.getBoolean("granted"),
                        Db.instant(rs, "recorded_at"), rs.getString("client_id"), rs.getString("ip")), userId);
    }
}
