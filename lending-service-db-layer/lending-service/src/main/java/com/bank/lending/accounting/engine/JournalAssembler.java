package com.bank.lending.accounting.engine;

import com.bank.lending.accounting.engine.TemplateEvaluator.Evaluated;
import com.bank.lending.accounting.model.Defs;
import com.bank.lending.accounting.model.Defs.AccountDef;
import com.bank.lending.accounting.model.Defs.DraftJournal;
import com.bank.lending.accounting.model.Defs.DraftLine;
import com.bank.lending.accounting.model.Defs.EventDef;
import com.bank.lending.accounting.model.Defs.LedgerDef;
import com.bank.lending.accounting.model.Defs.PostingRequest;
import com.bank.lending.accounting.model.Defs.RouteDef;
import com.bank.lending.accounting.model.Defs.TemplateDef;
import com.bank.lending.accounting.model.PostingException;
import com.bank.lending.accounting.model.PostingException.Reason;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a template, an event's amounts and the configuration into a balanced, routed draft journal. Pure: reading the
 * configuration and storing the result are the caller's job.
 *
 * <p>For each ledger the event is mapped to, every rule that applies to it is routed to a GL account, converted to the
 * ledger's base currency, and the ledger's lines are checked to balance (debits equal credits) before anything is
 * returned. A rule applies to a ledger when its {@code ledger_scope} is empty and the ledger is not a memo ledger, or
 * when it names the ledger's code, its purpose, or {@code TYPE:<ledger type>}.
 */
public final class JournalAssembler {

    public static final String ROUNDING_ROLE = "ROUNDING";

    private JournalAssembler() {
    }

    public record FxRate(BigDecimal rate, boolean stale) {
    }

    /** Source of exchange rates (txn currency to ledger base currency). */
    public interface FxRates {
        FxRate rate(String from, String to, String rateType, LocalDate date);
    }

    /** Rates for a deployment that books in one currency: only the identity rate exists. */
    public static final FxRates SAME_CURRENCY_ONLY = (from, to, type, date) -> {
        if (from.equals(to)) {
            return new FxRate(BigDecimal.ONE, false);
        }
        throw new PostingException(Reason.RATE_MISSING, "No exchange rate " + from + "/" + to
                + " (multi-currency is not enabled)");
    };

    public static int precision(String currency) {
        try {
            return Math.max(0, Currency.getInstance(currency).getDefaultFractionDigits());
        } catch (IllegalArgumentException e) {
            throw new PostingException(Reason.INVALID_REQUEST, "Unknown currency " + currency, e);
        }
    }

    /** @return the draft, or {@code null} when every line evaluates to zero (nothing to post) */
    public static DraftJournal assemble(AccountingConfig cfg, PostingRequest req, EventDef ev, TemplateDef tpl,
                                        FxRates fx) {
        String txnCcy = req.currency();
        int txnPrecision = precision(txnCcy);
        Map<String, Object> ctx = new HashMap<>(req.context() == null ? Map.of() : req.context());
        ctx.putIfAbsent("product_code", req.productCode());
        ctx.putIfAbsent("currency", txnCcy);

        List<Evaluated> evaluated = TemplateEvaluator.evaluate(tpl, req.amounts(), ev.tags(), ctx, txnPrecision);
        if (evaluated.isEmpty()) {
            return null;
        }
        List<Integer> ledgerIds = cfg.eventLedgers().getOrDefault(ev.eventCode(), List.of());
        List<LedgerDef> ledgers = new ArrayList<>();
        for (Integer id : ledgerIds) {
            LedgerDef l = cfg.ledgers().get(id);
            if (l != null && !"CLOSED".equals(l.status())) {
                ledgers.add(l);
            }
        }
        if (ledgers.isEmpty()) {
            throw new PostingException(Reason.NO_LEDGER, "Event " + ev.eventCode() + " is not mapped to an open ledger");
        }
        String stage = ctx.get("npa_stage") == null ? null : String.valueOf(ctx.get("npa_stage"));

        List<DraftLine> lines = new ArrayList<>();
        int seq = 0;
        String baseOfJournal = null;
        for (LedgerDef ledger : ledgers) {
            List<DraftLine> mine = new ArrayList<>();
            boolean converted = false;
            for (Evaluated e : evaluated) {
                if (!applies(e.rule().ledgerScope(), ledger)) {
                    continue;
                }
                RouteDef route = RouteResolver.resolve(cfg.routes(), ledger.ledgerId(), e.rule().roleCode(),
                        req.productCode(), stage, txnCcy, req.branchCode(), req.businessDate()).orElseThrow(() -> new PostingException(
                        Reason.ROUTE_MISSING, "No route for role " + e.rule().roleCode() + " in ledger "
                        + ledger.ledgerCode() + " (event " + ev.eventCode() + ", product " + req.productCode()
                        + ", stage " + stage + ", currency " + txnCcy + ", branch " + req.branchCode() + ")"));
                AccountDef acct = checkedAccount(cfg, ledger, route, txnCcy);
                String base = ledger.baseCurrency();
                BigDecimal amountTxn;
                BigDecimal rate;
                boolean stale = false;
                BigDecimal amountBase;
                if ("BASE".equals(e.rule().currencyBasis())) {
                    amountTxn = null;
                    rate = null;
                    amountBase = e.amount().setScale(precision(base), RoundingMode.HALF_UP);
                } else if (txnCcy.equals(base)) {
                    amountTxn = e.amount();
                    rate = BigDecimal.ONE;
                    amountBase = e.amount();
                } else {
                    FxRate r = fx.rate(txnCcy, base, ev.fxRateType(), req.businessDate());
                    amountTxn = e.amount();
                    rate = r.rate();
                    stale = r.stale();
                    amountBase = e.amount().multiply(r.rate()).setScale(precision(base), RoundingMode.HALF_UP);
                    converted = true;
                }
                mine.add(new DraftLine(0, ledger.ledgerId(), ledger.ledgerCode(), acct.code(), e.rule().roleCode(),
                        e.rule().side(), amountTxn, amountTxn == null ? null : txnCcy, rate,
                        rate == null ? null : ev.fxRateType(), amountBase, base, e.rule().ruleId(),
                        e.rule().narrative(), stale));
            }
            if (mine.isEmpty()) {
                continue;
            }
            balance(cfg, ledger, mine, req, txnCcy, converted, ev);
            for (DraftLine l : mine) {
                seq++;
                lines.add(new DraftLine(seq, l.ledgerId(), l.ledgerCode(), l.glAccountCode(), l.roleCode(), l.side(),
                        l.amountTxn(), l.currencyTxn(), l.fxRate(), l.fxRateType(), l.amountBase(), l.currencyBase(),
                        l.ruleId(), l.narrative(), l.rateStale()));
            }
            if (baseOfJournal == null) {
                baseOfJournal = ledger.baseCurrency();
            }
        }
        if (lines.isEmpty()) {
            return null;
        }
        return new DraftJournal(ev.eventCode(), tpl.templateId(), tpl.version(), txnCcy,
                baseOfJournal == null ? txnCcy : baseOfJournal, lines, narrative(ev, req));
    }

    /** Whether a rule with this ledger scope posts to the ledger: no scope means every non-memo ledger. */
    public static boolean applies(String scope, LedgerDef ledger) {
        if (scope == null || scope.isBlank()) {
            return !ledger.memo();
        }
        return scope.equals(ledger.ledgerCode()) || scope.equals(ledger.purpose())
                || scope.equals("TYPE:" + ledger.ledgerType());
    }

    private static AccountDef checkedAccount(AccountingConfig cfg, LedgerDef ledger, RouteDef route, String ccy) {
        AccountDef a = cfg.account(ledger.ledgerId(), route.glAccountCode()).orElseThrow(() -> new PostingException(
                Reason.ROUTE_MISSING, "Route " + route.routeId() + " points at unknown account "
                + route.glAccountCode() + " in ledger " + ledger.ledgerCode()));
        if (!"ACTIVE".equals(a.status()) || !a.postingAllowed()) {
            throw new PostingException(Reason.ACCOUNT_BLOCKED, "GL account " + a.code() + " in ledger "
                    + ledger.ledgerCode() + " is not open for posting (" + a.status() + ")");
        }
        if (!a.acceptsCurrency(ccy)) {
            throw new PostingException(Reason.CURRENCY_MISMATCH, "GL account " + a.code() + " does not accept " + ccy);
        }
        return a;
    }

    /**
     * Enforces debits = credits in the ledger's base currency and, for each transaction currency, in that currency.
     * A small residual left by converting lines may be absorbed by a ROUNDING line (one minor unit per line at most);
     * anything else, and any imbalance without conversion, is an error.
     */
    private static void balance(AccountingConfig cfg, LedgerDef ledger, List<DraftLine> mine, PostingRequest req,
                                String txnCcy, boolean converted, EventDef ev) {
        BigDecimal dr = BigDecimal.ZERO;
        BigDecimal cr = BigDecimal.ZERO;
        for (DraftLine l : mine) {
            if (Defs.DR.equals(l.side())) {
                dr = dr.add(l.amountBase());
            } else {
                cr = cr.add(l.amountBase());
            }
        }
        BigDecimal diff = dr.subtract(cr);
        if (diff.signum() != 0) {
            BigDecimal minor = BigDecimal.ONE.movePointLeft(precision(ledger.baseCurrency()));
            BigDecimal tolerance = minor.multiply(BigDecimal.valueOf(mine.size()));
            if (!converted || diff.abs().compareTo(tolerance) > 0) {
                throw new PostingException(Reason.UNBALANCED, "Journal for " + ev.eventCode() + " does not balance in ledger "
                        + ledger.ledgerCode() + ": debits " + dr + " credits " + cr);
            }
            RouteDef route = RouteResolver.resolve(cfg.routes(), ledger.ledgerId(), ROUNDING_ROLE, req.productCode(),
                    null, txnCcy, req.branchCode(), req.businessDate()).orElseThrow(() -> new PostingException(Reason.ROUTE_MISSING,
                    "No ROUNDING route in ledger " + ledger.ledgerCode()));
            AccountDef acct = checkedAccount(cfg, ledger, route, txnCcy);
            String side = diff.signum() > 0 ? Defs.CR : Defs.DR; // debits too high: add a credit
            mine.add(new DraftLine(0, ledger.ledgerId(), ledger.ledgerCode(), acct.code(), ROUNDING_ROLE, side, null,
                    null, null, null, diff.abs(), ledger.baseCurrency(), 0L, "Conversion rounding", false));
        }
        // per transaction currency (lines that carry a transaction amount)
        Map<String, BigDecimal> net = new LinkedHashMap<>();
        for (DraftLine l : mine) {
            if (l.amountTxn() == null) {
                continue;
            }
            BigDecimal signed = Defs.DR.equals(l.side()) ? l.amountTxn() : l.amountTxn().negate();
            net.merge(l.currencyTxn(), signed, BigDecimal::add);
        }
        for (Map.Entry<String, BigDecimal> n : net.entrySet()) {
            if (n.getValue().signum() != 0) {
                throw new PostingException(Reason.UNBALANCED, "Journal for " + ev.eventCode() + " does not balance in "
                        + n.getKey() + " in ledger " + ledger.ledgerCode() + " (net " + n.getValue() + ")");
            }
        }
    }

    static String narrative(EventDef ev, PostingRequest req) {
        String p = ev.narrativePattern();
        if (p == null || p.isBlank()) {
            p = "{event} account {account}";
        }
        return p.replace("{event}", ev.name())
                .replace("{account}", String.valueOf(req.accountId()))
                .replace("{qualifier}", req.qualifier() == null ? "" : req.qualifier())
                .replace("{valueDate}", String.valueOf(req.valueDate()))
                .trim();
    }
}
