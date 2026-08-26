package com.bank.lending.batch.core.partition;

import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * Generic min/max-ID range partitioner: splits [MIN(idColumn), MAX(idColumn)] on {@code table}
 * into {@code gridSize} contiguous, roughly-equal-width ranges, one per partition. This is what
 * lets any account-scoped job go from single-threaded to N-way concurrent purely by raising
 * {@code concurrency} in {@code batch_job_config} — the same partitioner instance (parameterized
 * per job by {@code BatchJobRegistry} from that job's config) works for every job.
 *
 * <p>Range-based (rather than hash-mod) partitioning is used because loan_account.account_id is
 * an IDENTITY column: ranges translate directly into an efficient {@code account_id BETWEEN ? AND ?}
 * predicate that can use the primary key index, whereas a hash-mod split would force a full scan
 * with a modulo predicate on every partition. Swap in a hash-mod strategy (also provided, see
 * {@link HashModPartitioner}) for a table without a dense, mostly-contiguous numeric key.
 */
@RequiredArgsConstructor
public class ColumnRangePartitioner implements Partitioner {

    private final DataSource dataSource;
    private final String table;
    private final String idColumn;
    /** Optional extra WHERE predicate (e.g. "account_status = 'ACTIVE'"), or null for none. */
    private final String whereClause;

    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        String sql = "SELECT MIN(" + idColumn + "), MAX(" + idColumn + ") FROM " + table
                + (whereClause != null && !whereClause.isBlank() ? " WHERE " + whereClause : "");

        long[] bounds = jdbcTemplate.queryForObject(sql, (rs, rowNum) ->
                new long[]{rs.getLong(1), rs.getLong(2)});

        Map<String, ExecutionContext> partitions = new HashMap<>();
        if (bounds == null || gridSize <= 1) {
            ExecutionContext ctx = new ExecutionContext();
            if (bounds != null) {
                ctx.putLong("minValue", bounds[0]);
                ctx.putLong("maxValue", bounds[1]);
            }
            partitions.put("partition0", ctx);
            return partitions;
        }

        long min = bounds[0];
        long max = bounds[1];
        long targetSize = (max - min) / gridSize + 1;

        long start = min;
        for (int i = 0; i < gridSize; i++) {
            long end = (i == gridSize - 1) ? max : Math.min(start + targetSize - 1, max);
            ExecutionContext ctx = new ExecutionContext();
            ctx.putLong("minValue", start);
            ctx.putLong("maxValue", end);
            partitions.put("partition" + i, ctx);
            start = end + 1;
            if (start > max) {
                break;
            }
        }
        return partitions;
    }
}
