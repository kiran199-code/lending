package com.bank.lending.e2e.run;

import com.bank.lending.e2e.client.AdminReadClient;
import com.bank.lending.e2e.client.BatchClient;
import com.bank.lending.e2e.client.LendingClient;
import com.bank.lending.e2e.config.E2eConfig;
import com.bank.lending.e2e.http.ApiResult;
import com.bank.lending.e2e.scenario.Scenario;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Read-only readiness checks shared by dry-run and by the start of a real run. Never writes to any system. */
public final class Preflight {

    public record Report(List<String> ok, List<String> problems) {
        public boolean passed() {
            return problems.isEmpty();
        }
    }

    private Preflight() {
    }

    public static Report run(E2eConfig cfg, LendingClient lending, BatchClient batch, AdminReadClient admin, List<Scenario> expanded) {
        List<String> ok = new ArrayList<>();
        List<String> bad = new ArrayList<>();

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
                    bad.add(s.scenarioId() + ": product " + s.productCode() + " does not exist");
                    continue;
                }
                if (!"ACTIVE".equals(p.path("status").asText())) {
                    bad.add(s.scenarioId() + ": product " + s.productCode() + " is " + p.path("status").asText());
                }
                limit(bad, s, "amount", s.amount(), p.path("minSanctionedAmount"), p.path("maxSanctionedAmount"));
                limit(bad, s, "tenure", BigDecimal.valueOf(s.tenureMonths()), p.path("minTenureMonths"), p.path("maxTenureMonths"));
                if (s.interestRate() != null) {
                    limit(bad, s, "rate", s.interestRate(), p.path("minInterestRate"), p.path("maxInterestRate"));
                }
                if ("MORTGAGE".equals(p.path("accountType").asText())) {
                    bad.add(s.scenarioId() + ": product " + s.productCode() + " is a MORTGAGE product (needs a mortgage record) - not supported by this suite");
                }
                String freq = s.repaymentFrequency() != null ? s.repaymentFrequency() : p.path("repaymentFrequency").asText("MONTHLY");
                boolean allowed = false;
                for (JsonNode a : p.path("allowedRepaymentFrequencies")) {
                    allowed |= a.asText().equals(freq);
                }
                if (p.has("allowedRepaymentFrequencies") && p.get("allowedRepaymentFrequencies").size() > 0 && !allowed) {
                    bad.add(s.scenarioId() + ": frequency " + freq + " not allowed by " + s.productCode());
                }
                int period = switch (freq) {
                    case "QUARTERLY" -> 3;
                    case "HALF_YEARLY" -> 6;
                    case "YEARLY" -> 12;
                    default -> 1;
                };
                if (s.tenureMonths() % period != 0) {
                    bad.add(s.scenarioId() + ": tenure " + s.tenureMonths() + " is not a multiple of " + freq);
                }
            }
            if (bad.isEmpty()) {
                ok.add("all " + seen.size() + " scenario(s) sit inside their product's limits");
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

        ApiResult me = admin.me();
        if (!me.ok()) {
            bad.add("admin portal not reachable / credentials rejected: " + me.brief());
        } else {
            ok.add("admin portal reachable (read-only verification surface)");
        }
        return new Report(ok, bad);
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
