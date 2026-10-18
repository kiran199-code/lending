package com.bank.lending.iam.keys;

import com.bank.lending.iam.common.Db;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class KeyRepository {
    public record KeyRow(String keyId, String publicPem, String privateEnc, String status, Instant createdAt, Instant retiredAt, Instant retireAfter) {}

    private static final RowMapper<KeyRow> MAP = (rs, i) -> new KeyRow(rs.getString("key_id"), rs.getString("public_pem"), rs.getString("private_enc"),
            rs.getString("status"), Db.instant(rs, "created_at"), Db.instant(rs, "retired_at"), Db.instant(rs, "retire_after"));
    private static final String COLS = "key_id, public_pem, private_enc, status, created_at, retired_at, retire_after";

    private final JdbcTemplate jdbc;

    public KeyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<KeyRow> active() {
        return Db.one(jdbc, "SELECT " + COLS + " FROM signing_key WHERE status = 'ACTIVE'", MAP);
    }

    public List<KeyRow> all() {
        return jdbc.query("SELECT " + COLS + " FROM signing_key ORDER BY created_at DESC", MAP);
    }

    /** The active key and the retired keys that are still published. */
    public List<KeyRow> published(Instant now) {
        return jdbc.query("SELECT " + COLS + " FROM signing_key WHERE status = 'ACTIVE' OR retire_after > ? ORDER BY created_at DESC", MAP, Db.ts(now));
    }

    /** Serialises key creation and rotation across instances for the length of the transaction. */
    public void lock() {
        jdbc.execute("SELECT pg_advisory_xact_lock(7000002)");
    }

    public void insertActive(String keyId, String publicPem, String privateEnc) {
        jdbc.update("INSERT INTO signing_key (key_id, public_pem, private_enc, status) VALUES (?,?,?,'ACTIVE')", keyId, publicPem, privateEnc);
    }

    public void retire(String keyId, Instant now, Instant retireAfter) {
        jdbc.update("UPDATE signing_key SET status = 'RETIRED', retired_at = ?, retire_after = ? WHERE key_id = ?", Db.ts(now), Db.ts(retireAfter), keyId);
    }

    /** Removes the private part of retired keys whose publishing period is over (they only needed it to sign). */
    public int dropExpiredPrivate(Instant now) {
        return jdbc.update("UPDATE signing_key SET private_enc = 'v1:expired' WHERE status = 'RETIRED' AND retire_after <= ? AND private_enc <> 'v1:expired'", Db.ts(now));
    }

    public void replacePrivate(String keyId, String privateEnc) {
        jdbc.update("UPDATE signing_key SET private_enc = ? WHERE key_id = ?", privateEnc, keyId);
    }
}
