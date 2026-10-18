package com.bank.lending.e2e.teardown;

import com.bank.lending.e2e.client.AdminReadClient;
import com.bank.lending.e2e.client.LcmClient;
import com.bank.lending.e2e.client.LendingClient;
import com.bank.lending.e2e.config.E2eConfig;
import com.bank.lending.e2e.config.EnvironmentGuard;
import com.bank.lending.e2e.http.ApiResult;
import com.bank.lending.e2e.http.Http;
import com.bank.lending.e2e.run.E2eRunner;
import com.bank.lending.e2e.run.RunContext;
import com.bank.lending.e2e.run.SecuredFlow;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Teardown by run id.
 *
 * <p>Level 1 (done here, through the public API): every account of the run that is not already in a terminal state is
 * foreclosed, so no test account stays live to be picked up by scheduled batch jobs.
 * <p>Level 2 (emitted, NOT executed): lending-service has no delete API and this suite never writes business tables, so
 * the physical removal of the rows is delivered as a reviewed SQL script for a DBA / pipeline to run against the
 * non-production database. The script is keyed on the run's customer-id range and Idempotency-Key prefix.
 */
public final class Teardown {

    private Teardown() {
    }

    public static int run(E2eConfig cfg, String runId, Consumer<String> log) throws IOException {
        EnvironmentGuard.enforce(cfg);
        Path root = Path.of(cfg.require("e2e.output-dir"));
        RunContext ctx = RunContext.fromRunId(runId, cfg.getLong("e2e.id-base"), root);
        Path manifest = ctx.outputDir().resolve("run-manifest.json");
        if (!Files.exists(manifest)) {
            log.accept("No manifest at " + manifest + " - emitting the id-range SQL only.");
        }
        Http lh = E2eRunner.http(cfg, "e2e.lending.base-url", "e2e.lending.username", "e2e.lending.password");
        Http ah = E2eRunner.http(cfg, "e2e.admin.base-url", "e2e.admin.username", "e2e.admin.password");
        LendingClient lending = new LendingClient(lh);
        AdminReadClient admin = new AdminReadClient(ah);
        Set<String> terminal = Set.copyOf(cfg.getList("e2e.status.terminal"));
        LocalDate date = cfg.has("e2e.teardown.date") ? cfg.getDate("e2e.teardown.date") : defaultDate(lending);
        int closed = 0;
        int already = 0;
        int failed = 0;
        if (Files.exists(manifest)) {
            JsonNode m = Http.MAPPER.readTree(Files.readString(manifest));
            for (JsonNode a : m.path("accounts")) {
                if (!a.hasNonNull("accountId")) {
                    continue;
                }
                long id = a.get("accountId").asLong();
                ApiResult acc = admin.account(id);
                String status = acc.body().path("accountStatus").asText("?");
                if (terminal.contains(status)) {
                    already++;
                    continue;
                }
                if (cfg.getBool("e2e.dry-run")) {
                    log.accept("  [dry-run] would foreclose account " + id + " (" + status + ")");
                    continue;
                }
                ApiResult r = lending.foreclose(id, date, cfg.require("e2e.payment.channel"), ctx.runId() + "-TEARDOWN-" + id);
                if (r.ok()) {
                    closed++;
                } else {
                    failed++;
                    log.accept("  could not foreclose " + id + ": " + r.brief());
                }
            }
        }
        if (Files.exists(manifest)) {
            JsonNode m = Http.MAPPER.readTree(Files.readString(manifest));
            boolean released = !cfg.getBool("e2e.dry-run") && releaseCollateral(cfg, m, log);
            if (released || "SECURED".equals(m.path("flow").asText())) {
                Path lsql = ctx.outputDir().resolve("cleanup-" + runId + "-lcm.sql");
                Files.createDirectories(lsql.getParent());
                Files.writeString(lsql, lcmCleanupSql(ctx), StandardCharsets.UTF_8);
                log.accept("Limits and Collateral cleanup script (run on the LCM database, NON-PRODUCTION only): " + lsql.toAbsolutePath());
            }
        }
        Path sql = ctx.outputDir().resolve("cleanup-" + runId + ".sql");
        Files.createDirectories(sql.getParent());
        Files.writeString(sql, cleanupSql(ctx), StandardCharsets.UTF_8);
        log.accept("Teardown " + runId + ": foreclosed " + closed + ", already terminal " + already + ", failed " + failed);
        log.accept("Physical cleanup script (review, then run on a NON-PRODUCTION database): " + sql.toAbsolutePath());
        return failed == 0 ? 0 : 1;
    }

    /**
     * Secured runs: once the loans are closed (LCM is told through the outbox, so wait for it) release each allocation and
     * collateral and close each facility, through LCM's API with the maker and checker names. Best effort; returns true when
     * the manifest has secured accounts.
     */
    private static boolean releaseCollateral(E2eConfig cfg, JsonNode manifest, Consumer<String> log) {
        boolean any = false;
        Http lh = E2eRunner.http(cfg, "e2e.lcm.base-url", "e2e.lcm.username", "e2e.lcm.password");
        for (JsonNode a : manifest.path("accounts")) {
            if (!a.hasNonNull("collateralRef")) {
                continue;
            }
            any = true;
            if (lh == null) {
                log.accept("  e2e.lcm.base-url is not set: collateral " + a.path("collateralRef").asText() + " and facility "
                        + a.path("facilityRef").asText() + " were left as they are");
                continue;
            }
            LcmClient lcm = new LcmClient(lh);
            String facility = a.path("facilityRef").asText();
            long deadline = System.currentTimeMillis() + Long.parseLong(cfg.get("e2e.secured.settle-seconds", "20")) * 1000L;
            while (System.currentTimeMillis() < deadline) {
                ApiResult av = lcm.availability(facility);
                if (av.ok() && av.body().path("utilised").asDouble(1) == 0 && av.body().path("reserved").asDouble(1) == 0) {
                    break;
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            SecuredFlow.release(lcm, cfg.get("e2e.lcm.maker", "e2e-maker"), cfg.get("e2e.lcm.checker", "e2e-checker"),
                    a.path("collateralRef").asText(), facility, log);
        }
        return any;
    }

    /** SQL for the LCM database, scoped by the run's reference prefix. Not executed by the suite. */
    public static String lcmCleanupSql(RunContext ctx) {
        String f = "(SELECT id FROM facility WHERE facility_ref LIKE '" + ctx.runId() + "-%')";
        String c = "(SELECT id FROM collateral WHERE collateral_ref LIKE '" + ctx.runId() + "-%')";
        return "-- Limits and Collateral cleanup for E2E run " + ctx.runId() + "\n"
                + "-- Generated by lending-e2e-test-service. NOT executed by the suite. Run ONLY on a non-production LCM database\n"
                + "-- (schema lcm), after the lending cleanup script. Facilities are '" + ctx.runId() + "-F<n>', collateral '" + ctx.runId() + "-C<n>'.\n"
                + "-- The lending-service Idempotency-Keys that LCM stored are random and cannot be scoped to the run; they are left in place.\n\n"
                + "BEGIN;\n"
                + "DELETE FROM breach WHERE facility_id IN " + f + " OR collateral_id IN " + c + ";\n"
                + "DELETE FROM limit_txn WHERE facility_id IN " + f + " OR origin_facility_id IN " + f + ";\n"
                + "DELETE FROM reservation WHERE facility_id IN " + f + ";\n"
                + "DELETE FROM utilisation_position WHERE facility_id IN " + f + ";\n"
                + "DELETE FROM collateral_allocation WHERE collateral_id IN " + c + " OR facility_id IN " + f + ";\n"
                + "DELETE FROM collateral_valuation WHERE collateral_id IN " + c + ";\n"
                + "DELETE FROM facility_change WHERE facility_id IN " + f + ";\n"
                + "DELETE FROM facility_excess WHERE facility_id IN " + f + ";\n"
                + "DELETE FROM collateral WHERE collateral_ref LIKE '" + ctx.runId() + "-%';\n"
                + "DELETE FROM facility WHERE facility_ref LIKE '" + ctx.runId() + "-%';\n"
                + "DELETE FROM lcm_event WHERE aggregate_ref LIKE '" + ctx.runId() + "-%';\n"
                + "DELETE FROM audit_log WHERE entity_ref LIKE '" + ctx.runId() + "-%';\n"
                + "COMMIT;\n";
    }

    /** Foreclosure value date: the platform's business date (a later date would be rejected), else today. */
    private static LocalDate defaultDate(LendingClient lending) {
        ApiResult r = lending.businessDate();
        if (r.ok()) {
            try {
                return LocalDate.parse(r.body().path("businessDate").asText());
            } catch (RuntimeException ignored) {
                // fall through to the wall clock
            }
        }
        return LocalDate.now();
    }

    public static String cleanupSql(RunContext ctx) {
        String ids = "(SELECT account_id FROM loan_account WHERE customer_id BETWEEN " + ctx.rangeLow() + " AND " + ctx.rangeHigh() + ")";
        return "-- Cleanup for E2E run " + ctx.runId() + "\n"
                + "-- Generated by lending-e2e-test-service. NOT executed by the suite. Review, then run ONLY against a non-production database\n"
                + "-- with a role that is allowed to delete from the lending schema.\n"
                + "-- Test accounts are identified by customer_id in [" + ctx.rangeLow() + ", " + ctx.rangeHigh() + "] and idempotency keys starting '" + ctx.runId() + "-'.\n"
                + "-- Batch execution history (batch schema) is not tagged per run and is left in place.\n\n"
                + "BEGIN;\n"
                + "-- accounting journals are append-only; this transaction opts in to the purge (see V16, acct_block_mutation)\n"
                + "SELECT set_config('acct.allow_purge', 'on', true);\n"
                + "DELETE FROM acct_journal_reversal WHERE original_journal_id IN (SELECT journal_id FROM acct_journal WHERE account_id IN " + ids + ")\n"
                + "    OR reversal_journal_id IN (SELECT journal_id FROM acct_journal WHERE account_id IN " + ids + ");\n"
                + "DELETE FROM acct_idempotency WHERE journal_id IN (SELECT journal_id FROM acct_journal WHERE account_id IN " + ids + ");\n"
                + "DELETE FROM acct_journal_line WHERE account_id IN " + ids + ";\n"
                + "DELETE FROM acct_journal WHERE account_id IN " + ids + ";\n"
                + "DELETE FROM acct_accrual_carry WHERE account_id IN " + ids + ";\n"
                + "DELETE FROM acct_posting_exception WHERE account_id IN " + ids + ";\n"
                + "DELETE FROM limit_outbox WHERE account_id IN " + ids + ";\n"
                + "DELETE FROM ledger_outbox WHERE account_id IN " + ids + ";\n"
                + "DELETE FROM payment_allocation WHERE account_id IN " + ids + ";\n"
                + "DELETE FROM penal_interest WHERE account_id IN " + ids + ";\n"
                + "DELETE FROM loan_demand WHERE account_id IN " + ids + ";\n"
                + "DELETE FROM emi_transaction WHERE account_id IN " + ids + ";\n"
                + "DELETE FROM interest_accrual WHERE account_id IN " + ids + ";\n"
                + "DELETE FROM amortization_schedule WHERE account_id IN " + ids + ";\n"
                + "DELETE FROM processed_request WHERE idempotency_key LIKE '" + ctx.runId() + "-%';\n"
                + "DELETE FROM loan_account WHERE customer_id BETWEEN " + ctx.rangeLow() + " AND " + ctx.rangeHigh() + ";\n"
                + "-- expect: the number of rows deleted from loan_account equals the number of accounts in run-manifest.json\n"
                + "COMMIT;\n";
    }
}
