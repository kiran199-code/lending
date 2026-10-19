package com.bank.lending.batch.maintenance;

import com.bank.lending.batch.core.eod.EndOfDayCoordinator;
import com.bank.lending.batch.maintenance.MaintenanceDtos.ClearResult;
import com.bank.lending.batch.maintenance.MaintenanceDtos.StatusView;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Spring-free: the guards of the batch history clear, and the one statement it runs. */
class MaintenanceServiceTest {

    private final JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    private final EndOfDayCoordinator endOfDay = Mockito.mock(EndOfDayCoordinator.class);
    private final JobExplorer explorer = Mockito.mock(JobExplorer.class);

    private MaintenanceService service(boolean enabled) {
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(3L);
        return new MaintenanceService(jdbc, endOfDay, explorer, enabled);
    }

    private JobExecution runningSince(LocalDateTime created) {
        JobExecution e = Mockito.mock(JobExecution.class);
        when(e.getCreateTime()).thenReturn(created);
        return e;
    }

    @Test
    void refusedWhenSwitchedOffAndNothingIsTouched() {
        MaintenanceService s = service(false);

        assertThatThrownBy(() -> s.clearAll("RESET", "admin"))
                .isInstanceOfSatisfying(MaintenanceException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.code()).isEqualTo("RESET_DISABLED");
                    assertThat(e.getMessage()).contains("BATCH_RESET_ENABLED");
                });
        verify(jdbc, never()).execute(anyString());
    }

    @Test
    void refusedWithoutTheConfirmationWord() {
        MaintenanceService s = service(true);

        assertThatThrownBy(() -> s.clearAll("yes", "admin"))
                .isInstanceOfSatisfying(MaintenanceException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.code()).isEqualTo("CONFIRMATION_MISSING");
                });
        assertThatThrownBy(() -> s.clearAll(null, "admin")).isInstanceOf(MaintenanceException.class);
        verify(jdbc, never()).execute(anyString());
    }

    @Test
    void refusedWhileEndOfDayIsRunning() {
        MaintenanceService s = service(true);
        when(endOfDay.isRunning()).thenReturn(true);

        assertThatThrownBy(() -> s.clearAll("RESET", "admin"))
                .isInstanceOfSatisfying(MaintenanceException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo("BATCH_BUSY");
                    assertThat(e.getMessage()).contains("End of day is running");
                });
        verify(jdbc, never()).execute(anyString());
    }

    @Test
    void refusedWhileARecentJobIsStillRunning() {
        MaintenanceService s = service(true);
        when(explorer.getJobNames()).thenReturn(java.util.List.of("INTEREST_ACCRUAL_DAILY"));
        when(explorer.findRunningJobExecutions("INTEREST_ACCRUAL_DAILY"))
                .thenReturn(Set.of(runningSince(LocalDateTime.now().minusMinutes(2))));

        assertThatThrownBy(() -> s.clearAll("RESET", "admin"))
                .isInstanceOfSatisfying(MaintenanceException.class, e -> {
                    assertThat(e.code()).isEqualTo("BATCH_BUSY");
                    assertThat(e.getMessage()).contains("INTEREST_ACCRUAL_DAILY");
                });
        verify(jdbc, never()).execute(anyString());
    }

    @Test
    void anOldExecutionStillMarkedRunningIsTreatedAsLeftOverFromACrashAndCleared() {
        MaintenanceService s = service(true);
        when(explorer.getJobNames()).thenReturn(java.util.List.of("DPD_UPDATE"));
        when(explorer.findRunningJobExecutions("DPD_UPDATE"))
                .thenReturn(Set.of(runningSince(LocalDateTime.now().minusHours(5))));

        ClearResult r = s.clearAll("RESET", "admin");

        assertThat(r.totalRows()).isEqualTo(3L * MaintenanceService.TABLES.size());
        verify(jdbc).execute(anyString());
    }

    @Test
    void clearsEveryHistoryTableInOneStatementAndKeepsTheConfigAndSchedule() {
        MaintenanceService s = service(true);
        when(explorer.getJobNames()).thenReturn(java.util.List.of());

        ClearResult r = s.clearAll("RESET", "  admin ");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).execute(sql.capture());
        assertThat(sql.getValue()).startsWith("TRUNCATE TABLE ")
                .contains("batch.batch_job_instance", "batch.batch_job_execution", "batch.batch_job_execution_params",
                        "batch.batch_job_execution_context", "batch.batch_step_execution", "batch.batch_step_execution_context",
                        "batch.batch_job_audit_log", "batch.batch_job_error_records")
                .doesNotContain("batch_job_config").doesNotContain("qrtz").doesNotContain("CASCADE").doesNotContain("RESTART IDENTITY");
        assertThat(r.clearedBy()).isEqualTo("admin");
        assertThat(r.deleted()).containsKeys("batch_job_instance", "batch_job_audit_log");
    }

    @Test
    void statusReportsBusyAndTheCounts() {
        MaintenanceService s = service(true);
        when(endOfDay.isRunning()).thenReturn(true);

        StatusView v = s.status();

        assertThat(v.resetEnabled()).isTrue();
        assertThat(v.busy()).isTrue();
        assertThat(v.busyReason()).isEqualTo("End of day is running");
        assertThat(v.rowCounts()).hasSize(MaintenanceService.TABLES.size());
    }

    @Test
    void statusIsNotBusyWhenNothingRuns() {
        MaintenanceService s = service(false);
        when(explorer.getJobNames()).thenReturn(java.util.List.of());

        StatusView v = s.status();

        assertThat(v.resetEnabled()).isFalse();
        assertThat(v.busy()).isFalse();
        assertThat(v.busyReason()).isNull();
    }
}
