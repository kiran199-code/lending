package com.bank.lending.e2e.run;

import com.bank.lending.e2e.client.AdminReadClient;
import com.bank.lending.e2e.client.BatchClient;
import com.bank.lending.e2e.client.LcmClient;
import com.bank.lending.e2e.client.LendingClient;
import com.bank.lending.e2e.config.E2eConfig;
import com.bank.lending.e2e.config.EnvironmentGuard;
import com.bank.lending.e2e.http.ApiResult;
import com.bank.lending.e2e.http.Http;
import com.bank.lending.e2e.report.Reporter;
import com.bank.lending.e2e.scenario.Scenario;
import com.bank.lending.e2e.scenario.ScenarioLoader;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Orchestrates a run: guard -> plan -> preflight -> probes -> simulation -> reports. Used by the CLI and the REST trigger. */
public final class E2eRunner {

    public record Outcome(String runId, int exitCode, String summary, Path outputDir) {
    }

    private final E2eConfig cfg;
    private final Consumer<String> log;

    public E2eRunner(E2eConfig cfg, Consumer<String> log) {
        this.cfg = cfg;
        this.log = log;
    }

    public static Http http(E2eConfig cfg, String baseUrlKey, String userKey, String passKey) {
        String url = cfg.get(baseUrlKey);
        if (url == null || url.isEmpty()) {
            return null;
        }
        return new Http(url, cfg.get(userKey), cfg.get(passKey), cfg.getSeconds("e2e.http.connect-timeout-seconds"),
                cfg.getSeconds("e2e.http.request-timeout-seconds"), cfg.getInt("e2e.http.retries"),
                Duration.ofMillis(cfg.getLong("e2e.http.retry-backoff-ms")));
    }

    public Outcome execute(boolean dryRun) throws IOException {
        // 1. the guard comes before anything touches the network
        EnvironmentGuard.enforce(cfg);

        Path root = Path.of(cfg.require("e2e.output-dir"));
        RunContext ctx = RunContext.create(cfg.getLong("e2e.id-base"), root);
        log.accept("Run id " + ctx.runId() + (dryRun ? " (DRY RUN - no writes)" : "") + ", profile " + cfg.profile());

        Http lendingHttp = http(cfg, "e2e.lending.base-url", "e2e.lending.username", "e2e.lending.password");
        Http batchHttp = http(cfg, "e2e.batch.base-url", "e2e.batch.username", "e2e.batch.password");
        Http adminHttp = http(cfg, "e2e.admin.base-url", "e2e.admin.username", "e2e.admin.password");
        if (lendingHttp == null || batchHttp == null || adminHttp == null) {
            throw new IllegalStateException("e2e.lending.base-url, e2e.batch.base-url and e2e.admin.base-url must all be set");
        }
        LendingClient lending = new LendingClient(lendingHttp);
        BatchClient batch = new BatchClient(batchHttp, cfg.getSeconds("e2e.batch.poll-timeout-seconds"),
                Duration.ofMillis(cfg.getLong("e2e.batch.poll-interval-ms")));
        AdminReadClient admin = new AdminReadClient(adminHttp);

        // Two separate flows: the unsecured flow (default) and the secured flow (collateral and facility in LCM first).
        // A scenario belongs to exactly one of them; the other flow leaves it out.
        boolean securedFlow = "SECURED".equalsIgnoreCase(cfg.get("e2e.flow", "UNSECURED"));
        LcmClient lcm = null;
        if (securedFlow) {
            Http lcmHttp = http(cfg, "e2e.lcm.base-url", "e2e.lcm.username", "e2e.lcm.password");
            if (lcmHttp == null) {
                throw new IllegalStateException("e2e.lcm.base-url must be set for the secured flow (the Limits and Collateral service)");
            }
            lcm = new LcmClient(lcmHttp);
        }
        List<Scenario> allRows = ScenarioLoader.load(cfg.require("e2e.scenario-file"));
        List<Scenario> matrix = allRows.stream().filter(sc -> SecuredFlow.isSecured(sc) == securedFlow).toList();
        if (matrix.size() < allRows.size()) {
            log.accept((allRows.size() - matrix.size()) + " scenario row(s) belong to the " + (securedFlow ? "unsecured" : "secured")
                    + " flow and are left out of this " + (securedFlow ? "secured" : "unsecured") + " run");
        }
        if (matrix.isEmpty()) {
            throw new IllegalStateException("e2e.scenario-file has no " + (securedFlow ? "secured (COLLATERAL)" : "unsecured") + " scenarios");
        }
        List<Scenario> expanded = ScenarioLoader.expand(matrix, cfg.getInt("e2e.accounts"), cfg.getInt("e2e.cohort-a-percent"));

        // Fit the plan to the products that exist, so a missing sample product never stops the run: the account is moved to the
        // closest existing product (or skipped), and every change is listed below and in the run report.
        ApiResult productList = lending.listProducts();
        List<String> planNotes = new ArrayList<>();
        List<Scenario> resolved = expanded;
        List<AccountSim> sims = new ArrayList<>();
        if (productList.ok()) {
            boolean auto = !"SKIP".equalsIgnoreCase(cfg.get("e2e.products.fallback", "AUTO"));
            ProductResolver.Result res = ProductResolver.resolve(productList.body(), expanded, auto, securedFlow);
            planNotes.addAll(res.notes());
            res.skipped().forEach(m -> planNotes.add("SKIPPED " + m));
            resolved = new ArrayList<>();
            for (int i = 0; i < res.scenarios().size(); i++) {
                Scenario sc = res.scenarios().get(i);
                if (sc != null) {
                    resolved.add(sc);
                    sims.add(new AccountSim(i + 1, sc, ctx.customerId(i + 1), ctx.sanctionRefId(i + 1)));
                }
            }
        } else {
            for (int i = 0; i < expanded.size(); i++) {
                sims.add(new AccountSim(i + 1, expanded.get(i), ctx.customerId(i + 1), ctx.sanctionRefId(i + 1)));
            }
        }

        Preflight.Report pre0 = Preflight.run(cfg, lending, batch, admin, resolved);
        List<String> problems = new ArrayList<>(pre0.problems());
        List<String> okList = new ArrayList<>(pre0.ok());
        if (securedFlow) {
            Preflight.secured(cfg, lcm, okList, problems);
        }
        if (productList.ok() && sims.isEmpty()) {
            problems.add(securedFlow
                    ? "no account can be planned: there is no ACTIVE MORTGAGE (secured) loan product. Create it first (admin portal: Maintenance -> Create base products creates HL01, or Products -> New product)."
                    : "no account can be planned: there is no ACTIVE unsecured loan product. Create products first (admin portal: Maintenance -> Create base products, or Products -> New product).");
        }
        Preflight.Report pre = new Preflight.Report(okList, problems, pre0.warnings());
        log.accept("Preflight: " + pre.ok().size() + " ok, " + pre.problems().size() + " problem(s), " + pre.warnings().size()
                + " warning(s), " + planNotes.size() + " plan change(s)");
        pre.ok().forEach(m -> log.accept("  ok    " + m));
        planNotes.forEach(m -> log.accept("  ADAPT " + m));
        pre.warnings().forEach(m -> log.accept("  WARN  " + m));
        pre.problems().forEach(m -> log.accept("  FIX   " + m));

        if (dryRun) {
            printPlan(sims, ctx);
            return new Outcome(ctx.runId(), pre.passed() ? 0 : 2, "Dry run " + (pre.passed() ? "passed" : "found problems"), ctx.outputDir());
        }
        if (!pre.passed()) {
            return new Outcome(ctx.runId(), 2, "Preflight failed - nothing was created. Fix the items above or run dry-run.", ctx.outputDir());
        }

        Instant started = Instant.now();
        Findings findings = new Findings();
        Map<String, String> caps = CapabilityProbes.run(cfg, findings, lendingHttp, adminHttp, batchHttp, cfg.getDate("e2e.start-date"));
        List<String> deviations = new ArrayList<>();
        planNotes.forEach(m -> deviations.add("Plan adapted to the products that exist - " + m));
        pre.warnings().forEach(m -> deviations.add("Preflight warning (run went ahead) - " + m));
        if ("API".equalsIgnoreCase(cfg.get("e2e.accrual.path"))) {
            deviations.add("e2e.accrual.path=API: accrual was triggered through lending-service directly, NOT through the batch service.");
        }
        if ("OFF".equalsIgnoreCase(cfg.get("e2e.business-date.mode", "MANAGED"))) {
            deviations.add("e2e.business-date.mode=OFF: the platform business date was NOT driven by the suite; dates were only passed in requests.");
        }
        if ("API".equalsIgnoreCase(cfg.get("e2e.dpd.path"))) {
            deviations.add("e2e.dpd.path=API: delinquency classification was triggered through lending-service directly, NOT through the batch service.");
        }

        Simulation sim = new Simulation(cfg, ctx, findings, lending, batch, admin, sims, log);
        if (securedFlow) {
            sim.withSecuredFlow(new SecuredFlow(cfg, ctx, findings, lcm, log));
        }
        Reporter.RunFacts partial = new Reporter.RunFacts("RUN", started, null, 0, cfg.getDate("e2e.start-date"), null, 0, caps, pre.ok(), deviations);
        Reporter.write(cfg, ctx, findings, sims, partial); // manifest exists even if the run crashes
        try {
            sim.run();
        } finally {
            if (sim.haltReason() != null) {
                deviations.add("Run HALTED early: " + sim.haltReason() + ". Accounts still mid-lifecycle were not carried to closure.");
            }
            if (!sim.truncated().isEmpty()) {
                deviations.add("Run stopped by e2e.max-sim-days before " + sim.truncated().size() + " account(s) finished their lifecycle ("
                        + String.join(", ", sim.truncated()) + "); their closure was not asserted. Raise e2e.max-sim-days.");
            }
            Reporter.RunFacts facts = new Reporter.RunFacts("RUN", started, Instant.now(), sim.daysRun(), cfg.getDate("e2e.start-date"),
                    sim.lastDay(), sim.batchTriggers(), caps, pre.ok(), deviations);
            Reporter.write(cfg, ctx, findings, sims, facts);
        }
        Findings.Severity threshold = Findings.Severity.valueOf(cfg.get("e2e.fail-on-severity", "HIGH").toUpperCase());
        boolean fail = findings.anyAtOrAbove(threshold, cfg.getBool("e2e.fail-on-not-found"));
        String summary = "Finished " + sim.daysRun() + " simulated days; results in " + ctx.outputDir().toAbsolutePath()
                + (fail ? " - FAILED at severity >= " + threshold : " - no check at severity >= " + threshold + " failed");
        return new Outcome(ctx.runId(), fail ? 1 : 0, summary, ctx.outputDir());
    }

    private void printPlan(List<AccountSim> sims, RunContext ctx) {
        log.accept("Plan: " + sims.size() + " accounts, simulated start " + cfg.get("e2e.start-date") + ", ids " + ctx.rangeLow() + ".." + ctx.rangeHigh());
        int longest = 0;
        for (AccountSim s : sims) {
            Scenario sc = s.scenario;
            log.accept(String.format("  #%-3d %-4s cohort %s %-5s %,12.0f x %2dm  %-22s %s", s.index, sc.scenarioId(), sc.cohort(), sc.productCode(),
                    sc.amount(), sc.tenureMonths(), sc.behaviour(), sc.behaviourParams()));
            longest = Math.max(longest, sc.tenureMonths() * 31 + sc.observeDaysAfterLastDue());
        }
        int days = Math.min(longest, cfg.getInt("e2e.max-sim-days"));
        int jobs = cfg.getList("e2e.batch.daily-jobs").size();
        log.accept("Estimate: up to ~" + days + " simulated days, ~" + (days * jobs) + " batch triggers (+polls), ~"
                + ((long) days * sims.size() * 2) + " read calls. Raise e2e.verify.every-n-days to cut verification calls.");
    }
}
