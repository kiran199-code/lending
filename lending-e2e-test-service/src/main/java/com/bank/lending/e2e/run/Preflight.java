package com.bank.lending.e2e.run;

import com.bank.lending.e2e.client.AdminReadClient;
import com.bank.lending.e2e.client.BatchClient;
import com.bank.lending.e2e.client.LendingClient;
import com.bank.lending.e2e.config.E2eConfig;
import com.bank.lending.e2e.http.ApiResult;
import com.bank.lending.e2e.scenario.Scenario;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Read-only readiness checks shared by dry-run and by the start of a real run. Never writes to any system. */
public final class Preflight {

    /**
     * {@code problems} block the run (a service is unreachable, a job is disabled, the business date is wrong). {@code warnings} are
     * per-account trouble (an override outside a product's limits...): the run goes ahead, and an account that cannot be created
     * is recorded as a defect without stopping the others.
     */
    public record Report(List<String> ok, List<String> problems, List<String> warnings) {
        public Report(List<String> ok, List<String> problems) {
            this(ok, problems, List.of());
        }

        public boolean passed() {
            return problems.isEmpty();
        }
    }

    private Preflight() {
    }

    public static Report run(E2eConfig cfg, LendingClient lending, BatchClient batch, AdminReadClient admin, List<Scenario> expanded) {
        List<String> ok = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        List<String> warn = new ArrayList<>();

        ApiResult products = lending.listProducts();
        if (!products.ok()) {
            bad.add("lending-service product list not reachable: " + products.brief());
        } else {
            ok.add("lending-service reachable (" + lending.http().baseUrl() + "), " + products.body().size() + " product(s)");
            Set<String> seen = new HashSet<>();
            for (Scenario s : expanded) {
                if (!seen.add(s.scenarioId())) {
                    continue;
                }
                JsonNode p = find(products.body(), s.productCode());
                if (p == null) {
                    warn.add(s.scenarioId() + ": product " + s.productCode() + " does not exist");
                    continue;
                }
                if (!"ACTIVE".equals(p.path("status").asText())) {
                    warn.add(s.scenarioId() + ": product " + s.productCode() + " is " + p.path("status").asText());
                }
                limit(warn, s, "amount", s.amount(), p.path("minSanctionedAmount"), p.path("maxSanctionedAmount"));
                limit(warn, s, "tenure", BigDecimal.valueOf(s.tenureMonths()), p.path("minTenureMonths"), p.path("maxTenureMonths"));
                if (s.interestRate() != null) {
                    limit(warn, s, "rate", s.interestRate(), p.path("minInterestRate"), p.path("maxInterestRate"));
                }
                boolean mortgage = "MORTGAGE".equals(p.path("accountType").asText());
                if (mortgage && !SecuredFlow.isSecured(s)) {
                    warn.add(s.scenarioId() + ": product " + s.productCode() + " is a MORTGAGE product; the unsecured flow does not use it (run the secured flow)");
                } else if (!mortgage && SecuredFlow.isSecured(s)) {
                    warn.add(s.scenarioId() + ": product " + s.productCode() + " is not a MORTGAGE product, so a loan on it would not need collateral");
                }
                String freq = s.repaymentFrequency() != null ? s.repaymentFrequency() : p.path("repaymentFrequency").asText("MONTHLY");
                boolean allowed = false;
                for (JsonNode a : p.path("allowedRepaymentFrequencies")) {
                    allowed |= a.asText().equals(freq);
                }
                if (p.has("allowedRepaymentFrequencies") && p.get("allowedRepaymentFrequencies").size() > 0 && !allowed) {
                    warn.add(s.scenarioId() + ": frequency " + freq + " not allowed by " + s.productCode());
                }
                int period = switch (freq) {
                    case "QUARTERLY" -> 3;
                    case "HALF_YEARLY" -> 6;
                    case "YEARLY" -> 12;
                    default -> 1;
                };
                if (s.tenureMonths() % period != 0) {
                    warn.add(s.scenarioId() + ": tenure " + s.tenureMonths() + " is not a multiple of " + freq);
                }
            }
            if (warn.isEmpty()) {
                ok.add("all " + seen.size() + " planned scenario(s) sit inside their product's limits");
            }
        }

        ApiResult jobs = batch.listJobs();
        if (!jobs.ok()) {
            bad.add("batch service /batch-admin/jobs not reachable (check credentials): " + jobs.brief());
        } else {
            Set<String> enabled = new HashSet<>();
            Set<String> known = new HashSet<>();
            for (JsonNode j : jobs.body()) {
                known.add(j.path("jobName").asText());
                if (j.path("enabled").asBoolean(false)) {
                    enabled.add(j.path("jobName").asText());
                }
            }
            ok.add("batch service reachable (" + known.size() + " jobs configured, " + enabled.size() + " enabled)");
            List<String> wanted = new ArrayList<>(cfg.getList("e2e.batch.daily-jobs"));
            wanted.addAll(cfg.getList("e2e.batch.monthly-jobs"));
            for (String w : wanted) {
                if (!known.contains(w)) {
                    bad.add("configured job " + w + " is not in batch_job_config");
                } else if (!enabled.contains(w)) {
                    bad.add("configured job " + w + " is disabled (a disabled job cannot be triggered)");
                }
            }
        }

        businessDate(cfg, lending, batch, admin, ok, bad);

        ApiResult me = admin.me();
        if (!me.ok()) {
            bad.add("admin portal not reachable / credentials rejected: " + me.brief());
        } else {
            ok.add("admin portal reachable (read-only verification surface)");
        }
        return new Report(ok, bad, warn);
    }

    /** Secured flow only: the Limits and Collateral service must be reachable with the service credential, and the actors must differ. */
    public static void secured(E2eConfig cfg, com.bank.lending.e2e.client.LcmClient lcm, List<String> ok, List<String> bad) {
        ApiResult types = lcm.collateralTypes();
        if (!types.ok()) {
            bad.add("Limits and Collateral service not reachable or credential rejected (GET /api/v1/lcm/collateral-types: " + types.brief()
                    + "). Set e2e.lcm.base-url / E2E_LCM_PASSWORD (the password lending-lcm-service uses for LCM_SERVICE_PASSWORD).");
        } else {
            ok.add("Limits and Collateral service reachable (" + lcm.http().baseUrl() + "), " + types.body().size() + " collateral type(s)");
            String wanted = cfg.get("e2e.secured.collateral-type", "");
            if (!wanted.isBlank()) {
                boolean found = false;
                for (JsonNode t : types.body()) {
                    found |= wanted.equalsIgnoreCase(t.path("typeCode").asText());
                }
                if (!found) {
                    bad.add("e2e.secured.collateral-type=" + wanted + " is not a collateral type in LCM");
                }
            }
        }
        if (cfg.get("e2e.lcm.maker", "e2e-maker").equalsIgnoreCase(cfg.get("e2e.lcm.checker", "e2e-checker"))) {
            bad.add("e2e.lcm.maker and e2e.lcm.checker must be different names");
        }
        ok.add("secured loans need lending-service to run with lending.lcm.mode=live (LENDING_LCM_MODE=live); the run checks this by trying to"
                + " disburse before the collateral is allocated, which must be refused with COLLATERAL_NOT_COVERED");
    }

    /** Business-date readiness. Read-only: the date itself is only moved by the run, not by preflight / dry-run. */
    private static void businessDate(E2eConfig cfg, LendingClient lending, BatchClient batch, AdminReadClient admin,
                                     List<String> ok, List<String> bad) {
        String mode = cfg.get("e2e.business-date.mode", "MANAGED").toUpperCase();
        if ("OFF".equals(mode)) {
            ok.add("business-date mode OFF: the suite only passes dates; lending-service rejects payments dated after its business date"
                    + " unless it runs with BUSINESS_DATE_ENFORCE_NO_FUTURE=false");
            return;
        }
        if (!"MANAGED".equals(mode)) {
            bad.add("e2e.business-date.mode must be MANAGED or OFF, not " + mode);
            return;
        }
        String eodMode = cfg.get("e2e.eod.mode", "EOD_RUN").toUpperCase();
        if (!"EOD_RUN".equals(eodMode) && !"JOBS_THEN_ADVANCE".equals(eodMode)) {
            bad.add("e2e.eod.mode must be EOD_RUN or JOBS_THEN_ADVANCE, not " + eodMode);
            return;
        }
        boolean apiPaths = "API".equalsIgnoreCase(cfg.get("e2e.accrual.path")) || "API".equalsIgnoreCase(cfg.get("e2e.dpd.path"));
        if (apiPaths && "EOD_RUN".equals(eodMode)) {
            bad.add("e2e.accrual.path=API / e2e.dpd.path=API cannot be combined with e2e.eod.mode=EOD_RUN (the end-of-day run executes"
                    + " those jobs itself). Use e2e.eod.mode=JOBS_THEN_ADVANCE.");
        }
        LocalDate start = cfg.getDate("e2e.start-date");
        ApiResult bd = lending.businessDate();
        if (!bd.ok()) {
            bad.add("lending-service has no readable business date (GET /api/v1/business-date: " + bd.brief() + "). Deploy a lending-service with"
                    + " the business-date API, or set e2e.business-date.mode=OFF and BUSINESS_DATE_ENFORCE_NO_FUTURE=false.");
        } else {
            LocalDate current = LocalDate.parse(bd.body().path("businessDate").asText());
            String status = bd.body().path("status").asText("?");
            if (!"OPEN".equals(status)) {
                bad.add("the platform business date " + current + " is " + status + " (an end of day is unfinished). Finish it first:"
                        + " POST /api/v1/batch-admin/eod/run on the batch service.");
            } else if (current.isAfter(start)) {
                bad.add("the platform business date is " + current + ", already after e2e.start-date " + start + ". The date only moves forward:"
                        + " use a later e2e.start-date (the run begins on the current business date if you set it to " + current + "), or run"
                        + " lending-service with BUSINESS_DATE_ALLOW_BACKWARD_SET=true and set the date back.");
            } else {
                ok.add("platform business date " + current + " (OPEN); the run will set it to the start date " + start
                        + (current.equals(start) ? " (already there)" : ""));
            }
        }
        ApiResult ab = admin.businessDate();
        if (!ab.ok()) {
            bad.add("admin portal has no /api/admin/business-date (" + ab.brief() + "): it still uses the wall clock. Deploy the current admin service.");
        } else if (!ab.body().path("fromLending").asBoolean(false)) {
            bad.add("admin portal cannot read business_date_state (it is falling back to the wall clock) - lending-service V13 not applied, or no grant");
        } else {
            ok.add("admin portal reads the platform business date " + ab.body().path("businessDate").asText());
        }
        if ("EOD_RUN".equals(eodMode)) {
            ApiResult st = batch.eodStatus();
            if (!st.ok()) {
                bad.add("batch service has no end-of-day API (GET /api/v1/batch-admin/eod/status: " + st.brief() + "). Deploy the current batch"
                        + " service or use e2e.eod.mode=JOBS_THEN_ADVANCE.");
            } else if (st.body().path("running").asBoolean(false)) {
                bad.add("an end-of-day run is already in progress on the batch service; wait for it to finish");
            } else {
                ok.add("batch end-of-day API reachable (mode EOD_RUN)");
            }
        }
    }

    private static JsonNode find(JsonNode array, String code) {
        for (JsonNode p : array) {
            if (code.equals(p.path("productCode").asText())) {
                return p;
            }
        }
        return null;
    }

    private static void limit(List<String> bad, Scenario s, String what, BigDecimal v, JsonNode min, JsonNode max) {
        if (min.isNumber() && v.compareTo(new BigDecimal(min.asText())) < 0) {
            bad.add(s.scenarioId() + ": " + what + " " + v + " below product minimum " + min.asText());
        }
        if (max.isNumber() && v.compareTo(new BigDecimal(max.asText())) > 0) {
            bad.add(s.scenarioId() + ": " + what + " " + v + " above product maximum " + max.asText());
        }
    }
}
