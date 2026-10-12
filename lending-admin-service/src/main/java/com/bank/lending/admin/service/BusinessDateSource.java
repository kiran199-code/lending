package com.bank.lending.admin.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The bank's current business date, as owned by lending-service ({@code business_date_state}).
 *
 * <p>Every "today" in the admin portal (days past due, overdue, upcoming, dashboard, accrual defaults)
 * comes from here instead of the wall clock, so the portal agrees with what the batch and the payment
 * rules treat as today. The portal stays read-only: the date is read straight from the shared database.
 *
 * <p>If the table cannot be read (lending-service has not applied V13 yet) the wall clock is used and a
 * warning is logged at most once a minute, so an older database keeps working.
 */
@Component
public class BusinessDateSource {

    private static final Logger log = LoggerFactory.getLogger(BusinessDateSource.class);
    private static final long WARN_EVERY_MS = 60_000;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AtomicLong lastWarn = new AtomicLong();

    public BusinessDateSource(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** The current business date; the wall-clock date in the business time zone if it cannot be read. */
    public LocalDate today() {
        try {
            List<Date> rows = jdbc.query("SELECT business_date FROM business_date_state WHERE id = 1",
                    (rs, i) -> rs.getDate(1));
            if (!rows.isEmpty() && rows.get(0) != null) {
                return rows.get(0).toLocalDate();
            }
            warn("business_date_state has no row");
        } catch (RuntimeException e) {
            warn("business_date_state not readable: " + e.getMessage());
        }
        return LocalDate.now(clock);
    }

    /** OPEN, EOD_RUNNING, or null when unknown. */
    public String status() {
        try {
            List<String> rows = jdbc.query("SELECT status FROM business_date_state WHERE id = 1",
                    (rs, i) -> rs.getString(1));
            return rows.isEmpty() ? null : rows.get(0);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** True when the date comes from the database rather than the wall-clock fallback. */
    public boolean fromLendingService() {
        return status() != null;
    }

    private void warn(String msg) {
        long now = System.currentTimeMillis();
        long prev = lastWarn.get();
        if (now - prev > WARN_EVERY_MS && lastWarn.compareAndSet(prev, now)) {
            log.warn("{} - using the wall-clock date instead", msg);
        }
    }
}
