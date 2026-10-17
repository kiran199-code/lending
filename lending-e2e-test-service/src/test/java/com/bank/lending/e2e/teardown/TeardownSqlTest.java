package com.bank.lending.e2e.teardown;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bank.lending.e2e.run.RunContext;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TeardownSqlTest {

    @Test
    void runIdMapsToAnIdRangeAndBack() {
        RunContext c = RunContext.create(9_000_000_000_000L, Path.of("target/test-results"));
        RunContext back = RunContext.fromRunId(c.runId(), 9_000_000_000_000L, Path.of("target/test-results"));
        assertTrue(back.rangeLow() == c.rangeLow());
        assertTrue(c.customerId(1) >= c.rangeLow() && c.customerId(999) <= c.rangeHigh());
        assertTrue(c.idempotencyKey(3, "I1").startsWith(c.runId() + "-"));
    }

    @Test
    void cleanupScriptIsScopedToTheRunAndIsNotADestructiveWildcard() {
        RunContext c = RunContext.create(9_000_000_000_000L, Path.of("target/test-results"));
        String sql = Teardown.cleanupSql(c);
        assertTrue(sql.contains("BETWEEN " + c.rangeLow() + " AND " + c.rangeHigh()));
        assertTrue(sql.contains("LIKE '" + c.runId() + "-%'"));
        assertTrue(sql.contains("BEGIN;") && sql.contains("COMMIT;"));
        assertFalse(sql.toLowerCase().contains("truncate"));
        // every DELETE carries a WHERE
        for (String line : sql.split("\n")) {
            if (line.startsWith("DELETE")) {
                assertTrue(line.contains("WHERE"), line);
            }
        }
    }

    @Test
    void cleanupScriptPurgesAccountingRowsUnderTheAppendOnlyOptIn() {
        RunContext c = RunContext.create(9_000_000_000_000L, Path.of("target/test-results"));
        String sql = Teardown.cleanupSql(c);
        int optIn = sql.indexOf("set_config('acct.allow_purge', 'on', true)");
        assertTrue(optIn > 0);
        // the opt-in comes first, and journal lines go before their headers
        assertTrue(optIn < sql.indexOf("DELETE FROM acct_journal_line"));
        assertTrue(sql.indexOf("DELETE FROM acct_journal_line") < sql.indexOf("DELETE FROM acct_journal WHERE"));
        assertTrue(sql.contains("DELETE FROM acct_accrual_carry WHERE"));
        assertTrue(sql.contains("DELETE FROM acct_posting_exception WHERE"));
    }
}
