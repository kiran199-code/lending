package com.bank.lending.lcm.monitoring;

import com.bank.lending.lcm.monitoring.MonitoringDtos.BreachView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class MonitoringRepository {

    private static final String SELECT = "SELECT b.id, b.breach_type, b.severity, f.facility_ref, c.collateral_ref, b.detail, b.status, "
            + "b.acknowledged_by, b.detected_at, b.closed_at FROM breach b LEFT JOIN facility f ON f.id = b.facility_id "
            + "LEFT JOIN collateral c ON c.id = b.collateral_id ";

    private static final RowMapper<BreachView> MAP = (rs, i) -> new BreachView(rs.getLong("id"), rs.getString("breach_type"),
            rs.getString("severity"), rs.getString("facility_ref"), rs.getString("collateral_ref"), rs.getString("detail"),
            rs.getString("status"), rs.getString("acknowledged_by"), rs.getObject("detected_at", OffsetDateTime.class),
            rs.getObject("closed_at", OffsetDateTime.class));

    /** Subject of a live breach: either a facility or a collateral id. */
    public record Live(long id, String severity, String detail) {}

    private final JdbcTemplate jdbc;

    public MonitoringRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Live> liveFacilityBreach(long facilityId, String type) {
        List<Live> l = jdbc.query("SELECT id, severity, detail FROM breach WHERE facility_id = ? AND breach_type = ? AND status <> 'CLOSED'",
                (rs, i) -> new Live(rs.getLong(1), rs.getString(2), rs.getString(3)), facilityId, type);
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    public Optional<Live> liveCollateralBreach(long collateralId, String type) {
        List<Live> l = jdbc.query("SELECT id, severity, detail FROM breach WHERE collateral_id = ? AND breach_type = ? AND status <> 'CLOSED'",
                (rs, i) -> new Live(rs.getLong(1), rs.getString(2), rs.getString(3)), collateralId, type);
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    public void open(String type, String severity, Long facilityId, Long collateralId, String detail) {
        jdbc.update("INSERT INTO breach (breach_type, severity, facility_id, collateral_id, detail) VALUES (?,?,?,?,?)",
                type, severity, facilityId, collateralId, detail);
    }

    public void close(long id) {
        jdbc.update("UPDATE breach SET status = 'CLOSED', closed_at = now() WHERE id = ?", id);
    }

    public int acknowledge(long id, String actor) {
        return jdbc.update("UPDATE breach SET status = 'ACKNOWLEDGED', acknowledged_by = ? WHERE id = ? AND status = 'OPEN'", actor, id);
    }

    public Optional<BreachView> find(long id) {
        List<BreachView> l = jdbc.query(SELECT + "WHERE b.id = ?", MAP, id);
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    public List<BreachView> list(String status) {
        StringBuilder sql = new StringBuilder(SELECT + "WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) { sql.append(" AND b.status = ?"); args.add(status.toUpperCase()); }
        sql.append(" ORDER BY b.id DESC LIMIT 500");
        return jdbc.query(sql.toString(), MAP, args.toArray());
    }
}
