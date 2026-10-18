package com.bank.lending.iam.user;

import com.bank.lending.iam.common.Db;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class UserRepository {
    private static final String COLS = "user_id, username, user_kind, display_name, email, mobile, customer_id, status, password_hash, "
            + "password_changed_at, must_change_password, failed_attempts, locked_until, mfa_enabled, mfa_secret_enc, mfa_pending_enc, "
            + "mfa_last_step, tokens_valid_after, last_login_at, bootstrap, created_at, created_by, version";

    static final RowMapper<UserRow> MAP = (rs, i) -> new UserRow(
            rs.getLong("user_id"), rs.getString("username"), rs.getString("user_kind"), rs.getString("display_name"),
            rs.getString("email"), rs.getString("mobile"), rs.getString("customer_id"), rs.getString("status"),
            rs.getString("password_hash"), Db.instant(rs, "password_changed_at"), rs.getBoolean("must_change_password"),
            rs.getInt("failed_attempts"), Db.instant(rs, "locked_until"), rs.getBoolean("mfa_enabled"),
            rs.getString("mfa_secret_enc"), rs.getString("mfa_pending_enc"), Db.longOrNull(rs, "mfa_last_step"),
            Db.instant(rs, "tokens_valid_after"), Db.instant(rs, "last_login_at"), rs.getBoolean("bootstrap"),
            Db.instant(rs, "created_at"), rs.getString("created_by"), rs.getLong("version"));

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserRow> findById(long id) {
        return Db.one(jdbc, "SELECT " + COLS + " FROM app_user WHERE user_id = ?", MAP, id);
    }

    public Optional<UserRow> findByUsername(String username) {
        return Db.one(jdbc, "SELECT " + COLS + " FROM app_user WHERE lower(username) = lower(?)", MAP, username);
    }

    /** Locks the row for the rest of the transaction so concurrent sign-ins count their failures one by one. */
    public Optional<UserRow> findByUsernameForUpdate(String username) {
        return Db.one(jdbc, "SELECT " + COLS + " FROM app_user WHERE lower(username) = lower(?) FOR UPDATE", MAP, username);
    }

    public Optional<UserRow> findByIdForUpdate(long id) {
        return Db.one(jdbc, "SELECT " + COLS + " FROM app_user WHERE user_id = ? FOR UPDATE", MAP, id);
    }

    /** Serialises start-up provisioning across instances for the length of the transaction. */
    public void lockBootstrap() {
        jdbc.execute("SELECT pg_advisory_xact_lock(7000003)");
    }

    public long count() {
        return Db.scalar(jdbc, "SELECT count(*) FROM app_user", Long.class);
    }

    public long insert(String username, String kind, String displayName, String email, String mobile, String customerId,
                       String status, String passwordHash, Instant passwordChangedAt, boolean mustChange, boolean bootstrap, String createdBy) {
        return Db.scalar(jdbc, "INSERT INTO app_user (username, user_kind, display_name, email, mobile, customer_id, status, password_hash, "
                        + "password_changed_at, must_change_password, bootstrap, created_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?) RETURNING user_id",
                Long.class, username, kind, displayName, email, mobile, customerId, status, passwordHash,
                Db.ts(passwordChangedAt), mustChange, bootstrap, createdBy);
    }

    public record Page(List<UserRow> rows, long total) {}

    public Page search(String kind, String status, String text, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (kind != null && !kind.isBlank()) { where.append(" AND user_kind = ?"); args.add(kind); }
        if (status != null && !status.isBlank()) { where.append(" AND status = ?"); args.add(status); }
        if (text != null && !text.isBlank()) {
            where.append(" AND (lower(username) LIKE ? ESCAPE '\\' OR lower(display_name) LIKE ? ESCAPE '\\' OR lower(coalesce(email,'')) LIKE ? ESCAPE '\\' OR coalesce(customer_id,'') = ?)");
            String like = Db.likeContains(text);
            args.add(like); args.add(like); args.add(like); args.add(text.trim());
        }
        long total = Db.scalar(jdbc, "SELECT count(*) FROM app_user" + where, Long.class, args.toArray());
        List<Object> a2 = new ArrayList<>(args);
        a2.add(size); a2.add((long) page * size);
        List<UserRow> rows = jdbc.query("SELECT " + COLS + " FROM app_user" + where + " ORDER BY user_id DESC LIMIT ? OFFSET ?", MAP, a2.toArray());
        return new Page(rows, total);
    }

    public int updateProfile(long id, String displayName, String email, String mobile) {
        return jdbc.update("UPDATE app_user SET display_name = ?, email = ?, mobile = ?, updated_at = now(), version = version + 1 WHERE user_id = ?",
                displayName, email, mobile, id);
    }

    public void setStatus(long id, String status) {
        jdbc.update("UPDATE app_user SET status = ?, updated_at = now(), version = version + 1 WHERE user_id = ?", status, id);
    }

    /** Failure count and lock after a wrong credential. */
    public void recordFailure(long id, int failedAttempts, Instant lockedUntil, String status) {
        jdbc.update("UPDATE app_user SET failed_attempts = ?, locked_until = ?, status = ?, updated_at = now() WHERE user_id = ?",
                failedAttempts, Db.ts(lockedUntil), status, id);
    }

    /** Clears the failure count (and an expired lock) after a good sign-in or a lock timeout. */
    public void clearFailures(long id, String statusAfter, Instant lastLogin) {
        jdbc.update("UPDATE app_user SET failed_attempts = 0, locked_until = NULL, status = ?, last_login_at = coalesce(?, last_login_at), updated_at = now() WHERE user_id = ?",
                statusAfter, Db.ts(lastLogin), id);
    }

    public void unlock(long id) {
        jdbc.update("UPDATE app_user SET failed_attempts = 0, locked_until = NULL, status = CASE WHEN status = 'LOCKED' THEN 'ACTIVE' ELSE status END, "
                + "updated_at = now(), version = version + 1 WHERE user_id = ?", id);
    }

    public void setPassword(long id, String hash, Instant changedAt, boolean mustChange, String status) {
        jdbc.update("UPDATE app_user SET password_hash = ?, password_changed_at = ?, must_change_password = ?, status = ?, failed_attempts = 0, "
                + "locked_until = NULL, updated_at = now(), version = version + 1 WHERE user_id = ?", hash, Db.ts(changedAt), mustChange, status, id);
    }

    public void markPasswordExpired(long id) {
        jdbc.update("UPDATE app_user SET status = 'EXPIRED', must_change_password = TRUE, updated_at = now(), version = version + 1 WHERE user_id = ?", id);
    }

    public void forcePasswordChange(long id) {
        jdbc.update("UPDATE app_user SET must_change_password = TRUE, status = CASE WHEN status = 'ACTIVE' THEN 'EXPIRED' ELSE status END, "
                + "updated_at = now(), version = version + 1 WHERE user_id = ?", id);
    }

    public void tokensValidAfter(long id, Instant t) {
        jdbc.update("UPDATE app_user SET tokens_valid_after = ? WHERE user_id = ?", Db.ts(t), id);
    }

    // ---- multi-factor
    public void setMfaPending(long id, String pendingEnc) {
        jdbc.update("UPDATE app_user SET mfa_pending_enc = ?, updated_at = now() WHERE user_id = ?", pendingEnc, id);
    }

    public void enableMfa(long id, String secretEnc, long lastStep) {
        jdbc.update("UPDATE app_user SET mfa_enabled = TRUE, mfa_secret_enc = ?, mfa_pending_enc = NULL, mfa_last_step = ?, updated_at = now(), version = version + 1 WHERE user_id = ?",
                secretEnc, lastStep, id);
    }

    public void clearMfa(long id) {
        jdbc.update("UPDATE app_user SET mfa_enabled = FALSE, mfa_secret_enc = NULL, mfa_pending_enc = NULL, mfa_last_step = NULL, updated_at = now(), version = version + 1 WHERE user_id = ?", id);
        jdbc.update("DELETE FROM mfa_recovery_code WHERE user_id = ?", id);
    }

    /** Stores the step of a used code only if it is later than the stored one: two concurrent uses of one code cannot both win. */
    public boolean useMfaStep(long id, long step) {
        return jdbc.update("UPDATE app_user SET mfa_last_step = ? WHERE user_id = ? AND (mfa_last_step IS NULL OR mfa_last_step < ?)", step, id, step) == 1;
    }

    public void replaceRecoveryCodes(long userId, List<String> hashes) {
        jdbc.update("DELETE FROM mfa_recovery_code WHERE user_id = ?", userId);
        for (String h : hashes) jdbc.update("INSERT INTO mfa_recovery_code (user_id, code_hash) VALUES (?, ?)", userId, h);
    }

    public boolean consumeRecoveryCode(long userId, String hash) {
        return jdbc.update("UPDATE mfa_recovery_code SET used_at = now() WHERE user_id = ? AND code_hash = ? AND used_at IS NULL", userId, hash) == 1;
    }

    public long unusedRecoveryCodes(long userId) {
        return Db.scalar(jdbc, "SELECT count(*) FROM mfa_recovery_code WHERE user_id = ? AND used_at IS NULL", Long.class, userId);
    }

    // ---- password history
    public List<String> passwordHistory(long userId, int limit) {
        return jdbc.query("SELECT password_hash FROM password_history WHERE user_id = ? ORDER BY history_id DESC LIMIT ?",
                (rs, i) -> rs.getString("password_hash"), userId, limit);
    }

    public void addPasswordHistory(long userId, String hash) {
        jdbc.update("INSERT INTO password_history (user_id, password_hash) VALUES (?, ?)", userId, hash);
        // keep a short tail only
        jdbc.update("DELETE FROM password_history WHERE user_id = ? AND history_id NOT IN "
                + "(SELECT history_id FROM password_history WHERE user_id = ? ORDER BY history_id DESC LIMIT 20)", userId, userId);
    }

    // ---- one-time tokens
    public record TokenRow(long tokenId, long userId, String purpose, Instant expiresAt, Instant usedAt) {}

    public void insertToken(long userId, String purpose, String tokenHash, Instant expiresAt) {
        jdbc.update("INSERT INTO one_time_token (user_id, purpose, token_hash, expires_at) VALUES (?,?,?,?)", userId, purpose, tokenHash, Db.ts(expiresAt));
    }

    public Optional<TokenRow> findToken(String tokenHash) {
        return Db.one(jdbc, "SELECT token_id, user_id, purpose, expires_at, used_at FROM one_time_token WHERE token_hash = ?",
                (rs, i) -> new TokenRow(rs.getLong("token_id"), rs.getLong("user_id"), rs.getString("purpose"),
                        Db.instant(rs, "expires_at"), Db.instant(rs, "used_at")), tokenHash);
    }

    /** Single use: true only for the one caller that flips used_at. */
    public boolean useToken(long tokenId) {
        return jdbc.update("UPDATE one_time_token SET used_at = now() WHERE token_id = ? AND used_at IS NULL AND expires_at > now()", tokenId) == 1;
    }

    public void invalidateTokens(long userId, String purpose) {
        jdbc.update("UPDATE one_time_token SET used_at = coalesce(used_at, now()) WHERE user_id = ? AND purpose = ? AND used_at IS NULL", userId, purpose);
    }

    /** The newest unused token of a purpose, to tell whether a temporary password has expired. */
    public Optional<TokenRow> latestOpenToken(long userId, String purpose) {
        return Db.one(jdbc, "SELECT token_id, user_id, purpose, expires_at, used_at FROM one_time_token WHERE user_id = ? AND purpose = ? AND used_at IS NULL ORDER BY token_id DESC LIMIT 1",
                (rs, i) -> new TokenRow(rs.getLong("token_id"), rs.getLong("user_id"), rs.getString("purpose"),
                        Db.instant(rs, "expires_at"), Db.instant(rs, "used_at")), userId, purpose);
    }
}
