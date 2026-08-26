package com.bank.lending.batch.core;

import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.DataSource;

/**
 * Base for the common shape most of the 36 business jobs share: "for every loan account (or
 * every active loan account, or every loan account matching some extra filter) in this
 * partition's ID range, do X". Subclasses get a ready-made, partition-aware reader over
 * {@code public.loan_account} for free and only need to implement {@link #processor()} (and
 * override {@link #writer()} once the TODO business logic actually persists something).
 *
 * <p>Subclasses that iterate a different table (e.g. amortization_schedule, emi_transaction)
 * should implement {@link BusinessChunkJob} directly instead of extending this class — it exists
 * purely to remove reader boilerplate for the account-scoped majority, not as a mandatory base.
 */
public abstract class AbstractAccountRangeChunkJob<O> implements BusinessChunkJob<Long, O> {

    protected final DataSource dataSource;

    protected AbstractAccountRangeChunkJob(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Extra WHERE predicate beyond the account_id range bound, e.g. "account_status = 'ACTIVE'".
     * Override per job; default restricts to accounts that have actually disbursed.
     */
    protected String accountFilter() {
        return "account_status NOT IN ('PENDING_DISBURSEMENT')";
    }

    @Override
    public org.springframework.batch.item.ItemReader<Long> reader(Long minId, Long maxId) {
        StringBuilder sql = new StringBuilder("SELECT account_id FROM public.loan_account WHERE 1=1");
        if (minId != null) {
            sql.append(" AND account_id >= ").append(minId);
        }
        if (maxId != null) {
            sql.append(" AND account_id <= ").append(maxId);
        }
        String filter = accountFilter();
        if (filter != null && !filter.isBlank()) {
            sql.append(" AND ").append(filter);
        }
        sql.append(" ORDER BY account_id");

        RowMapper<Long> rowMapper = (rs, rowNum) -> rs.getLong("account_id");
        JdbcCursorItemReader<Long> reader = new JdbcCursorItemReaderBuilder<Long>()
                .name(getJobName() + "-reader")
                .dataSource(dataSource)
                .sql(sql.toString())
                .rowMapper(rowMapper)
                .build();
        return reader;
    }

    /**
     * Scaffolding default: logs at debug and drops the item. Every job stub overrides this once
     * its TODO business logic actually needs to persist something (or leaves it as-is if the
     * job's processor performs its own persistence and the writer genuinely has nothing to do).
     */
    @Override
    public ItemWriter<O> writer() {
        return items -> {
            // TODO: business logic — replace with the real persistence/side-effect for this job,
            // or leave as a no-op if the processor step already wrote everything it needed to.
        };
    }
}
