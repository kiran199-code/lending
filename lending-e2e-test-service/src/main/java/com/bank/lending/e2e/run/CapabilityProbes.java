package com.bank.lending.e2e.run;

import com.bank.lending.e2e.config.E2eConfig;
import com.bank.lending.e2e.http.ApiResult;
import com.bank.lending.e2e.http.Http;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Decides "NOT FOUND" from evidence: it reads the OpenAPI documents the services publish and looks for an endpoint
 * that provides the capability. If no document can be read the capability is reported SKIPPED (unverified), never
 * silently passed and never assumed missing.
 */
public final class CapabilityProbes {

    /** capability check id -> regex over lower-cased "METHOD /path summary" lines. */
    private static final Map<String, Pattern> PROBES = new LinkedHashMap<>();

    static {
        PROBES.put("CAP-PENAL", Pattern.compile("penal"));
        PROBES.put("CAP-PROVISION", Pattern.compile("provision"));
        PROBES.put("CAP-NPA-SUSPENSE", Pattern.compile("suspense|interest-suspen|suspend-interest"));
        PROBES.put("CAP-LEDGER", Pattern.compile("journal|general-ledger|/gl[-/]|gl-balance|trial-balance|double-entry"));
        PROBES.put("CAP-RESTRUCTURE", Pattern.compile("restructur|reschedul"));
        PROBES.put("CAP-WRITEOFF", Pattern.compile("write-?off|write_off"));
        PROBES.put("CAP-CURE-RULE", Pattern.compile("\\bcure\\b|cure-period|upgrade-rule"));
        PROBES.put("CAP-THRESHOLDS", Pattern.compile("dpd-threshold|delinquency-(config|threshold|polic)|npa-(config|threshold)|classification-(config|polic|rule)"));
        PROBES.put("CAP-BIZDATE", Pattern.compile("business-date|/eod|eod-|rollover|day-end"));
    }

    private CapabilityProbes() {
    }

    public static Map<String, String> run(E2eConfig cfg, Findings findings, Http lending, Http admin, Http batch, LocalDate day) {
        List<String> corpus = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        collect(lending, cfg.get("e2e.probe.lending-openapi", "/v3/api-docs"), "lending-service", corpus, sources);
        collect(admin, cfg.get("e2e.probe.admin-openapi", "/api/admin/v3/api-docs"), "admin", corpus, sources);
        collect(batch, cfg.get("e2e.probe.batch-openapi", "/v3/api-docs"), "batch", corpus, sources);
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Pattern> e : PROBES.entrySet()) {
            String id = e.getKey();
            if (corpus.isEmpty()) {
                findings.skipped(id, "-", "-", day, "no OpenAPI document readable; capability unverified");
                result.put(id, "UNVERIFIED");
                continue;
            }
            boolean found = corpus.stream().anyMatch(l -> e.getValue().matcher(l).find());
            if (found) {
                findings.pass(id, "-", "-", day);
                result.put(id, "FOUND");
            } else {
                findings.notFound(id, "-", "-", day, "no endpoint in " + String.join(", ", sources) + " matches /" + e.getValue().pattern() + "/");
                result.put(id, "NOT_FOUND");
            }
        }
        return result;
    }

    private static void collect(Http http, String path, String name, List<String> corpus, List<String> sources) {
        if (http == null) {
            return;
        }
        ApiResult r = http.get(path);
        if (!r.ok() || !r.body().has("paths")) {
            return;
        }
        sources.add(name);
        Iterator<Map.Entry<String, JsonNode>> it = r.body().get("paths").fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> p = it.next();
            Iterator<Map.Entry<String, JsonNode>> ops = p.getValue().fields();
            while (ops.hasNext()) {
                Map.Entry<String, JsonNode> op = ops.next();
                corpus.add((op.getKey() + " " + p.getKey() + " " + op.getValue().path("summary").asText("") + " "
                        + op.getValue().path("operationId").asText("")).toLowerCase(Locale.ROOT));
            }
        }
    }
}
