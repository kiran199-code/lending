package com.bank.lending.lcm.utilisation;

import com.bank.lending.lcm.common.LcmException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class UtilisationRepository {

    static final String RES_COLS = "id, reservation_ref, facility_id, amount, confirmed_amount, currency_code, reference_type, reference_id, "
            + "status, enforced, would_reject, late_confirm, expires_at, created_by, created_at";

    static final RowMapper<Reservation> RES_MAP = (rs, i) -> new Reservation(
            rs.getLong("id"), rs.getString("reservation_ref"), rs.getLong("facility_id"), rs.getBigDecimal("amount"),
            rs.getBigDecimal("confirmed_amount"), rs.getString("currency_code").trim(), rs.getString("reference_type"),
            rs.getString("reference_id"), rs.getString("status"), rs.getBoolean("enforced"), rs.getString("would_reject"),
            rs.getBoolean("late_confirm"), rs.getObject("expires_at", OffsetDateTime.class), rs.getString("created_by"),
            rs.getObject("created_at", OffsetDateTime.class));

    static final String POS_COLS = "id, facility_id, reference_type, reference_id, drawn, repaid, written_off, status";

    static final RowMapper<Position> POS_MAP = (rs, i) -> new Position(
            rs.getLong("id"), rs.getLong("facility_id"), rs.getString("reference_type"), rs.getString("reference_id"),
            rs.getBigDecimal("drawn"), rs.getBigDecimal("repaid"), rs.getBigDecimal("written_off"), rs.getString("status"));

    private final JdbcTemplate jdbc;

    public UtilisationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- idempotency ----

    public record IdemOutcome(boolean replay, String resultRef) {}

    /** Claims the key. A repeat with the same request returns the stored result; a different request is a 409. */
    public IdemOutcome claim(String key, String operation, String hash) {
        int n = jdbc.update("INSERT INTO idempotency_key (idem_key, operation, request_hash) VALUES (?,?,?) ON CONFLICT (idem_key) DO NOTHING",
                key, operation, hash);
        if (n == 1) return new IdemOutcome(false, null);
        List<String[]> rows = jdbc.query("SELECT operation, request_hash, result_ref FROM idempotency_key WHERE idem_key = ?",
                (rs, i) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}, key);
        String[] row = rows.get(0);
        if (!row[0].equals(operation) || !row[1].equals(hash)) {
            throw LcmException.conflict("IDEMPOTENCY_KEY_REUSED", "Idempotency-Key " + key + " was already used for a different request");
        }
        return new IdemOutcome(true, row[2]);
    }

    public void setResult(String key, String resultRef) {
        jdbc.update("UPDATE idempotency_key SET result_ref = ? WHERE idem_key = ?", resultRef, key);
    }

    // ---- reservations ----

    public long insertReservation(long facilityId, BigDecimal amount, String currency, String refType, String refId, boolean enforced,
                                  String wouldReject, int ttlMinutes, String actor) {
        return jdbc.queryForObject("INSERT INTO reservation (facility_id, amount, currency_code, reference_type, reference_id, enforced, "
                + "would_reject, expires_at, created_by) VALUES (?,?,?,?,?,?,?, now() + (? * interval '1 minute'), ?) RETURNING id",
                Long.class, facilityId, amount, currency, refType, refId, enforced, wouldReject, ttlMinutes, actor);
    }

    public Reservation getReservation(long id) {
        return jdbc.query("SELECT " + RES_COLS + " FROM reservation WHERE id = ?", RES_MAP, id).get(0);
    }

    public Optional<Reservation> findByRef(String ref) {
        List<Reservation> l = jdbc.query("SELECT " + RES_COLS + " FROM reservation WHERE reservation_ref = ?", RES_MAP, ref);
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    public Optional<Reservation> lockByRef(String ref) {
        List<Reservation> l = jdbc.query("SELECT " + RES_COLS + " FROM reservation WHERE reservation_ref = ? FOR UPDATE", RES_MAP, ref);
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    public Optional<Reservation> lockById(long id) {
        List<Reservation> l = jdbc.query("SELECT " + RES_COLS + " FROM reservation WHERE id = ? FOR UPDATE", RES_MAP, id);
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    public Optional<Reservation> liveForReference(String refType, String refId) {
        List<Reservation> l = jdbc.query("SELECT " + RES_COLS + " FROM reservation WHERE reference_type = ? AND reference_id = ? AND status = 'RESERVED'",
                RES_MAP, refType, refId);
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    public void setReservationStatus(long id, String status, BigDecimal confirmedAmount, boolean lateConfirm) {
        jdbc.update("UPDATE reservation SET status = ?, confirmed_amount = COALESCE(?, confirmed_amount), "
                + "late_confirm = late_confirm OR ? WHERE id = ?", status, confirmedAmount, lateConfirm, id);
    }

    public List<Long> expiredReservationIds(int limit) {
        return jdbc.query("SELECT id FROM reservation WHERE status = 'RESERVED' AND expires_at < now() ORDER BY expires_at LIMIT ?",
                (rs, i) -> rs.getLong(1), limit);
    }

    public String facilityRef(long facilityId) {
        return jdbc.queryForObject("SELECT facility_ref FROM facility WHERE id = ?", String.class, facilityId);
    }

    // ---- positions ----

    public Optional<Position> findPosition(String refType, String refId) {
        List<Position> l = jdbc.query("SELECT " + POS_COLS + " FROM utilisation_position WHERE reference_type = ? AND reference_id = ?",
                POS_MAP, refType, refId);
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    /** Creates the position row if it does not exist, then locks and returns it. */
    public Position lockOrCreatePosition(long facilityId, String refType, String refId) {
        jdbc.update("INSERT INTO utilisation_position (facility_id, reference_type, reference_id) VALUES (?,?,?) "
                + "ON CONFLICT (reference_type, reference_id) DO NOTHING", facilityId, refType, refId);
        return lockPosition(refType, refId).orElseThrow();
    }

    public Optional<Position> lockPosition(String refType, String refId) {
        List<Position> l = jdbc.query("SELECT " + POS_COLS + " FROM utilisation_position WHERE reference_type = ? AND reference_id = ? FOR UPDATE",
                POS_MAP, refType, refId);
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    public void updatePosition(long id, BigDecimal drawn, BigDecimal repaid, BigDecimal writtenOff, String status) {
        jdbc.update("UPDATE utilisation_position SET drawn = ?, repaid = ?, written_off = ?, status = ?, version = version + 1 WHERE id = ?",
                drawn, repaid, writtenOff, status, id);
    }

    public List<Position> positionsForFacility(long facilityId) {
        return jdbc.query("SELECT " + POS_COLS + " FROM utilisation_position WHERE facility_id = ? ORDER BY id", POS_MAP, facilityId);
    }

    // ---- ledger ----

    public void insertTxn(long facilityId, long originId, String type, Long reservationId, Long positionId, BigDecimal amount,
                          BigDecimal dRes, BigDecimal dUtil, BigDecimal dDrawn, String refType, String refId, String actor) {
        jdbc.update("INSERT INTO limit_txn (facility_id, origin_facility_id, txn_type, reservation_id, position_id, amount, d_reserved, "
                + "d_utilised, d_drawn_total, reference_type, reference_id, actor) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                facilityId, originId, type, reservationId, positionId, amount, dRes, dUtil, dDrawn, refType, refId, actor);
    }

    /** Facilities whose counters differ from the sum of their ledger deltas: should always be empty. */
    public List<String> integrityDifferences() {
        List<String> out = new ArrayList<>();
        jdbc.query("SELECT f.facility_ref, f.reserved, f.utilised, f.drawn_total, COALESCE(SUM(t.d_reserved),0) AS sr, "
                + "COALESCE(SUM(t.d_utilised),0) AS su, COALESCE(SUM(t.d_drawn_total),0) AS sd FROM facility f "
                + "LEFT JOIN limit_txn t ON t.facility_id = f.id GROUP BY f.id, f.facility_ref, f.reserved, f.utilised, f.drawn_total "
                + "HAVING f.reserved <> COALESCE(SUM(t.d_reserved),0) OR f.utilised <> COALESCE(SUM(t.d_utilised),0) "
                + "OR f.drawn_total <> COALESCE(SUM(t.d_drawn_total),0) ORDER BY f.id",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> out.add(rs.getString("facility_ref") + ": counters reserved/utilised/drawn="
                        + rs.getBigDecimal("reserved") + "/" + rs.getBigDecimal("utilised") + "/" + rs.getBigDecimal("drawn_total")
                        + " ledger=" + rs.getBigDecimal("sr") + "/" + rs.getBigDecimal("su") + "/" + rs.getBigDecimal("sd")));
        return out;
    }
}
