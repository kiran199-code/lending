package com.bank.lending.chit.common;

import java.util.List;

/** Thrown when a group's rules do not reconcile. Carries every violation, not just the first. */
public class ConfigInvalidException extends ChitException {

    private final List<RuleViolation> violations;

    public ConfigInvalidException(List<RuleViolation> violations) {
        super("CONFIG_INVALID", summarise(violations));
        this.violations = List.copyOf(violations);
    }

    public List<RuleViolation> violations() {
        return violations;
    }

    private static String summarise(List<RuleViolation> violations) {
        StringBuilder sb = new StringBuilder("Chit configuration is invalid: ");
        for (int i = 0; i < violations.size(); i++) {
            if (i > 0) {
                sb.append("; ");
            }
            sb.append(violations.get(i).code()).append(" (").append(violations.get(i).field()).append(")");
        }
        return sb.toString();
    }
}
