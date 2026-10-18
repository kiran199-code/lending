package com.bank.lending.lcm.facility;

import com.bank.lending.lcm.common.LcmException;
import com.bank.lending.lcm.facility.FacilityDtos.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public class FacilityRepository {

    static final String COLS = "id, facility_ref, customer_id, parent_id, facility_type, product_code, currency_code, branch_code, "
            + "revolving, dp_governed, sanctioned_amount, max_single_amount, status, effective_from, expiry_date, sanction_ref_id, "
            + "source, utilised, drawn_total, reserved, created_by, approved_by, version";

    static final RowMapper<Facility> MAP = (rs, i) -> new Facility(
            rs.getLong("id"), rs.getString("facility_ref"), rs.getLong("customer_id"), nullableLong(rs, "parent_id"),
            rs.getString("facility_type"), rs.getString("product_code"), rs.getString("currency_code").trim(),
            rs.getString("branch_code"), rs.getBoolean("revolving"), rs.getBoolean("dp_governed"),
            rs.getBigDecimal("sanctioned_amount"), rs.getBigDecimal("max_single_amount"),
            FacilityStatus.valueOf(rs.getString("status")), localDate(rs, "effective_from"), localDate(rs, "expiry_date"),
            nullableLong(rs, "sanction_ref_id"), rs.getString("source"), rs.getBigDecimal("utilised"),
            rs.getBigDecimal("drawn_total"), rs.getBigDecimal("reserved"), rs.getString("created_by"),
            rs.getString("approved_by"), rs.getLong("version"));

    static Long nullableLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    static LocalDate localDate(ResultSet rs, String col) throws SQLException {
        return rs.getObject(col, LocalDate.class);
    }

    private final JdbcTemplate jdbc;

    public FacilityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- facility ----

    public Optional<Facility> findByRef(String ref) {
        List<Facility> l = jdbc.query("SELECT " + COLS + " FROM facility WHERE facility_ref = ?", MAP, ref);
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    public Facility getByRef(String ref) {
        return findByRef(ref).orElseThrow(() -> LcmException.notFound("Facility", ref));
    }

    public Facility getById(long id) {
        List<Facility> l = jdbc.query("SELECT " + COLS + " FROM facility WHERE id = ?", MAP, id);
        if (l.isEmpty()) throw LcmException.notFound("Facility", String.valueOf(id));
        return l.get(0);
    }

    public Optional<Facility> findBySanctionRefId(long sanctionRefId) {
        List<Facility> l = jdbc.query("SELECT " + COLS + " FROM facility WHERE sanction_ref_id = ?", MAP, sanctionRefId);
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    public long insert(Facility f) {
        return jdbc.queryForObject(
                "INSERT INTO facility (facility_ref, customer_id, parent_id, facility_type, product_code, currency_code, branch_code, "
                        + "revolving, dp_governed, sanctioned_amount, max_single_amount, status, effective_from, expiry_date, "
                        + "sanction_ref_id, source, created_by, approved_by) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
                Long.class,
                f.facilityRef(), f.customerId(), f.parentId(), f.facilityType(), f.productCode(), f.currencyCode(), f.branchCode(),
                f.revolving(), f.dpGoverned(), f.sanctionedAmount(), f.maxSingleAmount(), f.status().name(), f.effectiveFrom(),
                f.expiryDate(), f.sanctionRefId(), f.source(), f.createdBy(), f.approvedBy());
    }

    public List<Facility> search(Long customerId, String status, Long parentId) {
        StringBuilder sql = new StringBuilder("SELECT " + COLS + " FROM facility WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (customerId != null) { sql.append(" AND customer_id = ?"); args.add(customerId); }
        if (status != null && !status.isBlank()) { sql.append(" AND status = ?"); args.add(status.toUpperCase()); }
        if (parentId != null) { sql.append(" AND parent_id = ?"); args.add(parentId); }
        sql.append(" ORDER BY id LIMIT 500");
        return jdbc.query(sql.toString(), MAP, args.toArray());
    }

    public List<Facility> children(long parentId) {
        return jdbc.query("SELECT " + COLS + " FROM facility WHERE parent_id = ? ORDER BY id", MAP, parentId);
    }

    /** Ids from the facility up to the root, leaf first. Parent links never change, so this needs no lock. */
    public List<Long> chainIds(long facilityId) {
        List<Long> ids = new ArrayList<>();
        Long cur = facilityId;
        while (cur != null && ids.size() < 10) {
            ids.add(cur);
            cur = jdbc.queryForObject("SELECT parent_id FROM facility WHERE id = ?", Long.class, cur);
        }
        return ids;
    }

    /** Locks the rows in ascending id order (the single global lock order) and returns them as a map-friendly list. */
    public List<Facility> lockAll(Collection<Long> ids) {
        if (ids.isEmpty()) return List.of();
        StringBuilder in = new StringBuilder();
        List<Object> args = new ArrayList<>();
        for (Long id : ids) {
            if (in.length() > 0) in.append(',');
            in.append('?');
            args.add(id);
        }
        return jdbc.query("SELECT " + COLS + " FROM facility WHERE id IN (" + in + ") ORDER BY id FOR UPDATE", MAP, args.toArray());
    }

    public void applyCounters(long id, BigDecimal dReserved, BigDecimal dUtilised, BigDecimal dDrawn) {
        jdbc.update("UPDATE facility SET reserved = reserved + ?, utilised = utilised + ?, drawn_total = drawn_total + ?, "
                + "version = version + 1 WHERE id = ?", dReserved, dUtilised, dDrawn, id);
    }

    public void setStatus(long id, FacilityStatus status, String approvedBy) {
        jdbc.update("UPDATE facility SET status = ?, approved_by = COALESCE(?, approved_by), version = version + 1 WHERE id = ?",
                status.name(), approvedBy, id);
    }

    public void setSanctioned(long id, BigDecimal amount) {
        jdbc.update("UPDATE facility SET sanctioned_amount = ?, version = version + 1 WHERE id = ?", amount, id);
    }

    public void setExpiry(long id, LocalDate expiry) {
        jdbc.update("UPDATE facility SET expiry_date = ?, version = version + 1 WHERE id = ?", expiry, id);
    }

    public BigDecimal activeExcess(long facilityId, LocalDate asOf) {
        BigDecimal v = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM facility_excess WHERE facility_id = ? AND status = 'ACTIVE' "
                        + "AND valid_from <= ? AND valid_to >= ?", BigDecimal.class, facilityId, asOf, asOf);
        return v == null ? BigDecimal.ZERO : v;
    }

    public BigDecimal maxChildSanctioned(long facilityId) {
        BigDecimal v = jdbc.queryForObject(
                "SELECT COALESCE(MAX(sanctioned_amount), 0) FROM facility WHERE parent_id = ? AND status <> 'CLOSED'",
                BigDecimal.class, facilityId);
        return v == null ? BigDecimal.ZERO : v;
    }

    public int openChildren(long facilityId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM facility WHERE parent_id = ? AND status <> 'CLOSED'", Long.class, facilityId);
        return n == null ? 0 : n.intValue();
    }

    public List<Facility> expiredButOpen(LocalDate asOf) {
        return jdbc.query("SELECT " + COLS + " FROM facility WHERE status IN ('ACTIVE','SUSPENDED','FROZEN') "
                + "AND expiry_date IS NOT NULL AND expiry_date < ? ORDER BY id", MAP, asOf);
    }

    public List<Facility> liveFacilities() {
        return jdbc.query("SELECT " + COLS + " FROM facility WHERE status IN ('ACTIVE','SUSPENDED','FROZEN','EXPIRED') ORDER BY id", MAP);
    }

    // ---- changes ----

    static final String CHANGE_SELECT = "SELECT c.id, f.facility_ref, c.change_type, c.payload, c.reason, c.status, c.maker, c.checker, "
            + "c.decision_note, c.created_at, c.decided_at, c.facility_id FROM facility_change c JOIN facility f ON f.id = c.facility_id ";

    static final RowMapper<ChangeView> CHANGE_MAP = (rs, i) -> new ChangeView(
            rs.getLong("id"), rs.getString("facility_ref"), rs.getString("change_type"), rs.getString("payload"),
            rs.getString("reason"), rs.getString("status"), rs.getString("maker"), rs.getString("checker"),
            rs.getString("decision_note"), rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("decided_at", OffsetDateTime.class));

    public long insertChange(long facilityId, String type, String payload, String reason, String maker) {
        return jdbc.queryForObject("INSERT INTO facility_change (facility_id, change_type, payload, reason, maker) "
                + "VALUES (?,?,?,?,?) RETURNING id", Long.class, facilityId, type, payload, reason, maker);
    }

    public boolean pendingChangeExists(long facilityId, String type) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM facility_change WHERE facility_id = ? AND change_type = ? AND status = 'PENDING'",
                Long.class, facilityId, type);
        return n != null && n > 0;
    }

    public Optional<ChangeView> findChange(long id) {
        List<ChangeView> l = jdbc.query(CHANGE_SELECT + "WHERE c.id = ?", CHANGE_MAP, id);
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    /** Locks the change row and returns its facility id; empty when missing. */
    public Optional<Long> lockChange(long id) {
        List<Long> l = jdbc.query("SELECT facility_id FROM facility_change WHERE id = ? FOR UPDATE", (rs, i) -> rs.getLong(1), id);
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    public List<ChangeView> listChanges(String status, String facilityRef) {
        StringBuilder sql = new StringBuilder(CHANGE_SELECT + "WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) { sql.append(" AND c.status = ?"); args.add(status.toUpperCase()); }
        if (facilityRef != null && !facilityRef.isBlank()) { sql.append(" AND f.facility_ref = ?"); args.add(facilityRef); }
        sql.append(" ORDER BY c.id DESC LIMIT 200");
        return jdbc.query(sql.toString(), CHANGE_MAP, args.toArray());
    }

    public void decideChange(long id, String status, String checker, String note) {
        jdbc.update("UPDATE facility_change SET status = ?, checker = ?, decision_note = ?, decided_at = now() WHERE id = ?",
                status, checker, note, id);
    }

    // ---- excess ----

    static final String EXCESS_SELECT = "SELECT e.id, f.facility_ref, e.amount, e.valid_from, e.valid_to, e.reason, e.status, e.maker, "
            + "e.checker, e.created_at, e.decided_at FROM facility_excess e JOIN facility f ON f.id = e.facility_id ";

    static final RowMapper<ExcessView> EXCESS_MAP = (rs, i) -> new ExcessView(
            rs.getLong("id"), rs.getString("facility_ref"), rs.getBigDecimal("amount"), localDate(rs, "valid_from"),
            localDate(rs, "valid_to"), rs.getString("reason"), rs.getString("status"), rs.getString("maker"),
            rs.getString("checker"), rs.getObject("created_at", OffsetDateTime.class), rs.getObject("decided_at", OffsetDateTime.class));

    public long insertExcess(long facilityId, BigDecimal amount, LocalDate from, LocalDate to, String reason, String maker) {
        return jdbc.queryForObject("INSERT INTO facility_excess (facility_id, amount, valid_from, valid_to, reason, maker) "
                + "VALUES (?,?,?,?,?,?) RETURNING id", Long.class, facilityId, amount, from, to, reason, maker);
    }

    public Optional<ExcessView> findExcess(long id) {
        List<ExcessView> l = jdbc.query(EXCESS_SELECT + "WHERE e.id = ?", EXCESS_MAP, id);
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    public Optional<Long> lockExcess(long id) {
        List<Long> l = jdbc.query("SELECT facility_id FROM facility_excess WHERE id = ? FOR UPDATE", (rs, i) -> rs.getLong(1), id);
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    public List<ExcessView> listExcess(String facilityRef) {
        return jdbc.query(EXCESS_SELECT + "WHERE f.facility_ref = ? ORDER BY e.id DESC", EXCESS_MAP, facilityRef);
    }

    public void decideExcess(long id, String status, String checker) {
        jdbc.update("UPDATE facility_excess SET status = ?, checker = ?, decided_at = now() WHERE id = ?", status, checker, id);
    }

    // ---- ledger view ----

    public List<TxnView> transactions(long facilityId, Long beforeId, int limit) {
        String sql = "SELECT t.id, f.facility_ref, o.facility_ref AS origin_ref, t.txn_type, t.amount, t.d_reserved, t.d_utilised, "
                + "t.d_drawn_total, t.reference_type, t.reference_id, t.actor, t.created_at "
                + "FROM limit_txn t JOIN facility f ON f.id = t.facility_id JOIN facility o ON o.id = t.origin_facility_id "
                + "WHERE t.facility_id = ? " + (beforeId != null ? "AND t.id < ? " : "") + "ORDER BY t.id DESC LIMIT ?";
        RowMapper<TxnView> m = (rs, i) -> new TxnView(rs.getLong("id"), rs.getString("facility_ref"), rs.getString("origin_ref"),
                rs.getString("txn_type"), rs.getBigDecimal("amount"), rs.getBigDecimal("d_reserved"), rs.getBigDecimal("d_utilised"),
                rs.getBigDecimal("d_drawn_total"), rs.getString("reference_type"), rs.getString("reference_id"),
                rs.getString("actor"), rs.getObject("created_at", OffsetDateTime.class));
        return beforeId != null ? jdbc.query(sql, m, facilityId, beforeId, limit) : jdbc.query(sql, m, facilityId, limit);
    }
}
