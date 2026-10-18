package com.bank.lending.iam.config;

import com.bank.lending.iam.common.SecretHasher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * bcrypt in the delegating format "{bcrypt}$2a$...", so the algorithm can change later without touching stored values.
 * Also reads a bare "$2..." hash.
 */
public class BcryptSecretHasher implements SecretHasher {
    private static final String PREFIX = "{bcrypt}";
    private final BCryptPasswordEncoder bcrypt;

    public BcryptSecretHasher(int cost) {
        this.bcrypt = new BCryptPasswordEncoder(cost);
    }

    @Override
    public String hash(String raw) {
        return PREFIX + bcrypt.encode(raw);
    }

    @Override
    public boolean matches(String raw, String hash) {
        if (raw == null || hash == null) return false;
        String h = hash.startsWith(PREFIX) ? hash.substring(PREFIX.length()) : hash;
        if (!h.startsWith("$2")) return false;
        try {
            return bcrypt.matches(raw, h);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
