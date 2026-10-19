package com.bank.lending.lcm.maintenance;

import com.bank.lending.lcm.common.LcmException;
import com.bank.lending.lcm.maintenance.MaintenanceDtos.ClearResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Spring-free: the guards around clearing the limit and collateral data. */
class MaintenanceServiceTest {

    private final JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);

    private MaintenanceService service(boolean enabled) {
        return new MaintenanceService(jdbc, enabled);
    }

    @Test
    void clearIsRefusedWhileSwitchedOff() {
        assertThatThrownBy(() -> service(false).clearAll("RESET", "admin"))
                .isInstanceOfSatisfying(LcmException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.code()).isEqualTo("RESET_DISABLED");
                    assertThat(e.getMessage()).contains("LCM_RESET_ENABLED");
                });
        verify(jdbc, never()).execute(anyString());
    }

    @Test
    void clearNeedsTheConfirmationWord() {
        assertThatThrownBy(() -> service(true).clearAll("reset", "admin"))
                .isInstanceOfSatisfying(LcmException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service(true).clearAll(null, "admin")).isInstanceOf(LcmException.class);
        verify(jdbc, never()).execute(anyString());
    }

    @Test
    void clearTruncatesEveryLimitAndCollateralTableInOneStatementAndKeepsTheConfiguration() {
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(3L);

        ClearResult r = service(true).clearAll("RESET", "admin");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(1)).execute(sql.capture());
        assertThat(sql.getValue()).startsWith("TRUNCATE TABLE ").endsWith(" RESTART IDENTITY").doesNotContain("CASCADE");
        for (String table : List.of("facility", "facility_change", "facility_excess", "utilisation_position", "reservation",
                "limit_txn", "idempotency_key", "lcm_event", "audit_log", "collateral", "collateral_valuation",
                "collateral_allocation", "breach")) {
            assertThat(sql.getValue()).contains(table);
        }
        // The collateral types and their rules, and Flyway's history, are never touched.
        assertThat(sql.getValue()).doesNotContain("collateral_type_config", "schema_history");
        assertThat(r.deleted()).hasSize(13);
        assertThat(r.totalRows()).isEqualTo(39L);
        assertThat(r.clearedBy()).isEqualTo("admin");
    }

    @Test
    void statusReportsTheSwitchAndTheCounts() {
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(1L);

        assertThat(service(false).status().resetEnabled()).isFalse();
        assertThat(service(true).status().resetEnabled()).isTrue();
        assertThat(service(true).status().rowCounts()).hasSize(13);
    }
}
