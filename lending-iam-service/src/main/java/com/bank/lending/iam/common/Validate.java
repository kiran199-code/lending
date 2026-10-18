package com.bank.lending.iam.common;

import java.util.regex.Pattern;

/** Input checks that the services apply themselves, so no caller can skip them. */
public final class Validate {
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._@-]{2,59}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern MOBILE = Pattern.compile("^\\+?[0-9 ()-]{6,30}$");
    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9_.-]{2,40}$");
    private static final Pattern SCOPE = Pattern.compile("^(\\*|[A-Za-z0-9_.-]{1,40})$");
    private static final Pattern CURRENCY = Pattern.compile("^(\\*|[A-Z]{3})$");
    private static final Pattern REF = Pattern.compile("^[A-Za-z0-9_.:/-]{1,120}$");

    private Validate() {}

    public static String username(String v) {
        if (v == null || !USERNAME.matcher(v.trim()).matches()) {
            throw IamException.badRequest("VALIDATION_FAILED", "username must be 3 to 60 characters: letters, digits, dot, underscore, at sign and hyphen, starting with a letter or digit");
        }
        return v.trim();
    }

    public static String displayName(String v) {
        if (v == null || v.isBlank() || v.length() > 120 || v.chars().anyMatch(Character::isISOControl)) {
            throw IamException.badRequest("VALIDATION_FAILED", "displayName is required (up to 120 characters, no control characters)");
        }
        return v.trim();
    }

    public static String email(String v, boolean required) {
        if (v == null || v.isBlank()) {
            if (required) throw IamException.badRequest("VALIDATION_FAILED", "email is required");
            return null;
        }
        if (v.length() > 160 || !EMAIL.matcher(v.trim()).matches()) throw IamException.badRequest("VALIDATION_FAILED", "email is not valid");
        return v.trim();
    }

    public static String mobile(String v) {
        if (v == null || v.isBlank()) return null;
        if (!MOBILE.matcher(v.trim()).matches()) throw IamException.badRequest("VALIDATION_FAILED", "mobile is not valid");
        return v.trim();
    }

    public static String code(String v, String field) {
        if (v == null || !CODE.matcher(v.trim()).matches()) {
            throw IamException.badRequest("VALIDATION_FAILED", field + " must be 2 to 40 characters: letters, digits, dot, underscore, hyphen");
        }
        return v.trim();
    }

    public static String scopeValue(String v) {
        if (v == null || !SCOPE.matcher(v.trim()).matches()) throw IamException.badRequest("VALIDATION_FAILED", "scope value must be * or up to 40 letters, digits, dot, underscore, hyphen");
        return v.trim();
    }

    public static String currency(String v) {
        if (v == null || !CURRENCY.matcher(v.trim()).matches()) throw IamException.badRequest("VALIDATION_FAILED", "currency must be a three-letter code or *");
        return v.trim();
    }

    public static String ref(String v, String field) {
        if (v == null || !REF.matcher(v.trim()).matches()) throw IamException.badRequest("VALIDATION_FAILED", field + " must be 1 to 120 characters: letters, digits and . _ : / -");
        return v.trim();
    }

    public static String text(String v, String field, int max, boolean required) {
        if (v == null || v.isBlank()) {
            if (required) throw IamException.badRequest("VALIDATION_FAILED", field + " is required");
            return null;
        }
        if (v.length() > max || v.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n')) {
            throw IamException.badRequest("VALIDATION_FAILED", field + " must be at most " + max + " characters");
        }
        return v.trim();
    }

    public static <T> T oneOf(T v, String field, T... allowed) {
        for (T a : allowed) if (a.equals(v)) return v;
        throw IamException.badRequest("VALIDATION_FAILED", field + " is not one of the allowed values");
    }

    public static int page(Integer p) {
        return p == null || p < 0 ? 0 : Math.min(p, 100_000);
    }

    public static int size(Integer s) {
        return s == null || s < 1 ? 50 : Math.min(s, 200);
    }
}
