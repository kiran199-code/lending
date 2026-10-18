package com.bank.lending.e2e.run;

import com.bank.lending.e2e.calc.DayCount;
import com.bank.lending.e2e.calc.DpdModel;
import com.bank.lending.e2e.calc.ScheduleInvariants;
import com.bank.lending.e2e.client.AdminReadClient;
import com.bank.lending.e2e.client.BatchClient;
import com.bank.lending.e2e.client.LendingClient;
import com.bank.lending.e2e.config.E2eConfig;
import com.bank.lending.e2e.http.ApiResult;
import com.bank.lending.e2e.http.Http;
import com.bank.lending.e2e.report.AuditWriter;
import com.bank.lending.e2e.scenario.Scenario;
import com.bank.lending.e2e.scenario.Scenario.Behaviour;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The day-by-day lifecycle driver.
 *
 * <p>Each simulated business day: the borrowers behave (payments through the public API with the day as value
 * date), the REAL batch jobs are triggered for that business date and polled to a terminal state, and then every
 * live account is verified through read-only calls against the test's own ledger. Nothing here re-implements batch
 * logic and nothing writes a business table.
 *
 * <p>Business date (e2e.business-date.mode=MANAGED, the default): lending-service owns the date. Before the first day
 * the suite sets it to e2e.start-date through the public API; each simulated day it checks the platform is on that day,
 * posts the day's payments (value date = the business date), runs end of day (the batch service's
 * {@code POST /batch-admin/eod/run}, which runs the daily jobs and advances the date), and checks the date moved by
 * exactly one day. The admin portal's figures are then compared with the business date, not with the wall clock.
 */
public final class Simulation {

    private final E2eConfig cfg;
    private final RunContext ctx;
    private final Findings findings;
    private final LendingClient lending;
    private final BatchClient batch;
    private final AdminReadClient admin;
    private final List<AccountSim> sims;
    private final DpdModel dpdModel;
    private final Consumer<String> log;

    private final BigDecimal tolMoney;
    private final BigDecimal tolAccrual;
    private final Set<String> terminal;
    private final Set<String> closedOk;
    private final String channel;
    private final boolean payThenEod;
    private final boolean accrualViaApi;
    private final boolean dpdViaApi;
    private final int npaFrom;
    private final boolean managed;
    private final boolean eodRun;
    private final java.time.Duration eodTimeout;

    /** The platform business date as last observed (MANAGED mode only). */
    private LocalDate currentBd;
    private String haltReason;

    private AuditWriter audit;
    private AuditWriter batchLog;
    private int daysRun;
    private LocalDate lastDay;
    private boolean rerunChecked;
    private long batchTriggers;
    private final List<String> truncated = new ArrayList<>();
    private SecuredFlow secured; // set only for the secured flow; null = unsecured flow

    /** Switches on the collateral / facility steps for scenarios marked COLLATERAL. */
    public Simulation withSecuredFlow(SecuredFlow flow) {
        this.secured = flow;
        return this;
    }

    public Simulation(E2eConfig cfg, RunContext ctx, Findings findings, LendingClient lending, BatchClient batch,
                      AdminReadClient admin, List<AccountSim> sims, Consumer<String> log) {
        this.cfg = cfg;
        this.ctx = ctx;
        this.findings = findings;
        this.lending = lending;
        this.batch = batch;
        this.admin = admin;
        this.sims = sims;
        this.log = log;
        this.dpdModel = new DpdModel(cfg.require("e2e.dpd.buckets"));
        this.tolMoney = cfg.getDecimal("e2e.tolerance.money");
        this.tolAccrual = cfg.getDecimal("e2e.tolerance.accrual-daily");
        this.terminal = Set.copyOf(cfg.getList("e2e.status.terminal"));
        this.closedOk = Set.copyOf(cfg.getList("e2e.status.closed-ok"));
        this.channel = cfg.require("e2e.payment.channel");
        this.payThenEod = "PAY_THEN_EOD".equalsIgnoreCase(cfg.require("e2e.day.order"));
        this.accrualViaApi = "API".equalsIgnoreCase(cfg.require("e2e.accrual.path"));
        this.dpdViaApi = "API".equalsIgnoreCase(cfg.require("e2e.dpd.path"));
        this.npaFrom = cfg.getInt("e2e.dpd.npa-from-days");
        this.managed = "MANAGED".equalsIgnoreCase(cfg.get("e2e.business-date.mode", "MANAGED"));
        this.eodRun = "EOD_RUN".equalsIgnoreCase(cfg.get("e2e.eod.mode", "EOD_RUN"));
        this.eodTimeout = java.time.Duration.ofSeconds(Long.parseLong(cfg.get("e2e.eod.poll-timeout-seconds", "900")));
    }

    /** Why the run stopped early (business date out of step, end of day failed...), or null. */
    public String haltReason() {
        return haltReason;
    }

    public int daysRun() {
        return daysRun;
    }

    public LocalDate lastDay() {
        return lastDay;
    }

    /** Accounts that were still mid-lifecycle when e2e.max-sim-days ended the run. */
    public List<String> truncated() {
        return truncated;
    }

    public long batchTriggers() {
        return batchTriggers;
    }

    // ===================================================================================================== run

    public void run() {
        Path dir = ctx.outputDir();
        audit = new AuditWriter(dir.resolve("audit-trail.csv"), List.of("runId", "dayNo", "businessDate", "scenarioId", "cohort",
                "accountNumber", "accountId", "events", "modelOutstanding", "sysOutstanding", "modelDpd", "expectedStatus",
                "sysStatus", "expectedAccrual", "sysAccrual", "sysAccrualPrincipal", "cumSysAccrued", "failedChecksToday", "facilityUtilised"));
        batchLog = new AuditWriter(dir.resolve("batch-runs.csv"), List.of("runId", "businessDate", "job", "triggerHttp", "status",
                "exitCode", "executionId", "read", "write", "skip", "elapsedMs", "timedOut"));
        try {
            setup();
            if (haltReason == null) {
                loop();
            }
            finish();
        } finally {
            audit.close();
            batchLog.close();
        }
    }

    // ===================================================================================================== setup

    private void setup() {
        LocalDate start = cfg.getDate("e2e.start-date");
        log.accept("Setup: creating and disbursing " + sims.size() + " accounts, simulated start " + start);
        if (managed && !establishBusinessDate(start)) {
            return;
        }
        for (AccountSim s : sims) {
            try {
                setupOne(s, start);
            } catch (RuntimeException e) {
                // one account's trouble never stops the others
                s.setupFailed = true;
                findings.fail("SETUP-03", s.scenario.scenarioId(), s.accountNumber != null ? s.accountNumber : s.label(), start,
                        "account set up", e.getClass().getSimpleName() + ": " + e.getMessage(), "unexpected error while setting up the account");
            }
        }
        long failed = sims.stream().filter(a -> a.setupFailed).count();
        log.accept("Setup done: " + (sims.size() - failed) + " account(s) ready" + (failed > 0
                ? ", " + failed + " could not be set up (see checks.csv / defects.md, rows SETUP-01..03); the run carries on with the rest" : ""));
    }

    /** Creates, disburses and checks the schedule of one account. A failure marks the account and returns; it never throws on bad data. */
    private void setupOne(AccountSim s, LocalDate start) {
        {
            Scenario sc = s.scenario;
            boolean isSecured = secured != null && SecuredFlow.isSecured(sc);
            if (isSecured && !secured.prepare(s, start)) {
                s.setupFailed = true;
                return;
            }
            ObjectNode body = Http.MAPPER.createObjectNode();
            body.put("customerId", s.customerId);
            body.put("sanctionRefId", s.sanctionRefId);
            body.put("productCode", sc.productCode());
            body.put("sanctionedAmount", sc.amount());
            body.put("tenureMonths", sc.tenureMonths());
            putIf(body, "interestRate", sc.interestRate());
            putIf(body, "dayCountConvention", sc.dayCount());
            putIf(body, "repaymentFrequency", sc.repaymentFrequency());
            if (sc.moratoriumMonths() != null) {
                body.put("moratoriumMonths", sc.moratoriumMonths());
            }
            putIf(body, "balloonPercent", sc.balloonPercent());
            putIf(body, "stepUpPercent", sc.stepUpPercent());
            if (isSecured) {
                body.put("limitFacilityRef", s.facilityRef);
            }
            ApiResult created = lending.createAccount(body);
            if (!findings.check(created.ok() && created.body().hasNonNull("accountId"), "SETUP-01", sc.scenarioId(), s.label(), start,
                    "2xx with accountId", created.brief(), "create account failed")) {
                s.setupFailed = true;
                return;
            }
            s.accountId = created.body().get("accountId").asLong();
            s.accountNumber = created.body().path("accountNumber").asText(String.valueOf(s.accountId));
            JsonNode acc = created.body();
            String freq = acc.path("repaymentFrequency").asText("MONTHLY");
            int period = ScheduleInvariants.periodMonths(freq);
            LocalDate firstDue = start.plusMonths((long) period * cfg.getInt("e2e.disburse.first-due-in-periods"));
            ApiResult disb = null;
            if (isSecured) {
                ApiResult[] wrong = new ApiResult[1];
                SecuredFlow.Disbursal d = secured.refuseUntilCovered(s, start, lending, sc.amount(), firstDue, wrong);
                if (d == SecuredFlow.Disbursal.ACCEPTED) {
                    disb = wrong[0]; // already disbursed by mistake: carry on with what the platform did
                }
                if (!secured.cover(s, start)) {
                    s.setupFailed = true;
                    return;
                }
            }
            if (disb == null) {
                disb = lending.disburse(s.accountId, sc.amount(), firstDue);
            }
            if (isSecured) {
                findings.check(disb.ok() && "ACTIVE".equals(disb.body().path("accountStatus").asText()), "SEC-07", sc.scenarioId(),
                        s.accountNumber, start, "200 and ACTIVE after the collateral was allocated to " + s.facilityRef, disb.brief(),
                        "a secured loan with active, allocated collateral could not be disbursed");
            }
            if (!findings.check(disb.ok(), "SETUP-02", sc.scenarioId(), s.accountNumber, start, "2xx", disb.brief(), "disbursal failed")) {
                s.setupFailed = true;
                return;
            }
            JsonNode d = disb.body();
            s.terms = new ScheduleInvariants.Terms(
                    d.path("scheduleType").asText(acc.path("scheduleType").asText("EMI")), freq, sc.tenureMonths(),
                    new BigDecimal(d.path("disbursedAmount").asText(sc.amount().toPlainString())),
                    new BigDecimal(d.path("interestRate").asText(acc.path("interestRate").asText("0"))),
                    DayCount.Convention.parse(d.path("dayCountConvention").asText(acc.path("dayCountConvention").asText("ACT_365"))),
                    d.path("moratoriumMonths").asInt(acc.path("moratoriumMonths").asInt(0)),
                    dec(d, "balloonPercent"), dec(d, "stepUpPercent"), firstDue);
            s.modelOutstanding = s.terms.disbursed();
            s.openingOutstanding = s.terms.disbursed();
            if (isSecured) {
                secured.afterDisbursal(s, start, s.terms.disbursed());
            }
            ApiResult sched = lending.schedule(s.accountId);
            if (sched.ok() && sched.body().isArray()) {
                s.rows = ScheduleInvariants.rows(sched.body());
                List<String> viol = ScheduleInvariants.verify(s.terms, s.rows, tolMoney);
                findings.check(viol.isEmpty(), "SCH-01", sc.scenarioId(), s.accountNumber, start, "consistent schedule",
                        String.join(" | ", viol), "schedule invariants violated");
                if ("EMI".equals(s.terms.scheduleType()) && d.hasNonNull("emiAmount") && s.terms.moratoriumMonths() == 0 && !s.rows.isEmpty()) {
                    BigDecimal emi = new BigDecimal(d.get("emiAmount").asText());
                    BigDecimal first = s.rows.get(0).emi();
                    findings.check(emi.subtract(first).abs().compareTo(tolMoney) <= 0, "SCH-02", sc.scenarioId(), s.accountNumber, start,
                            first.toPlainString(), emi.toPlainString(), "account EMI differs from first instalment");
                }
            } else {
                findings.fail("SETUP-02", sc.scenarioId(), s.accountNumber, start, "schedule readable", sched.brief(), "cannot read schedule");
                s.setupFailed = true;
                return;
            }
            s.endDate = computeEnd(s);
            int horizon = sc.intParam("horizonDays", 0);
            if (horizon > 0 && start.plusDays(horizon).isBefore(s.endDate)) {
                s.endDate = start.plusDays(horizon); // a short window on a long loan; closure is not expected inside it
                s.horizonCapped = true;
            }
            statusSeen(s, d.path("accountStatus").asText("ACTIVE"));
        }
    }

    /** Last simulated day for an account: scenario-specific, derived from the original schedule. */
    private LocalDate computeEnd(AccountSim s) {
        Scenario sc = s.scenario;
        LocalDate lastDue = s.rows.get(s.rows.size() - 1).due();
        LocalDate end;
        switch (sc.behaviour()) {
            case STOP_PAYING, RESTRUCTURE_AFTER_NPA -> end = lastDue.plusDays(Math.max(sc.observeDaysAfterLastDue(), 0));
            case NPA_THEN_FORECLOSE -> {
                int n = sc.intParam("payInstalments", 1);
                LocalDate firstMissed = s.rows.get(Math.min(n, s.rows.size() - 1)).due();
                end = firstMissed.plusDays(sc.intParam("forecloseAfterDays", 120) + 3L);
            }
            case FORECLOSE_AT -> end = rowDue(s, sc.intParam("atInstalment", 1)).plusDays(3);
            case LATE_PAY -> end = lastDue.plusDays(sc.intParam("lateDays", 0) + 3L);
            case MISS_THEN_CURE -> {
                LocalDate cure = rowDue(s, sc.intParam("missInstalment", 1)).plusDays(sc.intParam("lateDays", 0));
                s.cureDate = cure;
                end = (cure.isAfter(lastDue) ? cure : lastDue).plusDays(3L + sc.observeDaysAfterLastDue());
            }
            default -> end = lastDue.plusDays(3L);
        }
        return end;
    }

    private LocalDate rowDue(AccountSim s, int no) {
        ScheduleInvariants.Row r = s.row(no);
        return r != null ? r.due() : s.rows.get(s.rows.size() - 1).due();
    }

    // ===================================================================================================== loop

    private void loop() {
        LocalDate day = cfg.getDate("e2e.start-date");
        int maxDays = cfg.getInt("e2e.max-sim-days");
        int stride = Math.max(1, cfg.getInt("e2e.verify.every-n-days"));
        for (int n = 1; n <= maxDays; n++, day = day.plusDays(1)) {
            final LocalDate today = day;
            List<AccountSim> live = sims.stream().filter(s -> s.accountId != null && !s.setupFailed
                    && !today.isAfter(s.endDate) && (!s.closed || !today.isAfter(s.closedOn.plusDays(1)))).toList();
            if (live.isEmpty()) {
                break;
            }
            if (managed && !syncBusinessDate(today)) {
                break;
            }
            daysRun = n;
            lastDay = today;
            Set<AccountSim> events = new java.util.LinkedHashSet<>();
            StringBuilder[] ev = new StringBuilder[sims.size() + 1];
            for (AccountSim s : live) {
                s.openingOutstanding = s.modelOutstanding;
                ev[s.index] = new StringBuilder();
            }
            if (payThenEod) {
                payments(today, live, ev);
                eod(today, live);
            } else {
                for (AccountSim s : live) {
                    s.dpdAtEod = s.closed ? 0 : DpdModel.dpd(today, s.unpaidDueDates());
                }
                eod(today, live);
                if (haltReason == null) {
                    payments(today, live, ev);
                }
            }
            if (haltReason != null) {
                log.accept("  HALTED on " + today + ": " + haltReason);
                break;
            }
            boolean verifyDay = n % stride == 0;
            for (AccountSim s : live) {
                boolean eventDay = ev[s.index].length() > 0;
                if (verifyDay || eventDay || !today.isBefore(s.endDate)) {
                    verify(n, today, s, ev[s.index].toString());
                }
            }
            if (n % 30 == 0) {
                log.accept("  day " + n + " (" + today + "), live accounts " + live.stream().filter(AccountSim::active).count()
                        + ", batch triggers so far " + batchTriggers);
            }
        }
    }

    // ===================================================================================================== borrower behaviour

    private LocalDate payDate(AccountSim s, ScheduleInvariants.Row r) {
        Scenario sc = s.scenario;
        switch (sc.behaviour()) {
            case LATE_PAY:
                return r.due().plusDays(sc.intParam("lateDays", 0));
            case MISS_THEN_CURE: {
                int miss = sc.intParam("missInstalment", 1);
                if (r.no() < miss) {
                    return r.due();
                }
                LocalDate cure = rowDue(s, miss).plusDays(sc.intParam("lateDays", 0));
                return cure.isAfter(r.due()) ? cure : r.due();
            }
            case STOP_PAYING:
            case RESTRUCTURE_AFTER_NPA:
            case NPA_THEN_FORECLOSE:
                return r.no() <= sc.intParam("payInstalments", 1) ? r.due() : null;
            case FORECLOSE_AT:
                return r.no() < sc.intParam("atInstalment", 1) ? r.due() : null;
            default:
                return r.due();
        }
    }

    private void payments(LocalDate day, List<AccountSim> live, StringBuilder[] ev) {
        for (AccountSim s : live) {
            if (!s.active() || s.diverged || day.isBefore(cfg.getDate("e2e.start-date"))) {
                continue;
            }
            StringBuilder sb = ev[s.index];
            Scenario sc = s.scenario;
            // foreclosure triggers
            if (sc.behaviour() == Behaviour.FORECLOSE_AT && !s.forecloseDone
                    && day.equals(rowDue(s, sc.intParam("atInstalment", 1)))) {
                foreclose(s, day, sb);
                continue;
            }
            if (sc.behaviour() == Behaviour.NPA_THEN_FORECLOSE && !s.forecloseDone) {
                int n = sc.intParam("payInstalments", 1);
                LocalDate firstMissed = s.rows.get(Math.min(n, s.rows.size() - 1)).due();
                if (!day.isBefore(firstMissed.plusDays(sc.intParam("forecloseAfterDays", 120)))) {
                    foreclose(s, day, sb);
                    continue;
                }
            }
            if (sc.behaviour() == Behaviour.RESTRUCTURE_AFTER_NPA && !s.restructureProbed
                    && DpdModel.dpd(day, s.unpaidDueDates()) >= npaFrom) {
                s.restructureProbed = true;
                sb.append("restructure-probe;");
                // no restructure endpoint exists to call; the capability probe records NOT FOUND from the API surface
            }
            int guard = 0;
            while (guard++ < 400 && s.active()) {
                List<ScheduleInvariants.Row> unpaid = s.unpaidRows();
                if (unpaid.isEmpty()) {
                    break;
                }
                ScheduleInvariants.Row r = unpaid.get(0);
                LocalDate pd = payDate(s, r);
                if (pd == null || pd.isAfter(day)) {
                    break;
                }
                if (sc.behaviour() == Behaviour.SHORT_PAY_THEN_PAY && !s.shortTried && r.no() == sc.intParam("shortInstalment", 1)) {
                    s.shortTried = true;
                    shortPay(s, r, day, sb);
                    if (s.diverged) {
                        break;
                    }
                }
                if (!payEmi(s, r, day, sb)) {
                    break;
                }
                if (sc.behaviour() == Behaviour.PART_PAY_THEN_ON_TIME && !s.partDone && r.no() == sc.intParam("afterInstalment", 1)) {
                    s.partDone = true;
                    partPay(s, day, sb);
                }
            }
        }
    }

    private boolean payEmi(AccountSim s, ScheduleInvariants.Row r, LocalDate day, StringBuilder sb) {
        BigDecimal amount = r.emi().setScale(2, RoundingMode.HALF_UP);
        String key = ctx.idempotencyKey(s.index, "I" + r.no() + "-D" + day);
        ApiResult res = lending.payEmi(s.accountId, day, amount, channel, key);
        String sid = s.scenario.scenarioId();
        if (!res.ok()) {
            reportRejection(s, day, "PAY-01", amount, res);
            sb.append("pay").append(r.no()).append("=REJECTED(").append(res.status()).append(");");
            return false;
        }
        JsonNode b = res.body();
        boolean oldest = b.path("installmentNumber").asInt(-1) == r.no();
        findings.check(oldest, "PAY-01", sid, s.accountNumber, day, "applied to instalment " + r.no(),
                "installmentNumber=" + b.path("installmentNumber").asText(), "payment applied to a different instalment");
        BigDecimal pp = new BigDecimal(b.path("principalPaid").asText("0"));
        BigDecimal ip = new BigDecimal(b.path("interestPaid").asText("0"));
        BigDecimal amt = new BigDecimal(b.path("amount").asText(amount.toPlainString()));
        boolean alloc = pp.subtract(r.principal()).abs().compareTo(tolMoney) <= 0 && ip.subtract(r.interest()).abs().compareTo(tolMoney) <= 0
                && pp.add(ip).subtract(amt).abs().compareTo(tolMoney) <= 0;
        findings.check(alloc, "PAY-02", sid, s.accountNumber, day, "principal " + r.principal() + " interest " + r.interest(),
                "principal " + pp + " interest " + ip + " amount " + amt, "allocation differs from the schedule split");
        s.paid.add(r.no());
        s.paidOn.put(r.no(), day);
        s.paidAmount.put(r.no(), amt);
        s.principalPaidTotal = s.principalPaidTotal.add(pp);
        s.interestPaidTotal = s.interestPaidTotal.add(ip);
        s.paymentsPosted++;
        s.paymentsPostedAmount = s.paymentsPostedAmount.add(amt);
        s.modelOutstanding = r.balance();
        sb.append("pay").append(r.no()).append('=').append(amt.toPlainString()).append(';');
        if (cfg.getBool("e2e.check.idempotency-replay") && !s.replayChecked) {
            s.replayChecked = true;
            replay(s, r, day, amount, key, b.path("transactionId").asLong(-1));
        }
        if (s.unpaidRows().isEmpty()) {
            sb.append("fully-paid;");
        }
        return true;
    }

    private void replay(AccountSim s, ScheduleInvariants.Row r, LocalDate day, BigDecimal amount, String key, long txnId) {
        ApiResult before = lending.payHistory(s.accountId);
        int n0 = before.body().isArray() ? before.body().size() : -1;
        ApiResult again = lending.payEmi(s.accountId, day, amount, channel, key);
        ApiResult after = lending.payHistory(s.accountId);
        int n1 = after.body().isArray() ? after.body().size() : -2;
        boolean same = again.ok() && again.body().path("transactionId").asLong(-3) == txnId && n0 == n1;
        findings.check(same, "PAY-03", s.scenario.scenarioId(), s.accountNumber, day, "same transaction " + txnId + ", history size " + n0,
                again.brief() + " / history " + n1, "replay of the same Idempotency-Key is not safe");
    }

    private void shortPay(AccountSim s, ScheduleInvariants.Row r, LocalDate day, StringBuilder sb) {
        BigDecimal frac = cfg.getDecimal("e2e.short-pay.fraction");
        BigDecimal amount = r.emi().multiply(frac).setScale(2, RoundingMode.HALF_UP);
        ApiResult before = admin.payments(s.accountId);
        ApiResult res = lending.payEmi(s.accountId, day, amount, channel, ctx.idempotencyKey(s.index, "SHORT" + r.no() + "-D" + day));
        ApiResult after = admin.payments(s.accountId);
        int n0 = before.body().path("rows").size();
        int n1 = after.body().path("rows").size();
        // Since demands and the payment waterfall, a short payment is accepted and applied to what is due (interest, then
        // principal, oldest demand first). It is only wrong if it is neither a clean rejection nor exactly one new row.
        boolean rejected = res.clientError() && n0 == n1;
        boolean accepted = res.ok() && n1 == n0 + 1;
        findings.check(rejected || accepted, "PAY-04", s.scenario.scenarioId(), s.accountNumber, day,
                "2xx with one new payment row, or 4xx with none",
                res.brief() + " rows " + n0 + "->" + n1, "short payment neither applied nor rejected cleanly");
        sb.append("short-pay=").append(res.status()).append(';');
        if (res.ok()) {
            // The model assumes whole instalments, so it no longer tracks this account.
            s.diverged = true;
        }
    }

    private void partPay(AccountSim s, LocalDate day, StringBuilder sb) {
        BigDecimal pct = new BigDecimal(s.scenario.param("percent", "10"));
        BigDecimal amount = s.modelOutstanding.multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        ApiResult res = lending.partPayment(s.accountId, day, amount, channel, ctx.idempotencyKey(s.index, "PART-D" + day));
        String sid = s.scenario.scenarioId();
        if (!res.ok()) {
            findings.fail("PAY-06", sid, s.accountNumber, day, "2xx", res.brief(), "part-payment rejected");
            sb.append("part=REJECTED;");
            return;
        }
        BigDecimal before = s.modelOutstanding;
        s.modelOutstanding = before.subtract(amount);
        s.partPaymentTotal = s.partPaymentTotal.add(amount);
        s.paymentsPosted++;
        s.paymentsPostedAmount = s.paymentsPostedAmount.add(amount);
        s.principalPaidTotal = s.principalPaidTotal.add(amount);
        sb.append("part=").append(amount.toPlainString()).append(';');
        ApiResult sched = lending.schedule(s.accountId);
        if (sched.ok() && sched.body().isArray()) {
            List<ScheduleInvariants.Row> fresh = ScheduleInvariants.rows(sched.body());
            List<ScheduleInvariants.Row> remaining = fresh.stream().filter(x -> !"PAID".equals(x.status())).toList();
            BigDecimal sumPrincipal = remaining.stream().map(ScheduleInvariants.Row::principal).reduce(BigDecimal.ZERO, BigDecimal::add);
            boolean ok = sumPrincipal.subtract(s.modelOutstanding).abs().compareTo(tolMoney) <= 0
                    && !remaining.isEmpty() && remaining.get(remaining.size() - 1).balance().abs().compareTo(tolMoney) <= 0;
            findings.check(ok, "PAY-06", sid, s.accountNumber, day, "remaining principal " + s.modelOutstanding,
                    "schedule principal " + sumPrincipal, "schedule after part-payment does not clear the reduced principal");
            Set<Integer> sysPaid = new java.util.TreeSet<>();
            fresh.stream().filter(x -> "PAID".equals(x.status())).forEach(x -> sysPaid.add(x.no()));
            s.rows = fresh;
            if (!sysPaid.equals(new java.util.TreeSet<>(s.paid))) {
                findings.fail("PAY-05", sid, s.accountNumber, day, "paid instalments " + s.paid, "system reports " + sysPaid,
                        "paid-instalment set changed after re-schedule");
                s.paid.clear();
                s.paid.addAll(sysPaid);
            }
        } else {
            findings.fail("PAY-06", sid, s.accountNumber, day, "schedule readable", sched.brief(), "cannot read schedule after part-payment");
        }
    }

    private void foreclose(AccountSim s, LocalDate day, StringBuilder sb) {
        s.forecloseDone = true;
        ApiResult res = lending.foreclose(s.accountId, day, channel, ctx.idempotencyKey(s.index, "FORECLOSE-D" + day));
        String sid = s.scenario.scenarioId();
        if (!res.ok()) {
            findings.fail("PAY-07", sid, s.accountNumber, day, "2xx", res.brief(), "foreclosure rejected");
            sb.append("foreclose=REJECTED(").append(res.status()).append(");");
            return;
        }
        BigDecimal amt = new BigDecimal(res.body().path("amount").asText("0"));
        BigDecimal pp = new BigDecimal(res.body().path("principalPaid").asText("0"));
        BigDecimal ip = new BigDecimal(res.body().path("interestPaid").asText("0"));
        s.paymentsPosted++;
        s.paymentsPostedAmount = s.paymentsPostedAmount.add(amt);
        s.principalPaidTotal = s.principalPaidTotal.add(pp);
        s.interestPaidTotal = s.interestPaidTotal.add(ip);
        findings.check(pp.subtract(s.modelOutstanding).abs().compareTo(tolMoney) <= 0, "PAY-07", sid, s.accountNumber, day,
                "principal settled = outstanding " + s.modelOutstanding, "principalPaid " + pp, "foreclosure principal differs from outstanding");
        s.modelOutstanding = BigDecimal.ZERO;
        s.closed = true;
        s.closedOn = day;
        sb.append("foreclose=").append(amt.toPlainString()).append(';');
    }

    private void reportRejection(AccountSim s, LocalDate day, String checkId, BigDecimal amount, ApiResult res) {
        String body = res.rawBody() == null ? "" : res.rawBody().toLowerCase();
        if (body.contains("partition") || (res.status() == 500 && body.contains("no partition"))) {
            findings.fail("ENV-01", s.scenario.scenarioId(), s.accountNumber, day, "partition exists for " + day, res.brief(),
                    "database partition for the simulated date is missing");
        } else {
            findings.fail(checkId, s.scenario.scenarioId(), s.accountNumber, day, "2xx for exact amount " + amount, res.brief(),
                    "payment of the exact amount due was rejected");
        }
    }

    // ===================================================================================================== batch

    private void eod(LocalDate day, List<AccountSim> live) {
        if (!managed) {
            runJobs(day, live, true);
            return;
        }
        if (eodRun) {
            runJobs(day, live, false);               // month-end jobs are not part of the end-of-day chain
            endOfDayViaBatch(day, live);
        } else {
            runJobs(day, live, true);
            advanceViaLending(day);
        }
        if (haltReason == null) {
            checkAdminBusinessDate(day);
        }
    }

    /** Triggers jobs one by one for {@code day}: the daily set when {@code includeDaily}, plus the monthly set on month end. */
    private void runJobs(LocalDate day, List<AccountSim> live, boolean includeDaily) {
        long eligible = live.stream().filter(AccountSim::active).count();
        List<String> jobs = new ArrayList<>();
        if (includeDaily) {
            jobs.addAll(cfg.getList("e2e.batch.daily-jobs"));
        }
        if (day.equals(YearMonth.from(day).atEndOfMonth())) {
            jobs.addAll(cfg.getList("e2e.batch.monthly-jobs"));
        }
        if (accrualViaApi && includeDaily) {
            jobs.remove("INTEREST_ACCRUAL_DAILY");
            ApiResult r = lending.runAccrualDirect(day);
            findings.check(r.ok(), "BAT-01", "-", "-", day, "2xx", r.brief(), "direct accrual run (e2e.accrual.path=API) failed");
        }
        if (dpdViaApi && includeDaily) {
            jobs.remove("DPD_UPDATE_DAILY");
            jobs.remove("NPA_CLASSIFICATION_CHECK_DAILY");
            ApiResult r = lending.classifyDirect(day);
            findings.check(r.ok(), "BAT-01", "-", "-", day, "2xx", r.brief(), "direct classification (e2e.dpd.path=API) failed");
        }
        List<String> recordJobs = cfg.getList("e2e.batch.record-jobs");
        for (String job : jobs) {
            BatchClient.Run run = batch.triggerAndWait(job, day);
            batchTriggers++;
            logBatch(day, run);
            findings.check(run.triggered(), "BAT-01", job, "-", day, "trigger accepted", run.triggerCall().brief(), "trigger rejected");
            if (!run.triggered()) {
                continue;
            }
            findings.check("COMPLETED".equals(run.status()) && !run.timedOut(), "BAT-02", job, "-", day, "COMPLETED",
                    run.status() + (run.timedOut() ? " (poll timeout)" : ""), "job did not complete");
            if (recordJobs.contains(job) && eligible > 0 && "COMPLETED".equals(run.status())) {
                findings.check(run.readCount() > 0 || run.writeCount() > 0, "BAT-03", job, "-", day,
                        "read>0 for " + eligible + " eligible accounts", "read=" + run.readCount() + " write=" + run.writeCount(),
                        "job completed but processed no records");
            }
        }
        if (includeDaily) {
            maybeRerun(day, live, jobs.contains("INTEREST_ACCRUAL_DAILY"));
        }
    }

    private void maybeRerun(LocalDate day, List<AccountSim> live, boolean accrualRanThroughBatch) {
        if (!rerunChecked && cfg.getBool("e2e.check.batch-rerun-idempotency") && !accrualViaApi && accrualRanThroughBatch && daysRun >= 3) {
            rerunChecked = true;
            batchRerun(day, live);
        }
    }

    // ===================================================================================================== business date

    /** Puts the platform on the simulated start date before anything is created. */
    private boolean establishBusinessDate(LocalDate start) {
        ApiResult cur = lending.businessDate();
        LocalDate now = cur.ok() ? parseDate(cur.body().path("businessDate").asText()) : null;
        if (start.equals(now) && "OPEN".equals(cur.body().path("status").asText())) {
            currentBd = start;
            return true;
        }
        ApiResult set = lending.setBusinessDate(start, "e2e run " + ctx.runId() + " start");
        boolean ok = findings.check(set.ok(), "BDT-01", "-", "-", start, "business date set to " + start, set.brief(),
                "cannot set the platform business date to the simulated start date");
        if (!ok) {
            haltReason = "could not set the platform business date to " + start + " (" + set.brief() + ")";
            return false;
        }
        currentBd = start;
        return true;
    }

    /**
     * Before each simulated day: the platform must be OPEN on that very day. If it is behind, move it forward; if it is
     * ahead (something else ran end of day, e.g. the nightly schedule) the run cannot continue meaningfully.
     */
    private boolean syncBusinessDate(LocalDate day) {
        ApiResult r = lending.businessDate();
        LocalDate actual = r.ok() ? parseDate(r.body().path("businessDate").asText()) : null;
        String status = r.body().path("status").asText("?");
        boolean inStep = day.equals(actual) && "OPEN".equals(status);
        findings.check(inStep, "BDT-01", "-", "-", day, day + " OPEN", (actual == null ? r.brief() : actual + " " + status),
                "platform business date is not the simulated day");
        if (inStep) {
            currentBd = day;
            return true;
        }
        if (actual != null && actual.isBefore(day) && "OPEN".equals(status)) {
            ApiResult set = lending.setBusinessDate(day, "e2e run " + ctx.runId() + " re-sync");
            if (set.ok()) {
                currentBd = day;
                return true;
            }
        }
        haltReason = "platform business date is " + (actual == null ? "unreadable" : actual + " " + status) + " but the simulated day is "
                + day + " (is the batch service's nightly end of day running against this environment?)";
        return false;
    }

    /** e2e.eod.mode=EOD_RUN: the production path - the batch service runs the daily chain and advances the date. */
    private void endOfDayViaBatch(LocalDate day, List<AccountSim> live) {
        long eligible = live.stream().filter(AccountSim::active).count();
        BatchClient.EodRun run = batch.runEndOfDay(day, eodTimeout);
        batchTriggers++;
        findings.check(run.advanced(), "BAT-05", "EOD", "-", day, "ADVANCED",
                run.outcome() + (run.error().isEmpty() ? "" : ": " + run.error()), "end-of-day run did not complete and advance the business date");
        List<String> recordJobs = cfg.getList("e2e.batch.record-jobs");
        for (JsonNode j : run.jobs()) {
            String job = j.path("jobName").asText();
            String status = j.path("status").asText();
            JsonNode exec = "SKIPPED".equals(status) ? null : executionFor(job, day);
            boolean done = "COMPLETED".equals(status) || "SKIPPED".equals(status);
            findings.check(done, "BAT-02", job, "-", day, "COMPLETED", status + (j.hasNonNull("message") && !done ? " " + j.get("message").asText() : ""),
                    "job did not complete in the end-of-day run");
            long read = exec == null ? -1 : exec.path("readCount").asLong(-1);
            long write = exec == null ? -1 : exec.path("writeCount").asLong(-1);
            batchLog.row(List.of(ctx.runId(), day, job, "EOD", status, exec == null ? "" : exec.path("exitCode").asText(""),
                    exec == null || !exec.hasNonNull("jobExecutionId") ? "" : exec.get("jobExecutionId").asText(), read, write,
                    exec == null ? -1 : exec.path("skipCount").asLong(-1), "", run.timedOut()));
            if (recordJobs.contains(job) && eligible > 0 && "COMPLETED".equals(status) && exec != null) {
                findings.check(read > 0 || write > 0, "BAT-03", job, "-", day, "read>0 for " + eligible + " eligible accounts",
                        "read=" + read + " write=" + write, "job completed but processed no records");
            }
        }
        if (!run.advanced()) {
            haltReason = "end of day for " + day + " ended " + run.outcome() + (run.error().isEmpty() ? "" : " (" + run.error() + ")")
                    + "; the platform stays on " + day + " in EOD_RUNNING";
            return;
        }
        if (!confirmAdvanced(day)) {
            return;
        }
        maybeRerun(day, live, cfg.getList("e2e.batch.daily-jobs").contains("INTEREST_ACCRUAL_DAILY"));
    }

    /** e2e.eod.mode=JOBS_THEN_ADVANCE: the suite ran the jobs itself, so it closes the day through lending-service directly. */
    private void advanceViaLending(LocalDate day) {
        ApiResult start = lending.startEndOfDay();
        if (!findings.check(start.ok(), "BDT-02", "-", "-", day, "end of day started", start.brief(), "cannot start end of day")) {
            haltReason = "lending-service refused to start end of day for " + day + " (" + start.brief() + ")";
            return;
        }
        ApiResult adv = lending.advanceBusinessDate(day);
        if (!adv.ok()) {
            findings.fail("BDT-02", "-", "-", day, "advance accepted", adv.brief(), "business date could not be advanced");
            haltReason = "lending-service refused to advance the business date from " + day + " (" + adv.brief() + ")";
            return;
        }
        confirmAdvanced(day);
    }

    /** BDT-02: the date is now day+1 and OPEN. */
    private boolean confirmAdvanced(LocalDate day) {
        ApiResult r = lending.businessDate();
        LocalDate next = r.ok() ? parseDate(r.body().path("businessDate").asText()) : null;
        String status = r.body().path("status").asText("?");
        boolean ok = day.plusDays(1).equals(next) && "OPEN".equals(status);
        findings.check(ok, "BDT-02", "-", "-", day, day.plusDays(1) + " OPEN", next == null ? r.brief() : next + " " + status,
                "end of day did not move the business date to the next day");
        if (!ok) {
            haltReason = "after end of day for " + day + " the platform business date is " + (next == null ? "unreadable" : next + " " + status)
                    + ", expected " + day.plusDays(1) + " OPEN";
            return false;
        }
        currentBd = next;
        return true;
    }

    /** BDT-03: the admin portal shows the same business date as lending-service. */
    private void checkAdminBusinessDate(LocalDate day) {
        if (!cfg.getBool("e2e.check.admin-dpd-wallclock") || currentBd == null) {
            return;
        }
        ApiResult a = admin.businessDate();
        String shown = a.ok() ? a.body().path("businessDate").asText("?") : a.brief();
        findings.check(a.ok() && currentBd.toString().equals(shown) && a.body().path("fromLending").asBoolean(false), "BDT-03", "-", "-", day,
                currentBd.toString(), shown + (a.ok() && !a.body().path("fromLending").asBoolean(false) ? " (wall-clock fallback)" : ""),
                "admin portal business date differs from lending-service's");
    }

    private JsonNode executionFor(String job, LocalDate day) {
        ApiResult list = batch.executions(job, 10);
        if (list.ok() && list.body().isArray()) {
            for (JsonNode e : list.body()) {
                if (day.toString().equals(e.path("businessDate").asText())) {
                    return e;
                }
            }
        }
        return null;
    }

    private static LocalDate parseDate(String v) {
        try {
            return LocalDate.parse(v);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void batchRerun(LocalDate day, List<AccountSim> live) {
        AccountSim probe = live.stream().filter(AccountSim::active).findFirst().orElse(null);
        if (probe == null) {
            return;
        }
        int before = admin.accruals(probe.accountId, day, day).body().path("rows").size();
        BatchClient.Run again = batch.triggerAndWait("INTEREST_ACCRUAL_DAILY", day);
        batchTriggers++;
        logBatch(day, again);
        int after = admin.accruals(probe.accountId, day, day).body().path("rows").size();
        boolean noDup = after <= Math.max(before, 1);
        findings.check(noDup, "BAT-04", "INTEREST_ACCRUAL_DAILY", probe.accountNumber, day, "accrual rows for the date stay <= 1",
                "rows " + before + " -> " + after + ", re-run outcome " + again.status(), "re-run for the same business date duplicated accruals");
    }

    private void logBatch(LocalDate day, BatchClient.Run r) {
        batchLog.row(List.of(ctx.runId(), day, r.job(), r.triggerCall().status(), r.status(),
                r.finalExecution() == null ? "" : r.finalExecution().path("exitCode").asText(""), r.executionId() == null ? "" : r.executionId(),
                r.readCount(), r.writeCount(), r.skipCount(), r.elapsedMs(), r.timedOut()));
    }

    // ===================================================================================================== verification

    private void verify(int dayNo, LocalDate day, AccountSim s, String events) {
        String sid = s.scenario.scenarioId();
        ApiResult accRes = admin.account(s.accountId);
        if (!accRes.ok()) {
            findings.fail("STA-02", sid, s.accountNumber, day, "account readable", accRes.brief(), "admin read failed");
            return;
        }
        JsonNode acc = accRes.body();
        String status = acc.path("accountStatus").asText("?");
        BigDecimal sysOut = acc.path("figures").hasNonNull("outstandingPrincipal")
                ? new BigDecimal(acc.path("figures").get("outstandingPrincipal").asText()) : null;
        boolean postClose;

        // a fully paid account is closed from that day on - decide that BEFORE any delinquency expectation is computed
        boolean closedByRepayment = false;
        if (!s.closed && s.unpaidRows().isEmpty() && !s.diverged) {
            s.closed = true;
            s.closedOn = day;
            s.modelOutstanding = BigDecimal.ZERO;
            closedByRepayment = true;
        }
        postClose = s.closed && day.isAfter(s.closedOn);

        // ---- delinquency
        int dpd = s.closed ? 0 : DpdModel.dpd(day, s.unpaidDueDates());
        s.maxDpdSeen = Math.max(s.maxDpdSeen, dpd);
        // with EOD_THEN_PAY the DPD job saw the book BEFORE today's payments, so the expectation is taken from that moment
        int dpdForStatus = payThenEod ? dpd : s.dpdAtEod;
        String expectedStatus = s.closed ? null : dpdModel.classify(dpdForStatus);
        if (!s.closed && !s.diverged) {
            findings.check(status.equals(expectedStatus), "DPD-01", sid, s.accountNumber, day, expectedStatus + " (dpd " + dpdForStatus + ")",
                    status, "status differs from the bucket for the independently computed DPD");
            if (dpdForStatus >= npaFrom) {
                findings.check(status.startsWith("NPA_"), "NPA-01", sid, s.accountNumber, day, "NPA status at dpd " + dpdForStatus, status,
                        "account past the NPA threshold is not classified NPA");
            }
            if (dpdForStatus == 0 && s.maxDpdSeen > 0) {
                findings.check("ACTIVE".equals(status), "DPD-03", sid, s.accountNumber, day, "ACTIVE after arrears cleared", status,
                        "cured account did not return to ACTIVE");
            }
            if (dpd == 1 || (dpd > 0 && dpd % 30 == 0)) {
                arrearsMarked(s, day, sid);
            }
            // the portal measures days past due at the platform business date; once end of day has run that is the NEXT day
            LocalDate adminDay = managed && currentBd != null ? currentBd : day;
            int adminExpected = adminDay.equals(day) ? dpd : DpdModel.dpd(adminDay, s.unpaidDueDates());
            if (cfg.getBool("e2e.check.admin-dpd-wallclock") && acc.path("figures").has("daysPastDue")
                    && (adminExpected > 0 || dpd > 0 || s.maxDpdSeen > 0)) {
                int adminDpd = acc.path("figures").path("daysPastDue").asInt();
                findings.check(adminDpd == adminExpected, "DPD-04", sid, s.accountNumber, day,
                        "dpd " + adminExpected + " at business date " + adminDay, "admin shows " + adminDpd,
                        "admin portal days-past-due is not derived from the platform business date");
            }
        }
        // ladder
        String prev = s.statusHistory.isEmpty() ? null : s.statusHistory.get(s.statusHistory.size() - 1);
        if (prev != null && !prev.equals(status)) {
            int a = dpdModel.rank(prev);
            int b = dpdModel.rank(status);
            boolean legal = !(terminal.contains(prev)) && (b < 0 || a < 0 || b == a + 1 || status.equals("ACTIVE") || terminal.contains(status));
            findings.check(legal, "DPD-02", sid, s.accountNumber, day, "step to adjacent bucket / ACTIVE / terminal", prev + " -> " + status,
                    "status ladder skipped a bucket or left a terminal state");
        }
        statusSeen(s, status);
        if (!terminal.contains(status) && s.closed) {
            findings.fail("STA-02", sid, s.accountNumber, day, "terminal status after closure", status, "account closed by the test but still " + status);
        }
        if ("PENDING_DISBURSEMENT".equals(status)) {
            findings.fail("STA-02", sid, s.accountNumber, day, "ACTIVE after disbursal", status, "disbursed account still pending");
        }

        // ---- balances
        if (sysOut != null && !s.diverged) {
            findings.check(sysOut.subtract(s.modelOutstanding).abs().compareTo(tolMoney) <= 0, "BAL-01", sid, s.accountNumber, day,
                    s.modelOutstanding.toPlainString(), sysOut.toPlainString(), "outstanding principal differs from the test ledger");
        }
        if (s.closed && sysOut != null && !postClose) {
            findings.check(sysOut.abs().compareTo(cfg.getDecimal("e2e.tolerance.close-balance")) <= 0, "BAL-02", sid, s.accountNumber, day,
                    "0", sysOut.toPlainString(), "closed account carries outstanding principal");
        }
        // closure by repayment
        if (closedByRepayment) {
            findings.check(closedOk.contains(status), "STA-01", sid, s.accountNumber, day, "CLOSED", status,
                    "fully paid account is not closed");
            if (sysOut != null) {
                findings.check(sysOut.abs().compareTo(cfg.getDecimal("e2e.tolerance.close-balance")) <= 0, "BAL-02", sid, s.accountNumber, day,
                        "0", sysOut.toPlainString(), "closed account carries outstanding principal");
            }
        }

        // ---- accrual
        BigDecimal expAccrual = null;
        BigDecimal sysAccrual = null;
        BigDecimal sysAccrualPrincipal = null;
        ApiResult accr = admin.accruals(s.accountId, day, day);
        JsonNode rows = accr.body().path("rows");
        boolean accrualExpected = !postClose && !(s.closedOn != null && day.equals(s.closedOn)) && s.terms != null
                && s.openingOutstanding.signum() > 0 && !s.diverged;
        if (postClose) {
            findings.check(rows.size() == 0, "ACC-04", sid, s.accountNumber, day, "no accrual after closure", rows.size() + " row(s)",
                    "accrual posted after the account closed");
        } else if (accrualExpected) {
            if (rows.size() == 0) {
                findings.fail("ACC-01", sid, s.accountNumber, day, "1 accrual row", "0 rows" + (accr.ok() ? "" : " (" + accr.brief() + ")"),
                        "no accrual for an active account");
            } else {
                findings.pass("ACC-01", sid, s.accountNumber, day);
                JsonNode row = rows.get(0);
                sysAccrual = new BigDecimal(row.path("accruedAmount").asText("0"));
                sysAccrualPrincipal = new BigDecimal(row.path("outstandingPrincipal").asText("0"));
                BigDecimal basis = payThenEod ? s.modelOutstanding : s.openingOutstanding;
                findings.check(sysAccrualPrincipal.subtract(basis).abs().compareTo(tolMoney) <= 0, "ACC-03", sid, s.accountNumber, day,
                        basis.toPlainString() + (payThenEod ? " (closing)" : " (opening)"), sysAccrualPrincipal.toPlainString(),
                        "accrual used a different principal than the configured day order implies");
                expAccrual = DayCount.dailyInterest(sysAccrualPrincipal, s.terms.ratePct(), s.terms.dayCount(), day);
                findings.check(sysAccrual.subtract(expAccrual).abs().compareTo(tolAccrual) <= 0, "ACC-02", sid, s.accountNumber, day,
                        expAccrual.toPlainString(), sysAccrual.toPlainString(), "accrued amount differs from principal x rate / basis");
                s.cumulativeAccrued = s.cumulativeAccrued.add(sysAccrual);
                s.cumulativeExpectedAccrual = s.cumulativeExpectedAccrual.add(expAccrual);
                s.lastAccrualRows++;
            }
        }
        String facilityUtilised = secured != null && s.secured() ? secured.daily(s, day) : "";
        audit.row(List.of(ctx.runId(), dayNo, day, sid, s.scenario.cohort(), s.accountNumber, s.accountId, events,
                s.modelOutstanding, sysOut == null ? "" : sysOut, dpd, expectedStatus == null ? "(closed)" : expectedStatus, status,
                expAccrual == null ? "" : expAccrual, sysAccrual == null ? "" : sysAccrual,
                sysAccrualPrincipal == null ? "" : sysAccrualPrincipal, s.cumulativeAccrued, findings.failuresToday(sid, s.accountNumber, day),
                facilityUtilised));
    }

    private void arrearsMarked(AccountSim s, LocalDate day, String sid) {
        ApiResult sched = admin.schedule(s.accountId);
        if (!sched.ok()) {
            return;
        }
        boolean allMarked = true;
        StringBuilder bad = new StringBuilder();
        for (JsonNode r : sched.body().path("rows")) {
            int no = r.path("installmentNumber").asInt();
            LocalDate due = LocalDate.parse(r.path("dueDate").asText());
            if (due.isBefore(day) && !s.paid.contains(no) && !"OVERDUE".equals(r.path("status").asText())) {
                allMarked = false;
                bad.append('#').append(no).append('=').append(r.path("status").asText()).append(' ');
            }
        }
        findings.check(allMarked, "ARR-01", sid, s.accountNumber, day, "OVERDUE for every past-due unpaid instalment", bad.toString().trim(),
                "past-due unpaid instalments are not marked OVERDUE");
    }

    private void statusSeen(AccountSim s, String status) {
        if (s.statusHistory.isEmpty() || !s.statusHistory.get(s.statusHistory.size() - 1).equals(status)) {
            s.statusHistory.add(status);
        }
        s.lastStatus = status;
    }

    // ===================================================================================================== wrap-up

    private void finish() {
        for (AccountSim s : sims) {
            if (s.accountId == null || s.setupFailed) {
                continue;
            }
            String sid = s.scenario.scenarioId();
            LocalDate day = lastDay;
            boolean shouldClose = !s.horizonCapped && switch (s.scenario.behaviour()) {
                case STOP_PAYING, RESTRUCTURE_AFTER_NPA -> false;
                default -> true;
            };
            if (shouldClose && !s.diverged && !s.closed && lastDay != null && s.endDate != null && lastDay.isBefore(s.endDate)) {
                truncated.add(s.label());
                findings.skipped("STA-01", sid, s.accountNumber, day, "simulation stopped by e2e.max-sim-days at " + lastDay
                        + " before this account's planned end " + s.endDate);
            } else if (shouldClose && !s.diverged) {
                findings.check(s.closed && closedOk.contains(s.lastStatus), "STA-01", sid, s.accountNumber, day, "CLOSED/FORECLOSED",
                        s.lastStatus + (s.closed ? "" : " (not closed by test ledger)"), "account did not reach a closed state");
            }
            ApiResult pay = admin.payments(s.accountId);
            if (pay.ok() && !s.diverged) {
                int cnt = 0;
                BigDecimal total = BigDecimal.ZERO;
                for (JsonNode r : pay.body().path("rows")) {
                    if (!r.path("status").asText("").toUpperCase().contains("FAIL")) {
                        cnt++;
                        total = total.add(new BigDecimal(r.path("amount").asText("0")));
                    }
                }
                findings.check(cnt == s.paymentsPosted && total.subtract(s.paymentsPostedAmount).abs().compareTo(tolMoney) <= 0, "PAY-05", sid,
                        s.accountNumber, day, s.paymentsPosted + " payments / " + s.paymentsPostedAmount, cnt + " payments / " + total,
                        "payment history differs from what the test posted");
            }
            if (s.closed && s.cumulativeAccrued.signum() > 0 && s.interestPaidTotal.signum() > 0) {
                BigDecimal gap = s.cumulativeAccrued.subtract(s.interestPaidTotal).abs();
                BigDecimal pct = gap.multiply(BigDecimal.valueOf(100)).divide(s.interestPaidTotal, 4, RoundingMode.HALF_UP);
                findings.check(pct.compareTo(cfg.getDecimal("e2e.tolerance.accrual-vs-paid-pct")) <= 0, "ACC-05", sid, s.accountNumber, day,
                        "accrued ~ interest paid (" + s.interestPaidTotal + ") within " + cfg.get("e2e.tolerance.accrual-vs-paid-pct") + "%",
                        "accrued " + s.cumulativeAccrued + " (gap " + pct + "%)", "accrued interest does not reconcile to interest paid");
            }
        }
        ledgerCheck();
    }

    private void ledgerCheck() {
        java.util.Map<String, Integer> perAccount = new java.util.HashMap<>();
        int pages = cfg.getInt("e2e.ledger.max-pages");
        boolean reachable = true;
        for (int p = 0; p < pages; p++) {
            ApiResult r = admin.ledgerOutbox(200, p);
            if (!r.ok()) {
                reachable = false;
                break;
            }
            JsonNode items = r.body().path("page").path("items");
            if (items.size() == 0) {
                break;
            }
            for (JsonNode row : items) {
                perAccount.merge(row.path("accountNumber").asText(""), 1, Integer::sum);
            }
            if (p + 1 >= r.body().path("page").path("totalPages").asInt(1)) {
                break;
            }
        }
        for (AccountSim s : sims) {
            if (s.accountId == null || s.setupFailed) {
                continue;
            }
            if (!reachable) {
                findings.skipped("LED-01", s.scenario.scenarioId(), s.accountNumber, lastDay, "ledger outbox not readable");
                continue;
            }
            int n = perAccount.getOrDefault(s.accountNumber, 0);
            findings.check(n >= 1, "LED-01", s.scenario.scenarioId(), s.accountNumber, lastDay, ">= 1 outbox row (disbursal)", n + " rows",
                    "no ledger outbox event for the account");
        }
    }

    // ===================================================================================================== helpers

    private static void putIf(ObjectNode n, String k, Object v) {
        if (v == null) {
            return;
        }
        if (v instanceof BigDecimal b) {
            n.put(k, b);
        } else {
            n.put(k, String.valueOf(v));
        }
    }

    private static BigDecimal dec(JsonNode n, String f) {
        return n.hasNonNull(f) ? new BigDecimal(n.get(f).asText()) : null;
    }
}
