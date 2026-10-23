package com.bank.lending.chit.group;

import com.bank.lending.chit.calendar.ScheduledCycle;
import com.bank.lending.chit.domain.Frequency;
import com.bank.lending.chit.domain.GroupStatus;
import com.bank.lending.chit.domain.rules.ChitRules;
import com.bank.lending.chit.group.GroupModels.CycleRow;
import com.bank.lending.chit.group.GroupModels.GroupRow;
import com.bank.lending.chit.group.GroupModels.RulesVersionRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** JDBC access to groups, their rules versions and their cycles. Every method runs in the caller's transaction. */
@Repository
public class GroupRepository {

    private static final String GROUP_COLUMNS = "id, group_code, name, foreman_ref, status, chit_value, ticket_count, cycle_count, "
            + "subscription, frequency, start_date, current_rules_version, activated_at, activated_by, cancelled_at, cancelled_by, "
            + "cancel_reason, version, created_at, created_by";

    private final JdbcTemplate jdbc;

    public GroupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- groups ---------------------------------------------------------------------------------------------------

    /** Inserts a DRAFT group whose summary columns come from the rules; returns its id. */
    public long insert(String code, String name, String foremanRef, ChitRules r, String actor) {
        return jdbc.query(
                "INSERT INTO chit_group (group_code, name, foreman_ref, chit_value, ticket_count, cycle_count, subscription, "
                        + "frequency, start_date, created_by) VALUES (?,?,?,?,?,?,?,?,?,?) RETURNING id",
                (rs, i) -> rs.getLong(1),
                code, name, foremanRef, r.chitValue(), r.ticketCount(), r.cycleCount(), r.subscription(),
                r.frequency().name(), Date.valueOf(r.startDate()), actor).get(0);
    }

    public boolean exists(String code) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM chit_group WHERE group_code = ?", Integer.class, code);
        return n != null && n > 0;
    }

    public Optional<GroupRow> find(String code) {
        return first(jdbc.query("SELECT " + GROUP_COLUMNS + " FROM chit_group WHERE group_code = ?", GroupRepository::map, code));
    }

    /** Takes the row lock; every state change of a group starts here so changes to one group are serialised. */
    public Optional<GroupRow> findForUpdate(String code) {
        return first(jdbc.query("SELECT " + GROUP_COLUMNS + " FROM chit_group WHERE group_code = ? FOR UPDATE", GroupRepository::map, code));
    }

    public List<GroupRow> list(GroupStatus status, int limit, int offset) {
        if (status == null) {
            return jdbc.query("SELECT " + GROUP_COLUMNS + " FROM chit_group ORDER BY id DESC LIMIT ? OFFSET ?",
                    GroupRepository::map, limit, offset);
        }
        return jdbc.query("SELECT " + GROUP_COLUMNS + " FROM chit_group WHERE status = ? ORDER BY id DESC LIMIT ? OFFSET ?",
                GroupRepository::map, status.name(), limit, offset);
    }

    public long count(GroupStatus status) {
        Long n = status == null
                ? jdbc.queryForObject("SELECT count(*) FROM chit_group", Long.class)
                : jdbc.queryForObject("SELECT count(*) FROM chit_group WHERE status = ?", Long.class, status.name());
        return n == null ? 0 : n;
    }

    public void setStatus(long id, GroupStatus status, String actor) {
        jdbc.update("UPDATE chit_group SET status = ?, version = version + 1, updated_by = ? WHERE id = ?", status.name(), actor, id);
    }

    /** Copies the summary columns of a new rules version onto the group. */
    public void applyRules(long id, ChitRules r, int versionNo, String actor) {
        jdbc.update("UPDATE chit_group SET chit_value = ?, ticket_count = ?, cycle_count = ?, subscription = ?, frequency = ?, "
                        + "start_date = ?, current_rules_version = ?, version = version + 1, updated_by = ? WHERE id = ?",
                r.chitValue(), r.ticketCount(), r.cycleCount(), r.subscription(), r.frequency().name(),
                Date.valueOf(r.startDate()), versionNo, actor, id);
    }

    public void markActivated(long id, String actor) {
        jdbc.update("UPDATE chit_group SET status = 'ACTIVE', activated_at = now(), activated_by = ?, version = version + 1, "
                + "updated_by = ? WHERE id = ?", actor, actor, id);
    }

    public void markCancelled(long id, String reason, String actor) {
        jdbc.update("UPDATE chit_group SET status = 'CANCELLED', cancelled_at = now(), cancelled_by = ?, cancel_reason = ?, "
                + "version = version + 1, updated_by = ? WHERE id = ?", actor, reason, actor, id);
    }

    // ---- rules versions -------------------------------------------------------------------------------------------

    public void insertRulesVersion(long groupId, int versionNo, ChitRules rules, String actor) {
        jdbc.update("INSERT INTO chit_group_rules_version (group_id, version_no, rules_json, created_by) VALUES (?,?,?,?)",
                groupId, versionNo, RulesJson.write(rules), actor);
    }

    public Optional<RulesVersionRow> rulesVersion(long groupId, int versionNo) {
        return first(jdbc.query("SELECT group_id, version_no, rules_json, frozen, frozen_at, created_at, created_by "
                + "FROM chit_group_rules_version WHERE group_id = ? AND version_no = ?", GroupRepository::mapRules, groupId, versionNo));
    }

    public List<RulesVersionRow> rulesVersions(long groupId) {
        return jdbc.query("SELECT group_id, version_no, rules_json, frozen, frozen_at, created_at, created_by "
                + "FROM chit_group_rules_version WHERE group_id = ? ORDER BY version_no", GroupRepository::mapRules, groupId);
    }

    public void freezeRulesVersion(long groupId, int versionNo) {
        jdbc.update("UPDATE chit_group_rules_version SET frozen = TRUE, frozen_at = now() WHERE group_id = ? AND version_no = ?",
                groupId, versionNo);
    }

    // ---- cycles ---------------------------------------------------------------------------------------------------

    public void insertCycle(long groupId, ScheduledCycle c) {
        jdbc.update("INSERT INTO chit_cycle (group_id, cycle_no, due_date, auction_date) VALUES (?,?,?,?)",
                groupId, c.cycleNo(), Date.valueOf(c.dueDate()), Date.valueOf(c.auctionDate()));
    }

    public List<CycleRow> cycles(long groupId) {
        return jdbc.query("SELECT id, cycle_no, status, due_date, auction_date FROM chit_cycle WHERE group_id = ? ORDER BY cycle_no",
                (rs, i) -> new CycleRow(rs.getLong("id"), rs.getInt("cycle_no"), rs.getString("status"),
                        rs.getObject("due_date", LocalDate.class), rs.getObject("auction_date", LocalDate.class)), groupId);
    }

    // ---- mapping --------------------------------------------------------------------------------------------------

    private static GroupRow map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new GroupRow(rs.getLong("id"), rs.getString("group_code"), rs.getString("name"), rs.getString("foreman_ref"),
                GroupStatus.valueOf(rs.getString("status")), rs.getBigDecimal("chit_value"), rs.getInt("ticket_count"),
                rs.getInt("cycle_count"), rs.getBigDecimal("subscription"), Frequency.valueOf(rs.getString("frequency")),
                rs.getObject("start_date", LocalDate.class), rs.getInt("current_rules_version"),
                rs.getObject("activated_at", OffsetDateTime.class), rs.getString("activated_by"),
                rs.getObject("cancelled_at", OffsetDateTime.class), rs.getString("cancelled_by"), rs.getString("cancel_reason"),
                rs.getLong("version"), rs.getObject("created_at", OffsetDateTime.class), rs.getString("created_by"));
    }

    private static RulesVersionRow mapRules(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new RulesVersionRow(rs.getLong("group_id"), rs.getInt("version_no"), RulesJson.read(rs.getString("rules_json")),
                rs.getBoolean("frozen"), rs.getObject("frozen_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class), rs.getString("created_by"));
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
