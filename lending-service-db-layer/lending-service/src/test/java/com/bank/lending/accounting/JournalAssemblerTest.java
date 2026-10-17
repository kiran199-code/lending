package com.bank.lending.accounting;

import com.bank.lending.accounting.engine.AccountingConfig;
import com.bank.lending.accounting.engine.JournalAssembler;
import com.bank.lending.accounting.engine.TemplateSelector;
import com.bank.lending.accounting.model.Defs;
import com.bank.lending.accounting.model.Defs.DraftJournal;
import com.bank.lending.accounting.model.Defs.DraftLine;
import com.bank.lending.accounting.model.Defs.PostingRequest;
import com.bank.lending.accounting.model.PostingException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The default templates against the worked scenarios of the requirements document (section 17). */
class JournalAssemblerTest {

    private static final LocalDate D = LocalDate.of(2026, 10, 12);
    private final AccountingConfig cfg = SeedFixtures.config();

    private DraftJournal post(String event, String stage, String... kv) {
        Map<String, BigDecimal> amounts = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            amounts.put(kv[i], new BigDecimal(kv[i + 1]));
        }
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("npa_stage", stage);
        PostingRequest req = new PostingRequest(event, 7L, "HL", "TEST", 1L, D, null, D, D, "INR", amounts, ctx,
                List.of(), "test");
        Defs.EventDef ev = cfg.events().get(event);
        Defs.TemplateDef tpl = TemplateSelector.select(cfg.templatesByEvent().get(event), "HL", D).orElseThrow();
        return JournalAssembler.assemble(cfg, req, ev, tpl, JournalAssembler.SAME_CURRENCY_ONLY);
    }

    /** "DR 1100 1000000.00" style summary of every line, in order. */
    private static List<String> view(DraftJournal j) {
        return j.lines().stream().map(l -> l.ledgerCode() + " " + l.side() + " " + l.glAccountCode() + " "
                + l.amountBase().toPlainString()).toList();
    }

    private static void assertBalanced(DraftJournal j) {
        BigDecimal dr = BigDecimal.ZERO;
        BigDecimal cr = BigDecimal.ZERO;
        for (DraftLine l : j.lines()) {
            if (l.side().equals("DR")) {
                dr = dr.add(l.amountBase());
            } else {
                cr = cr.add(l.amountBase());
            }
        }
        assertThat(dr).isEqualByComparingTo(cr);
    }

    @Test
    void scenario1DisbursementWithFeeDeducted() {
        DraftJournal j = post("DISBURSE", "ACTIVE", "DISBURSED_AMT", "1000000.00", "FEE_DEDUCTED_AMT", "10000.00",
                "NET_PAYOUT_AMT", "990000.00");
        assertThat(view(j)).containsExactly("MAIN DR 1100 1000000.00", "MAIN CR 1210 990000.00", "MAIN CR 4120 10000.00");
        assertBalanced(j);
    }

    @Test
    void disbursementWithoutFeeSuppressesTheZeroLine() {
        DraftJournal j = post("DISBURSE", "ACTIVE", "DISBURSED_AMT", "500000.00", "NET_PAYOUT_AMT", "500000.00");
        assertThat(view(j)).containsExactly("MAIN DR 1100 500000.00", "MAIN CR 1210 500000.00");
    }

    @Test
    void scenario2AccrualOnAPerformingAccountGoesToIncome() {
        DraftJournal j = post("INT_ACCRUE", "ACTIVE", "INT_ACCRUED_AMT", "328.76");
        assertThat(view(j)).containsExactly("MAIN DR 1120 328.76", "MAIN CR 4100 328.76");
    }

    @Test
    void accrualOnANonPerformingAccountGoesToSuspense() {
        DraftJournal j = post("INT_ACCRUE", "NPA_SUBSTANDARD", "INT_ACCRUED_AMT", "328.76");
        assertThat(view(j)).containsExactly("MAIN DR 1120 328.76", "MAIN CR 2200 328.76");
    }

    @Test
    void smaStagesAreStillPerformingForIncome() {
        DraftJournal j = post("INT_ACCRUE", "NPA_SMA2", "INT_ACCRUED_AMT", "10.00");
        assertThat(view(j)).containsExactly("MAIN DR 1120 10.00", "MAIN CR 4100 10.00");
    }

    @Test
    void scenario3DemandRaise() {
        DraftJournal j = post("DEMAND_RAISE", "ACTIVE", "PRINCIPAL_DUE_AMT", "7500.00", "INTEREST_DUE_AMT", "9000.00");
        assertThat(view(j)).containsExactly("MAIN DR 1110 7500.00", "MAIN DR 1130 9000.00", "MAIN CR 1100 7500.00",
                "MAIN CR 1120 9000.00");
        assertBalanced(j);
    }

    @Test
    void scenario4PenalAccrualPerformingAndSuspended() {
        assertThat(view(post("PENAL_ACCRUE", "ACTIVE", "PENAL_ACCRUED_AMT", "10.85")))
                .containsExactly("MAIN DR 1140 10.85", "MAIN CR 4110 10.85");
        assertThat(view(post("PENAL_ACCRUE", "NPA_DOUBTFUL", "PENAL_ACCRUED_AMT", "10.85")))
                .containsExactly("MAIN DR 1140 10.85", "MAIN CR 2210 10.85");
    }

    @Test
    void scenario5PaymentWithPenalInterestPrincipalAndAdvance() {
        DraftJournal j = post("EMI_PAYMENT", "ACTIVE", "PAYMENT_AMT", "17000.00", "PENAL_PAID", "249.53",
                "INTEREST_PAID", "9000.00", "PRINCIPAL_PAID", "7500.00", "ADVANCE_CREATED", "250.47");
        assertThat(view(j)).containsExactly("MAIN DR 1200 17000.00", "MAIN CR 1140 249.53", "MAIN CR 1130 9000.00",
                "MAIN CR 1110 7500.00", "MAIN CR 2100 250.47");
        assertBalanced(j);
    }

    @Test
    void partPaymentPrincipalBeyondTheDemandsReducesNotYetDuePrincipal() {
        DraftJournal j = post("PART_PAYMENT", "ACTIVE", "PAYMENT_AMT", "60000.00", "INTEREST_PAID", "9000.00",
                "PRINCIPAL_PAID", "7500.00", "PREPAID_PRINCIPAL_AMT", "43500.00");
        assertThat(view(j)).containsExactly("MAIN DR 1200 60000.00", "MAIN CR 1130 9000.00", "MAIN CR 1110 7500.00",
                "MAIN CR 1100 43500.00");
    }

    @Test
    void advanceAppliedToADemand() {
        DraftJournal j = post("ADVANCE_ADJUST", "ACTIVE", "ADVANCE_APPLIED_AMT", "16500.00", "INTEREST_PAID", "9000.00",
                "PRINCIPAL_PAID", "7500.00");
        assertThat(view(j)).containsExactly("MAIN DR 2100 16500.00", "MAIN CR 1130 9000.00", "MAIN CR 1110 7500.00");
    }

    @Test
    void scenario16ForeclosureWithAdvance() {
        DraftJournal j = post("FORECLOSURE", "ACTIVE", "PAYMENT_AMT", "709249.53", "ADVANCE_APPLIED_AMT", "250.47",
                "PRINCIPAL_NOT_DUE_PAID", "700000.00", "ACCRUED_INT_PAID", "2500.00", "FORECLOSURE_FEE_AMT", "7000.00");
        assertThat(view(j)).containsExactly("MAIN DR 1200 709249.53", "MAIN DR 2100 250.47", "MAIN CR 1100 700000.00",
                "MAIN CR 1120 2500.00", "MAIN CR 4120 7000.00");
        assertBalanced(j);
    }

    @Test
    void interestTrueUpBothDirections() {
        assertThat(view(post("INT_TRUEUP", "ACTIVE", "INT_TRUEUP_AMT", "0.04")))
                .containsExactly("MAIN DR 1120 0.04", "MAIN CR 4100 0.04");
        assertThat(view(post("INT_TRUEUP", "ACTIVE", "INT_TRUEDOWN_AMT", "0.03")))
                .containsExactly("MAIN DR 4100 0.03", "MAIN CR 1120 0.03");
    }

    @Test
    void feeLevyAndReversal() {
        assertThat(view(post("FEE_LEVY", "ACTIVE", "FEE_AMT", "500.00")))
                .containsExactly("MAIN DR 1150 500.00", "MAIN CR 4120 500.00");
        assertThat(view(post("FEE_REVERSAL", "ACTIVE", "FEE_REVERSED_AMT", "500.00")))
                .containsExactly("MAIN DR 4120 500.00", "MAIN CR 1150 500.00");
    }

    @Test
    void openingBalanceNetsAgainstTheContraAccount() {
        DraftJournal j = post("OPENING_BALANCE", "ACTIVE", "OPENING_PRINCIPAL", "700000.00", "OPENING_PRINCIPAL_DUE",
                "200000.00", "OPENING_INT_ACCRUED", "2500.00", "OPENING_INT_DUE", "9000.00", "OPENING_PENAL", "249.53",
                "OPENING_ADVANCE", "100.00", "OPENING_NET", "911649.53");
        assertBalanced(j);
        assertThat(view(j)).contains("MAIN CR 2950 911649.53", "MAIN CR 2100 100.00");
    }

    @Test
    void allZeroAmountsProduceNoJournal() {
        assertThat(post("EMI_PAYMENT", "ACTIVE", "PAYMENT_AMT", "0.00")).isNull();
    }

    @Test
    void anUnbalancedEventIsRejectedWithoutPartialOutput() {
        assertThatThrownBy(() -> post("EMI_PAYMENT", "ACTIVE", "PAYMENT_AMT", "100.00", "INTEREST_PAID", "90.00"))
                .isInstanceOf(PostingException.class).hasMessageContaining("does not balance");
    }

    @Test
    void aMissingRouteNamesTheRoleLedgerAndProduct() {
        AccountingConfig noRoutes = new AccountingConfig(cfg.events(), cfg.templatesByEvent(), cfg.ledgers(),
                cfg.accounts(), List.of(), cfg.eventLedgers());
        Defs.EventDef ev = cfg.events().get("FEE_LEVY");
        Defs.TemplateDef tpl = cfg.templatesByEvent().get("FEE_LEVY").get(0);
        PostingRequest req = new PostingRequest("FEE_LEVY", 7L, "HL", "TEST", 1L, D, "X", D, D, "INR",
                Map.of("FEE_AMT", new BigDecimal("5.00")), Map.of("npa_stage", "ACTIVE"), List.of(), "test");
        assertThatThrownBy(() -> JournalAssembler.assemble(noRoutes, req, ev, tpl, JournalAssembler.SAME_CURRENCY_ONLY))
                .isInstanceOf(PostingException.class).hasMessageContaining("FEE_RECEIVABLE")
                .hasMessageContaining("MAIN");
    }

    @Test
    void aForeignCurrencyIsRejectedUntilMultiCurrencyIsEnabled() {
        Defs.EventDef ev = cfg.events().get("FEE_LEVY");
        Defs.TemplateDef tpl = cfg.templatesByEvent().get("FEE_LEVY").get(0);
        PostingRequest req = new PostingRequest("FEE_LEVY", 7L, "HL", "TEST", 1L, D, "X", D, D, "USD",
                Map.of("FEE_AMT", new BigDecimal("5.00")), Map.of("npa_stage", "ACTIVE"), List.of(), "test");
        assertThatThrownBy(() -> JournalAssembler.assemble(cfg, req, ev, tpl, JournalAssembler.SAME_CURRENCY_ONLY))
                .isInstanceOf(PostingException.class);
    }

    @Test
    void aSingleAmountConvertsIdenticallyOnBothSidesSoNoRoundingLineIsNeeded() {
        Defs.EventDef ev = cfg.events().get("FEE_LEVY");
        Defs.TemplateDef tpl = cfg.templatesByEvent().get("FEE_LEVY").get(0);
        PostingRequest req = new PostingRequest("FEE_LEVY", 7L, "HL", "TEST", 1L, D, "X", D, D, "USD",
                Map.of("FEE_AMT", new BigDecimal("100.01")), Map.of("npa_stage", "ACTIVE"), List.of(), "test");
        JournalAssembler.FxRates fx = (from, to, type, date) -> new JournalAssembler.FxRate(new BigDecimal("85.0049"), false);
        DraftJournal j = JournalAssembler.assemble(cfg, req, ev, tpl, fx);
        // both lines convert identically, so no rounding line is needed and base = 100.01 x 85.0049 = 8501.34
        assertThat(j.lines()).hasSize(2);
        assertThat(j.lines().get(0).amountBase()).isEqualByComparingTo("8501.34");
        assertThat(j.lines().get(0).fxRate()).isEqualByComparingTo("85.0049");
        assertBalanced(j);
    }

    @Test
    void conversionResidualWithinToleranceGoesToTheRoundingAccount() {
        // three credit lines of 0.01 USD (0.005 base, rounds to 0.01 each) against a 0.03 USD debit (0.015, rounds to 0.02)
        Defs.EventDef ev = cfg.events().get("EMI_PAYMENT");
        Defs.TemplateDef tpl = cfg.templatesByEvent().get("EMI_PAYMENT").get(0);
        Map<String, BigDecimal> amounts = Map.of("PAYMENT_AMT", new BigDecimal("0.03"), "PENAL_PAID", new BigDecimal("0.01"),
                "INTEREST_PAID", new BigDecimal("0.01"), "PRINCIPAL_PAID", new BigDecimal("0.01"));
        PostingRequest req = new PostingRequest("EMI_PAYMENT", 7L, "HL", "TEST", 1L, D, null, D, D, "USD", amounts,
                Map.of("npa_stage", "ACTIVE"), List.of(), "test");
        JournalAssembler.FxRates fx = (from, to, type, date) -> new JournalAssembler.FxRate(new BigDecimal("0.5"), false);
        DraftJournal j = JournalAssembler.assemble(cfg, req, ev, tpl, fx);
        assertThat(view(j)).contains("MAIN DR 6900 0.01");
        assertBalanced(j);
    }
}
