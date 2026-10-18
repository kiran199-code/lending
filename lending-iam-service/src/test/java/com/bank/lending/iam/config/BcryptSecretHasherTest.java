package com.bank.lending.iam.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Needs the real spring-security-crypto (runs under Maven). */
class BcryptSecretHasherTest {
    @Test
    void hashesInDelegatingFormatAndVerifies() {
        BcryptSecretHasher h = new BcryptSecretHasher(4);
        String hash = h.hash("Tr0ub4dor&Horse9");
        assertThat(hash.startsWith("{bcrypt}$2")).isTrue();
        assertThat(h.matches("Tr0ub4dor&Horse9", hash)).isTrue();
        assertThat(h.matches("wrong", hash)).isFalse();
        // a bare bcrypt hash is read as well; garbage never matches and never throws
        assertThat(h.matches("Tr0ub4dor&Horse9", hash.substring("{bcrypt}".length()))).isTrue();
        assertThat(h.matches("x", "plaintext")).isFalse();
        assertThat(h.matches("x", null)).isFalse();
        assertThat(h.hash("same")).isNotEqualTo(h.hash("same"));
    }
}
