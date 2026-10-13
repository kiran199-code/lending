package com.bank.lending.e2e.run;

import com.bank.lending.e2e.scenario.Scenario;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Fits the scenario plan to the products that actually exist, so a run never stops (or never even starts) because a sample
 * product was not created. Read-only: it only looks at the product list.
 *
 * <p>For each planned account: if its product exists and is usable (ACTIVE, not a mortgage product) the amount, tenure, rate
 * and frequency are moved inside that product's limits where they fall outside; if the product is missing (or unusable) and
 * {@code e2e.products.fallback=AUTO} the closest existing ACTIVE unsecured product is used instead, with the scenario's
 * product-specific overrides (day count, moratorium, balloon, step-up) dropped; with {@code SKIP} - or when there is no
 * usable product at all - the account is left out and reported. Every change is listed in {@link Result#notes()}.
 */
public final class ProductResolver {

    /** {@code scenarios} has the same size and order as the input; an entry is null when that account is skipped. */
    public record Result(List<Scenario> scenarios, List<String> notes, List<String> skipped) {
        public int usable() {
            return (int) scenarios.stream().filter(s -> s != null).count();
        }
    }

    private ProductResolver() {
    }

    public static Result resolve(JsonNode products, List<Scenario> planned, boolean autoFallback) {
        List<Scenario> out = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<JsonNode> usable = new ArrayList<>();
        for (JsonNode p : products) {
            if (isUsable(p)) {
                usable.add(p);
            }
        }
        for (int i = 0; i < planned.size(); i++) {
            Scenario sc = planned.get(i);
            String tag = "#" + (i + 1) + " " + sc.scenarioId();
            JsonNode own = find(products, sc.productCode());
            if (own != null && isUsable(own)) {
                out.add(fit(sc, own, false, tag, notes));
                continue;
            }
            String why = own == null ? "product " + sc.productCode() + " does not exist"
                    : "product " + sc.productCode() + " is " + (own.path("status").asText().equals("ACTIVE") ? "a MORTGAGE product" : own.path("status").asText());
            JsonNode alt = autoFallback ? closest(usable, sc) : null;
            if (alt == null) {
                out.add(null);
                skipped.add(tag + ": " + why + (autoFallback ? " and no other usable product exists" : " (e2e.products.fallback=SKIP)") + " - account skipped");
                continue;
            }
            notes.add(tag + ": " + why + "; using " + alt.path("productCode").asText() + " instead");
            out.add(fit(sc, alt, true, tag, notes));
        }
        return new Result(out, notes, skipped);
    }

    private static boolean isUsable(JsonNode p) {
        return "ACTIVE".equals(p.path("status").asText()) && !"MORTGAGE".equals(p.path("accountType").asText());
    }

    /** The usable product that needs the fewest changes to the plan: inside its limits first, then plain EMI, then fixed rate. */
    private static JsonNode closest(List<JsonNode> usable, Scenario sc) {
        return usable.stream().min(Comparator
                .comparingInt((JsonNode p) -> misses(p, sc))
                .thenComparingInt(p -> "EMI".equals(p.path("scheduleType").asText("EMI")) ? 0 : 1)
                .thenComparingInt(p -> "FIXED".equals(p.path("rateType").asText()) ? 0 : 1)
                .thenComparing(p -> p.path("productCode").asText())).orElse(null);
    }

    private static int misses(JsonNode p, Scenario sc) {
        int m = 0;
        if (outside(sc.amount(), p.path("minSanctionedAmount"), p.path("maxSanctionedAmount"))) {
            m++;
        }
        if (outside(BigDecimal.valueOf(sc.tenureMonths()), p.path("minTenureMonths"), p.path("maxTenureMonths"))) {
            m++;
        }
        return m;
    }

    private static boolean outside(BigDecimal v, JsonNode min, JsonNode max) {
        return min.isNumber() && v.compareTo(new BigDecimal(min.asText())) < 0
                || max.isNumber() && v.compareTo(new BigDecimal(max.asText())) > 0;
    }

    private static Scenario fit(Scenario sc, JsonNode p, boolean substituted, String tag, List<String> notes) {
        String code = p.path("productCode").asText();
        BigDecimal amount = clamp(sc.amount(), p.path("minSanctionedAmount"), p.path("maxSanctionedAmount"));
        BigDecimal rate = sc.interestRate() == null ? null : clamp(sc.interestRate(), p.path("minInterestRate"), p.path("maxInterestRate"));
        String freq = sc.repaymentFrequency();
        String dayCount = sc.dayCount();
        Integer moratorium = sc.moratoriumMonths();
        BigDecimal balloon = sc.balloonPercent();
        BigDecimal stepUp = sc.stepUpPercent();
        if (substituted) {
            freq = null;
            rate = null;
            dayCount = null;
            moratorium = null;
            balloon = null;
            stepUp = null;
        } else if (freq != null && !allowed(p, freq)) {
            notes.add(tag + ": frequency " + freq + " is not allowed by " + code + "; using its default");
            freq = null;
        }
        String effective = freq != null ? freq : p.path("repaymentFrequency").asText("MONTHLY");
        int period = periodMonths(effective);
        int tenure = sc.tenureMonths();
        int lo = p.path("minTenureMonths").isNumber() ? p.path("minTenureMonths").asInt() : 1;
        int hi = p.path("maxTenureMonths").isNumber() ? p.path("maxTenureMonths").asInt() : Integer.MAX_VALUE;
        tenure = Math.max(lo, Math.min(hi, tenure));
        if (tenure % period != 0) {
            int down = tenure - tenure % period;
            int up = down + period;
            tenure = down >= lo && down > 0 ? down : Math.min(up, hi);
        }
        if (amount.compareTo(sc.amount()) != 0 || tenure != sc.tenureMonths()
                || (rate != null && sc.interestRate() != null && rate.compareTo(sc.interestRate()) != 0)) {
            notes.add(tag + ": adjusted to " + code + "'s limits (amount " + sc.amount().toPlainString() + " -> " + amount.toPlainString()
                    + ", tenure " + sc.tenureMonths() + " -> " + tenure + "m"
                    + (rate != null && sc.interestRate() != null && rate.compareTo(sc.interestRate()) != 0
                    ? ", rate " + sc.interestRate().toPlainString() + " -> " + rate.toPlainString() : "") + ")");
        }
        return new Scenario(sc.scenarioId(), sc.cohort(), sc.weight(), sc.description(), code, amount, tenure, freq, rate,
                dayCount, moratorium, balloon, stepUp, sc.behaviour(), sc.behaviourParams(), sc.observeDaysAfterLastDue(),
                sc.requiresFeatures());
    }

    private static boolean allowed(JsonNode p, String freq) {
        JsonNode a = p.path("allowedRepaymentFrequencies");
        if (!a.isArray() || a.size() == 0) {
            return freq.equals(p.path("repaymentFrequency").asText("MONTHLY"));
        }
        for (JsonNode n : a) {
            if (n.asText().equals(freq)) {
                return true;
            }
        }
        return false;
    }

    private static int periodMonths(String freq) {
        return switch (freq) {
            case "QUARTERLY" -> 3;
            case "HALF_YEARLY" -> 6;
            case "YEARLY" -> 12;
            default -> 1;
        };
    }

    private static BigDecimal clamp(BigDecimal v, JsonNode min, JsonNode max) {
        if (min.isNumber() && v.compareTo(new BigDecimal(min.asText())) < 0) {
            return new BigDecimal(min.asText());
        }
        if (max.isNumber() && v.compareTo(new BigDecimal(max.asText())) > 0) {
            return new BigDecimal(max.asText());
        }
        return v;
    }

    private static JsonNode find(JsonNode array, String code) {
        for (JsonNode p : array) {
            if (code.equals(p.path("productCode").asText())) {
                return p;
            }
        }
        return null;
    }
}
