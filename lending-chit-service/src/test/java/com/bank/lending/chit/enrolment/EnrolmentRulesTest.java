package com.bank.lending.chit.enrolment;

import com.bank.lending.chit.common.ChitException;
import com.bank.lending.chit.domain.GroupStatus;
import com.bank.lending.chit.domain.SecurityRule;
import com.bank.lending.chit.domain.TestRules;
import com.bank.lending.chit.domain.rules.ChitRules;
import com.bank.lending.chit.enrolment.ActivationCheck.Blocker;
import com.bank.lending.chit.enrolment.ActivationCheck.SecurityState;
import com.bank.lending.chit.enrolment.ActivationCheck.TicketState;
import com.bank.lending.chit.enrolment.EnrolmentQuote.Received;
import com.bank.lending.chit.enrolment.RefundPlan.Item;
import com.bank.lending.chit.enrolment.RefundPlan.Kind;
import com.bank.lending.chit.enrolment.RefundPlan.TicketMoney;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnrolmentRulesTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static void same(String expected, BigDecimal actual) {
        assertEquals(0, d(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    private static ChitRules.Builder fees() {
        return TestRules.baseline().enrolmentFee(d("500")).registrationFee(d("250"));
    }

    // ---- quote ---------------------------------------------------------------------------------------------------

    @Test
    void quoteWithNoSecurityIsJustTheFees() {
        EnrolmentQuote q = EnrolmentQuote.from(fees().build());
        same("500", q.enrolmentFee());
        same("250", q.registrationFee());
        same("0", q.securityDeposit());
        same("750", q.cashDue());
        assertFalse(q.guarantorRequired());
    }

    @Test
    void depositIsAPercentageOfTheChitValueAndIsCash() {
        ChitRules r = fees().securityRule(SecurityRule.DEPOSIT).securityDepositPct(d("10")).build();
        EnrolmentQuote q = EnrolmentQuote.from(r);
        same("10000", q.securityDeposit());
        same("0", q.lienAmount());
        same("10750", q.cashDue());
    }

    @Test
    void lienIsTheSameFigureButNoCash() {
        ChitRules r = fees().securityRule(SecurityRule.LIEN_ON_DEPOSIT).securityDepositPct(d("10")).build();
        EnrolmentQuote q = EnrolmentQuote.from(r);
        same("0", q.securityDeposit());
        same("10000", q.lienAmount());
        same("750", q.cashDue());
    }

    @Test
    void matchingAmountsAreAccepted() {
        EnrolmentQuote q = EnrolmentQuote.from(fees().build());
        q.check(new Received(d("500"), d("250.00"), null, null, null), 7L);
    }

    @Test
    void shortOrExcessAmountsAreRefused() {
        EnrolmentQuote q = EnrolmentQuote.from(fees().build());
        assertEquals("ENROLMENT_AMOUNT_MISMATCH",
                assertThrows(ChitException.class, () -> q.check(new Received(d("499"), d("250"), null, null, null), 7L)).code());
        assertEquals("ENROLMENT_AMOUNT_MISMATCH",
                assertThrows(ChitException.class, () -> q.check(new Received(d("500"), d("251"), null, null, null), 7L)).code());
        assertEquals("ENROLMENT_AMOUNT_MISMATCH",
                assertThrows(ChitException.class, () -> q.check(new Received(d("500"), d("250"), d("1"), null, null), 7L)).code());
        assertEquals("ENROLMENT_AMOUNT_MISMATCH",
                assertThrows(ChitException.class, () -> q.check(new Received(null, null, null, null, null), 7L)).code());
    }

    @Test
    void aFreeGroupNeedsNoAmounts() {
        EnrolmentQuote q = EnrolmentQuote.from(TestRules.valid());
        q.check(new Received(null, null, null, null, null), 7L);
        same("0", q.cashDue());
    }

    @Test
    void depositMustBePaidInFull() {
        EnrolmentQuote q = EnrolmentQuote.from(fees().securityRule(SecurityRule.DEPOSIT).securityDepositPct(d("10")).build());
        q.check(new Received(d("500"), d("250"), d("10000.00"), null, null), 7L);
        assertEquals("ENROLMENT_AMOUNT_MISMATCH",
                assertThrows(ChitException.class, () -> q.check(new Received(d("500"), d("250"), d("9999.99"), null, null), 7L)).code());
    }

    @Test
    void guarantorRulesAreEnforced() {
        EnrolmentQuote q = EnrolmentQuote.from(TestRules.baseline().securityRule(SecurityRule.GUARANTOR).build());
        assertTrue(q.guarantorRequired());
        assertEquals("GUARANTOR_REQUIRED",
                assertThrows(ChitException.class, () -> q.check(new Received(null, null, null, null, null), 7L)).code());
        assertEquals("GUARANTOR_IS_SUBSCRIBER",
                assertThrows(ChitException.class, () -> q.check(new Received(null, null, null, 7L, null), 7L)).code());
        q.check(new Received(null, null, null, 8L, null), 7L);

        EnrolmentQuote none = EnrolmentQuote.from(TestRules.valid());
        assertEquals("GUARANTOR_NOT_APPLICABLE",
                assertThrows(ChitException.class, () -> none.check(new Received(null, null, null, 8L, null), 7L)).code());
    }

    @Test
    void lienNeedsAReferenceAndOnlyWhenTheRuleIsLien() {
        EnrolmentQuote q = EnrolmentQuote.from(TestRules.baseline().securityRule(SecurityRule.LIEN_ON_DEPOSIT)
                .securityDepositPct(d("5")).build());
        assertEquals("LIEN_REFERENCE_REQUIRED",
                assertThrows(ChitException.class, () -> q.check(new Received(null, null, null, null, " "), 7L)).code());
        q.check(new Received(null, null, null, null, "FD-123"), 7L);
        EnrolmentQuote none = EnrolmentQuote.from(TestRules.valid());
        assertEquals("LIEN_NOT_APPLICABLE",
                assertThrows(ChitException.class, () -> none.check(new Received(null, null, null, null, "FD-123"), 7L)).code());
    }

    // ---- activation ----------------------------------------------------------------------------------------------

    private static List<TicketState> tickets(int n, SecurityState s) {
        List<TicketState> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            out.add(new TicketState(i, s));
        }
        return out;
    }

    private static List<String> codes(List<Blocker> b) {
        return b.stream().map(Blocker::code).toList();
    }

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 12);
    private static final LocalDate FIRST_DUE = LocalDate.of(2026, 11, 5);

    @Test
    void aFullVerifiedGroupHasNoBlockers() {
        ChitRules r = TestRules.baseline().securityRule(SecurityRule.GUARANTOR).build();
        List<Blocker> b = ActivationCheck.blockers(new ActivationCheck.Input(GroupStatus.FULL, r, tickets(20, SecurityState.VERIFIED), TODAY, FIRST_DUE));
        assertTrue(b.isEmpty(), b.toString());
    }

    @Test
    void everyBlockerIsReportedTogether() {
        ChitRules r = TestRules.baseline().securityRule(SecurityRule.DEPOSIT).securityDepositPct(d("10")).build();
        List<TicketState> t = tickets(18, SecurityState.VERIFIED);
        t.set(0, new TicketState(1, SecurityState.HELD));
        t.set(1, new TicketState(2, SecurityState.NONE));
        List<Blocker> b = ActivationCheck.blockers(new ActivationCheck.Input(GroupStatus.OPEN_FOR_ENROLMENT, r, t, TODAY,
                LocalDate.of(2026, 10, 5)));
        assertEquals(List.of("GROUP_NOT_FULL", "TICKETS_INCOMPLETE", "SECURITY_MISSING", "SECURITY_NOT_VERIFIED", "FIRST_DUE_DATE_PASSED"), codes(b));
    }

    @Test
    void ticketNumbersMustBeExactlyOneToN() {
        ChitRules r = TestRules.valid();
        List<TicketState> t = tickets(20, SecurityState.NONE);
        t.set(19, new TicketState(21, SecurityState.NONE)); // 1..19 and 21: right count, wrong numbers
        assertEquals(List.of("TICKETS_INCOMPLETE"),
                codes(ActivationCheck.blockers(new ActivationCheck.Input(GroupStatus.FULL, r, t, TODAY, FIRST_DUE))));
    }

    @Test
    void securityIsNotChecked_whenTheRuleIsNone() {
        List<Blocker> b = ActivationCheck.blockers(new ActivationCheck.Input(GroupStatus.FULL, TestRules.valid(),
                tickets(20, SecurityState.NONE), TODAY, FIRST_DUE));
        assertTrue(b.isEmpty());
    }

    @Test
    void firstDueDateOnTheBusinessDateIsAllowed() {
        List<Blocker> b = ActivationCheck.blockers(new ActivationCheck.Input(GroupStatus.FULL, TestRules.valid(),
                tickets(20, SecurityState.NONE), TODAY, TODAY));
        assertTrue(b.isEmpty());
    }

    @Test
    void requireClearThrowsWithTheCodesListed() {
        ChitException e = assertThrows(ChitException.class, () -> ActivationCheck.requireClear(
                new ActivationCheck.Input(GroupStatus.DRAFT, TestRules.valid(), tickets(3, SecurityState.NONE), TODAY, FIRST_DUE)));
        assertEquals("ACTIVATION_BLOCKED", e.code());
        assertTrue(e.getMessage().contains("GROUP_NOT_FULL"));
        assertTrue(e.getMessage().contains("TICKETS_INCOMPLETE"));
    }

    // ---- refunds -------------------------------------------------------------------------------------------------

    private static TicketMoney money(long id, int no, String enrol, String reg, String dep, String lien) {
        return new TicketMoney(id, no, enrol == null ? null : d(enrol), reg == null ? null : d(reg),
                dep == null ? null : d(dep), lien == null ? null : d(lien));
    }

    @Test
    void cancellationReturnsDepositThenFeesTicketByTicket() {
        List<Item> plan = RefundPlan.forCancellation(List.of(
                money(22, 2, "500", "250", null, null),
                money(21, 1, "500", "250", "10000", null)));
        assertEquals(List.of(Kind.DEPOSIT, Kind.ENROLMENT_FEE, Kind.REGISTRATION_FEE, Kind.ENROLMENT_FEE, Kind.REGISTRATION_FEE),
                plan.stream().map(Item::kind).toList());
        assertEquals(List.of(1, 1, 1, 2, 2), plan.stream().map(Item::ticketNo).toList());
        same("11500", RefundPlan.totalCash(plan));
    }

    @Test
    void aLienIsReleasedNotPaid() {
        List<Item> plan = RefundPlan.forCancellation(List.of(money(21, 1, null, null, null, "10000")));
        assertEquals(1, plan.size());
        assertEquals(Kind.LIEN_RELEASE, plan.get(0).kind());
        same("0", RefundPlan.totalCash(plan));
    }

    @Test
    void zeroAndMissingAmountsAreLeftOut() {
        assertTrue(RefundPlan.forCancellation(List.of(money(21, 1, "0", null, "0.00", null))).isEmpty());
    }

    @Test
    void withdrawalKeepsFeesUnlessToldOtherwise() {
        TicketMoney t = money(21, 1, "500", "250", "10000", null);
        List<Item> kept = RefundPlan.forWithdrawal(t, false);
        assertEquals(List.of(Kind.DEPOSIT), kept.stream().map(Item::kind).toList());
        same("10000", RefundPlan.totalCash(kept));
        same("10750", RefundPlan.totalCash(RefundPlan.forWithdrawal(t, true)));
    }

    // ---- rule changes after enrolment ----------------------------------------------------------------------------

    @Test
    void moneyTermsAreLockedOnceTicketsAreEnrolled() {
        ChitRules before = fees().build();
        assertEquals(List.of("enrolmentFee", "securityRule", "securityDepositPct"),
                RulesChangePolicy.lockedFieldsChanged(before,
                        before.toBuilder().enrolmentFee(d("600")).securityRule(SecurityRule.DEPOSIT).securityDepositPct(d("5")).build()));
        assertEquals(List.of("chitValue", "subscription"),
                RulesChangePolicy.lockedFieldsChanged(before,
                        before.toBuilder().chitValue(d("200000")).subscription(d("10000")).build()));
    }

    @Test
    void otherRulesAndEqualValuesWithDifferentScaleAreNotLocked() {
        ChitRules before = fees().build();
        ChitRules after = before.toBuilder().enrolmentFee(d("500.00")).graceDays(3).commissionPct(d("4")).build();
        assertTrue(RulesChangePolicy.lockedFieldsChanged(before, after).isEmpty());
    }
}
