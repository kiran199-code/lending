package com.bank.lending.e2e.calc;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Days-past-due and status classification, from configurable buckets ("ACTIVE:0,NPA_SMA0:30,...,LOSS:max").
 * DPD is derived from the test's own ledger of what was due and what was paid - never read back from the system under test.
 */
public final class DpdModel {

    private record Bucket(String status, int maxDpd) {
    }

    private final List<Bucket> buckets = new ArrayList<>();

    public DpdModel(String spec) {
        for (String part : spec.split(",")) {
            String[] kv = part.trim().split(":");
            buckets.add(new Bucket(kv[0].trim(), "max".equalsIgnoreCase(kv[1].trim()) ? Integer.MAX_VALUE : Integer.parseInt(kv[1].trim())));
        }
    }

    /** Days past due on {@code asOf}: age of the oldest instalment that was due before asOf and is still unpaid. */
    public static int dpd(LocalDate asOf, List<LocalDate> unpaidDueDates) {
        int best = 0;
        for (LocalDate due : unpaidDueDates) {
            if (due.isBefore(asOf)) {
                best = Math.max(best, (int) ChronoUnit.DAYS.between(due, asOf));
            }
        }
        return best;
    }

    public String classify(int dpd) {
        for (Bucket b : buckets) {
            if (dpd <= b.maxDpd()) {
                return b.status();
            }
        }
        return buckets.get(buckets.size() - 1).status();
    }

    /** Position of a status in the bucket ladder (for "never skips a bucket upward without passing through it"). */
    public int rank(String status) {
        for (int i = 0; i < buckets.size(); i++) {
            if (buckets.get(i).status().equals(status)) {
                return i;
            }
        }
        return -1;
    }
}
