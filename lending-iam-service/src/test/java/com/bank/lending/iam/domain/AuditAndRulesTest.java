package com.bank.lending.iam.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AuditAndRulesTest {
    private static final Instant T0 = Instant.parse("2026-10-10T10:00:00.123456Z");

    private static List<AuditHasher.Row> chain(int n) {
        List<AuditHasher.Row> rows = new ArrayList<>();
        String prev = AuditHasher.GENESIS;
        for (int i = 1; i <= n; i++) {
            Instant at = T0.plusSeconds(i);
            String detail = "{\"n\":" + i + "}";
            String h = AuditHasher.hash(prev, at, "USER", "alice", "ACTION_" + i, "USER", "bob", "SUCCESS", "10.0.0.1", "corr" + i, detail);
            rows.add(new AuditHasher.Row(i, at, "USER", "alice", "ACTION_" + i, "USER", "bob", "SUCCESS", "10.0.0.1", "corr" + i, detail, prev, h));
            prev = h;
        }
        return rows;
    }

    private static AuditHasher.Row withDetail(AuditHasher.Row r, String detail) {
        return new AuditHasher.Row(r.id(), r.occurredAt(), r.actorType(), r.actor(), r.action(), r.targetType(), r.targetId(), r.outcome(), r.ip(), r.correlationId(), detail, r.prevHash(), r.rowHash());
    }

    @Test
    void anUntouchedChainVerifies() {
        AuditHasher.Verification v = AuditHasher.verify(chain(5), null, true);
        assertThat(v.ok()).isTrue();
        assertThat(v.count()).isEqualTo(5L);
    }

    @Test
    void aChangedRowIsFoundAtThatRow() {
        List<AuditHasher.Row> rows = chain(5);
        rows.set(2, withDetail(rows.get(2), "{\"n\":999}"));
        AuditHasher.Verification v = AuditHasher.verify(rows, null, true);
        assertThat(v.ok()).isFalse();
        assertThat(v.firstBrokenId()).isEqualTo(3L);
        assertThat(v.reason()).contains("changed");
    }

    @Test
    void aRemovedRowIsFoundAtTheNextRow() {
        List<AuditHasher.Row> rows = chain(5);
        rows.remove(1);
        AuditHasher.Verification v = AuditHasher.verify(rows, null, true);
        assertThat(v.ok()).isFalse();
        assertThat(v.firstBrokenId()).isEqualTo(3L);
        assertThat(v.reason()).contains("previous hash");
    }

    @Test
    void aRangeInTheMiddleVerifiesAgainstTheRowBeforeIt() {
        List<AuditHasher.Row> rows = chain(6);
        List<AuditHasher.Row> middle = rows.subList(2, 5);
        assertThat(AuditHasher.verify(middle, rows.get(1).rowHash(), false).ok()).isTrue();
        assertThat(AuditHasher.verify(middle, "f".repeat(64), false).ok()).isFalse();
        // without the row before, only the rows' own hashes and links among themselves are checked
        assertThat(AuditHasher.verify(middle, null, false).ok()).isTrue();
    }

    @Test
    void theFirstRowMustPointAtGenesis() {
        List<AuditHasher.Row> rows = chain(3);
        assertThat(AuditHasher.verify(rows.subList(1, 3), null, true).ok()).isFalse();
    }

    @Test
    void timeIsCutToMicrosecondsBeforeHashing() {
        Instant withNanos = Instant.parse("2026-10-10T10:00:00.123456789Z");
        assertThat(AuditHasher.hash("p", withNanos, "a", "b", "c", "d", "e", "f", "g", "h", "i"))
                .isEqualTo(AuditHasher.hash("p", AuditHasher.normalise(withNanos), "a", "b", "c", "d", "e", "f", "g", "h", "i"));
        assertThat(AuditHasher.normalise(withNanos)).isEqualTo(Instant.parse("2026-10-10T10:00:00.123456Z"));
    }

    @Test
    void sanitizerRedactsSecretsMasksContactDetailsAndKeepsCounts() {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("password", "hunter2");
        in.put("newPassword", "x");
        in.put("clientSecret", "s");
        in.put("tokensRevoked", 3);
        in.put("email", "alice@example.com");
        in.put("mobile", "+65 9123 4567");
        in.put("note", "plain");
        in.put("nested", Map.of("otpCode", "123456", "branch", "SG1"));
        in.put("list", List.of(Map.of("apiKey", "k")));
        Map<String, Object> out = DetailSanitizer.sanitizeMap(in);
        assertThat(out.get("password")).isEqualTo("[redacted]");
        assertThat(out.get("newPassword")).isEqualTo("[redacted]");
        assertThat(out.get("clientSecret")).isEqualTo("[redacted]");
        assertThat(out.get("tokensRevoked")).isEqualTo(3);
        assertThat(out.get("email")).isEqualTo("a***@example.com");
        assertThat(out.get("mobile")).isEqualTo("***4567");
        assertThat(out.get("note")).isEqualTo("plain");
        assertThat(((Map<?, ?>) out.get("nested")).get("otpCode")).isEqualTo("[redacted]");
        assertThat(((Map<?, ?>) out.get("nested")).get("branch")).isEqualTo("SG1");
        assertThat(((Map<?, ?>) ((List<?>) out.get("list")).get(0)).get("apiKey")).isEqualTo("[redacted]");
    }

    @Test
    void separationOfDutiesFindsEveryBrokenRule() {
        List<SodChecker.Rule> rules = List.of(new SodChecker.Rule(1, "a.create", "a.approve", "maker/checker"), new SodChecker.Rule(2, "x", "y", "other"));
        assertThat(SodChecker.violations(Set.of("a.create"), rules)).isEmpty();
        List<SodChecker.Violation> v = SodChecker.violations(Set.of("a.create", "a.approve", "x"), rules);
        assertThat(v).hasSize(1);
        assertThat(v.get(0).ruleId()).isEqualTo(1L);
        assertThat(SodChecker.describe(v)).isEqualTo("a.create + a.approve");
        Set<String> union = SodChecker.union(List.of("R1", "R2", "MISSING"), Map.of("R1", Set.of("p1"), "R2", Set.of("p2", "p1")));
        assertThat(union).hasSize(2);
    }

    @Test
    void approvalPreconditionsInTheirOrder() {
        Instant now = Instant.parse("2026-10-10T10:00:00Z");
        ApprovalRules.ActionView pending = new ApprovalRules.ActionView("PENDING", "maker1", now.plusSeconds(60), null, 2);
        assertThat(ApprovalRules.blockDecision(pending, "checker1", Set.of(), now)).isNull();
        assertThat(ApprovalRules.blockDecision(pending, "MAKER1", Set.of(), now)).isEqualTo("MAKER_CANNOT_APPROVE");
        assertThat(ApprovalRules.blockDecision(pending, "checker1", Set.of("Checker1"), now)).isEqualTo("ALREADY_DECIDED");
        assertThat(ApprovalRules.blockDecision(pending, "checker1", Set.of(), now.plusSeconds(60))).isEqualTo("ACTION_EXPIRED");
        ApprovalRules.ActionView done = new ApprovalRules.ActionView("APPROVED", "maker1", now.plusSeconds(60), null, 1);
        assertThat(ApprovalRules.blockDecision(done, "checker1", Set.of(), now)).isEqualTo("ACTION_NOT_PENDING");
        assertThat(ApprovalRules.isApproved(1, 2)).isFalse();
        assertThat(ApprovalRules.isApproved(2, 2)).isTrue();
        assertThat(ApprovalRules.canCancel(pending, "Maker1")).isTrue();
        assertThat(ApprovalRules.canCancel(pending, "checker1")).isFalse();
        assertThat(ApprovalRules.canCancel(done, "maker1")).isFalse();
        assertThat(ApprovalRules.consumeDeadline(now, 24)).isEqualTo(now.plusSeconds(86400));
    }

    @Test
    void webhookAddressPolicy() {
        assertThat(UrlPolicy.problem("https://hooks.example.com/iam", false)).isNull();
        assertThat(UrlPolicy.problem("http://hooks.example.com/iam", false)).isEqualTo("url must use https");
        assertThat(UrlPolicy.problem("https://user:pw@hooks.example.com/", false)).isEqualTo("url must not contain credentials");
        assertThat(UrlPolicy.problem("https://localhost/x", false)).isEqualTo("url host is not allowed");
        assertThat(UrlPolicy.problem("https://svc.internal/x", false)).isEqualTo("url host is not allowed");
        assertThat(UrlPolicy.problem("https://10.1.2.3/x", false)).isEqualTo("url host is not allowed");
        assertThat(UrlPolicy.problem("https://169.254.169.254/latest", false)).isEqualTo("url host is not allowed");
        assertThat(UrlPolicy.problem("https://127.0.0.1/", false)).isEqualTo("url host is not allowed");
        assertThat(UrlPolicy.problem("https://[::1]/", false)).isEqualTo("url host is not allowed");
        assertThat(UrlPolicy.problem("https://100.64.0.1/", false)).isEqualTo("url host is not allowed");
        assertThat(UrlPolicy.problem("ftp://hooks.example.com/", false)).isEqualTo("url must use https");
        assertThat(UrlPolicy.problem("", false)).contains("required");
        assertThat(UrlPolicy.problem("https://" + "a".repeat(300) + ".com", false)).contains("required");
        // local development relaxes the address rules
        assertThat(UrlPolicy.problem("http://localhost:9000/hook", true)).isNull();
    }
}
