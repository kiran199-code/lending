package com.bank.lending.limits;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A LimitTransport that answers from a script and records every call. */
class ScriptedTransport implements LimitTransport {

    record Call(String method, String path, String key, String body) {}

    final List<Call> calls = new ArrayList<>();
    /** path suffix -> queue of answers; the last answer repeats. */
    private final Map<String, List<Object>> script = new LinkedHashMap<>();
    private final Map<String, Integer> used = new LinkedHashMap<>();

    /** Adds an answer for paths ending in {@code suffix}: a Response, or an IOException to throw. */
    ScriptedTransport on(String suffix, Object answer) {
        script.computeIfAbsent(suffix, k -> new ArrayList<>()).add(answer);
        return this;
    }

    ScriptedTransport ok(String suffix, String json) {
        return on(suffix, new Response(200, json));
    }

    ScriptedTransport error(String suffix, int status, String code, String message) {
        return on(suffix, new Response(status, "{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}"));
    }

    @Override
    public Response send(String method, String path, String idempotencyKey, String json) throws IOException {
        calls.add(new Call(method, path, idempotencyKey, json));
        for (Map.Entry<String, List<Object>> e : script.entrySet()) {
            if (path.endsWith(e.getKey())) {
                int n = used.merge(e.getKey(), 1, Integer::sum) - 1;
                Object a = e.getValue().get(Math.min(n, e.getValue().size() - 1));
                if (a instanceof IOException io) {
                    throw io;
                }
                return (Response) a;
            }
        }
        return new Response(200, "{}");
    }

    List<Call> callsTo(String suffix) {
        return calls.stream().filter(c -> c.path().endsWith(suffix)).toList();
    }
}
