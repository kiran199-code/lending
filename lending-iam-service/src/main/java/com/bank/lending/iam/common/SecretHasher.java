package com.bank.lending.iam.common;

/** One-way hashing of passwords and client secrets (bcrypt in the delegating format in production). */
public interface SecretHasher {
    String hash(String raw);
    boolean matches(String raw, String hash);
}
