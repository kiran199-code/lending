package com.bank.lending.chit.common;

/**
 * Who is acting, taken from the X-Actor header. Maker-checker compares these values.
 * When the IAM resource server is adopted, the value comes from the token subject instead and the header is ignored.
 */
public final class Actor {
    public static final String SERVICE_DEFAULT = "lending-service";

    private Actor() {
    }

    /** Human workflows (maker-checker, admin changes): the header is mandatory. */
    public static String required(String header) {
        if (header == null || header.isBlank()) {
            throw ChitException.badRequest("ACTOR_REQUIRED", "X-Actor header is required for this operation");
        }
        return clean(header);
    }

    /** System-to-system calls: falls back to the service name. */
    public static String orService(String header) {
        return header == null || header.isBlank() ? SERVICE_DEFAULT : clean(header);
    }

    private static String clean(String v) {
        String t = v.trim();
        return t.length() > 60 ? t.substring(0, 60) : t;
    }
}
