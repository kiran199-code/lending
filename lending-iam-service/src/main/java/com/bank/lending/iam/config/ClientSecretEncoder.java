package com.bank.lending.iam.config;

import com.bank.lending.iam.common.SecretHasher;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Checks client secrets for the authorization server. A secret rotated recently stays valid next to the new one for the
 * grace period; the registered-client repository then hands over both hashes as "{iam-dual}&lt;new&gt;~&lt;old&gt;".
 * bcrypt output never contains '~', so the split is unambiguous. Never asks for a re-encode (the library would try to save).
 */
public class ClientSecretEncoder implements PasswordEncoder {
    public static final String DUAL = "{iam-dual}";
    private final SecretHasher hasher;

    public ClientSecretEncoder(SecretHasher hasher) {
        this.hasher = hasher;
    }

    @Override
    public String encode(CharSequence raw) {
        return hasher.hash(raw.toString());
    }

    @Override
    public boolean matches(CharSequence raw, String encoded) {
        if (raw == null || encoded == null) return false;
        if (encoded.startsWith(DUAL)) {
            boolean ok = false;
            for (String part : encoded.substring(DUAL.length()).split("~")) {
                // every part is checked so that the time taken does not reveal which one matched
                ok |= hasher.matches(raw.toString(), part);
            }
            return ok;
        }
        return hasher.matches(raw.toString(), encoded);
    }

    @Override
    public boolean upgradeEncoding(String encoded) {
        return false;
    }
}
