package com.bank.lending.e2e.calc;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Property checks on a generated repayment schedule. Instead of re-implementing the product's amortisation
 * (which would only prove the code equals itself) it asserts what must be true of ANY correct schedule for the
 * account's terms, using day-count maths that is independent of lending-service.
 */
public final class ScheduleInvariants {

    private ScheduleInvariants() {
    }

    public record Terms(String scheduleType, String repaymentFrequency, int tenureMonths, BigDecimal disbursed,
                        BigDecimal ratePct, DayCount.Convention dayCount, int moratoriumMonths,
                        BigDecimal balloonPct, BigDecimal stepUpPct, LocalDate firstDue) {
    }

    public record Row(int no, LocalDate due, BigDecimal principal, BigDecimal interest, BigDecimal balance, String status) {
        public Row(int no, LocalDate due, BigDecimal principal, BigDecimal interest, BigDecimal balance) {
            this(no, due, principal, interest, balance, "");
        }

        public BigDecimal emi() {
            return principal.add(interest);
        }
    }

    public static List<Row> rows(JsonNode scheduleArray) {
        List<Row> out = new ArrayList<>();
        for (JsonNode n : scheduleArray) {
            out.add(new Row(n.path("installmentNumber").asInt(), LocalDate.parse(n.path("dueDate").asText()),
                    new BigDecimal(n.path("principalComponent").asText("0")), new BigDecimal(n.path("interestComponent").asText("0")),
                    new BigDecimal(n.path("outstandingBalance").asText("0")), n.path("installmentStatus").asText("")));
        }
        out.sort(java.util.Comparator.comparingInt(Row::no));
        return out;
    }

    public static int periodMonths(String frequency) {
        return switch (frequency) {
            case "MONTHLY" -> 1;
            case "QUARTERLY" -> 3;
            case "HALF_YEARLY" -> 6;
            case "YEARLY" -> 12;
            default -> throw new IllegalArgumentException("Unknown frequency " + frequency);
        };
    }

    /** @return human-readable violations; empty list = schedule is consistent with the terms. */
    public static List<String> verify(Terms t, List<Row> rows, BigDecimal tol) {
        List<String> v = new ArrayList<>();
        int period = periodMonths(t.repaymentFrequency());
        int expected = t.tenureMonths() / period;
        if (rows.size() != expected) {
            v.add("instalment count " + rows.size() + " != tenure/frequency " + expected);
            return v;
        }
        BigDecimal opening = t.disbursed();
        BigDecimal principalSum = BigDecimal.ZERO;
        LocalDate periodStart = t.firstDue().minusMonths(period);
        int moratoriumInstalments = t.moratoriumMonths() / period;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            LocalDate wantDue = t.firstDue().plusMonths((long) i * period);
            if (!r.due().equals(wantDue)) {
                v.add("#" + r.no() + " due " + r.due() + " != expected " + wantDue);
            }
            BigDecimal wantInt = opening.multiply(t.ratePct()).divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)
                    .multiply(DayCount.yearFraction(periodStart, r.due(), t.dayCount())).setScale(2, RoundingMode.HALF_UP);
            // tolerance: the product may size instalments on a smoothed rate; interest must still be within tol of the day-count figure
            if (r.interest().subtract(wantInt).abs().compareTo(tol.max(opening.multiply(new BigDecimal("0.0005")))) > 0) {
                v.add("#" + r.no() + " interest " + r.interest() + " vs day-count " + t.dayCount() + " expectation " + wantInt);
            }
            if (r.balance().compareTo(opening.subtract(r.principal())) != 0
                    && r.balance().subtract(opening.subtract(r.principal())).abs().compareTo(tol) > 0) {
                v.add("#" + r.no() + " balance " + r.balance() + " != opening " + opening + " - principal " + r.principal());
            }
            if (r.principal().signum() < 0 || r.interest().signum() < 0) {
                v.add("#" + r.no() + " negative component");
            }
            principalSum = principalSum.add(r.principal());
            opening = r.balance();
            periodStart = r.due();
        }
        if (principalSum.subtract(t.disbursed()).abs().compareTo(tol) > 0) {
            v.add("principal total " + principalSum + " != disbursed " + t.disbursed());
        }
        if (rows.get(rows.size() - 1).balance().abs().compareTo(tol) > 0) {
            v.add("closing balance " + rows.get(rows.size() - 1).balance() + " is not zero");
        }
        typeRules(t, rows, tol, period, moratoriumInstalments, v);
        return v;
    }

    private static void typeRules(Terms t, List<Row> rows, BigDecimal tol, int period, int mor, List<String> v) {
        int n = rows.size();
        for (int i = 0; i < Math.min(mor, n - 1); i++) {
            if (rows.get(i).principal().abs().compareTo(tol) > 0) {
                v.add("moratorium instalment #" + rows.get(i).no() + " carries principal " + rows.get(i).principal());
            }
        }
        switch (t.scheduleType()) {
            case "EMI" -> {
                BigDecimal ref = null;
                for (int i = mor; i < n - 1; i++) {
                    BigDecimal e = rows.get(i).emi();
                    if (ref == null) {
                        ref = e;
                    } else if (e.subtract(ref).abs().compareTo(tol.max(ref.multiply(new BigDecimal("0.002")))) > 0) {
                        v.add("EMI not level: #" + rows.get(i).no() + " " + e + " vs " + ref);
                    }
                }
            }
            case "EQUAL_PRINCIPAL" -> {
                BigDecimal ref = null;
                for (int i = mor; i < n - 1; i++) {
                    BigDecimal p = rows.get(i).principal();
                    if (ref == null) {
                        ref = p;
                    } else if (p.subtract(ref).abs().compareTo(tol) > 0) {
                        v.add("equal-principal violated at #" + rows.get(i).no() + ": " + p + " vs " + ref);
                    }
                }
            }
            case "INTEREST_ONLY" -> {
                for (int i = 0; i < n - 1; i++) {
                    if (rows.get(i).principal().abs().compareTo(tol) > 0) {
                        v.add("interest-only instalment #" + rows.get(i).no() + " carries principal");
                    }
                }
                if (rows.get(n - 1).principal().subtract(t.disbursed()).abs().compareTo(tol) > 0) {
                    v.add("bullet instalment principal " + rows.get(n - 1).principal() + " != full principal");
                }
            }
            case "BALLOON" -> {
                if (t.balloonPct() != null) {
                    BigDecimal balloon = t.disbursed().multiply(t.balloonPct()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                    if (rows.get(n - 1).principal().compareTo(balloon.subtract(tol)) < 0) {
                        v.add("last principal " + rows.get(n - 1).principal() + " is below the balloon amount " + balloon);
                    }
                }
            }
            case "STEP_UP_EMI" -> {
                for (int i = mor + 1; i < n - 1; i++) {
                    if (rows.get(i).emi().compareTo(rows.get(i - 1).emi().subtract(tol)) < 0) {
                        v.add("step-up instalment fell: #" + rows.get(i).no());
                    }
                }
            }
            default -> v.add("unknown scheduleType " + t.scheduleType());
        }
    }
}
