package com.bank.lending.e2e.run;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Collects the outcome of every assertion. Defects are derived from these, never hand-written. */
public final class Findings {

    public enum Status { PASS, FAIL, NOT_FOUND, SKIPPED }

    public enum Severity { CRITICAL, HIGH, MEDIUM, LOW, NONE }

    public record CheckDef(String id, String area, Severity severity, String title, String expectation) {
    }

    public record Finding(String checkId, Status status, String scenarioId, String accountNumber, LocalDate businessDate,
                          String expected, String actual, String message) {
    }

    private final Map<String, CheckDef> catalogue = new LinkedHashMap<>();
    private final List<Finding> all = new ArrayList<>();
    private final Map<String, int[]> passCounts = new LinkedHashMap<>(); // checkId -> [pass]

    public Findings() {
        try (InputStream in = Findings.class.getClassLoader().getResourceAsStream("checks-catalogue.csv");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            boolean header = true;
            while ((line = r.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                if (header) {
                    header = false;
                    continue;
                }
                String[] p = line.split(",", 5);
                catalogue.put(p[0], new CheckDef(p[0], p[1], Severity.valueOf(p[2]), p[3], p.length > 4 ? p[4] : ""));
            }
        } catch (IOException | NullPointerException e) {
            throw new IllegalStateException("checks-catalogue.csv missing or unreadable", e);
        }
    }

    public Map<String, CheckDef> catalogue() {
        return catalogue;
    }

    public CheckDef def(String id) {
        CheckDef d = catalogue.get(id);
        if (d == null) {
            throw new IllegalArgumentException("Check id not in catalogue: " + id);
        }
        return d;
    }

    public void pass(String id, String scenario, String acct, LocalDate day) {
        def(id);
        passCounts.computeIfAbsent(id, k -> new int[1])[0]++;
    }

    public void fail(String id, String scenario, String acct, LocalDate day, String expected, String actual, String message) {
        def(id);
        all.add(new Finding(id, Status.FAIL, scenario, acct, day, expected, actual, message));
    }

    public void notFound(String id, String scenario, String acct, LocalDate day, String message) {
        def(id);
        all.add(new Finding(id, Status.NOT_FOUND, scenario, acct, day, "", "", message));
    }

    public void skipped(String id, String scenario, String acct, LocalDate day, String message) {
        def(id);
        all.add(new Finding(id, Status.SKIPPED, scenario, acct, day, "", "", message));
    }

    /** Convenience: assert and record. */
    public boolean check(boolean ok, String id, String scenario, String acct, LocalDate day, String expected, String actual, String message) {
        if (ok) {
            pass(id, scenario, acct, day);
        } else {
            fail(id, scenario, acct, day, expected, actual, message);
        }
        return ok;
    }

    public List<Finding> all() {
        return all;
    }

    public int passes(String id) {
        int[] c = passCounts.get(id);
        return c == null ? 0 : c[0];
    }

    public long failuresToday(String scenario, String acct, LocalDate day) {
        return all.stream().filter(f -> f.status() == Status.FAIL && f.businessDate() != null && f.businessDate().equals(day)
                && acct.equals(f.accountNumber())).count();
    }

    public boolean anyAtOrAbove(Severity threshold, boolean countNotFound) {
        if (threshold == Severity.NONE) {
            return false;
        }
        return all.stream().anyMatch(f -> (f.status() == Status.FAIL || (countNotFound && f.status() == Status.NOT_FOUND))
                && def(f.checkId()).severity().ordinal() <= threshold.ordinal());
    }
}
