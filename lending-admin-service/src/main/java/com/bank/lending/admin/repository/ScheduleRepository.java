package com.bank.lending.admin.repository;

import com.bank.lending.admin.dto.AdminDtos.ScheduleRow;
import com.bank.lending.admin.dto.AdminDtos.ScheduleVersion;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

@Repository
public class ScheduleRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public ScheduleRepository(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    private RowMapper<ScheduleRow> mapper() {
        LocalDate today = LocalDate.now(clock);
        return (rs, i) -> {
            String status = rs.getString("installment_status");
            LocalDate due = Sql.date(rs, "due_date");
            var principal = rs.getBigDecimal("principal_component");
            var interest = rs.getBigDecimal("interest_component");
            return new ScheduleRow(rs.getInt("installment_number"), due, principal, interest,
                    principal.add(interest), rs.getBigDecimal("outstanding_balance"), status,
                    rs.getInt("schedule_version"), "PAID".equals(status) ? 0 : Sql.dpd(due, today));
        };
    }

    /** Every instalment at its latest version, so instalments paid before a part-payment still show. */
    public List<ScheduleRow> combined(long accountId) {
        return jdbc.query("""
                SELECT DISTINCT ON (installment_number) installment_number, due_date, principal_component,
                       interest_component, outstanding_balance, installment_status, schedule_version
                FROM amortization_schedule WHERE account_id = :id
                ORDER BY installment_number, schedule_version DESC
                """, new MapSqlParameterSource("id", accountId), mapper());
    }

    public List<ScheduleRow> byVersion(long accountId, int version) {
        return jdbc.query("""
                SELECT installment_number, due_date, principal_component, interest_component,
                       outstanding_balance, installment_status, schedule_version
                FROM amortization_schedule WHERE account_id = :id AND schedule_version = :v
                ORDER BY installment_number
                """, new MapSqlParameterSource("id", accountId).addValue("v", version), mapper());
    }

    public List<ScheduleVersion> versions(long accountId) {
        return jdbc.query("""
                SELECT schedule_version, COUNT(*) AS n, MIN(created_at) AS generated_at
                FROM amortization_schedule WHERE account_id = :id
                GROUP BY schedule_version ORDER BY schedule_version
                """, new MapSqlParameterSource("id", accountId),
                (rs, i) -> new ScheduleVersion(rs.getInt("schedule_version"), rs.getInt("n"),
                        Sql.ts(rs, "generated_at")));
    }
}
