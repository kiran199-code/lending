package com.bank.lending.iam.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Makes an audit detail safe: values of keys that look like secrets are replaced, e-mail addresses and
 * mobile numbers are masked, at every level of nesting.
 */
public final class DetailSanitizer {
    private static final List<String> SECRET_KEYS = List.of("password", "secret", "token", "otp", "recovery", "private", "hash", "totp", "authorization", "apikey");

    private DetailSanitizer() {}

    @SuppressWarnings("unchecked")
    public static Object sanitize(String key, Object value) {
        String k = key == null ? "" : key.toLowerCase(Locale.ROOT);
        if (value == null) return null;
        // "count" style keys such as tokensRevoked hold numbers, not secrets
        if (!(value instanceof Number) && !(value instanceof Boolean) && SECRET_KEYS.stream().anyMatch(k::contains)) return "[redacted]";
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<Object, Object>) m).forEach((kk, vv) -> out.put(String.valueOf(kk), sanitize(String.valueOf(kk), vv)));
            return out;
        }
        if (value instanceof List<?> l) {
            return l.stream().map(x -> sanitize(key, x)).toList();
        }
        if (value instanceof String s) {
            if (k.contains("email") || k.contains("mail")) return maskEmail(s);
            if (k.contains("mobile") || k.contains("phone")) return maskMobile(s);
        }
        return value;
    }

    public static Map<String, Object> sanitizeMap(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        in.forEach((k, v) -> out.put(k, sanitize(k, v)));
        return out;
    }

    public static String maskEmail(String e) {
        if (e == null) return null;
        int at = e.indexOf('@');
        if (at <= 0) return "***";
        return e.charAt(0) + "***" + e.substring(at);
    }

    public static String maskMobile(String m) {
        if (m == null) return null;
        String digits = m.replaceAll("[^0-9]", "");
        if (digits.length() <= 4) return "***";
        return "***" + digits.substring(digits.length() - 4);
    }
}
