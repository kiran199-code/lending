package com.bank.lending.e2e.server;

import com.bank.lending.e2e.config.E2eConfig;
import com.bank.lending.e2e.http.Http;
import com.bank.lending.e2e.run.E2eRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Optional REST trigger so a pipeline or an operator can start a run without shell access:
 * POST /runs[?dryRun=true]  (body: optional flat JSON of e2e.* overrides)  GET /runs/current  GET /runs/{runId}/{report|defects|audit}  GET /health.
 * One run at a time. Binds to loopback unless e2e.server.bind is set, and refuses a non-loopback bind without e2e.server.token.
 */
public final class TriggerServer {

    private final E2eConfig base;
    private final String token;
    private final AtomicReference<Map<String, Object>> current = new AtomicReference<>(Map.of("state", "IDLE"));

    public TriggerServer(E2eConfig base) {
        this.base = base;
        this.token = base.get("e2e.server.token", "");
    }

    public void start() throws IOException {
        String bind = base.get("e2e.server.bind", "127.0.0.1");
        boolean loopback = InetAddress.getByName(bind).isLoopbackAddress();
        if (!loopback && token.isEmpty()) {
            throw new IllegalStateException("Refusing to bind " + bind + " without e2e.server.token (E2E_SERVER_TOKEN)");
        }
        int port = base.getInt("e2e.server.port");
        HttpServer server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        server.createContext("/health", ex -> send(ex, 200, "{\"status\":\"UP\"}"));
        server.createContext("/runs", this::runs);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        System.out.println("E2E trigger server on http://" + bind + ":" + port + " (profile " + base.profile() + ")");
    }

    private void runs(HttpExchange ex) throws IOException {
        if (!token.isEmpty() && !token.equals(ex.getRequestHeaders().getFirst("X-E2E-Token"))) {
            send(ex, 401, "{\"error\":\"missing or wrong X-E2E-Token\"}");
            return;
        }
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        if (method.equals("POST") && path.equals("/runs")) {
            start(ex);
        } else if (method.equals("GET") && path.equals("/runs/current")) {
            send(ex, 200, Http.MAPPER.writeValueAsString(current.get()));
        } else if (method.equals("GET") && path.matches("/runs/E2E-[0-9]+-[0-9A-F]+/(report|defects|audit)")) {
            String[] p = path.split("/");
            String file = switch (p[3]) {
                case "report" -> "run-report.md";
                case "defects" -> "defects.md";
                default -> "audit-trail.csv";
            };
            Path f = Path.of(base.require("e2e.output-dir")).resolve(p[2]).resolve(file);
            if (Files.exists(f)) {
                send(ex, 200, Files.readString(f));
            } else {
                send(ex, 404, "{\"error\":\"not found\"}");
            }
        } else {
            send(ex, 404, "{\"error\":\"unknown route\"}");
        }
    }

    private void start(HttpExchange ex) throws IOException {
        synchronized (current) {
            if ("RUNNING".equals(current.get().get("state"))) {
                send(ex, 409, "{\"error\":\"a run is already in progress\"}");
                return;
            }
            Map<String, String> overrides = new LinkedHashMap<>();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (!body.isBlank()) {
                JsonNode n = Http.MAPPER.readTree(body);
                n.fields().forEachRemaining(e -> overrides.put(e.getKey().startsWith("e2e.") ? e.getKey() : "e2e." + e.getKey(), e.getValue().asText()));
            }
            // the trigger can never change where the suite points or loosen the guard
            overrides.keySet().removeIf(k -> k.startsWith("e2e.guard.") || k.endsWith("base-url") || k.endsWith("password") || k.equals("e2e.profile"));
            boolean dry = "true".equalsIgnoreCase(ex.getRequestURI().getQuery() == null ? "" : ex.getRequestURI().getQuery().replace("dryRun=", "").trim());
            E2eConfig cfg = E2eConfig.load(withBase(overrides));
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("state", "RUNNING");
            state.put("dryRun", dry);
            current.set(state);
            Thread t = new Thread(() -> {
                Map<String, Object> done = new LinkedHashMap<>();
                try {
                    StringBuilder log = new StringBuilder();
                    E2eRunner.Outcome o = new E2eRunner(cfg, line -> {
                        System.out.println(line);
                        log.append(line).append('\n');
                    }).execute(dry);
                    done.put("state", "FINISHED");
                    done.put("runId", o.runId());
                    done.put("exitCode", o.exitCode());
                    done.put("summary", o.summary());
                } catch (Exception e) {
                    done.put("state", "ERROR");
                    done.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                }
                current.set(done);
            }, "e2e-run");
            t.start();
            send(ex, 202, "{\"state\":\"RUNNING\"}");
        }
    }

    private Map<String, String> withBase(Map<String, String> overrides) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("e2e.profile", base.profile());
        m.putAll(overrides);
        return m;
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", body.startsWith("{") ? "application/json" : "text/plain; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }
}
