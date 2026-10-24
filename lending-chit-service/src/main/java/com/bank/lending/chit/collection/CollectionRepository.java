package com.bank.lending.chit.collection;

import com.bank.lending.chit.calc.DemandFigures;
import com.bank.lending.chit.collection.CollectionModels.AdvanceRow;
import com.bank.lending.chit.collection.CollectionModels.AllocationRow;
import com.bank.lending.chit.collection.CollectionModels.CycleDue;
import com.bank.lending.chit.collection.CollectionModels.CycleTotals;
import com.bank.lending.chit.collection.CollectionModels.DefaultCaseRow;
import com.bank.lending.chit.collection.CollectionModels.DemandRow;
import com.bank.lending.chit.collection.CollectionModels.DividendRow;
import com.bank.lending.chit.collection.CollectionModels.PaymentRow;
import com.bank.lending.chit.domain.AllocationBucket;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** JDBC access to demands, payments, allocations, late fees, dividends and default cases. Every method runs in the caller's transaction. */
@Repository
public class CollectionRepository {

    private static final String DEMAND_SELECT = "SELECT d.id, d.demand_ref, d.cycle_id, c.cycle_no, d.ticket_id, d.due_date, d.raised_on, "
            + "d.subscription_amount, d.dividend_adjustment, d.subscription_paid, d.late_fee_accrued, d.late_fee_paid, d.status "
            + "FROM chit_demand d JOIN chit_cycle c ON c.id = d.cycle_id ";
    private static final String PAYMENT_COLUMNS = "id, payment_ref, group_id, ticket_id, amount, channel, status, business_date, external_ref, "
            + "reversal_reason, reversed_at, reversed_by, created_at, created_by";
    private static final String CASE_COLUMNS = "id, ticket_id, opened_on, status, shortfall_amount, reason, missed_instalments, days_overdue, "
            + "closed_on, foreman_covered, opened_by";

    private final JdbcTemplate jdbc;

    public CollectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- groups and cycles ----------------------------------------------------------------------------------------

    /** Codes of the groups that collect money: ACTIVE and CLOSING, oldest first. */
    public List<String> collectingGroupCodes() {
        return jdbc.query("SELECT group_code FROM chit_group WHERE status IN ('ACTIVE','CLOSING') ORDER BY id", (rs, i) -> rs.getString(1));
    }

    /** SCHEDULED cycles whose due date has been reached, in cycle order. */
    public List<CycleDue> scheduledCyclesDue(long groupId, LocalDate onOrBefore) {
        return jdbc.query("SELECT id, cycle_no, due_date FROM chit_cycle WHERE group_id = ? AND status = 'SCHEDULED' AND due_date <= ? "
                        + "ORDER BY cycle_no",
                (rs, i) -> new CycleDue(rs.getLong("id"), rs.getInt("cycle_no"), rs.getObject("due_date", LocalDate.class)),
                groupId, Date.valueOf(onOrBefore));
    }

    public void openCycleCollection(long cycleId, LocalDate on) {
        jdbc.update("UPDATE chit_cycle SET status = 'COLLECTION_OPEN', collection_opened_on = ?, version = version + 1 WHERE id = ?",
                Date.valueOf(on), cycleId);
    }

    /** The highest cycle number whose collection has opened; 0 before the first. */
    public int latestStartedCycleNo(long groupId) {
        Integer n = jdbc.queryForObject("SELECT COALESCE(max(cycle_no), 0) FROM chit_cycle WHERE group_id = ? AND status <> 'SCHEDULED'",
                Integer.class, groupId);
        return n == null ? 0 : n;
    }

    public int countScheduledCycles(long groupId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM chit_cycle WHERE group_id = ? AND status = 'SCHEDULED'", Integer.class, groupId);
        return n == null ? 0 : n;
    }

    // ---- demands --------------------------------------------------------------------------------------------------

    /** Creates the demand unless this ticket already has one for the cycle. Returns its id, or empty when it already existed. */
    public Optional<Long> insertDemand(long cycleId, long ticketId, String demandRef, LocalDate dueDate, LocalDate raisedOn, BigDecimal amount) {
        List<Long> ids = jdbc.query("INSERT INTO chit_demand (demand_ref, cycle_id, ticket_id, due_date, raised_on, subscription_amount) "
                        + "VALUES (?,?,?,?,?,?) ON CONFLICT (cycle_id, ticket_id) DO NOTHING RETURNING id",
                (rs, i) -> rs.getLong(1), demandRef, cycleId, ticketId, Date.valueOf(dueDate), Date.valueOf(raisedOn), amount);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    /** Writes the figures and the status that follows from them (PAID exactly when nothing is left to pay). */
    public void saveFigures(long demandId, DemandFigures f) {
        jdbc.update("UPDATE chit_demand SET dividend_adjustment = ?, subscription_paid = ?, late_fee_accrued = ?, late_fee_paid = ?, "
                        + "status = ?, version = version + 1 WHERE id = ?",
                f.dividendAdjustment(), f.subscriptionPaid(), f.lateFeeAccrued(), f.lateFeePaid(), f.settled() ? "PAID" : "OPEN", demandId);
    }

    public Optional<DemandRow> findDemand(long demandId) {
        return first(jdbc.query(DEMAND_SELECT + "WHERE d.id = ?", CollectionRepository::mapDemand, demandId));
    }

    /** Demands of one ticket still open, oldest cycle first. */
    public List<DemandRow> openDemandsOfTicket(long ticketId) {
        return jdbc.query(DEMAND_SELECT + "WHERE d.ticket_id = ? AND d.status = 'OPEN' ORDER BY c.cycle_no, d.id",
                CollectionRepository::mapDemand, ticketId);
    }

    public List<DemandRow> demandsOfTicket(long ticketId) {
        return jdbc.query(DEMAND_SELECT + "WHERE d.ticket_id = ? ORDER BY c.cycle_no, d.id", CollectionRepository::mapDemand, ticketId);
    }

    /** Demands of a group, optionally for one ticket and/or one status (OPEN, PAID), newest cycle first. */
    public List<DemandRow> demandsOfGroup(long groupId, Long ticketId, String status, int limit, int offset) {
        return jdbc.query(DEMAND_SELECT + "JOIN chit_ticket t ON t.id = d.ticket_id WHERE c.group_id = ? "
                        + "AND (?::bigint IS NULL OR d.ticket_id = ?::bigint) AND (?::text IS NULL OR d.status = ?::text) "
                        + "ORDER BY c.cycle_no DESC, t.ticket_no, d.id LIMIT ? OFFSET ?",
                CollectionRepository::mapDemand, groupId, ticketId, ticketId, status, status, limit, offset);
    }

    /** Open demands of the group whose due date is before {@code day}: the ones that can carry a late fee. */
    public List<DemandRow> overdueOpenDemands(long groupId, LocalDate day) {
        return jdbc.query(DEMAND_SELECT + "WHERE c.group_id = ? AND d.status = 'OPEN' AND d.due_date < ? ORDER BY c.cycle_no, d.id",
                CollectionRepository::mapDemand, groupId, Date.valueOf(day));
    }

    /** Every open demand of a group, for the default check. */
    public List<DemandRow> openDemandsOfGroup(long groupId) {
        return jdbc.query(DEMAND_SELECT + "WHERE c.group_id = ? AND d.status = 'OPEN' ORDER BY d.ticket_id, c.cycle_no",
                CollectionRepository::mapDemand, groupId);
    }

    public List<CycleTotals> cycleTotals(long groupId) {
        return jdbc.query("SELECT c.cycle_no, c.status, c.due_date, count(d.id) AS demands, "
                        + "count(d.id) FILTER (WHERE d.status = 'PAID') AS settled, "
                        + "COALESCE(sum(d.subscription_amount - d.dividend_adjustment), 0) AS due, "
                        + "COALESCE(sum(d.subscription_paid), 0) AS collected, COALESCE(sum(d.late_fee_accrued), 0) AS fee_accrued, "
                        + "COALESCE(sum(d.late_fee_paid), 0) AS fee_paid "
                        + "FROM chit_cycle c LEFT JOIN chit_demand d ON d.cycle_id = c.id WHERE c.group_id = ? "
                        + "GROUP BY c.id, c.cycle_no, c.status, c.due_date ORDER BY c.cycle_no",
                (rs, i) -> new CycleTotals(rs.getInt("cycle_no"), rs.getString("status"), rs.getObject("due_date", LocalDate.class),
                        rs.getInt("demands"), rs.getInt("settled"), rs.getBigDecimal("due"), rs.getBigDecimal("collected"),
                        rs.getBigDecimal("fee_accrued"), rs.getBigDecimal("fee_paid")), groupId);
    }

    // ---- late fees ------------------------------------------------------------------------------------------------

    /** True when the day's fee row was written; false when that day was already charged (a rerun). */
    public boolean insertLateFee(long demandId, LocalDate day, BigDecimal amount) {
        return jdbc.update("INSERT INTO chit_late_fee (demand_id, fee_date, amount) VALUES (?,?,?) ON CONFLICT (demand_id, fee_date) DO NOTHING",
                demandId, Date.valueOf(day), amount) == 1;
    }

    public Optional<LocalDate> lastLateFeeDate(long demandId) {
        List<LocalDate> d = jdbc.query("SELECT max(fee_date) FROM chit_late_fee WHERE demand_id = ?",
                (rs, i) -> rs.getObject(1, LocalDate.class), demandId);
        return d.isEmpty() || d.get(0) == null ? Optional.empty() : Optional.of(d.get(0));
    }

    // ---- dividends ------------------------------------------------------------------------------------------------

    /** Declared dividends to be set against the next instalment, from cycles before {@code cycleNo}, oldest first. */
    public List<DividendRow> declaredDividendsBefore(long ticketId, int cycleNo) {
        return jdbc.query("SELECT v.id, v.cycle_id, c.cycle_no, v.ticket_id, v.amount FROM chit_dividend v JOIN chit_cycle c ON c.id = v.cycle_id "
                        + "WHERE v.ticket_id = ? AND v.status = 'DECLARED' AND v.mode = 'ADJUST_NEXT_INSTALMENT' AND c.cycle_no < ? "
                        + "ORDER BY c.cycle_no, v.id",
                (rs, i) -> new DividendRow(rs.getLong("id"), rs.getLong("cycle_id"), rs.getInt("cycle_no"), rs.getLong("ticket_id"),
                        rs.getBigDecimal("amount")), ticketId, cycleNo);
    }

    public void markDividendApplied(long dividendId, long demandId) {
        jdbc.update("UPDATE chit_dividend SET status = 'APPLIED', applied_to_demand_id = ? WHERE id = ?", demandId, dividendId);
    }

    // ---- payments and allocations ---------------------------------------------------------------------------------

    public long nextPaymentId() {
        Long id = jdbc.queryForObject("SELECT nextval('chit_payment_id_seq')", Long.class);
        return id == null ? 0 : id;
    }

    public void insertPayment(long id, String ref, long groupId, long ticketId, BigDecimal amount, String channel, LocalDate businessDate,
                              String idempotencyKey, String externalRef, String actor) {
        jdbc.update("INSERT INTO chit_payment (id, payment_ref, group_id, ticket_id, amount, channel, business_date, idempotency_key, "
                        + "external_ref, created_by) VALUES (?,?,?,?,?,?,?,?,?,?)",
                id, ref, groupId, ticketId, amount, channel, Date.valueOf(businessDate), idempotencyKey, externalRef, actor);
    }

    public Optional<PaymentRow> findPayment(long groupId, String paymentRef) {
        return first(jdbc.query("SELECT " + PAYMENT_COLUMNS + " FROM chit_payment WHERE group_id = ? AND payment_ref = ?",
                CollectionRepository::mapPayment, groupId, paymentRef));
    }

    public Optional<PaymentRow> findPaymentByRef(String paymentRef) {
        return first(jdbc.query("SELECT " + PAYMENT_COLUMNS + " FROM chit_payment WHERE payment_ref = ?", CollectionRepository::mapPayment, paymentRef));
    }

    public List<PaymentRow> paymentsOfGroup(long groupId, Long ticketId, int limit, int offset) {
        return jdbc.query("SELECT " + PAYMENT_COLUMNS + " FROM chit_payment WHERE group_id = ? AND (?::bigint IS NULL OR ticket_id = ?::bigint) "
                        + "ORDER BY id DESC LIMIT ? OFFSET ?", CollectionRepository::mapPayment, groupId, ticketId, ticketId, limit, offset);
    }

    public List<PaymentRow> paymentsOfTicket(long ticketId) {
        return jdbc.query("SELECT " + PAYMENT_COLUMNS + " FROM chit_payment WHERE ticket_id = ? ORDER BY id DESC",
                CollectionRepository::mapPayment, ticketId);
    }

    public void markPaymentReversed(long paymentId, String reason, String actor) {
        jdbc.update("UPDATE chit_payment SET status = 'REVERSED', reversal_reason = ?, reversed_by = ?, reversed_at = now(), version = version + 1 "
                + "WHERE id = ?", reason, actor, paymentId);
    }

    public long insertAllocation(long paymentId, Long demandId, AllocationBucket bucket, BigDecimal amount, boolean fromAdvance) {
        return jdbc.query("INSERT INTO chit_payment_allocation (payment_id, demand_id, bucket, amount, from_advance) VALUES (?,?,?,?,?) RETURNING id",
                (rs, i) -> rs.getLong(1), paymentId, demandId, bucket.name(), amount, fromAdvance).get(0);
    }

    public List<AllocationRow> allocationsOf(long paymentId) {
        return jdbc.query("SELECT id, payment_id, demand_id, bucket, amount, reversed, from_advance, advance_used FROM chit_payment_allocation "
                + "WHERE payment_id = ? ORDER BY id", CollectionRepository::mapAllocation, paymentId);
    }

    public void markAllocationsReversed(long paymentId) {
        jdbc.update("UPDATE chit_payment_allocation SET reversed = TRUE WHERE payment_id = ?", paymentId);
    }

    /** Unused advance held for a ticket, oldest first. */
    public List<AdvanceRow> openAdvance(long ticketId) {
        return jdbc.query("SELECT a.id, a.payment_id, a.amount, a.advance_used FROM chit_payment_allocation a "
                        + "JOIN chit_payment p ON p.id = a.payment_id WHERE p.ticket_id = ? AND p.status = 'RECEIVED' "
                        + "AND a.bucket = 'ADVANCE' AND NOT a.reversed AND a.advance_used < a.amount ORDER BY a.id",
                (rs, i) -> new AdvanceRow(rs.getLong(1), rs.getLong(2), rs.getBigDecimal(3), rs.getBigDecimal(4)), ticketId);
    }

    public void useAdvance(long allocationId, BigDecimal amount) {
        jdbc.update("UPDATE chit_payment_allocation SET advance_used = advance_used + ? WHERE id = ?", amount, allocationId);
    }

    // ---- default cases --------------------------------------------------------------------------------------------

    public Optional<DefaultCaseRow> openCase(long ticketId) {
        return first(jdbc.query("SELECT " + CASE_COLUMNS + " FROM chit_default_case WHERE ticket_id = ? AND status = 'OPEN'",
                CollectionRepository::mapCase, ticketId));
    }

    public long insertCase(long ticketId, LocalDate openedOn, BigDecimal shortfall, String reason, int missed, int daysOverdue, String actor) {
        return jdbc.query("INSERT INTO chit_default_case (ticket_id, opened_on, shortfall_amount, reason, missed_instalments, days_overdue, opened_by) "
                        + "VALUES (?,?,?,?,?,?,?) RETURNING id", (rs, i) -> rs.getLong(1),
                ticketId, Date.valueOf(openedOn), shortfall, reason, missed, daysOverdue, actor).get(0);
    }

    public void closeCase(long caseId, String status, LocalDate on) {
        jdbc.update("UPDATE chit_default_case SET status = ?, closed_on = ?, version = version + 1 WHERE id = ?", status, Date.valueOf(on), caseId);
    }

    public List<DefaultCaseRow> casesOfGroup(long groupId, String status) {
        return jdbc.query("SELECT " + prefixed("k", CASE_COLUMNS) + " FROM chit_default_case k JOIN chit_ticket t ON t.id = k.ticket_id "
                        + "WHERE t.group_id = ? AND (?::text IS NULL OR k.status = ?::text) ORDER BY k.id DESC",
                CollectionRepository::mapCase, groupId, status, status);
    }

    public boolean hasWonPrize(long ticketId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM chit_cycle WHERE winner_ticket_id = ?", Integer.class, ticketId);
        return n != null && n > 0;
    }

    // ---- cross-group dues -----------------------------------------------------------------------------------------

    public record DueRecord(String groupCode, String groupName, int ticketNo, long customerId, String ticketStatus, int cycleNo,
                            String demandRef, LocalDate dueDate, BigDecimal subscriptionOutstanding, BigDecimal lateFeeOutstanding) {
    }

    /** Raised demands with something owed. {@code overdueBefore} restricts to demands due before that day (null: all open ones). */
    public List<DueRecord> openDues(LocalDate overdueBefore, String groupCode, int limit, int offset) {
        return jdbc.query("SELECT g.group_code, g.name, t.ticket_no, t.customer_id, t.status AS ticket_status, c.cycle_no, d.demand_ref, d.due_date, "
                        + "GREATEST(d.subscription_amount - d.dividend_adjustment - d.subscription_paid, 0) AS sub_out, "
                        + "GREATEST(d.late_fee_accrued - d.late_fee_paid, 0) AS fee_out "
                        + "FROM chit_demand d JOIN chit_cycle c ON c.id = d.cycle_id JOIN chit_group g ON g.id = c.group_id "
                        + "JOIN chit_ticket t ON t.id = d.ticket_id WHERE d.status = 'OPEN' "
                        + "AND (?::date IS NULL OR d.due_date < ?::date) AND (?::text IS NULL OR g.group_code = ?::text) "
                        + "ORDER BY d.due_date, g.group_code, t.ticket_no LIMIT ? OFFSET ?",
                (rs, i) -> new DueRecord(rs.getString("group_code"), rs.getString("name"), rs.getInt("ticket_no"), rs.getLong("customer_id"),
                        rs.getString("ticket_status"), rs.getInt("cycle_no"), rs.getString("demand_ref"), rs.getObject("due_date", LocalDate.class),
                        rs.getBigDecimal("sub_out"), rs.getBigDecimal("fee_out")),
                overdueBefore == null ? null : Date.valueOf(overdueBefore), overdueBefore == null ? null : Date.valueOf(overdueBefore),
                groupCode, groupCode, limit, offset);
    }

    /** Instalments not raised yet: SCHEDULED cycles of collecting groups falling due within the window, one row per live ticket. */
    public List<DueRecord> upcomingDues(LocalDate from, LocalDate to, String groupCode, int limit, int offset) {
        return jdbc.query("SELECT g.group_code, g.name, t.ticket_no, t.customer_id, t.status AS ticket_status, c.cycle_no, c.due_date, g.subscription "
                        + "FROM chit_cycle c JOIN chit_group g ON g.id = c.group_id JOIN chit_ticket t ON t.group_id = g.id "
                        + "WHERE c.status = 'SCHEDULED' AND g.status IN ('ACTIVE','CLOSING') AND c.due_date BETWEEN ? AND ? "
                        + "AND t.status IN ('ACTIVE','PRIZED','DEFAULTED','TRANSFERRED') AND (?::text IS NULL OR g.group_code = ?::text) "
                        + "ORDER BY c.due_date, g.group_code, t.ticket_no LIMIT ? OFFSET ?",
                (rs, i) -> new DueRecord(rs.getString("group_code"), rs.getString("name"), rs.getInt("ticket_no"), rs.getLong("customer_id"),
                        rs.getString("ticket_status"), rs.getInt("cycle_no"), null, rs.getObject("due_date", LocalDate.class),
                        rs.getBigDecimal("subscription"), BigDecimal.ZERO),
                Date.valueOf(from), Date.valueOf(to), groupCode, groupCode, limit, offset);
    }

    // ---- mapping --------------------------------------------------------------------------------------------------

    private static String prefixed(String alias, String columns) {
        StringBuilder sb = new StringBuilder();
        for (String c : columns.split(",\\s*")) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(alias).append('.').append(c.trim());
        }
        return sb.toString();
    }

    private static DemandRow mapDemand(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new DemandRow(rs.getLong("id"), rs.getString("demand_ref"), rs.getLong("cycle_id"), rs.getInt("cycle_no"), rs.getLong("ticket_id"),
                rs.getObject("due_date", LocalDate.class), rs.getObject("raised_on", LocalDate.class), rs.getBigDecimal("subscription_amount"),
                rs.getBigDecimal("dividend_adjustment"), rs.getBigDecimal("subscription_paid"), rs.getBigDecimal("late_fee_accrued"),
                rs.getBigDecimal("late_fee_paid"), rs.getString("status"));
    }

    private static PaymentRow mapPayment(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new PaymentRow(rs.getLong("id"), rs.getString("payment_ref"), rs.getLong("group_id"), rs.getLong("ticket_id"), rs.getBigDecimal("amount"),
                rs.getString("channel"), rs.getString("status"), rs.getObject("business_date", LocalDate.class), rs.getString("external_ref"),
                rs.getString("reversal_reason"), rs.getObject("reversed_at", OffsetDateTime.class), rs.getString("reversed_by"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getString("created_by"));
    }

    private static AllocationRow mapAllocation(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        long d = rs.getLong("demand_id");
        Long demandId = rs.wasNull() ? null : d;
        return new AllocationRow(rs.getLong("id"), rs.getLong("payment_id"), demandId, AllocationBucket.valueOf(rs.getString("bucket")),
                rs.getBigDecimal("amount"), rs.getBoolean("reversed"), rs.getBoolean("from_advance"), rs.getBigDecimal("advance_used"));
    }

    private static DefaultCaseRow mapCase(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        int missed = rs.getInt("missed_instalments");
        Integer missedN = rs.wasNull() ? null : missed;
        int days = rs.getInt("days_overdue");
        Integer daysN = rs.wasNull() ? null : days;
        return new DefaultCaseRow(rs.getLong("id"), rs.getLong("ticket_id"), rs.getObject("opened_on", LocalDate.class), rs.getString("status"),
                rs.getBigDecimal("shortfall_amount"), rs.getString("reason"), missedN, daysN, rs.getObject("closed_on", LocalDate.class),
                rs.getBigDecimal("foreman_covered"), rs.getString("opened_by"));
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
