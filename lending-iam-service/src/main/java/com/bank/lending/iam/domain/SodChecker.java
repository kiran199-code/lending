package com.bank.lending.iam.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Separation of duties: no one may hold both permissions of a rule. */
public final class SodChecker {
    public record Rule(long ruleId, String permissionA, String permissionB, String description) {}
    public record Violation(long ruleId, String permissionA, String permissionB, String description) {}

    private SodChecker() {}

    public static List<Violation> violations(Set<String> permissions, List<Rule> rules) {
        List<Violation> out = new ArrayList<>();
        for (Rule r : rules) {
            if (permissions.contains(r.permissionA()) && permissions.contains(r.permissionB())) {
                out.add(new Violation(r.ruleId(), r.permissionA(), r.permissionB(), r.description()));
            }
        }
        return out;
    }

    /** The union of the permissions of the given roles. */
    public static Set<String> union(Iterable<String> roleCodes, Map<String, Set<String>> permissionsByRole) {
        Set<String> all = new HashSet<>();
        for (String r : roleCodes) {
            Set<String> p = permissionsByRole.get(r);
            if (p != null) all.addAll(p);
        }
        return all;
    }

    public static String describe(List<Violation> v) {
        StringBuilder sb = new StringBuilder();
        for (Violation x : v) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(x.permissionA()).append(" + ").append(x.permissionB());
        }
        return sb.toString();
    }
}
