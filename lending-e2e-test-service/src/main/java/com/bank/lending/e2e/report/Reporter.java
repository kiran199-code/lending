package com.bank.lending.e2e.report;

import com.bank.lending.e2e.config.E2eConfig;
import com.bank.lending.e2e.http.Http;
import com.bank.lending.e2e.run.AccountSim;
import com.bank.lending.e2e.run.Findings;
import com.bank.lending.e2e.run.Findings.CheckDef;
import com.bank.lending.e2e.run.Findings.Finding;
import com.bank.lending.e2e.run.Findings.Severity;
import com.bank.lending.e2e.run.Findings.Status;
import com.bank.lending.e2e.run.RunContext;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Writes the run report, the ranked defect list, the full check log and the run manifest. */
public final class Reporter {

    public record RunFacts(String mode, Instant startedAt, Instant finishedAt, int days, LocalDate firstDay, LocalDate lastDay,
                           long batchTriggers, Map<String, String> capabilities, List<String> preflightOk, List<String> deviations) {
    }

    private Reporter() {
    }

    public static void write(E2eConfig cfg, RunContext ctx, Findings f, List<AccountSim> sims, RunFacts facts) throws IOException {
        Files.createDirectories(ctx.outputDir());
        writeChecks(ctx, f);
        writeManifest(cfg, ctx, sims, facts);
        Files.writeString(ctx.outputDir().resolve("defects.md"), defects(ctx, f), StandardCharsets.UTF_8);
        Files.writeString(ctx.outputDir().resolve("run-report.md"), report(cfg, ctx, f, sims, facts), StandardCharsets.UTF_8);
    }

    // --------------------------------------------------------------------------------------------- checks.csv

    private static void writeChecks(RunContext ctx, Findings f) {
        try (AuditWriter w = new AuditWriter(ctx.outputDir().resolve("checks.csv"),
                List.of("checkId", "severity", "status", "scenario", "account", "businessDate", "expected", "actual", "message"))) {
            for (Finding x : f.all()) {
                w.row(List.of(x.checkId(), f.def(x.checkId()).severity(), x.status(), nz(x.scenarioId()), nz(x.accountNumber()),
                        x.businessDate() == null ? "" : x.businessDate(), nz(x.expected()), nz(x.actual()), nz(x.message())));
            }
        }
    }

    // --------------------------------------------------------------------------------------------- manifest

    private static void writeManifest(E2eConfig cfg, RunContext ctx, List<AccountSim> sims, RunFacts facts) throws IOException {
        ObjectNode root = Http.MAPPER.createObjectNode();
        root.put("runId", ctx.runId());
        root.put("mode", facts.mode());
        root.put("startedAt", facts.startedAt().toString());
        root.put("finishedAt", facts.finishedAt() == null ? "" : facts.finishedAt().toString());
        root.put("profile", cfg.profile());
        root.put("flow", cfg.get("e2e.flow", "UNSECURED").toUpperCase());
        root.put("environment", cfg.get("e2e.env.name", ""));
        ObjectNode range = root.putObject("taggedIdRange");
        range.put("customerIdLow", ctx.rangeLow());
        range.put("customerIdHigh", ctx.rangeHigh());
        range.put("idempotencyKeyPrefix", ctx.runId() + "-");
        ArrayNode accounts = root.putArray("accounts");
        for (AccountSim s : sims) {
            ObjectNode a = accounts.addObject();
            a.put("index", s.index);
            a.put("scenarioId", s.scenario.scenarioId());
            a.put("cohort", s.scenario.cohort().name());
            a.put("customerId", s.customerId);
            a.put("sanctionRefId", s.sanctionRefId);
            if (s.accountId != null) {
                a.put("accountId", s.accountId);
                a.put("accountNumber", s.accountNumber);
            }
            if (s.secured()) {
                a.put("collateralRef", s.collateralRef);
                a.put("facilityRef", s.facilityRef);
                if (s.allocationId != null) {
                    a.put("allocationId", s.allocationId);
                }
            }
            a.put("finalStatus", s.lastStatus);
            a.put("closed", s.closed);
            a.put("setupFailed", s.setupFailed);
        }
        ObjectNode settings = root.putObject("effectiveSettings");
        cfg.effectiveMasked().forEach(settings::put);
        Files.writeString(ctx.outputDir().resolve("run-manifest.json"),
                Http.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root), StandardCharsets.UTF_8);
    }

    // --------------------------------------------------------------------------------------------- defects

    record Defect(String id, CheckDef def, Status status, long occurrences, int passes, Set<String> scenarios, Set<String> accounts,
                  LocalDate first, LocalDate last, Finding example) {
    }

    static List<Defect> aggregate(Findings f) {
        Map<String, List<Finding>> byCheck = new LinkedHashMap<>();
        for (Finding x : f.all()) {
            if (x.status() == Status.FAIL || x.status() == Status.NOT_FOUND) {
                byCheck.computeIfAbsent(x.checkId(), k -> new ArrayList<>()).add(x);
            }
        }
        List<Defect> out = new ArrayList<>();
        for (Map.Entry<String, List<Finding>> e : byCheck.entrySet()) {
            List<Finding> l = e.getValue();
            Status st = l.stream().anyMatch(x -> x.status() == Status.FAIL) ? Status.FAIL : Status.NOT_FOUND;
            Set<String> sc = l.stream().map(Finding::scenarioId).filter(s -> s != null && !s.equals("-") && !s.isEmpty())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Set<String> ac = l.stream().map(Finding::accountNumber).filter(s -> s != null && !s.equals("-") && !s.isEmpty())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            LocalDate first = l.stream().map(Finding::businessDate).filter(d -> d != null).min(Comparator.naturalOrder()).orElse(null);
            LocalDate last = l.stream().map(Finding::businessDate).filter(d -> d != null).max(Comparator.naturalOrder()).orElse(null);
            out.add(new Defect("", f.def(e.getKey()), st, l.size(), f.passes(e.getKey()), sc, ac, first, last, l.get(0)));
        }
        out.sort(Comparator.comparing((Defect d) -> d.def().severity()).thenComparing(d -> -d.occurrences()));
        List<Defect> numbered = new ArrayList<>();
        int n = 1;
        for (Defect d : out) {
            numbered.add(new Defect(String.format("D-%03d", n++), d.def(), d.status(), d.occurrences(), d.passes(), d.scenarios(), d.accounts(),
                    d.first(), d.last(), d.example()));
        }
        return numbered;
    }

    private static String defects(RunContext ctx, Findings f) {
        List<Defect> ds = aggregate(f);
        StringBuilder sb = new StringBuilder();
        sb.append("# Defect list - run ").append(ctx.runId()).append("\n\n");
        sb.append("Ranked by severity, then by number of occurrences. Every entry is derived from a failed or NOT FOUND check; ")
                .append("the full evidence is in `checks.csv` and the day-by-day trail in `audit-trail.csv`.\n\n");
        if (ds.isEmpty()) {
            sb.append("No failed or NOT FOUND checks in this run.\n");
            return sb.toString();
        }
        sb.append("| # | Severity | Check | Area | Result | Occurrences | Passed | Title |\n|---|---|---|---|---|---|---|---|\n");
        for (Defect d : ds) {
            sb.append("| ").append(d.id()).append(" | ").append(d.def().severity()).append(" | ").append(d.def().id()).append(" | ")
                    .append(d.def().area()).append(" | ").append(d.status() == Status.NOT_FOUND ? "NOT FOUND" : "FAIL").append(" | ")
                    .append(d.occurrences()).append(" | ").append(d.passes()).append(" | ").append(d.def().title()).append(" |\n");
        }
        sb.append('\n');
        for (Defect d : ds) {
            sb.append("## ").append(d.id()).append(" - ").append(d.def().title()).append(" (").append(d.def().severity()).append(")\n\n");
            sb.append("- Check: `").append(d.def().id()).append("` - ").append(d.def().expectation()).append('\n');
            sb.append("- Result: ").append(d.status() == Status.NOT_FOUND ? "NOT FOUND (capability not present in the platform)" : "FAIL")
                    .append(", ").append(d.occurrences()).append(" occurrence(s), ").append(d.passes()).append(" pass(es)\n");
            if (d.first() != null) {
                sb.append("- Simulated dates: ").append(d.first()).append(" .. ").append(d.last()).append('\n');
            }
            if (!d.scenarios().isEmpty()) {
                sb.append("- Scenarios: ").append(String.join(", ", d.scenarios())).append('\n');
            }
            if (!d.accounts().isEmpty()) {
                sb.append("- Accounts (").append(d.accounts().size()).append("): ")
                        .append(d.accounts().stream().limit(8).collect(Collectors.joining(", ")))
                        .append(d.accounts().size() > 8 ? ", ..." : "").append('\n');
            }
            Finding e = d.example();
            if (e.message() != null && !e.message().isEmpty()) {
                sb.append("- What happened: ").append(e.message()).append('\n');
            }
            if (e.expected() != null && !e.expected().isEmpty()) {
                sb.append("- Expected: ").append(e.expected()).append('\n');
                sb.append("- Actual: ").append(e.actual()).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    // --------------------------------------------------------------------------------------------- run report

    private static String report(E2eConfig cfg, RunContext ctx, Findings f, List<AccountSim> sims, RunFacts facts) {
        StringBuilder sb = new StringBuilder();
        boolean securedFlow = "SECURED".equalsIgnoreCase(cfg.get("e2e.flow", "UNSECURED"));
        sb.append(securedFlow ? "# Lending lifecycle E2E run report - SECURED flow (collateral, facility, loan, end of day)\n\n"
                : "# Lending lifecycle E2E run report - UNSECURED flow\n\n");
        sb.append("- Run id: `").append(ctx.runId()).append("`\n");
        sb.append("- Mode: ").append(facts.mode()).append("\n");
        sb.append("- Environment: ").append(cfg.get("e2e.env.name", "?")).append(" (profile ").append(cfg.profile()).append(")\n");
        sb.append("- Started: ").append(facts.startedAt()).append(", finished: ").append(facts.finishedAt()).append("\n");
        sb.append("- Simulated period: ").append(facts.firstDay()).append(" to ").append(facts.lastDay()).append(" (").append(facts.days())
                .append(" business days), batch triggers: ").append(facts.batchTriggers()).append("\n");
        sb.append("- Accounts: ").append(sims.size()).append(" (cohort A ").append(sims.stream().filter(s -> s.scenario.cohort().name().equals("A")).count())
                .append(", cohort B ").append(sims.stream().filter(s -> s.scenario.cohort().name().equals("B")).count()).append(")\n\n");

        List<Defect> ds = aggregate(f);
        sb.append("## Verdict\n\n");
        for (Severity sev : List.of(Severity.CRITICAL, Severity.HIGH, Severity.MEDIUM, Severity.LOW)) {
            long fails = ds.stream().filter(d -> d.def().severity() == sev && d.status() == Status.FAIL).count();
            long nf = ds.stream().filter(d -> d.def().severity() == sev && d.status() == Status.NOT_FOUND).count();
            sb.append("- ").append(sev).append(": ").append(fails).append(" failing check(s), ").append(nf).append(" NOT FOUND capability(ies)\n");
        }
        sb.append('\n');

        if (!facts.deviations().isEmpty()) {
            sb.append("## Deviations from the agreed path (read first)\n\n");
            facts.deviations().forEach(d -> sb.append("- ").append(d).append('\n'));
            sb.append('\n');
        }

        sb.append("## Accounts\n\n| # | Scenario | Cohort | Account | Product | Behaviour | Final status | Closed | Days | Failed checks |\n|---|---|---|---|---|---|---|---|---|---|\n");
        for (AccountSim s : sims) {
            long fails = f.all().stream().filter(x -> x.status() == Status.FAIL && s.accountNumber != null && s.accountNumber.equals(x.accountNumber())).count();
            if (s.setupFailed) {
                fails = f.all().stream().filter(x -> x.status() == Status.FAIL && s.label().equals(x.accountNumber())).count();
            }
            sb.append("| ").append(s.index).append(" | ").append(s.scenario.scenarioId()).append(" | ").append(s.scenario.cohort()).append(" | ")
                    .append(s.accountNumber == null ? "(setup failed)" : s.accountNumber).append(" | ").append(s.scenario.productCode()).append(" | ")
                    .append(s.scenario.behaviour()).append(" | ").append(s.lastStatus).append(" | ").append(s.closed ? "yes" : "no").append(" | ")
                    .append(s.closedOn == null ? "" : s.closedOn).append(" | ").append(fails).append(" |\n");
        }
        sb.append('\n');

        sb.append("## Checks\n\n| Check | Severity | Passed | Failed | Not found | Title |\n|---|---|---|---|---|---|\n");
        for (CheckDef d : f.catalogue().values()) {
            long fail = f.all().stream().filter(x -> x.checkId().equals(d.id()) && x.status() == Status.FAIL).count();
            long nf = f.all().stream().filter(x -> x.checkId().equals(d.id()) && x.status() == Status.NOT_FOUND).count();
            long sk = f.all().stream().filter(x -> x.checkId().equals(d.id()) && x.status() == Status.SKIPPED).count();
            int pass = f.passes(d.id());
            if (pass + fail + nf + sk == 0) {
                continue;
            }
            sb.append("| ").append(d.id()).append(" | ").append(d.severity()).append(" | ").append(pass).append(" | ").append(fail).append(" | ")
                    .append(nf).append(sk > 0 ? " (+" + sk + " unverified)" : "").append(" | ").append(d.title()).append(" |\n");
        }
        sb.append('\n');

        sb.append("## Capabilities (from the services' published API)\n\n");
        facts.capabilities().forEach((k, v) -> sb.append("- ").append(k).append(": ").append(v).append('\n'));
        sb.append("\nScenarios whose `requiresFeatures` include a NOT FOUND capability are reported as gaps in the defect list, not as passes.\n\n");

        sb.append("## Settings that were assumed (labelled defaults)\n\n");
        for (String k : List.of("e2e.accounts", "e2e.cohort-a-percent", "e2e.start-date", "e2e.day.order", "e2e.dpd.buckets", "e2e.dpd.npa-from-days",
                "e2e.accrual.path", "e2e.dpd.path", "e2e.tolerance.money", "e2e.tolerance.accrual-daily", "e2e.batch.daily-jobs")) {
            sb.append("- `").append(k).append("` = ").append(cfg.get(k, "")).append('\n');
        }
        sb.append("\n## Open questions\n\n");
        sb.append("1. Which principal should the daily accrual use - opening or closing balance of the day? The suite assumes `")
                .append(cfg.get("e2e.day.order")).append("` (set `e2e.day.order`).\n");
        sb.append("2. Confirm DPD bucket boundaries and the NPA cut-off. The suite uses the platform's current hard-coded values as a labelled default.\n");
        sb.append("3. Is the payment channel an API or a file/batch channel in the target environment? The suite posts through the API; ")
                .append("a file channel needs the AUTO_DEBIT / collections job to be implemented first.\n");
        sb.append("4. Provisioning percentages, NPA interest-suspension and penal-interest rules are not defined in the platform; supply the policy before those can be asserted.\n");
        return sb.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
