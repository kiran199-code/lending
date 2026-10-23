package com.bank.lending.chit.events;

import com.bank.lending.chit.common.ChitException;
import com.bank.lending.chit.common.Json;
import com.bank.lending.chit.config.ChitSettings;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Pull-based event feed, so consumers (portal, notifications, reporting) need no broker.
 *
 * <p>Gap-free reading: ids are handed out when a row is inserted but become visible when its transaction commits,
 * so a higher id can be visible before a lower one. The feed therefore only returns events whose transaction id is
 * below the oldest transaction still in flight, and orders and pages by (transaction id, id). A consumer keeps the
 * opaque cursor of the last event it processed and can never skip one.
 */
@RestController
@RequestMapping("/api/v1/chit/events")
@Tag(name = "Events", description = "Pull feed of chit events")
public class EventFeed {

    public record EventView(long id, String cursor, String eventType, String aggregateType, String aggregateRef,
                            Map<String, Object> payload, OffsetDateTime createdAt) {
    }

    public record EventPage(List<EventView> events, String nextCursor) {
    }

    private final JdbcTemplate jdbc;
    private final ChitSettings settings;

    public EventFeed(JdbcTemplate jdbc, ChitSettings settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    @GetMapping
    @Transactional(readOnly = true)
    @Operation(summary = "Events after the cursor, oldest first; pass nextCursor back to continue (chit.events.read)")
    public EventPage read(@RequestParam(value = "cursor", required = false) String cursor,
                          @RequestParam(value = "limit", defaultValue = "100") int limit) {
        long[] after = parseCursor(cursor);
        int size = Math.max(1, Math.min(limit, settings.maxEventPage()));
        List<EventView> events = jdbc.query(
                "SELECT id, tx_id, event_type, aggregate_type, aggregate_ref, payload, created_at FROM chit_event "
                        + "WHERE (tx_id, id) > (?, ?) AND tx_id < pg_snapshot_xmin(pg_current_snapshot())::text::bigint "
                        + "ORDER BY tx_id, id LIMIT ?",
                (rs, i) -> new EventView(rs.getLong("id"), rs.getLong("tx_id") + "-" + rs.getLong("id"), rs.getString("event_type"),
                        rs.getString("aggregate_type"), rs.getString("aggregate_ref"), Json.readMap(rs.getString("payload")),
                        rs.getObject("created_at", OffsetDateTime.class)),
                after[0], after[1], size);
        String next = events.isEmpty() ? (cursor == null || cursor.isBlank() ? "0-0" : cursor) : events.get(events.size() - 1).cursor();
        return new EventPage(events, next);
    }

    /** "txid-id" to {txid, id}; null or blank means from the start. Package-private for the unit test. */
    static long[] parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return new long[]{0, 0};
        }
        String[] p = cursor.split("-");
        try {
            if (p.length != 2) {
                throw new NumberFormatException();
            }
            return new long[]{Long.parseLong(p[0]), Long.parseLong(p[1])};
        } catch (NumberFormatException e) {
            throw ChitException.badRequest("INVALID_CURSOR", "cursor must be a value previously returned as nextCursor");
        }
    }
}
