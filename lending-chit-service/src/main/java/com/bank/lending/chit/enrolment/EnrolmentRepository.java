package com.bank.lending.chit.enrolment;

import com.bank.lending.chit.domain.TicketStatus;
import com.bank.lending.chit.enrolment.EnrolmentModels.FeeRow;
import com.bank.lending.chit.enrolment.EnrolmentModels.SecurityRow;
import com.bank.lending.chit.enrolment.EnrolmentModels.TicketRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** JDBC access to tickets, subscriber links, fee receipts and security rows. Every method runs in the caller's transaction. */
@Repository
public class EnrolmentRepository {

    private static final String TICKET_COLUMNS = "id, group_id, ticket_no, customer_id, status, payout_account_ref, exit_reason, "
            + "exited_on, created_at, created_by";
    private static final String SECURITY_COLUMNS = "id, ticket_id, kind, amount, guarantor_customer_id, reference, status, received_on, "
            + "verified_by, verified_at, returned_on, created_by";

    private final JdbcTemplate jdbc;

    public EnrolmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- tickets --------------------------------------------------------------------------------------------------

    /** The lowest seat number in 1..ticketCount not held by a live ticket, if any. A withdrawn seat is given back. */
    public Optional<Integer> lowestFreeNumber(long groupId, int ticketCount) {
        List<Integer> free = jdbc.query(
                "SELECT g FROM generate_series(1, ?) g WHERE NOT EXISTS (SELECT 1 FROM chit_ticket t WHERE t.group_id = ? "
                        + "AND t.ticket_no = g AND t.status <> 'EXITED') ORDER BY g LIMIT 1",
                (rs, i) -> rs.getInt(1), ticketCount, groupId);
        return free.isEmpty() ? Optional.empty() : Optional.of(free.get(0));
    }

    public int countLive(long groupId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM chit_ticket WHERE group_id = ? AND status <> 'EXITED'", Integer.class, groupId);
        return n == null ? 0 : n;
    }

    public long insertTicket(long groupId, int ticketNo, long customerId, String payoutAccountRef, String actor) {
        return jdbc.query("INSERT INTO chit_ticket (group_id, ticket_no, customer_id, payout_account_ref, created_by) "
                        + "VALUES (?,?,?,?,?) RETURNING id", (rs, i) -> rs.getLong(1),
                groupId, ticketNo, customerId, payoutAccountRef, actor).get(0);
    }

    public void insertLink(long ticketId, long customerId, LocalDate from, String reason, String actor) {
        jdbc.update("INSERT INTO chit_subscriber_link (ticket_id, customer_id, valid_from, reason, created_by) VALUES (?,?,?,?,?)",
                ticketId, customerId, Date.valueOf(from), reason, actor);
    }

    public void closeLink(long ticketId, LocalDate on) {
        jdbc.update("UPDATE chit_subscriber_link SET valid_to = GREATEST(valid_from, ?) WHERE ticket_id = ? AND valid_to IS NULL",
                Date.valueOf(on), ticketId);
    }

    /** Live ticket by number (a withdrawn ticket's number may have been reused, so withdrawn rows are not matched). */
    public Optional<TicketRow> findLive(long groupId, int ticketNo) {
        return first(jdbc.query("SELECT " + TICKET_COLUMNS + " FROM chit_ticket WHERE group_id = ? AND ticket_no = ? AND status <> 'EXITED'",
                EnrolmentRepository::mapTicket, groupId, ticketNo));
    }

    public Optional<TicketRow> findById(long ticketId) {
        return first(jdbc.query("SELECT " + TICKET_COLUMNS + " FROM chit_ticket WHERE id = ?", EnrolmentRepository::mapTicket, ticketId));
    }

    public List<TicketRow> tickets(long groupId, boolean includeExited) {
        return jdbc.query("SELECT " + TICKET_COLUMNS + " FROM chit_ticket WHERE group_id = ? "
                        + (includeExited ? "" : "AND status <> 'EXITED' ") + "ORDER BY ticket_no, id",
                EnrolmentRepository::mapTicket, groupId);
    }

    public void setTicketStatus(long ticketId, TicketStatus status, String actor) {
        jdbc.update("UPDATE chit_ticket SET status = ?, version = version + 1, updated_by = ? WHERE id = ?", status.name(), actor, ticketId);
    }

    public void exitTicket(long ticketId, String reason, LocalDate on, String actor) {
        jdbc.update("UPDATE chit_ticket SET status = 'EXITED', exit_reason = ?, exited_on = ?, version = version + 1, updated_by = ? WHERE id = ?",
                reason, Date.valueOf(on), actor, ticketId);
    }

    /** Group activation: every enrolled ticket becomes ACTIVE. Returns how many. */
    public int activateTickets(long groupId, String actor) {
        return jdbc.update("UPDATE chit_ticket SET status = 'ACTIVE', version = version + 1, updated_by = ? WHERE group_id = ? AND status = 'ENROLLED'",
                actor, groupId);
    }

    // ---- fee receipts ---------------------------------------------------------------------------------------------

    public void insertFeeReceipt(long ticketId, String feeKind, BigDecimal amount, LocalDate on, String actor) {
        jdbc.update("INSERT INTO chit_fee_receipt (ticket_id, fee_kind, amount, received_on, created_by) VALUES (?,?,?,?,?)",
                ticketId, feeKind, amount, Date.valueOf(on), actor);
    }

    public List<FeeRow> feesOfGroup(long groupId) {
        return jdbc.query("SELECT f.id, f.ticket_id, f.fee_kind, f.amount, f.status, f.received_on, f.refunded_on FROM chit_fee_receipt f "
                        + "JOIN chit_ticket t ON t.id = f.ticket_id WHERE t.group_id = ? ORDER BY f.ticket_id, f.fee_kind",
                EnrolmentRepository::mapFee, groupId);
    }

    public List<FeeRow> feesOfTicket(long ticketId) {
        return jdbc.query("SELECT id, ticket_id, fee_kind, amount, status, received_on, refunded_on FROM chit_fee_receipt "
                + "WHERE ticket_id = ? ORDER BY fee_kind", EnrolmentRepository::mapFee, ticketId);
    }

    public void markFeeRefunded(long feeId, LocalDate on) {
        jdbc.update("UPDATE chit_fee_receipt SET status = 'REFUNDED', refunded_on = ? WHERE id = ?", Date.valueOf(on), feeId);
    }

    // ---- security -------------------------------------------------------------------------------------------------

    public long insertSecurity(long ticketId, String kind, BigDecimal amount, Long guarantorCustomerId, String reference,
                               LocalDate receivedOn, String actor) {
        return jdbc.query("INSERT INTO chit_security (ticket_id, kind, amount, guarantor_customer_id, reference, received_on, created_by) "
                        + "VALUES (?,?,?,?,?,?,?) RETURNING id", (rs, i) -> rs.getLong(1),
                ticketId, kind, amount, guarantorCustomerId, reference, Date.valueOf(receivedOn), actor).get(0);
    }

    /** Every security row of the group's tickets, returned ones included, oldest first (a ticket's latest row is last). */
    public List<SecurityRow> allSecurityOfGroup(long groupId) {
        return jdbc.query("SELECT s.id, s.ticket_id, s.kind, s.amount, s.guarantor_customer_id, s.reference, s.status, s.received_on, "
                        + "s.verified_by, s.verified_at, s.returned_on, s.created_by FROM chit_security s "
                        + "JOIN chit_ticket t ON t.id = s.ticket_id WHERE t.group_id = ? ORDER BY s.ticket_id, s.id",
                EnrolmentRepository::mapSecurity, groupId);
    }

    /** Security rows that have not been returned, for every ticket of the group. */
    public List<SecurityRow> liveSecurityOfGroup(long groupId) {
        return jdbc.query("SELECT s.id, s.ticket_id, s.kind, s.amount, s.guarantor_customer_id, s.reference, s.status, s.received_on, "
                        + "s.verified_by, s.verified_at, s.returned_on, s.created_by FROM chit_security s "
                        + "JOIN chit_ticket t ON t.id = s.ticket_id WHERE t.group_id = ? AND s.status <> 'RETURNED' ORDER BY s.ticket_id",
                EnrolmentRepository::mapSecurity, groupId);
    }

    public Optional<SecurityRow> liveSecurityOfTicket(long ticketId) {
        return first(jdbc.query("SELECT " + SECURITY_COLUMNS + " FROM chit_security WHERE ticket_id = ? AND status <> 'RETURNED'",
                EnrolmentRepository::mapSecurity, ticketId));
    }

    public Optional<SecurityRow> latestSecurityOfTicket(long ticketId) {
        return first(jdbc.query("SELECT " + SECURITY_COLUMNS + " FROM chit_security WHERE ticket_id = ? ORDER BY id DESC LIMIT 1",
                EnrolmentRepository::mapSecurity, ticketId));
    }

    public void verifySecurity(long securityId, String actor) {
        jdbc.update("UPDATE chit_security SET status = 'VERIFIED', verified_by = ?, verified_at = now() WHERE id = ?", actor, securityId);
    }

    public void markSecurityReturned(long securityId, LocalDate on) {
        jdbc.update("UPDATE chit_security SET status = 'RETURNED', returned_on = ? WHERE id = ?", Date.valueOf(on), securityId);
    }

    // ---- mapping --------------------------------------------------------------------------------------------------

    private static TicketRow mapTicket(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new TicketRow(rs.getLong("id"), rs.getLong("group_id"), rs.getInt("ticket_no"), rs.getLong("customer_id"),
                TicketStatus.valueOf(rs.getString("status")), rs.getString("payout_account_ref"), rs.getString("exit_reason"),
                rs.getObject("exited_on", LocalDate.class), rs.getObject("created_at", OffsetDateTime.class), rs.getString("created_by"));
    }

    private static FeeRow mapFee(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new FeeRow(rs.getLong("id"), rs.getLong("ticket_id"), rs.getString("fee_kind"), rs.getBigDecimal("amount"),
                rs.getString("status"), rs.getObject("received_on", LocalDate.class), rs.getObject("refunded_on", LocalDate.class));
    }

    private static SecurityRow mapSecurity(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        long g = rs.getLong("guarantor_customer_id");
        Long guarantor = rs.wasNull() ? null : g;
        return new SecurityRow(rs.getLong("id"), rs.getLong("ticket_id"), rs.getString("kind"), rs.getBigDecimal("amount"), guarantor,
                rs.getString("reference"), rs.getString("status"), rs.getObject("received_on", LocalDate.class),
                rs.getString("verified_by"), rs.getObject("verified_at", OffsetDateTime.class),
                rs.getObject("returned_on", LocalDate.class), rs.getString("created_by"));
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
