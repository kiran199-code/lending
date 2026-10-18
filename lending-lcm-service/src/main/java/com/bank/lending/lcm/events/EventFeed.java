package com.bank.lending.lcm.events;

import com.bank.lending.lcm.common.Json;
import com.bank.lending.lcm.common.LcmException;
import com.bank.lending.lcm.config.LcmSettings;
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
@RequestMapping("/api/v1/lcm/events")
@Tag(name = "Events", description = "Pull feed of limit and collateral events")
public class EventFeed {

    public record EventView(long id, String cursor, String eventType, String aggregateType, String aggregateRef,
                            Map<String, Object> payload, OffsetDateTime createdAt) {}

    public record EventPage(List<EventView> events, String nextCursor) {}

    private final JdbcTemplate jdbc;
    private final LcmSettings settings;

    public EventFeed(JdbcTemplate jdbc, LcmSettings settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    @GetMapping
    @Transactional(readOnly = true)
    @Operation(summary = "Events after the cursor, oldest first; pass nextCursor back to continue (lcm.events.read)")
    public EventPage read(@RequestParam(value = "cursor", required = false) String cursor,
                          @RequestParam(value = "limit", defaultValue = "100") int limit) {
        long afterTx = 0;
        long afterId = 0;
        if (cursor != null && !cursor.isBlank()) {
            String[] p = cursor.split("-");
            try {
                if (p.length != 2) throw new NumberFormatException();
                afterTx = Long.parseLong(p[0]);
                afterId = Long.parseLong(p[1]);
            } catch (NumberFormatException e) {
                throw LcmException.badRequest("INVALID_CURSOR", "cursor must be a value previously returned as nextCursor");
            }
        }
        int size = Math.max(1, Math.min(limit, settings.maxEventPage()));
        List<EventView> events = jdbc.query("SELECT id, tx_id, event_type, aggregate_type, aggregate_ref, payload, created_at FROM lcm_event "
                        + "WHERE (tx_id, id) > (?, ?) AND tx_id < pg_snapshot_xmin(pg_current_snapshot())::text::bigint "
                        + "ORDER BY tx_id, id LIMIT ?",
                (rs, i) -> new EventView(rs.getLong("id"), rs.getLong("tx_id") + "-" + rs.getLong("id"), rs.getString("event_type"),
                        rs.getString("aggregate_type"), rs.getString("aggregate_ref"), Json.readMap(rs.getString("payload")),
                        rs.getObject("created_at", OffsetDateTime.class)),
                afterTx, afterId, size);
        String next = events.isEmpty() ? (cursor == null ? "0-0" : cursor) : events.get(events.size() - 1).cursor();
        return new EventPage(events, next);
    }
}
