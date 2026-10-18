package com.bank.lending.e2e.run;

import com.bank.lending.e2e.client.LcmClient;
import com.bank.lending.e2e.client.LendingClient;
import com.bank.lending.e2e.config.E2eConfig;
import com.bank.lending.e2e.http.ApiResult;
import com.bank.lending.e2e.http.Http;
import com.bank.lending.e2e.scenario.Scenario;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.function.Consumer;

/**
 * The SECURED lending flow: the steps that exist only for a loan backed by collateral held in the Limits and Collateral (LCM)
 * service, in the order a bank does them.
 *
 * <pre>
 *   prepare   register collateral (DRAFT) - activation refused while requirements are open - create the facility (DRAFT),
 *             activate it by maker-checker (the maker cannot approve their own proposal)
 *   (loan)    the caller creates the loan account on that facility
 *   refuse    disbursal is refused while no ACTIVE collateral is allocated to the facility; the account stays pending
 *   cover     legal verification, valuation (maker-checker), charge, insurance, activation, allocation to the facility;
 *             LCM reports the facility as covered
 *   (loan)    the caller disburses; the facility shows the drawing
 *   daily     every verified day: the facility's utilisation equals the test ledger's outstanding principal, the collateral
 *             is still ACTIVE
 *   end       closed loans leave nothing utilised
 * </pre>
 *
 * It only talks to LCM and lending-service through their public APIs. Unsecured loans never touch this class.
 */
public final class SecuredFlow {

    public enum Disbursal { REFUSED, ACCEPTED, OTHER }

    /** Marks a scenario as secured: it is only run by the secured flow, and the unsecured flow leaves it out. */
    public static final String FEATURE = "COLLATERAL";

    public static boolean isSecured(Scenario sc) {
        return sc.requiresFeatures() != null && sc.requiresFeatures().contains(FEATURE);
    }

    private final E2eConfig cfg;
    private final RunContext ctx;
    private final Findings findings;
    private final LcmClient lcm;
    private final Consumer<String> log;
    private final String maker;
    private final String checker;
    private final BigDecimal tol;
    private final long settleMs;
    private String collateralType;

    public SecuredFlow(E2eConfig cfg, RunContext ctx, Findings findings, LcmClient lcm, Consumer<String> log) {
        this.cfg = cfg;
        this.ctx = ctx;
        this.findings = findings;
        this.lcm = lcm;
        this.log = log;
        this.maker = cfg.get("e2e.lcm.maker", "e2e-maker");
        this.checker = cfg.get("e2e.lcm.checker", "e2e-checker");
        this.tol = cfg.getDecimal("e2e.tolerance.money");
        this.settleMs = Long.parseLong(cfg.get("e2e.secured.settle-seconds", "20")) * 1000L;
        if (maker.equalsIgnoreCase(checker)) {
            throw new IllegalArgumentException("e2e.lcm.maker and e2e.lcm.checker must be different names (maker-checker)");
        }
    }

    // ================================================================================================ before the loan exists

    /** Registers the collateral and creates and activates the facility. False (account marked failed) when a step cannot be done. */
    public boolean prepare(AccountSim s, LocalDate day) {
        Scenario sc = s.scenario;
        String sid = sc.scenarioId();
        String who = s.label();
        BigDecimal amount = sc.amount();
        s.collateralRef = ctx.runId() + "-C" + s.index;
        s.facilityRef = ctx.runId() + "-F" + s.index;
        s.facilityAmount = amount.multiply(cfg.getDecimal("e2e.secured.facility-factor")).setScale(2, RoundingMode.HALF_UP);

        String type = collateralType();
        if (type == null) {
            findings.fail("SETUP-04", sid, who, day, "a collateral type in LCM", "none (GET /collateral-types empty or unreadable)",
                    "cannot register collateral");
            return false;
        }

        // 1. collateral, DRAFT
        ObjectNode c = Http.MAPPER.createObjectNode();
        c.put("customerId", s.customerId);
        c.put("collateralRef", s.collateralRef);
        c.put("collateralType", type);
        c.put("description", "E2E " + ctx.runId() + " secured loan " + s.index);
        c.put("currencyCode", cfg.get("e2e.secured.currency", "INR"));
        ApiResult created = lcm.createCollateral(c, maker);
        if (!findings.check(created.ok() && "DRAFT".equals(created.body().path("status").asText()), "SEC-01", sid, who, day,
                "201 and status DRAFT", created.brief(), "collateral was not registered as DRAFT")) {
            return false;
        }

        // 2. activation must be refused while the requirements are open
        ApiResult early = lcm.activateCollateral(s.collateralRef, maker);
        findings.check(!early.ok() && early.clientError() && early.rawBody() != null && early.rawBody().contains("ACTIVATION_REQUIREMENTS_NOT_MET"),
                "SEC-02", sid, who, day, "4xx ACTIVATION_REQUIREMENTS_NOT_MET", early.brief(),
                "collateral without legal verification, valuation, charge or insurance was activated or refused for another reason");

        // 3. facility, DRAFT -> ACTIVE by maker-checker
        ObjectNode f = Http.MAPPER.createObjectNode();
        f.put("customerId", s.customerId);
        f.put("facilityRef", s.facilityRef);
        f.put("facilityType", "OVERALL");
        f.put("productCode", sc.productCode());
        f.put("currencyCode", cfg.get("e2e.secured.currency", "INR"));
        f.put("revolving", false);
        f.put("dpGoverned", false);
        f.put("sanctionedAmount", s.facilityAmount);
        f.put("expiryDate", day.plusMonths(sc.tenureMonths()).plusYears(1).toString());
        f.put("sanctionRefId", s.sanctionRefId);
        ApiResult fac = lcm.createFacility(f, maker);
        if (!findings.check(fac.ok() && "DRAFT".equals(fac.body().path("status").asText()), "SEC-04", sid, who, day,
                "201 and status DRAFT", fac.brief(), "facility was not created")) {
            return false;
        }
        ApiResult prop = lcm.proposeChange(s.facilityRef, "ACTIVATE", "E2E " + ctx.runId(), maker);
        if (!findings.check(prop.ok() && prop.body().hasNonNull("id"), "SEC-04", sid, who, day, "201 with a change id", prop.brief(),
                "facility activation could not be proposed")) {
            return false;
        }
        long changeId = prop.body().get("id").asLong();
        ApiResult self = lcm.approveChange(changeId, maker);
        findings.check(!self.ok() && self.clientError(), "SEC-12", sid, who, day, "4xx: the maker cannot approve their own change",
                self.brief(), "maker-checker is not enforced on a facility change");
        ApiResult appr = lcm.approveChange(changeId, checker);
        if (!appr.ok()) {
            findings.fail("SEC-04", sid, who, day, "checker approval accepted", appr.brief(), "facility activation was not approved");
            return false;
        }
        ApiResult now = lcm.facility(s.facilityRef);
        return findings.check(now.ok() && "ACTIVE".equals(now.body().path("status").asText()), "SEC-04", sid, who, day,
                "facility ACTIVE", now.brief(), "facility is not ACTIVE after approval");
    }

    /**
     * Disbursal with nothing allocated yet must be refused and must leave the account pending.
     * {@code accepted[0]} receives the disbursal answer when (wrongly) it went through, so the caller does not disburse twice.
     */
    public Disbursal refuseUntilCovered(AccountSim s, LocalDate day, LendingClient lending, BigDecimal amount, LocalDate firstDue,
                                        ApiResult[] accepted) {
        String sid = s.scenario.scenarioId();
        ApiResult r = lending.disburse(s.accountId, amount, firstDue);
        if (r.ok()) {
            accepted[0] = r;
            findings.fail("SEC-06", sid, s.accountNumber, day, "refused: no active collateral is allocated to " + s.facilityRef,
                    r.brief(), "a secured loan was disbursed without collateral");
            return Disbursal.ACCEPTED;
        }
        boolean byCollateral = r.rawBody() != null && r.rawBody().contains("COLLATERAL_NOT_COVERED");
        ApiResult acc = lending.getAccount(s.accountId);
        boolean pending = acc.ok() && "PENDING_DISBURSEMENT".equals(acc.body().path("accountStatus").asText());
        findings.check(byCollateral && pending, "SEC-06", sid, s.accountNumber, day,
                "422 COLLATERAL_NOT_COVERED and the account still PENDING_DISBURSEMENT",
                r.brief() + (pending ? "" : " | account status " + acc.body().path("accountStatus").asText("?")),
                r.rawBody() != null && r.rawBody().contains("COLLATERAL_NOT_CHECKED")
                        ? "lending.lcm.mode is not live on lending-service: secured loans cannot be disbursed"
                        : "disbursal was refused for another reason, or the account changed");
        return byCollateral ? Disbursal.REFUSED : Disbursal.OTHER;
    }

    /** Makes the collateral ACTIVE and allocates it to the facility. */
    public boolean cover(AccountSim s, LocalDate day) {
        String sid = s.scenario.scenarioId();
        String who = s.accountNumber;
        String ref = s.collateralRef;
        LocalDate today = LocalDate.now(); // LCM judges validity against its own clock, and a valuation cannot be dated in the future

        ApiResult legal = lcm.setLegal(ref, "VERIFIED", maker);
        if (!step(legal, "SEC-03", sid, who, day, "legal verification recorded")) {
            return false;
        }
        BigDecimal value = s.scenario.amount().multiply(cfg.getDecimal("e2e.secured.cover-ratio")).setScale(2, RoundingMode.HALF_UP);
        ApiResult val = lcm.proposeValuation(ref, today, value, "E2E-VALUER", maker);
        if (!step(val, "SEC-03", sid, who, day, "valuation proposed") || !val.body().hasNonNull("id")) {
            return false;
        }
        long valId = val.body().get("id").asLong();
        ApiResult selfVal = lcm.approveValuation(valId, maker);
        findings.check(!selfVal.ok() && selfVal.clientError(), "SEC-12", sid, who, day, "4xx: the maker cannot approve their own valuation",
                selfVal.brief(), "maker-checker is not enforced on a valuation");
        if (!step(lcm.approveValuation(valId, checker), "SEC-03", sid, who, day, "valuation approved by the checker")) {
            return false;
        }
        if (!step(lcm.registerCharge(ref, "CHG-" + ref, today, maker), "SEC-03", sid, who, day, "charge registered")) {
            return false;
        }
        LocalDate insured = today.plusYears(Long.parseLong(cfg.get("e2e.secured.insurance-years", "5")));
        if (!step(lcm.setInsurance(ref, insured, maker), "SEC-03", sid, who, day, "insurance recorded")) {
            return false;
        }
        ApiResult act = lcm.activateCollateral(ref, maker);
        if (!findings.check(act.ok() && "ACTIVE".equals(act.body().path("status").asText()), "SEC-03", sid, who, day,
                "collateral ACTIVE", act.brief(), "collateral with every requirement met could not be activated")) {
            return false;
        }
        ApiResult alloc = lcm.allocate(ref, s.facilityRef, new BigDecimal("100"), maker);
        if (!findings.check(alloc.ok() && alloc.body().hasNonNull("id"), "SEC-05", sid, who, day, "201 with an allocation id",
                alloc.brief(), "collateral was not allocated to the facility")) {
            return false;
        }
        s.allocationId = alloc.body().get("id").asLong();
        ApiResult cov = lcm.collateralCover(s.facilityRef);
        boolean listed = false;
        for (JsonNode i : cov.body().path("items")) {
            listed |= ref.equals(i.path("collateralRef").asText());
        }
        return findings.check(cov.ok() && cov.body().path("covered").asBoolean(false) && listed
                        && new BigDecimal(cov.body().path("coveredValue").asText("0")).signum() > 0, "SEC-05", sid, who, day,
                "covered=true with " + ref + " listed and a covered value", cov.brief(), "LCM does not report the facility as covered");
    }

    // ================================================================================================ after the loan is disbursed

    /** The drawing must show on the facility (LCM is told after the loan transaction commits, so it may take a moment). */
    public void afterDisbursal(AccountSim s, LocalDate day, BigDecimal disbursed) {
        JsonNode a = await(s, disbursed);
        boolean ok = a != null && near(a.path("utilised"), disbursed) && near(a.path("drawnTotal"), disbursed)
                && near(a.path("reserved"), BigDecimal.ZERO);
        if (!findings.check(ok, "SEC-08", s.scenario.scenarioId(), s.accountNumber, day,
                "utilised " + disbursed + ", drawn " + disbursed + ", reserved 0 on " + s.facilityRef,
                a == null ? "facility not readable" : "utilised " + a.path("utilised").asText() + ", drawn " + a.path("drawnTotal").asText()
                        + ", reserved " + a.path("reserved").asText(), "the disbursement is not reflected on the facility")) {
            s.limitDiverged = true;
        }
    }

    /** Called after each verified day. Returns the facility's utilisation as LCM reports it (for the audit trail), or "". */
    public String daily(AccountSim s, LocalDate day) {
        if (!s.secured() || s.accountId == null || s.diverged) {
            return "";
        }
        String sid = s.scenario.scenarioId();
        BigDecimal expected = s.closed ? BigDecimal.ZERO : s.modelOutstanding;
        JsonNode a = await(s, expected);
        boolean ok = a != null && near(a.path("utilised"), expected);
        String id = s.closed ? "SEC-10" : "SEC-09";
        if (!findings.check(ok, id, sid, s.accountNumber, day, "utilised " + expected.toPlainString() + " on " + s.facilityRef,
                a == null ? "facility not readable" : "utilised " + a.path("utilised").asText(),
                s.closed ? "a closed loan still uses the facility" : "facility utilisation differs from the loan's outstanding principal")) {
            s.limitDiverged = true;
        }
        ApiResult col = lcm.collateral(s.collateralRef);
        findings.check(col.ok() && "ACTIVE".equals(col.body().path("status").asText()), "SEC-11", sid, s.accountNumber, day,
                "collateral ACTIVE", col.ok() ? col.body().path("status").asText() : col.brief(),
                "collateral behind a live loan changed state during the run");
        return a == null ? "" : a.path("utilised").asText("");
    }

    // ================================================================================================ clean-up through the API

    /**
     * Tidy-up used by teardown once the loan is closed: release the allocation and the collateral, close the facility
     * (maker-checker). Best effort; what cannot be done is reported, never thrown.
     */
    public static void release(LcmClient lcm, String maker, String checker, String collateralRef, String facilityRef, Consumer<String> log) {
        ApiResult al = lcm.allocations(collateralRef);
        for (JsonNode a : al.body()) {
            if ("ACTIVE".equals(a.path("status").asText())) {
                ApiResult r = lcm.releaseAllocation(a.path("id").asLong(), maker);
                log.accept("  release allocation " + a.path("id").asText() + ": " + (r.ok() ? "ok" : r.brief()));
            }
        }
        ApiResult rc = lcm.releaseCollateral(collateralRef, maker);
        log.accept("  release collateral " + collateralRef + ": " + (rc.ok() ? "ok" : rc.brief()));
        ApiResult p = lcm.proposeChange(facilityRef, "CLOSE", "E2E teardown", maker);
        if (p.ok() && p.body().hasNonNull("id")) {
            ApiResult a = lcm.approveChange(p.body().get("id").asLong(), checker);
            log.accept("  close facility " + facilityRef + ": " + (a.ok() ? "ok" : a.brief()));
        } else {
            log.accept("  close facility " + facilityRef + ": " + p.brief());
        }
    }

    // ================================================================================================ helpers

    private boolean step(ApiResult r, String id, String sid, String who, LocalDate day, String what) {
        return findings.check(r.ok(), id, sid, who, day, what, r.brief(), "collateral set-up step failed: " + what);
    }

    private String collateralType() {
        if (collateralType != null) {
            return collateralType;
        }
        String wanted = cfg.get("e2e.secured.collateral-type", "");
        ApiResult types = lcm.collateralTypes();
        if (!types.ok() || !types.body().isArray() || types.body().size() == 0) {
            return null;
        }
        String first = null;
        String best = null;
        for (JsonNode t : types.body()) {
            String code = t.path("typeCode").asText();
            if (!wanted.isBlank() && wanted.equalsIgnoreCase(code)) {
                collateralType = code;
                return code;
            }
            if (first == null) {
                first = code;
            }
            if (best == null && t.path("requiresCharge").asBoolean(false) && t.path("requiresInsurance").asBoolean(false)) {
                best = code;
            }
        }
        collateralType = wanted.isBlank() ? (best != null ? best : first) : null;
        if (collateralType == null) {
            log.accept("  e2e.secured.collateral-type=" + wanted + " is not a collateral type in LCM");
        }
        return collateralType;
    }

    /** Polls the facility until its utilisation is the expected figure (or the settle time is used up). */
    private JsonNode await(AccountSim s, BigDecimal expectedUtilised) {
        long deadline = System.currentTimeMillis() + (s.limitDiverged ? 0 : settleMs);
        JsonNode last = null;
        while (true) {
            ApiResult r = lcm.availability(s.facilityRef);
            if (r.ok()) {
                last = r.body();
                if (near(last.path("utilised"), expectedUtilised)) {
                    return last;
                }
            }
            if (System.currentTimeMillis() >= deadline) {
                return last;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return last;
            }
        }
    }

    private boolean near(JsonNode n, BigDecimal expected) {
        if (!n.isNumber() && !(n.isTextual() && !n.asText().isBlank())) {
            return false;
        }
        try {
            return new BigDecimal(n.asText()).subtract(expected).abs().compareTo(tol) <= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
