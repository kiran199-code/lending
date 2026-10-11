package com.bank.lending.e2e.scenario;

import com.bank.lending.e2e.scenario.Scenario.Behaviour;
import com.bank.lending.e2e.scenario.Scenario.Cohort;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Loads the scenario matrix from CSV (header row required, '#' comment lines allowed). */
public final class ScenarioLoader {

    private ScenarioLoader() {
    }

    public static List<Scenario> load(String location) {
        try (InputStream in = open(location)) {
            return parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().collect(Collectors.toList()));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read scenario file '" + location + "': " + e.getMessage(), e);
        }
    }

    private static InputStream open(String location) throws IOException {
        if (location.startsWith("classpath:")) {
            InputStream in = ScenarioLoader.class.getClassLoader().getResourceAsStream(location.substring("classpath:".length()));
            if (in == null) {
                throw new IOException("not on classpath");
            }
            return in;
        }
        return Files.newInputStream(Path.of(location));
    }

    public static List<Scenario> parse(List<String> lines) {
        List<String> rows = lines.stream().filter(l -> !l.isBlank() && !l.trim().startsWith("#")).toList();
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Scenario file has no rows");
        }
        List<String> header = split(rows.get(0));
        List<Scenario> out = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> cells = split(rows.get(i));
            Map<String, String> r = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) {
                r.put(header.get(c).trim(), c < cells.size() ? cells.get(c).trim() : "");
            }
            try {
                out.add(toScenario(r));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Scenario row " + (i + 1) + " (" + r.get("scenarioId") + "): " + e.getMessage(), e);
            }
        }
        long distinct = out.stream().map(Scenario::scenarioId).distinct().count();
        if (distinct != out.size()) {
            throw new IllegalArgumentException("Duplicate scenarioId in scenario file");
        }
        return out;
    }

    private static Scenario toScenario(Map<String, String> r) {
        Map<String, String> params = new LinkedHashMap<>();
        String p = r.getOrDefault("behaviourParams", "");
        if (!p.isBlank()) {
            for (String kv : p.split(";")) {
                String[] parts = kv.split("=", 2);
                if (parts.length != 2) {
                    throw new IllegalArgumentException("bad behaviourParams entry '" + kv + "'");
                }
                params.put(parts[0].trim(), parts[1].trim());
            }
        }
        return new Scenario(
                req(r, "scenarioId"),
                Cohort.valueOf(req(r, "cohort").toUpperCase()),
                intOr(r.get("weight"), 1),
                r.getOrDefault("description", ""),
                req(r, "productCode"),
                new BigDecimal(req(r, "amount")),
                Integer.parseInt(req(r, "tenureMonths")),
                blankToNull(r.get("repaymentFrequency")),
                dec(r.get("interestRate")),
                blankToNull(r.get("dayCount")),
                blankToNull(r.get("moratoriumMonths")) == null ? null : Integer.valueOf(r.get("moratoriumMonths")),
                dec(r.get("balloonPercent")),
                dec(r.get("stepUpPercent")),
                Behaviour.valueOf(req(r, "behaviour").toUpperCase()),
                Map.copyOf(params),
                intOr(r.get("observeDaysAfterLastDue"), 0),
                r.getOrDefault("requiresFeatures", "").isBlank() ? List.of()
                        : Arrays.stream(r.get("requiresFeatures").split("\\|")).map(String::trim).toList());
    }

    private static String req(Map<String, String> r, String k) {
        String v = r.get(k);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("column '" + k + "' is required");
        }
        return v;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static BigDecimal dec(String s) {
        return s == null || s.isBlank() ? null : new BigDecimal(s);
    }

    private static int intOr(String s, int d) {
        return s == null || s.isBlank() ? d : Integer.parseInt(s.trim());
    }

    /** Minimal CSV splitter: commas, double-quoted cells with "" escapes. */
    static List<String> split(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cur.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                cells.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        cells.add(cur.toString());
        return cells;
    }

    /**
     * Expands the matrix to exactly {@code n} accounts honouring cohort split and weights:
     * round-robin over the cohort's scenarios (weight = repeat factor). Cohort A gets {@code cohortAPercent} % of n.
     */
    public static List<Scenario> expand(List<Scenario> matrix, int n, int cohortAPercent) {
        int nA = (int) Math.round(n * cohortAPercent / 100.0);
        List<Scenario> out = new ArrayList<>();
        out.addAll(roundRobin(matrix, Cohort.A, nA));
        out.addAll(roundRobin(matrix, Cohort.B, n - nA));
        return out;
    }

    private static List<Scenario> roundRobin(List<Scenario> matrix, Cohort cohort, int count) {
        List<Scenario> pool = new ArrayList<>();
        for (Scenario s : matrix) {
            if (s.cohort() == cohort) {
                for (int i = 0; i < s.weight(); i++) { // weight 0 = row disabled
                    pool.add(s);
                }
            }
        }
        if (pool.isEmpty() && count > 0) {
            throw new IllegalArgumentException("No scenarios for cohort " + cohort + " but " + count + " accounts requested");
        }
        List<Scenario> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(pool.get(i % pool.size()));
        }
        return out;
    }
}
