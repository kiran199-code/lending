package com.bank.lending.admin.service;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BusinessDateSourceTest {

    private static final Clock WALL = Clock.fixed(Instant.parse("2026-10-12T10:00:00Z"), ZoneId.of("Asia/Kolkata"));

    @Test
    @SuppressWarnings("unchecked")
    void usesTheDateHeldByLendingService() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), (RowMapper<Date>) org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(Date.valueOf("2026-03-15")));

        assertThat(new BusinessDateSource(jdbc, WALL).today()).isEqualTo(LocalDate.of(2026, 3, 15));
    }

    @Test
    @SuppressWarnings("unchecked")
    void fallsBackToTheWallClockWhenTheTableCannotBeRead() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), (RowMapper<Date>) org.mockito.ArgumentMatchers.any()))
                .thenThrow(new DataAccessResourceFailureException("relation business_date_state does not exist"));

        assertThat(new BusinessDateSource(jdbc, WALL).today()).isEqualTo(LocalDate.of(2026, 10, 12));
    }

    @Test
    @SuppressWarnings("unchecked")
    void fallsBackToTheWallClockWhenTheRowIsMissing() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), (RowMapper<Date>) org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        assertThat(new BusinessDateSource(jdbc, WALL).today()).isEqualTo(LocalDate.of(2026, 10, 12));
    }
}
