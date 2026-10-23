package com.bank.lending.chit.idempotency;

import com.bank.lending.chit.common.ChitException;
import com.bank.lending.chit.idempotency.IdempotencyCheck.Replay;
import com.bank.lending.chit.idempotency.IdempotencyCheck.Stored;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Claims an idempotency key inside the caller's transaction. The first caller inserts the row and goes on; a later
 * caller with the same key finds the row and either gets the earlier result back (same request) or a 409 (different
 * request). Two callers racing on one key are serialised by the primary key: the second waits for the first to
 * commit, then sees the row.
 *
 * <p>Usage: {@code Replay r = store.begin(key, "PAYMENT", hash); if (r != null) return load(r.resultRef());}
 * then do the work and call {@code store.complete(key, ref)} in the same transaction.
 */
@Component
public class IdempotencyStore {

    private final JdbcTemplate jdbc;

    public IdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns null when this call owns the key (go ahead), or the replay of the earlier identical request. */
    public Replay begin(String key, String operation, String requestHash) {
        if (key == null || key.isBlank()) {
            throw ChitException.badRequest("IDEMPOTENCY_KEY_REQUIRED", "An idempotency key is required for this operation");
        }
        int inserted = jdbc.update(
                "INSERT INTO idempotency_key (idem_key, operation, request_hash) VALUES (?,?,?) ON CONFLICT (idem_key) DO NOTHING",
                key, operation, requestHash);
        if (inserted == 1) {
            return null;
        }
        List<Stored> rows = jdbc.query("SELECT operation, request_hash, result_ref FROM idempotency_key WHERE idem_key = ?",
                (rs, i) -> new Stored(rs.getString("operation"), rs.getString("request_hash"), rs.getString("result_ref")), key);
        if (rows.isEmpty()) {
            // the row vanished between the insert and the read (a reset ran meanwhile); treat the key as new on retry
            throw ChitException.conflict("DATA_CONFLICT", "Idempotency key '" + key + "' changed while being read; retry");
        }
        return IdempotencyCheck.decide(key, rows.get(0), operation, requestHash);
    }

    /**
     * Read-only look at a key: the replay if the same request already completed under it, null if the key is unused,
     * IDEMPOTENCY_KEY_REUSED if it was used for a different request. Lets a caller answer a retry before doing any
     * expensive preparation (such as reading the calendar) that a replay does not need.
     */
    public Replay peek(String key, String operation, String requestHash) {
        if (key == null || key.isBlank()) {
            throw ChitException.badRequest("IDEMPOTENCY_KEY_REQUIRED", "An idempotency key is required for this operation");
        }
        List<Stored> rows = jdbc.query("SELECT operation, request_hash, result_ref FROM idempotency_key WHERE idem_key = ?",
                (rs, i) -> new Stored(rs.getString("operation"), rs.getString("request_hash"), rs.getString("result_ref")), key);
        return rows.isEmpty() ? null : IdempotencyCheck.decide(key, rows.get(0), operation, requestHash);
    }

    public void complete(String key, String resultRef) {
        jdbc.update("UPDATE idempotency_key SET result_ref = ? WHERE idem_key = ?", resultRef, key);
    }
}
