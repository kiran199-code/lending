package com.bank.lending.iam.domain;

import com.bank.lending.iam.common.SecretHasher;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordAndLockoutTest {
    private final PasswordPolicy policy = new PasswordPolicy(12, 3, 5, 90, 365);
    /** A reversible fake: enough to test the policy without bcrypt cost. */
    private final SecretHasher hasher = new SecretHasher() {
        public String hash(String raw) { return "h:" + raw; }
        public boolean matches(String raw, String hash) { return hash.equals("h:" + raw); }
    };

    private Set<String> codes(String pw, String user, List<String> history) {
        return policy.check(pw, user, history, hasher).stream().map(PasswordPolicy.Violation::code).collect(Collectors.toSet());
    }

    @Test
    void acceptsAStrongPassword() {
        assertThat(codes("Tr0ub4dor&Horse9", "alice", List.of())).isEmpty();
    }

    @Test
    void rejectsShortPasswordsAndTooFewClasses() {
        assertThat(codes("Ab1!", "alice", List.of())).contains("LENGTH");
        assertThat(codes("alllowercaseletters", "alice", List.of())).contains("CLASSES");
        assertThat(codes("ALLUPPER1234567", "alice", List.of())).contains("CLASSES");
    }

    @Test
    void rejectsUsernameInsideThePasswordIgnoringCase() {
        assertThat(codes("xxALICExx-Strong9", "alice", List.of())).contains("CONTAINS_USERNAME");
    }

    @Test
    void rejectsCommonPasswordsEvenWithDecoration() {
        assertThat(codes("Password123!", "bob", List.of())).contains("COMMON");
        assertThat(codes("MyPassword-Strong-1", "bob", List.of())).contains("COMMON");
        assertThat(PasswordPolicy.isCommon("Qwerty!234abcd")).isTrue();
    }

    @Test
    void rejectsReuseOfOneOfTheLastFiveOnly() {
        List<String> history = List.of("h:Oldest-Pass-1x", "h:Second-Pass-2y", "h:Third-Pass-3z", "h:Fourth-Pass-4a", "h:Fifth-Pass-5b", "h:Sixth-Pass-6c");
        assertThat(codes("Fifth-Pass-5b", "bob", history)).contains("REUSED");
        // the sixth entry is older than the history size, so it may be used again
        assertThat(codes("Sixth-Pass-6c", "bob", history)).isEmpty();
    }

    @Test
    void tooLongIsRefused() {
        assertThat(codes("Aa1!".repeat(40), "bob", List.of())).contains("TOO_LONG");
    }

    @Test
    void expiryUsesTheKindsOwnLimit() {
        Instant now = Instant.parse("2026-10-10T00:00:00Z");
        assertThat(policy.isExpired(now.minus(Duration.ofDays(91)), false, now)).isTrue();
        assertThat(policy.isExpired(now.minus(Duration.ofDays(89)), false, now)).isFalse();
        assertThat(policy.isExpired(now.minus(Duration.ofDays(91)), true, now)).isFalse();
        assertThat(policy.isExpired(null, false, now)).isTrue();
    }

    @Test
    void generatedTemporaryPasswordsAlwaysMeetThePolicy() {
        for (int i = 0; i < 300; i++) {
            String pw = PasswordGenerator.temporary();
            assertThat(policy.check(pw, "someone", List.of(), hasher)).isEmpty();
        }
    }

    @Test
    void lockoutLocksOnTheFifthFailureOnly() {
        LockoutPolicy p = new LockoutPolicy(5, Duration.ofMinutes(15));
        Instant now = Instant.parse("2026-10-10T10:00:00Z");
        assertThat(p.onFailure(3, now).lockedNow()).isFalse();
        LockoutPolicy.Outcome o = p.onFailure(4, now);
        assertThat(o.lockedNow()).isTrue();
        assertThat(o.failedAttempts()).isEqualTo(5);
        assertThat(o.lockedUntil()).isEqualTo(now.plus(Duration.ofMinutes(15)));
        assertThat(p.isLocked(o.lockedUntil(), now.plusSeconds(899))).isTrue();
        assertThat(p.isLocked(o.lockedUntil(), now.plusSeconds(900))).isFalse();
        assertThat(p.isLocked(null, now)).isFalse();
    }
}
