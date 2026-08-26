package com.bank.lending.batch.core.partition;

import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.item.ExecutionContext;

import java.util.HashMap;
import java.util.Map;

/**
 * Alternate partitioning strategy for tables without a dense, contiguous numeric primary key
 * (e.g. a UUID or natural key): each partition is assigned a bucket index, and its reader
 * filters with {@code MOD(hashing_column, gridSize) = bucket}. Not currently wired into any
 * seeded job (all 36 business jobs partition off loan_account.account_id, an IDENTITY column,
 * for which {@link ColumnRangePartitioner} is the better fit) — provided so a future job keyed
 * off a non-numeric column doesn't need a new partitioner written from scratch.
 */
public class HashModPartitioner implements Partitioner {

    private final int explicitGridSizeHint;

    public HashModPartitioner() {
        this(0);
    }

    public HashModPartitioner(int explicitGridSizeHint) {
        this.explicitGridSizeHint = explicitGridSizeHint;
    }

    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        int buckets = explicitGridSizeHint > 0 ? explicitGridSizeHint : gridSize;
        Map<String, ExecutionContext> partitions = new HashMap<>();
        for (int bucket = 0; bucket < buckets; bucket++) {
            ExecutionContext ctx = new ExecutionContext();
            ctx.putInt("bucket", bucket);
            ctx.putInt("bucketCount", buckets);
            partitions.put("partition" + bucket, ctx);
        }
        return partitions;
    }
}
