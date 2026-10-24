package com.bank.lending.chit.collection;

import com.bank.lending.chit.collection.CollectionDtos.GroupFailure;
import com.bank.lending.chit.collection.CollectionDtos.RunSummary;
import com.bank.lending.chit.common.Audit;
import com.bank.lending.chit.common.ChitException;
import com.bank.lending.chit.common.Money;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs one of the three daily jobs over every collecting group. Deliberately not transactional: each group is its own
 * transaction (in {@link DemandWriter}, {@link LateFeeWriter}, {@link DefaultWriter}), so one broken group is reported
 * in the summary and the others still run. The caller (the batch job) fails the job when the summary shows failures,
 * and the next run picks the failed group up again because every job is safe to repeat.
 */
@Service
public class CollectionBatchService {

    public static final String JOB_DEMAND = "CHIT_DEMAND_RAISE";
    public static final String JOB_LATE_FEE = "CHIT_LATE_FEE";
    public static final String JOB_DEFAULT = "CHIT_DEFAULT_CHECK";

    private final CollectionRepository repo;
    private final DemandWriter demands;
    private final LateFeeWriter lateFees;
    private final DefaultWriter defaults;
    private final Audit audit;

    public CollectionBatchService(CollectionRepository repo, DemandWriter demands, LateFeeWriter lateFees, DefaultWriter defaults, Audit audit) {
        this.repo = repo;
        this.demands = demands;
        this.lateFees = lateFees;
        this.defaults = defaults;
        this.audit = audit;
    }

    public RunSummary raiseDemands(LocalDate today, String actor) {
        return run(JOB_DEMAND, today, actor, demands::raise);
    }

    public RunSummary accrueLateFees(LocalDate today, String actor) {
        return run(JOB_LATE_FEE, today, actor, lateFees::accrue);
    }

    public RunSummary checkDefaults(LocalDate today, String actor) {
        return run(JOB_DEFAULT, today, actor, defaults::detect);
    }

    private RunSummary run(String job, LocalDate today, String actor, GroupJob work) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        List<GroupFailure> failures = new ArrayList<>();
        int processed = 0;
        for (String code : repo.collectingGroupCodes()) {
            try {
                work.apply(code, today, actor).forEach((k, v) -> totals.merge(k, v, BigDecimal::add));
                processed++;
            } catch (RuntimeException e) {
                String c = e instanceof ChitException ce ? ce.code() : "INTERNAL_ERROR";
                failures.add(new GroupFailure(code, c, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }
        totals.replaceAll((k, v) -> v.scale() == 0 ? v : Money.scale(v));
        RunSummary summary = new RunSummary(job, today, processed, failures.size(), totals, failures);
        audit.log("BATCH", job, failures.isEmpty() ? "RUN_OK" : "RUN_PARTIAL", actor,
                "business date " + today + ", " + processed + " group(s) done, " + failures.size() + " failed, " + totals);
        return summary;
    }

    @FunctionalInterface
    private interface GroupJob {
        Map<String, BigDecimal> apply(String groupCode, LocalDate today, String actor);
    }
}
