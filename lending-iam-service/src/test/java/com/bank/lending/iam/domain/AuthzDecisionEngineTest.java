package com.bank.lending.iam.domain;

import com.bank.lending.iam.domain.AuthzDecisionEngine.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The decision table of section 9.2: status, grant, scopes, ceiling, account link, step-up; first failure wins. */
class AuthzDecisionEngineTest {
    private static final Instant NOW = Instant.parse("2026-10-10T10:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);

    private final AuthzDecisionEngine engine = new AuthzDecisionEngine(Duration.ofMinutes(15),
            Set.of("portal.account.read"),
            Map.of("loan.disburse", new PermissionInfo("loan.disburse", false, "HIGH"),
                    "iam.user.create", new PermissionInfo("iam.user.create", true, "HIGH"),
                    "portal.account.read", new PermissionInfo("portal.account.read", false, "LOW")));

    private static Subject staff(Set<String> perms, Map<String, List<Map<String, BigDecimal>>> ceilings, Map<String, Set<String>> scopes) {
        return new Subject("STAFF", true, null, perms, ceilings, scopes, List.of());
    }

    private static Check check(String perm) {
        return new Check(perm, null, null, null, null, null, null, null);
    }

    private static Map<String, BigDecimal> ceil(String currency, String amount) {
        return Map.of(currency, new BigDecimal(amount));
    }

    @Test
    void inactiveBeatsEverything() {
        Subject s = new Subject("STAFF", false, null, Set.of("loan.disburse"), Map.of(), Map.of(), List.of());
        assertThat(engine.decide(s, check("loan.disburse"), NOW, TODAY).code()).isEqualTo("USER_NOT_ACTIVE");
    }

    @Test
    void notGrantedIsRefused() {
        Decision d = engine.decide(staff(Set.of("other"), Map.of(), Map.of()), check("loan.disburse"), NOW, TODAY);
        assertThat(d.allowed()).isFalse();
        assertThat(d.code()).isEqualTo("NOT_GRANTED");
    }

    @Test
    void grantedWithNoRestrictionsIsAllowed() {
        Decision d = engine.decide(staff(Set.of("loan.disburse"), Map.of(), Map.of()), check("loan.disburse"), NOW, TODAY);
        assertThat(d.allowed()).isTrue();
        assertThat(d.code()).isEqualTo("OK");
    }

    @Test
    void scopesRestrictOnlyWhenTheCallNamesTheAttribute() {
        Subject s = staff(Set.of("loan.disburse"), Map.of(), Map.of("BRANCH", Set.of("SG1", "SG2"), "PRODUCT", Set.of("HL"), "CURRENCY", Set.of("SGD")));
        assertThat(engine.decide(s, new Check("loan.disburse", "SG1", "HL", "SGD", null, null, null, null), NOW, TODAY).allowed()).isTrue();
        assertThat(engine.decide(s, new Check("loan.disburse", "MY1", "HL", "SGD", null, null, null, null), NOW, TODAY).code()).isEqualTo("SCOPE_BRANCH");
        assertThat(engine.decide(s, new Check("loan.disburse", "SG1", "PL", "SGD", null, null, null, null), NOW, TODAY).code()).isEqualTo("SCOPE_PRODUCT");
        assertThat(engine.decide(s, new Check("loan.disburse", "SG1", "HL", "USD", null, null, null, null), NOW, TODAY).code()).isEqualTo("SCOPE_CURRENCY");
        // not naming the branch is not a restriction
        assertThat(engine.decide(s, check("loan.disburse"), NOW, TODAY).allowed()).isTrue();
    }

    @Test
    void wildcardAndEmptyScopeMeanUnrestricted() {
        Subject star = staff(Set.of("loan.disburse"), Map.of(), Map.of("BRANCH", Set.of("*")));
        Subject none = staff(Set.of("loan.disburse"), Map.of(), Map.of());
        Check c = new Check("loan.disburse", "ANY", null, null, null, null, null, null);
        assertThat(engine.decide(star, c, NOW, TODAY).allowed()).isTrue();
        assertThat(engine.decide(none, c, NOW, TODAY).allowed()).isTrue();
    }

    @Test
    void ceilingIsTheGreatestAcrossGrantingRolesAndReportsTheLimit() {
        Subject s = staff(Set.of("loan.disburse"), Map.of("loan.disburse", List.of(ceil("SGD", "10000"), ceil("SGD", "50000"))), Map.of());
        Check within = new Check("loan.disburse", null, null, "SGD", new BigDecimal("50000"), null, null, null);
        Check above = new Check("loan.disburse", null, null, "SGD", new BigDecimal("50000.01"), null, null, null);
        assertThat(engine.decide(s, within, NOW, TODAY).allowed()).isTrue();
        Decision d = engine.decide(s, above, NOW, TODAY);
        assertThat(d.code()).isEqualTo("AMOUNT_ABOVE_LIMIT");
        assertThat(d.limit()).isEqualByComparingTo("50000");
    }

    @Test
    void aRoleWithoutACeilingForTheCurrencyMeansNoCeiling() {
        // one role has a ceiling in SGD, the other has none at all (empty map): unlimited
        Subject s = staff(Set.of("loan.disburse"), Map.of("loan.disburse", List.of(ceil("SGD", "10"), Map.of())), Map.of());
        assertThat(engine.decide(s, new Check("loan.disburse", null, null, "SGD", new BigDecimal("1000000"), null, null, null), NOW, TODAY).allowed()).isTrue();
    }

    @Test
    void wildcardCurrencyCeilingAppliesToAnyCurrencyAndAnExactRowWins() {
        Subject s = staff(Set.of("loan.disburse"), Map.of("loan.disburse", List.of(Map.of("*", new BigDecimal("100"), "USD", new BigDecimal("500")))), Map.of());
        assertThat(engine.decide(s, new Check("loan.disburse", null, null, "EUR", new BigDecimal("101"), null, null, null), NOW, TODAY).code()).isEqualTo("AMOUNT_ABOVE_LIMIT");
        assertThat(engine.decide(s, new Check("loan.disburse", null, null, "USD", new BigDecimal("500"), null, null, null), NOW, TODAY).allowed()).isTrue();
        assertThat(engine.decide(s, new Check("loan.disburse", null, null, null, new BigDecimal("100"), null, null, null), NOW, TODAY).allowed()).isTrue();
    }

    @Test
    void aCeilingForOtherCurrenciesOnlyDoesNotLimitThisOne() {
        Subject s = staff(Set.of("loan.disburse"), Map.of("loan.disburse", List.of(ceil("USD", "10"))), Map.of());
        assertThat(engine.decide(s, new Check("loan.disburse", null, null, "SGD", new BigDecimal("999999"), null, null, null), NOW, TODAY).allowed()).isTrue();
    }

    @Test
    void customersReachOnlyLinkedAccounts() {
        Link acct = new Link("ACCOUNT", null, "ACC-1", "ACTIVE", TODAY.minusDays(1), null);
        Link cust = new Link("CUSTOMER", "CUST-9", null, "ACTIVE", TODAY.minusDays(1), null);
        Link expired = new Link("ACCOUNT", null, "ACC-3", "ACTIVE", TODAY.minusDays(30), TODAY.minusDays(1));
        Link pending = new Link("ACCOUNT", null, "ACC-4", "PENDING", TODAY.minusDays(1), null);
        Link future = new Link("ACCOUNT", null, "ACC-5", "ACTIVE", TODAY.plusDays(1), null);
        Subject s = new Subject("CUSTOMER", true, "CUST-1", Set.of("portal.account.read"), Map.of(), Map.of(), List.of(acct, cust, expired, pending, future));
        assertThat(engine.decide(s, new Check("portal.account.read", null, null, null, null, "ACC-1", null, null), NOW, TODAY).allowed()).isTrue();
        assertThat(engine.decide(s, new Check("portal.account.read", null, null, null, null, null, "CUST-9", null), NOW, TODAY).allowed()).isTrue();
        for (String ref : new String[]{"ACC-2", "ACC-3", "ACC-4", "ACC-5"}) {
            assertThat(engine.decide(s, new Check("portal.account.read", null, null, null, null, ref, null, null), NOW, TODAY).code()).isEqualTo("ACCOUNT_NOT_LINKED");
        }
        // naming no account at all is not enough either
        assertThat(engine.decide(s, check("portal.account.read"), NOW, TODAY).code()).isEqualTo("ACCOUNT_NOT_LINKED");
    }

    @Test
    void staffAreNotHeldToAccountLinks() {
        Subject s = staff(Set.of("portal.account.read"), Map.of(), Map.of());
        assertThat(engine.decide(s, new Check("portal.account.read", null, null, null, null, "ACC-9", null, null), NOW, TODAY).allowed()).isTrue();
    }

    @Test
    void stepUpNeedsASecondFactorWithinTheWindow() {
        Subject s = staff(Set.of("iam.user.create"), Map.of(), Map.of());
        Decision none = engine.decide(s, check("iam.user.create"), NOW, TODAY);
        assertThat(none.allowed()).isFalse();
        assertThat(none.code()).isEqualTo("STEP_UP_REQUIRED");
        assertThat(none.stepUpRequired()).isTrue();
        assertThat(engine.decide(s, check("iam.user.create").withMfa(NOW.minus(Duration.ofMinutes(14))), NOW, TODAY).allowed()).isTrue();
        assertThat(engine.decide(s, check("iam.user.create").withMfa(NOW.minus(Duration.ofMinutes(15))), NOW, TODAY).allowed()).isTrue();
        assertThat(engine.decide(s, check("iam.user.create").withMfa(NOW.minus(Duration.ofMinutes(16))), NOW, TODAY).code()).isEqualTo("STEP_UP_REQUIRED");
        // a time in the future (beyond clock drift) is not accepted
        assertThat(engine.decide(s, check("iam.user.create").withMfa(NOW.plus(Duration.ofMinutes(5))), NOW, TODAY).code()).isEqualTo("STEP_UP_REQUIRED");
    }

    @Test
    void earlierChecksWinOverLaterOnes() {
        // missing grant is reported before a step-up need, scope before ceiling
        Subject s = staff(Set.of("loan.disburse"), Map.of("loan.disburse", List.of(ceil("SGD", "1"))), Map.of("BRANCH", Set.of("SG1")));
        Check c = new Check("loan.disburse", "MY1", null, "SGD", new BigDecimal("100"), null, null, null);
        assertThat(engine.decide(s, c, NOW, TODAY).code()).isEqualTo("SCOPE_BRANCH");
        assertThat(engine.decide(s, check("iam.user.create"), NOW, TODAY).code()).isEqualTo("NOT_GRANTED");
    }
}
