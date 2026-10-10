package com.bank.lending.common.partition;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Keeps monthly partitions of interest_accrual and emi_transaction ahead of the calendar, so a
 * posting never fails with "no partition of relation ... found for row". Runs at startup and on
 * the 1st and 15th of each month; create_monthly_partition (V7) is idempotent.
 */
@Slf4j
@Service
@EnableScheduling
public class PartitionMaintenanceService {

    private static final List<String> TABLES = List.of("interest_accrual", "emi_transaction");
    private static final int MONTHS_AHEAD = 6;

    private final JdbcTemplate jdbcTemplate;

    public PartitionMaintenanceService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        ensurePartitions();
    }

    @Scheduled(cron = "0 30 2 1,15 * *", zone = "Asia/Kolkata")
    public void scheduled() {
        ensurePartitions();
    }

    public void ensurePartitions() {
        LocalDate month = LocalDate.now(ZoneId.of("Asia/Kolkata")).withDayOfMonth(1);
        try {
            for (int i = 0; i <= MONTHS_AHEAD; i++) {
                LocalDate m = month.plusMonths(i);
                for (String table : TABLES) {
                    jdbcTemplate.queryForObject("SELECT create_monthly_partition(?, ?, ?)::text",
                            String.class, table, m.getYear(), m.getMonthValue());
                }
            }
        } catch (RuntimeException e) {
            // Never block startup over housekeeping; log loudly instead.
            log.error("Partition maintenance failed", e);
        }
    }
}
