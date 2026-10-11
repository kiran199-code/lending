package com.bank.lending.e2e.config;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Hard stop against pointing the suite at production. There is intentionally no "force" switch.
 *
 * <p>A run is refused when ANY of these hold:
 * <ul>
 *   <li>the profile is not in {@code e2e.guard.allowed-profiles} (default local, dev, sit, uat)</li>
 *   <li>the profile or {@code e2e.env.name} equals / contains a word in {@code e2e.guard.production-words}</li>
 *   <li>any target URL's host matches a regular expression in {@code e2e.guard.production-host-patterns}</li>
 *   <li>any target URL's host is NOT matched by {@code e2e.guard.allowed-host-patterns}, when that list is non-empty
 *       (an allow-list is the safer setting for shared environments)</li>
 *   <li>no target URL is configured at all</li>
 * </ul>
 */
public final class EnvironmentGuard {

    private EnvironmentGuard() {
    }

    public record Verdict(boolean allowed, List<String> reasons) {
    }

    public static Verdict evaluate(E2eConfig cfg) {
        java.util.ArrayList<String> reasons = new java.util.ArrayList<>();
        String profile = cfg.profile();
        List<String> allowedProfiles = lower(cfg.getList("e2e.guard.allowed-profiles"));
        if (!allowedProfiles.contains(profile)) {
            reasons.add("profile '" + profile + "' is not in e2e.guard.allowed-profiles " + allowedProfiles);
        }
        String envName = cfg.get("e2e.env.name", "").toLowerCase(Locale.ROOT);
        for (String word : lower(cfg.getList("e2e.guard.production-words"))) {
            if (profile.contains(word) || envName.contains(word)) {
                reasons.add("profile/environment name '" + profile + "/" + envName + "' contains production word '" + word + "'");
            }
        }
        List<String> urls = new java.util.ArrayList<>();
        for (String key : List.of("e2e.lending.base-url", "e2e.batch.base-url", "e2e.admin.base-url")) {
            String u = cfg.get(key);
            if (u != null && !u.isEmpty()) {
                urls.add(u);
            }
        }
        if (urls.isEmpty()) {
            reasons.add("no target URL configured (e2e.lending.base-url / e2e.batch.base-url / e2e.admin.base-url)");
        }
        List<String> prodHosts = cfg.getList("e2e.guard.production-host-patterns");
        List<String> allowHosts = cfg.getList("e2e.guard.allowed-host-patterns");
        for (String url : urls) {
            String host;
            try {
                host = URI.create(url).getHost();
            } catch (IllegalArgumentException e) {
                reasons.add("unparseable URL '" + url + "'");
                continue;
            }
            if (host == null) {
                reasons.add("URL without host '" + url + "'");
                continue;
            }
            String h = host.toLowerCase(Locale.ROOT);
            for (String p : prodHosts) {
                if (Pattern.compile(p, Pattern.CASE_INSENSITIVE).matcher(h).find()) {
                    reasons.add("host '" + h + "' matches production pattern '" + p + "'");
                }
            }
            if (!allowHosts.isEmpty() && allowHosts.stream().noneMatch(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE).matcher(h).find())) {
                reasons.add("host '" + h + "' is not matched by e2e.guard.allowed-host-patterns " + allowHosts);
            }
        }
        return new Verdict(reasons.isEmpty(), List.copyOf(reasons));
    }

    /** Throws when the verdict is not "allowed". */
    public static void enforce(E2eConfig cfg) {
        Verdict v = evaluate(cfg);
        if (!v.allowed()) {
            throw new ProductionRefusedException("REFUSING TO RUN: " + String.join("; ", v.reasons()));
        }
    }

    private static List<String> lower(List<String> in) {
        return in.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
    }

    public static final class ProductionRefusedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ProductionRefusedException(String message) {
            super(message);
        }
    }
}
