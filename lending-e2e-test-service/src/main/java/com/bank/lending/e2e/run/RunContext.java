package com.bank.lending.e2e.run;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Identity of one run. Every test account is tagged by the run id in two ways:
 * (1) its customerId / sanctionRefId are drawn from a numeric range derived from the run's timestamp, so a
 * single range predicate finds every account of a run; (2) every Idempotency-Key starts with the run id so the
 * processed_request rows can be found too. The manifest written next to the results lists the exact ids.
 */
public record RunContext(String runId, long stamp, long idBase, Path outputDir) {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyMMddHHmmss");

    public static RunContext create(long idBase, Path outputRoot) {
        long stamp = Long.parseLong(LocalDateTime.now().format(STAMP));
        String suffix = Integer.toHexString(new SecureRandom().nextInt(0x1000)).toUpperCase();
        String runId = "E2E-" + stamp + "-" + suffix;
        return new RunContext(runId, stamp, idBase, outputRoot.resolve(runId));
    }

    /** Rebuilds the context of an earlier run (teardown / report regeneration). */
    public static RunContext fromRunId(String runId, long idBase, Path outputRoot) {
        String[] p = runId.split("-");
        if (p.length < 3 || !"E2E".equals(p[0])) {
            throw new IllegalArgumentException("Not a run id: " + runId);
        }
        return new RunContext(runId, Long.parseLong(p[1]), idBase, outputRoot.resolve(runId));
    }

    public long customerId(int index) {
        return idBase + stamp * 1000 + index;
    }

    public long sanctionRefId(int index) {
        return customerId(index);
    }

    public long rangeLow() {
        return idBase + stamp * 1000;
    }

    public long rangeHigh() {
        return rangeLow() + 999;
    }

    public String idempotencyKey(int index, String what) {
        return runId + "-A" + index + "-" + what;
    }
}
