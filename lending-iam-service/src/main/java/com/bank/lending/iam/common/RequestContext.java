package com.bank.lending.iam.common;

/**
 * Who is calling and from where, for the audit trail. Set by the request filter and the access guard,
 * cleared when the request ends. Outside a request (start-up, scheduled jobs) the actor is SYSTEM.
 */
public final class RequestContext {
    private static final ThreadLocal<RequestContext> CURRENT = new ThreadLocal<>();

    private final String correlationId;
    private final String ip;
    private String actorType = "ANONYMOUS";
    private String actor = "anonymous";

    public RequestContext(String correlationId, String ip) {
        this.correlationId = correlationId;
        this.ip = ip;
    }

    public static RequestContext current() {
        RequestContext c = CURRENT.get();
        return c != null ? c : new RequestContext(null, null).as("SYSTEM", "system");
    }

    public static void set(RequestContext c) {
        CURRENT.set(c);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Runs a task as the system (scheduled jobs, start-up). */
    public static void runAsSystem(String name, Runnable r) {
        RequestContext previous = CURRENT.get();
        CURRENT.set(new RequestContext(null, null).as("SYSTEM", name));
        try {
            r.run();
        } finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }

    public RequestContext as(String type, String name) {
        this.actorType = type;
        this.actor = name;
        return this;
    }

    public String correlationId() { return correlationId; }
    public String ip() { return ip; }
    public String actorType() { return actorType; }
    public String actor() { return actor; }
}
