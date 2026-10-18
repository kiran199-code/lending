package com.bank.lending.lcm.collateral;

import com.bank.lending.lcm.collateral.CollateralDtos.AllocationView;
import com.bank.lending.lcm.collateral.CollateralDtos.ValuationView;
import com.bank.lending.lcm.collateral.CollateralModels.*;
import com.bank.lending.lcm.collateral.ValuationCalculator.Input;
import com.bank.lending.lcm.common.LcmException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class CollateralRepository {

    static final String COLS = "id, collateral_ref, customer_id, collateral_type, description, currency_code, status, legal_status, "
            + "charge_registered, charge_ref, charge_registered_on, prior_charge_amount, insurance_expiry, haircut_pct, max_ltv_pct, "
            + "lending_mortgage_id, source, created_by";

    static final RowMapper<Collateral> MAP = (rs, i) -> {
        long mortgage = rs.getLong("lending_mortgage_id");
        boolean noMortgage = rs.wasNull();
        return new Collateral(rs.getLong("id"), rs.getString("collateral_ref"), rs.getLong("customer_id"), rs.getString("collateral_type"),
                rs.getString("description"), rs.getString("currency_code").trim(), rs.getString("status"), rs.getString("legal_status"),
                rs.getBoolean("charge_registered"), rs.getString("charge_ref"), rs.getObject("charge_registered_on", LocalDate.class),
                rs.getBigDecimal("prior_charge_amount"), rs.getObject("insurance_expiry", LocalDate.class), rs.getBigDecimal("haircut_pct"),
                rs.getBigDecimal("max_ltv_pct"), noMortgage ? null : mortgage, rs.getString("source"), rs.getString("created_by"));
    };

    static final RowMapper<TypeConfig> TYPE_MAP = (rs, i) -> new TypeConfig(rs.getString("type_code"), rs.getString("description"),
            rs.getBigDecimal("default_haircut_pct"), rs.getBigDecimal("max_ltv_pct"), rs.getInt("valuation_validity_days"),
            rs.getBoolean("requires_charge"), rs.getBoolean("requires_insurance"), rs.getBoolean("active"));

    static final String VAL_COLS = "id, collateral_id, valuation_date, market_value, forced_sale_value, valuer_ref, source, status, maker, checker";

    static final RowMapper<Valuation> VAL_MAP = (rs, i) -> new Valuation(rs.getLong("id"), rs.getLong("collateral_id"),
            rs.getObject("valuation_date", LocalDate.class), rs.getBigDecimal("market_value"), rs.getBigDecimal("forced_sale_value"),
            rs.getString("valuer_ref"), rs.getString("source"), rs.getString("status"), rs.getString("maker"), rs.getString("checker"));

    static final RowMapper<Allocation> ALLOC_MAP = (rs, i) -> new Allocation(rs.getLong("id"), rs.getLong("collateral_id"),
            rs.getLong("facility_id"), rs.getBigDecimal("allocation_pct"), rs.getString("status"));

    private final JdbcTemplate jdbc;

    public CollateralRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- types ----

    public List<TypeConfig> types() {
        return jdbc.query("SELECT * FROM collateral_type_config ORDER BY type_code", TYPE_MAP);
    }

    public Optional<TypeConfig> type(String code) {
        List<TypeConfig> l = jdbc.query("SELECT * FROM collateral_type_config WHERE type_code = ?", TYPE_MAP, code);
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    // ---- collateral ----

    public long insert(String ref, long customerId, String type, String description, String currency, BigDecimal haircut, BigDecimal ltv,
                       BigDecimal prior, Long mortgageId, String source, String legalStatus, boolean chargeRegistered, String actor) {
        return jdbc.queryForObject("INSERT INTO collateral (collateral_ref, customer_id, collateral_type, description, currency_code, haircut_pct, "
                + "max_ltv_pct, prior_charge_amount, lending_mortgage_id, source, legal_status, charge_registered, created_by) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id", Long.class,
                ref, customerId, type, description, currency, haircut, ltv, prior, mortgageId, source, legalStatus, chargeRegistered, actor);
    }

    public Optional<Collateral> findByRef(String ref) {
        List<Collateral> l = jdbc.query("SELECT " + COLS + " FROM collateral WHERE collateral_ref = ?", MAP, ref);
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    public Collateral getByRef(String ref) {
        return findByRef(ref).orElseThrow(() -> LcmException.notFound("Collateral", ref));
    }

    public Collateral getById(long id) {
        List<Collateral> l = jdbc.query("SELECT " + COLS + " FROM collateral WHERE id = ?", MAP, id);
        if (l.isEmpty()) throw LcmException.notFound("Collateral", String.valueOf(id));
        return l.get(0);
    }

    public Collateral lockByRef(String ref) {
        List<Collateral> l = jdbc.query("SELECT " + COLS + " FROM collateral WHERE collateral_ref = ? FOR UPDATE", MAP, ref);
        if (l.isEmpty()) throw LcmException.notFound("Collateral", ref);
        return l.get(0);
    }

    public Optional<Collateral> findByMortgageId(long mortgageId) {
        List<Collateral> l = jdbc.query("SELECT " + COLS + " FROM collateral WHERE lending_mortgage_id = ?", MAP, mortgageId);
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    public List<Collateral> search(Long customerId, String status) {
        StringBuilder sql = new StringBuilder("SELECT " + COLS + " FROM collateral WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (customerId != null) { sql.append(" AND customer_id = ?"); args.add(customerId); }
        if (status != null && !status.isBlank()) { sql.append(" AND status = ?"); args.add(status.toUpperCase()); }
        sql.append(" ORDER BY id LIMIT 500");
        return jdbc.query(sql.toString(), MAP, args.toArray());
    }

    public List<Collateral> activeOrSuspended() {
        return jdbc.query("SELECT " + COLS + " FROM collateral WHERE status IN ('ACTIVE','SUSPENDED') ORDER BY id", MAP);
    }

    public void setStatus(long id, String status) {
        jdbc.update("UPDATE collateral SET status = ?, version = version + 1 WHERE id = ?", status, id);
    }

    public void setLegal(long id, String legalStatus) {
        jdbc.update("UPDATE collateral SET legal_status = ?, version = version + 1 WHERE id = ?", legalStatus, id);
    }

    public void setCharge(long id, String chargeRef, LocalDate registeredOn, BigDecimal prior) {
        jdbc.update("UPDATE collateral SET charge_registered = TRUE, charge_ref = ?, charge_registered_on = ?, "
                + "prior_charge_amount = COALESCE(?, prior_charge_amount), version = version + 1 WHERE id = ?", chargeRef, registeredOn, prior, id);
    }

    public void setInsurance(long id, LocalDate expiry) {
        jdbc.update("UPDATE collateral SET insurance_expiry = ?, version = version + 1 WHERE id = ?", expiry, id);
    }

    // ---- valuations ----

    public long insertValuation(long collateralId, LocalDate date, BigDecimal market, BigDecimal forced, String valuer, String source,
                                String status, String maker, String checker) {
        return jdbc.queryForObject("INSERT INTO collateral_valuation (collateral_id, valuation_date, market_value, forced_sale_value, valuer_ref, "
                + "source, status, maker, checker, decided_at) VALUES (?,?,?,?,?,?,?,?,?, CASE WHEN ? = 'PENDING' THEN NULL ELSE now() END) RETURNING id",
                Long.class, collateralId, date, market, forced, valuer, source, status, maker, checker, status);
    }

    public Optional<Valuation> findValuation(long id) {
        List<Valuation> l = jdbc.query("SELECT " + VAL_COLS + " FROM collateral_valuation WHERE id = ?", VAL_MAP, id);
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    public Optional<Valuation> lockValuation(long id) {
        List<Valuation> l = jdbc.query("SELECT " + VAL_COLS + " FROM collateral_valuation WHERE id = ? FOR UPDATE", VAL_MAP, id);
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    public Optional<Valuation> latestApproved(long collateralId) {
        List<Valuation> l = jdbc.query("SELECT " + VAL_COLS + " FROM collateral_valuation WHERE collateral_id = ? AND status = 'APPROVED' "
                + "ORDER BY valuation_date DESC, id DESC LIMIT 1", VAL_MAP, collateralId);
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    public boolean bridgeValuationExists(long collateralId, LocalDate date) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM collateral_valuation WHERE collateral_id = ? AND valuation_date = ? AND source = 'BRIDGE'",
                Long.class, collateralId, date);
        return n != null && n > 0;
    }

    public void decideValuation(long id, String status, String checker) {
        jdbc.update("UPDATE collateral_valuation SET status = ?, checker = ?, decided_at = now() WHERE id = ?", status, checker, id);
    }

    public List<ValuationView> valuations(String collateralRef) {
        return jdbc.query("SELECT v.id, c.collateral_ref, v.valuation_date, v.market_value, v.forced_sale_value, v.valuer_ref, v.source, v.status, "
                + "v.maker, v.checker, v.created_at FROM collateral_valuation v JOIN collateral c ON c.id = v.collateral_id "
                + "WHERE c.collateral_ref = ? ORDER BY v.valuation_date DESC, v.id DESC", VIEW_VAL, collateralRef);
    }

    public Optional<ValuationView> valuationView(long id) {
        List<ValuationView> l = jdbc.query("SELECT v.id, c.collateral_ref, v.valuation_date, v.market_value, v.forced_sale_value, v.valuer_ref, "
                + "v.source, v.status, v.maker, v.checker, v.created_at FROM collateral_valuation v JOIN collateral c ON c.id = v.collateral_id "
                + "WHERE v.id = ?", VIEW_VAL, id);
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    static final RowMapper<ValuationView> VIEW_VAL = (rs, i) -> new ValuationView(rs.getLong("id"), rs.getString("collateral_ref"),
            rs.getObject("valuation_date", LocalDate.class), rs.getBigDecimal("market_value"), rs.getBigDecimal("forced_sale_value"),
            rs.getString("valuer_ref"), rs.getString("source"), rs.getString("status"), rs.getString("maker"), rs.getString("checker"),
            rs.getObject("created_at", OffsetDateTime.class));

    // ---- allocations ----

    public BigDecimal activeAllocationPct(long collateralId) {
        BigDecimal v = jdbc.queryForObject("SELECT COALESCE(SUM(allocation_pct), 0) FROM collateral_allocation WHERE collateral_id = ? AND status = 'ACTIVE'",
                BigDecimal.class, collateralId);
        return v == null ? BigDecimal.ZERO : v;
    }

    public boolean activeAllocationExists(long collateralId, long facilityId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM collateral_allocation WHERE collateral_id = ? AND facility_id = ? AND status = 'ACTIVE'",
                Long.class, collateralId, facilityId);
        return n != null && n > 0;
    }

    public long insertAllocation(long collateralId, long facilityId, BigDecimal pct, String actor) {
        return jdbc.queryForObject("INSERT INTO collateral_allocation (collateral_id, facility_id, allocation_pct, created_by) VALUES (?,?,?,?) RETURNING id",
                Long.class, collateralId, facilityId, pct, actor);
    }

    public Optional<Allocation> lockAllocation(long id) {
        List<Allocation> l = jdbc.query("SELECT id, collateral_id, facility_id, allocation_pct, status FROM collateral_allocation WHERE id = ? FOR UPDATE",
                ALLOC_MAP, id);
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    public void releaseAllocation(long id) {
        jdbc.update("UPDATE collateral_allocation SET status = 'RELEASED', released_at = now() WHERE id = ?", id);
    }

    public List<AllocationView> allocationViews(String collateralRef) {
        return jdbc.query(ALLOC_VIEW_SQL + "WHERE c.collateral_ref = ? ORDER BY a.id", ALLOC_VIEW, collateralRef);
    }

    public Optional<AllocationView> allocationView(long id) {
        List<AllocationView> l = jdbc.query(ALLOC_VIEW_SQL + "WHERE a.id = ?", ALLOC_VIEW, id);
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    public List<Long> activeFacilityIdsFor(long collateralId) {
        return jdbc.query("SELECT facility_id FROM collateral_allocation WHERE collateral_id = ? AND status = 'ACTIVE' ORDER BY facility_id",
                (rs, i) -> rs.getLong(1), collateralId);
    }

    static final String ALLOC_VIEW_SQL = "SELECT a.id, c.collateral_ref, f.facility_ref, a.allocation_pct, a.status, a.created_by, a.created_at, a.released_at "
            + "FROM collateral_allocation a JOIN collateral c ON c.id = a.collateral_id JOIN facility f ON f.id = a.facility_id ";

    static final RowMapper<AllocationView> ALLOC_VIEW = (rs, i) -> new AllocationView(rs.getLong("id"), rs.getString("collateral_ref"),
            rs.getString("facility_ref"), rs.getBigDecimal("allocation_pct"), rs.getString("status"), rs.getString("created_by"),
            rs.getObject("created_at", OffsetDateTime.class), rs.getObject("released_at", OffsetDateTime.class));

    /** Inputs for the drawing-power calculation: every ACTIVE allocation to the facility with the latest approved valuation. */
    public List<Input> drawingPowerInputs(long facilityId) {
        return jdbc.query("SELECT c.collateral_ref, c.status, a.allocation_pct, v.base_value, v.valuation_date, "
                + "COALESCE(c.haircut_pct, t.default_haircut_pct) AS haircut, COALESCE(c.max_ltv_pct, t.max_ltv_pct) AS ltv, "
                + "c.prior_charge_amount, t.valuation_validity_days, t.requires_insurance, c.insurance_expiry "
                + "FROM collateral_allocation a JOIN collateral c ON c.id = a.collateral_id "
                + "JOIN collateral_type_config t ON t.type_code = c.collateral_type "
                + "LEFT JOIN LATERAL (SELECT valuation_date, COALESCE(forced_sale_value, market_value) AS base_value FROM collateral_valuation "
                + "WHERE collateral_id = c.id AND status = 'APPROVED' ORDER BY valuation_date DESC, id DESC LIMIT 1) v ON TRUE "
                + "WHERE a.facility_id = ? AND a.status = 'ACTIVE' ORDER BY a.id",
                (rs, i) -> new Input(rs.getString("collateral_ref"), rs.getString("status"), rs.getBigDecimal("allocation_pct"),
                        rs.getBigDecimal("base_value"), rs.getObject("valuation_date", LocalDate.class), rs.getBigDecimal("haircut"),
                        rs.getBigDecimal("ltv"), rs.getBigDecimal("prior_charge_amount"), rs.getInt("valuation_validity_days"),
                        rs.getBoolean("requires_insurance"), rs.getObject("insurance_expiry", LocalDate.class)),
                facilityId);
    }
}
