package com.bank.lending.e2e.server;

import com.bank.lending.e2e.config.E2eConfig;
import com.bank.lending.e2e.http.Http;
import com.bank.lending.e2e.run.E2eRunner;
import com.bank.lending.e2e.teardown.Teardown;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * REST trigger + tiny web console so the suite can be hosted (for example as a Render web service) and driven from a
 * browser or a pipeline instead of a shell:
 * <pre>
 *   GET  /                          web console (paste the token, press Dry run / Run)
 *   GET  /health                    liveness, no token
 *   POST /runs[?dryRun=true][&flow=secured]  start a run (optional flat JSON of e2e.* overrides); one run at a time.
 *                                   flow=secured runs the SECURED flow (collateral and facility in LCM first); default is unsecured
 *   GET  /runs                      run ids found in the results directory
 *   GET  /runs/current              state of the current / last run, with the log tail
 *   GET  /runs/{runId}/{report|defects|audit|checks|batch|manifest|cleanup}
 *   POST /runs/{runId}/teardown     foreclose the run's open accounts and emit the cleanup SQL
 * </pre>
 * Everything except /health and / needs the header {@code X-E2E-Token}. The server binds to loopback unless
 * {@code e2e.server.bind} is set and refuses a non-loopback bind without {@code e2e.server.token}. It takes its port from
 * {@code PORT} when that is set (Render, Heroku-style hosts), else {@code e2e.server.port}. The trigger can never change
 * target URLs, the profile, credentials or the production guard.
 */
public final class TriggerServer {

    private static final Map<String, String> FILES = Map.of("report", "run-report.md", "defects", "defects.md", "audit", "audit-trail.csv",
            "checks", "checks.csv", "batch", "batch-runs.csv", "manifest", "run-manifest.json");

    private final E2eConfig base;
    private final String token;
    private final AtomicReference<Map<String, Object>> current = new AtomicReference<>(Map.of("state", "IDLE"));
    private final Deque<String> logTail = new ArrayDeque<>();
    private volatile Instant keepAliveUntil = Instant.EPOCH;

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
        String envPort = System.getenv("PORT");
        int port = envPort != null && !envPort.isBlank() ? Integer.parseInt(envPort.trim()) : base.getInt("e2e.server.port");
        HttpServer server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        server.createContext("/health", ex -> send(ex, 200, "{\"status\":\"UP\"}"));
        server.createContext("/runs", this::runs);
        server.createContext("/", ex -> {
            if (ex.getRequestURI().getPath().equals("/")) {
                sendHtml(ex, console());
            } else {
                send(ex, 404, "{\"error\":\"unknown route\"}");
            }
        });
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        startKeepAlive();
        System.out.println("E2E trigger server on http://" + bind + ":" + port + " (profile " + base.profile() + ")");
    }

    /**
     * Free hosts put a web service to sleep after ~15 minutes without INBOUND requests, and outbound calls to the system
     * under test do not count. While a run is active (and for a while after, so the results can be fetched) the server
     * therefore calls its own public /health. Uses RENDER_EXTERNAL_URL, or e2e.server.public-url.
     */
    private void startKeepAlive() {
        String url = base.get("e2e.server.public-url", System.getenv("RENDER_EXTERNAL_URL"));
        if (url == null || url.isBlank()) {
            return;
        }
        final String health = url.replaceAll("/+$", "") + "/health";
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "e2e-keepalive");
            t.setDaemon(true);
            return t;
        });
        ses.scheduleWithFixedDelay(() -> {
            boolean running = "RUNNING".equals(current.get().get("state"));
            if (running || Instant.now().isBefore(keepAliveUntil)) {
                try {
                    client.send(HttpRequest.newBuilder(URI.create(health)).timeout(Duration.ofSeconds(20)).GET().build(),
                            HttpResponse.BodyHandlers.discarding());
                } catch (IOException | InterruptedException e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }, 1, 4, TimeUnit.MINUTES);
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
            ObjectNode n = Http.MAPPER.valueToTree(current.get());
            ArrayNode tail = n.putArray("log");
            synchronized (logTail) {
                logTail.forEach(tail::add);
            }
            send(ex, 200, Http.MAPPER.writeValueAsString(n));
        } else if (method.equals("GET") && path.equals("/runs")) {
            ArrayNode ids = Http.MAPPER.createArrayNode();
            Path root = Path.of(base.require("e2e.output-dir"));
            if (Files.isDirectory(root)) {
                try (Stream<Path> s = Files.list(root)) {
                    s.filter(Files::isDirectory).map(p -> p.getFileName().toString()).filter(n -> n.startsWith("E2E-")).sorted().forEach(ids::add);
                }
            }
            send(ex, 200, Http.MAPPER.writeValueAsString(ids));
        } else if (method.equals("GET") && path.matches("/runs/E2E-[0-9]+-[0-9A-F]+/[a-z]+")) {
            String[] p = path.split("/");
            String file = p[3].equals("cleanup") ? "cleanup-" + p[2] + ".sql" : FILES.get(p[3]);
            Path f = file == null ? null : Path.of(base.require("e2e.output-dir")).resolve(p[2]).resolve(file);
            if (f != null && Files.exists(f)) {
                send(ex, 200, Files.readString(f));
            } else {
                send(ex, 404, "{\"error\":\"not found\"}");
            }
        } else if (method.equals("POST") && path.matches("/runs/E2E-[0-9]+-[0-9A-F]+/teardown")) {
            teardown(ex, path.split("/")[2]);
        } else {
            send(ex, 404, "{\"error\":\"unknown route\"}");
        }
    }

    private void teardown(HttpExchange ex, String runId) throws IOException {
        synchronized (current) {
            if ("RUNNING".equals(current.get().get("state"))) {
                send(ex, 409, "{\"error\":\"a run is in progress\"}");
                return;
            }
        }
        StringBuilder out = new StringBuilder();
        int code;
        try {
            code = Teardown.run(E2eConfig.load(withBase(Map.of())), runId, line -> {
                System.out.println(line);
                out.append(line).append('\n');
            });
        } catch (Exception e) {
            send(ex, 500, Http.MAPPER.writeValueAsString(Map.of("error", String.valueOf(e.getMessage()))));
            return;
        }
        keepAliveUntil = Instant.now().plus(Duration.ofMinutes(base.getInt("e2e.server.keepalive-minutes")));
        send(ex, 200, Http.MAPPER.writeValueAsString(Map.of("exitCode", code, "log", out.toString())));
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
            // the trigger can never change where the suite points, who it logs in as, or loosen the guard
            overrides.keySet().removeIf(k -> k.startsWith("e2e.guard.") || k.endsWith("base-url") || k.endsWith("password")
                    || k.endsWith("username") || k.startsWith("e2e.server.") || k.equals("e2e.profile") || k.equals("e2e.output-dir"));
            String query = ex.getRequestURI().getQuery();
            boolean dry = query != null && query.contains("dryRun=true");
            if (query != null && query.toLowerCase(java.util.Locale.ROOT).contains("flow=secured")) {
                overrides.put("e2e.flow", "SECURED");
            }
            E2eConfig cfg = E2eConfig.load(withBase(overrides));
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("state", "RUNNING");
            state.put("dryRun", dry);
            state.put("startedAt", Instant.now().toString());
            state.put("overrides", overrides);
            current.set(state);
            synchronized (logTail) {
                logTail.clear();
            }
            Thread t = new Thread(() -> {
                Map<String, Object> done = new LinkedHashMap<>();
                try {
                    E2eRunner.Outcome o = new E2eRunner(cfg, this::log).execute(dry);
                    done.put("state", "FINISHED");
                    done.put("runId", o.runId());
                    done.put("exitCode", o.exitCode());
                    done.put("summary", o.summary());
                } catch (Exception e) {
                    done.put("state", "ERROR");
                    done.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                    log("ERROR " + e);
                }
                done.put("dryRun", dry);
                done.put("finishedAt", Instant.now().toString());
                keepAliveUntil = Instant.now().plus(Duration.ofMinutes(base.getInt("e2e.server.keepalive-minutes")));
                current.set(done);
            }, "e2e-run");
            t.start();
            send(ex, 202, "{\"state\":\"RUNNING\"}");
        }
    }

    private void log(String line) {
        System.out.println(line);
        synchronized (logTail) {
            logTail.addLast(line);
            while (logTail.size() > 200) {
                logTail.removeFirst();
            }
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
        ex.getResponseHeaders().add("Content-Type", body.startsWith("{") || body.startsWith("[") ? "application/json" : "text/plain; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    private static void sendHtml(HttpExchange ex, String html) throws IOException {
        byte[] b = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    private static String console() throws IOException {
        try (InputStream in = TriggerServer.class.getClassLoader().getResourceAsStream("console.html")) {
            return in == null ? "<p>console.html missing</p>" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** For tests. */
    static List<String> fileKeys() {
        return List.copyOf(FILES.keySet());
    }
}
