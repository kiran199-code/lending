package com.bank.lending.iam.auth;

import com.bank.lending.iam.audit.AuditService;
import com.bank.lending.iam.crypto.SecretCrypto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Key rotation for the encryption key. With the new key in IAM_ENCRYPTION_KEY and the old key in
 * IAM_ENCRYPTION_KEY_PREVIOUS, every encrypted value that the new key cannot read is read with the old key and
 * written back with the new one. Values the new key already reads are left alone, so running it twice is harmless.
 */
@Service
public class ReencryptService {
    public record Result(int mfaSecrets, int signingKeys, int webhookSecrets, int unreadable) {}

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public ReencryptService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    private record Row(String key, String value) {}

    @Transactional
    public Result run(SecretCrypto current, SecretCrypto previous) {
        int[] counts = new int[4];
        // MFA secrets and pending enrolments
        for (Row r : rows("SELECT user_id::text AS k, mfa_secret_enc AS v FROM app_user WHERE mfa_secret_enc IS NOT NULL")) {
            String ctx = "mfa:" + r.key();
            String re = reencrypt(r.value(), ctx, current, previous, counts, 0);
            if (re != null) jdbc.update("UPDATE app_user SET mfa_secret_enc = ? WHERE user_id = ?::bigint", re, r.key());
        }
        for (Row r : rows("SELECT user_id::text AS k, mfa_pending_enc AS v FROM app_user WHERE mfa_pending_enc IS NOT NULL")) {
            String re = reencrypt(r.value(), "mfa-pending:" + r.key(), current, previous, counts, 0);
            if (re != null) jdbc.update("UPDATE app_user SET mfa_pending_enc = ? WHERE user_id = ?::bigint", re, r.key());
        }
        for (Row r : rows("SELECT key_id AS k, private_enc AS v FROM signing_key WHERE private_enc <> 'v1:expired'")) {
            String re = reencrypt(r.value(), "signing-key:" + r.key(), current, previous, counts, 1);
            if (re != null) jdbc.update("UPDATE signing_key SET private_enc = ? WHERE key_id = ?", re, r.key());
        }
        for (Row r : rows("SELECT sub_id::text AS k, secret_enc AS v FROM webhook_subscription")) {
            String re = reencrypt(r.value(), "webhook", current, previous, counts, 2);
            if (re != null) jdbc.update("UPDATE webhook_subscription SET secret_enc = ? WHERE sub_id = ?::bigint", re, r.key());
        }
        Result res = new Result(counts[0], counts[1], counts[2], counts[3]);
        if (counts[0] + counts[1] + counts[2] + counts[3] > 0) {
            audit.recordAs("SYSTEM", "reencrypt", "ENCRYPTION_KEY_ROTATED", "SYSTEM", "iam", counts[3] == 0 ? "SUCCESS" : "FAILURE",
                    "mfaSecrets", counts[0], "signingKeys", counts[1], "webhookSecrets", counts[2], "unreadable", counts[3]);
        }
        return res;
    }

    private List<Row> rows(String sql) {
        return new ArrayList<>(jdbc.query(sql, (rs, i) -> new Row(rs.getString("k"), rs.getString("v"))));
    }

    /** @return the value re-encrypted with the current key, or null when nothing needs to change */
    private static String reencrypt(String stored, String ctx, SecretCrypto current, SecretCrypto previous, int[] counts, int bucket) {
        if (current.canDecrypt(stored, ctx)) return null;
        if (previous != null && previous.canDecrypt(stored, ctx)) {
            counts[bucket]++;
            return current.encrypt(previous.decrypt(stored, ctx), ctx);
        }
        counts[3]++;
        return null;
    }
}
