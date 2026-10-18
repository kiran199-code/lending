package com.bank.lending.iam.domain;

import com.bank.lending.iam.common.SecretHasher;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** The password rules of section 5.2. Every violated rule is reported, not only the first. */
public final class PasswordPolicy {
    public record Violation(String code, String message) {}

    private static final Set<String> COMMON = Set.of(
            "password", "passw0rd", "p@ssword", "p@ssw0rd", "123456", "1234567", "12345678", "123456789", "1234567890",
            "qwerty", "qwertyuiop", "abc123", "letmein", "welcome", "admin", "administrator", "iloveyou", "monkey",
            "dragon", "football", "baseball", "master", "login", "princess", "sunshine", "shadow", "superman",
            "trustno1", "changeme", "default", "secret", "root", "test", "guest", "india", "bank", "lending",
            "111111", "000000", "121212", "654321", "666666", "696969", "123123", "1q2w3e4r", "zaq12wsx", "asdfgh",
            "asdfghjkl", "zxcvbn", "zxcvbnm", "welcome1", "summer", "winter", "spring", "autumn", "january");
    private static final List<String> BANNED_SUBSTRINGS = List.of("password", "qwerty", "letmein", "welcome", "iloveyou", "changeme", "123456");

    private final int minLength;
    private final int minClasses;
    private final int historySize;
    private final int maxAgeStaffDays;
    private final int maxAgeCustomerDays;

    public PasswordPolicy(int minLength, int minClasses, int historySize, int maxAgeStaffDays, int maxAgeCustomerDays) {
        this.minLength = minLength;
        this.minClasses = minClasses;
        this.historySize = historySize;
        this.maxAgeStaffDays = maxAgeStaffDays;
        this.maxAgeCustomerDays = maxAgeCustomerDays;
    }

    public int historySize() { return historySize; }
    public int minLength() { return minLength; }

    /**
     * @param previousHashes the user's most recent password hashes (the policy looks at the newest {@code historySize})
     */
    public List<Violation> check(String password, String username, Collection<String> previousHashes, SecretHasher hasher) {
        List<Violation> v = new ArrayList<>();
        String p = password == null ? "" : password;
        if (p.length() < minLength) {
            v.add(new Violation("LENGTH", "Use at least " + minLength + " characters"));
        }
        if (p.length() > 128) {
            v.add(new Violation("TOO_LONG", "Use at most 128 characters"));
        }
        if (classes(p) < minClasses) {
            v.add(new Violation("CLASSES", "Use at least " + minClasses + " of: lower case, upper case, digit, symbol"));
        }
        String lower = p.toLowerCase(Locale.ROOT);
        if (username != null && !username.isBlank() && lower.contains(username.toLowerCase(Locale.ROOT))) {
            v.add(new Violation("CONTAINS_USERNAME", "The password must not contain the username"));
        }
        if (isCommon(p)) {
            v.add(new Violation("COMMON", "This password is too common"));
        }
        if (previousHashes != null && hasher != null && !p.isEmpty()) {
            int n = 0;
            for (String h : previousHashes) {
                if (n++ >= historySize) break;
                if (h != null && hasher.matches(p, h)) {
                    v.add(new Violation("REUSED", "Do not reuse one of your last " + historySize + " passwords"));
                    break;
                }
            }
        }
        return v;
    }

    public boolean isExpired(Instant changedAt, boolean customer, Instant now) {
        if (changedAt == null) return true;
        int days = customer ? maxAgeCustomerDays : maxAgeStaffDays;
        if (days <= 0) return false;
        return now.isAfter(changedAt.plus(Duration.ofDays(days)));
    }

    public static int classes(String p) {
        boolean lo = false, up = false, di = false, sy = false;
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (Character.isLowerCase(c)) lo = true;
            else if (Character.isUpperCase(c)) up = true;
            else if (Character.isDigit(c)) di = true;
            else if (!Character.isWhitespace(c)) sy = true;
        }
        return (lo ? 1 : 0) + (up ? 1 : 0) + (di ? 1 : 0) + (sy ? 1 : 0);
    }

    public static boolean isCommon(String p) {
        String lower = p.toLowerCase(Locale.ROOT);
        // strip trailing digits and symbols: "Password123!" is "password"
        int end = lower.length();
        while (end > 0 && !Character.isLetter(lower.charAt(end - 1))) end--;
        String stem = lower.substring(0, end);
        if (COMMON.contains(lower) || COMMON.contains(stem)) return true;
        for (String s : BANNED_SUBSTRINGS) if (lower.contains(s)) return true;
        return false;
    }
}
