package com.bank.lending.e2e.config;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Every tunable of the suite. Nothing is hard-coded in the test logic: URLs, credentials, thresholds,
 * dates, population size and job lists all come from here.
 *
 * <p>Resolution order, lowest to highest precedence:
 * <ol>
 *   <li>{@code application.properties} on the classpath (labelled defaults)</li>
 *   <li>{@code application-<profile>.properties} on the classpath (local, dev, sit, uat)</li>
 *   <li>an external file named by {@code -De2e.config=...} or {@code E2E_CONFIG}</li>
 *   <li>environment variables: {@code E2E_BATCH_BASE_URL} overrides {@code e2e.batch.base-url}</li>
 *   <li>JVM system properties ({@code -De2e.accounts=40}) and CLI {@code --key=value} options</li>
 * </ol>
 * Credentials are never read from the classpath files: only from the environment / system properties.
 */
public final class E2eConfig {

    private final Properties props;
    private final String profile;

    private E2eConfig(Properties props, String profile) {
        this.props = props;
        this.profile = profile;
    }

    public static E2eConfig load(Map<String, String> cliOverrides) {
        Properties merged = new Properties();
        loadClasspath(merged, "application.properties");

        String profile = firstNonBlank(cliOverrides.get("e2e.profile"), System.getProperty("e2e.profile"),
                System.getenv("E2E_PROFILE"), merged.getProperty("e2e.profile"), "local");
        profile = profile.toLowerCase(Locale.ROOT).trim();
        loadClasspath(merged, "application-" + profile + ".properties");

        String external = firstNonBlank(cliOverrides.get("e2e.config"), System.getProperty("e2e.config"),
                System.getenv("E2E_CONFIG"), null);
        if (external != null) {
            try (InputStream in = Files.newInputStream(Path.of(external))) {
                merged.load(in);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read external config " + external + ": " + e.getMessage(), e);
            }
        }
        // environment: E2E_FOO_BAR -> e2e.foo.bar  (dots and dashes both map to '_')
        for (Map.Entry<String, String> env : System.getenv().entrySet()) {
            if (env.getKey().startsWith("E2E_")) {
                String key = env.getKey().toLowerCase(Locale.ROOT).replace('_', '.');
                merged.setProperty(canonical(merged, key), env.getValue());
            }
        }
        for (String name : System.getProperties().stringPropertyNames()) {
            if (name.startsWith("e2e.")) {
                merged.setProperty(name, System.getProperty(name));
            }
        }
        cliOverrides.forEach(merged::setProperty);
        merged.setProperty("e2e.profile", profile);
        applyFlowDefaults(merged, cliOverrides);
        return new E2eConfig(merged, profile);
    }

    /**
     * The SECURED flow has its own scenario file, account count, cohort split and day limit (e2e.secured.*). They replace the
     * general settings unless the general setting was given explicitly (CLI option, -D or E2E_ variable), so the two flows never
     * share a scenario matrix by accident.
     */
    private static void applyFlowDefaults(Properties merged, Map<String, String> cli) {
        if (!"SECURED".equalsIgnoreCase(merged.getProperty("e2e.flow", "UNSECURED").trim())) {
            return;
        }
        String[][] pairs = {{"e2e.scenario-file", "e2e.secured.scenario-file"}, {"e2e.accounts", "e2e.secured.accounts"},
                {"e2e.cohort-a-percent", "e2e.secured.cohort-a-percent"}, {"e2e.max-sim-days", "e2e.secured.max-sim-days"}};
        for (String[] p : pairs) {
            String dflt = merged.getProperty(p[1]);
            if (dflt != null && !dflt.isBlank() && !explicit(cli, p[0])) {
                merged.setProperty(p[0], dflt.trim());
            }
        }
    }

    private static boolean explicit(Map<String, String> cli, String key) {
        String env = key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
        return cli.containsKey(key) || System.getProperty(key) != null || System.getenv(env) != null;
    }

    /** Env var names cannot carry '-', so match "e2e.batch.base.url" to the declared "e2e.batch.base-url". */
    private static String canonical(Properties known, String envDerived) {
        String flat = envDerived.replace('-', '.');
        for (String declared : known.stringPropertyNames()) {
            if (declared.replace('-', '.').equals(flat)) {
                return declared;
            }
        }
        return envDerived;
    }

    private static void loadClasspath(Properties target, String resource) {
        try (InputStream in = E2eConfig.class.getClassLoader().getResourceAsStream(resource)) {
            if (in != null) {
                target.load(in);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + resource, e);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- typed accessors

    public String profile() {
        return profile;
    }

    public String get(String key) {
        String v = props.getProperty(key);
        return v == null ? null : v.trim();
    }

    public String require(String key) {
        String v = get(key);
        if (v == null || v.isEmpty()) {
            throw new IllegalStateException("Missing required setting '" + key + "' (profile=" + profile + ")");
        }
        return v;
    }

    public String get(String key, String dflt) {
        String v = get(key);
        return v == null || v.isEmpty() ? dflt : v;
    }

    public int getInt(String key) {
        return Integer.parseInt(require(key));
    }

    public long getLong(String key) {
        return Long.parseLong(require(key));
    }

    public boolean getBool(String key) {
        return Boolean.parseBoolean(require(key));
    }

    public BigDecimal getDecimal(String key) {
        return new BigDecimal(require(key));
    }

    public LocalDate getDate(String key) {
        return LocalDate.parse(require(key));
    }

    public Duration getSeconds(String key) {
        return Duration.ofSeconds(Long.parseLong(require(key)));
    }

    public List<String> getList(String key) {
        String v = get(key);
        if (v == null || v.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        Arrays.stream(v.split(",")).map(String::trim).filter(s -> !s.isEmpty()).forEach(out::add);
        return out;
    }

    public boolean has(String key) {
        String v = get(key);
        return v != null && !v.isEmpty();
    }

    /** Effective settings with secrets masked, for the run manifest and dry-run output. */
    public Map<String, String> effectiveMasked() {
        Map<String, String> out = new TreeMap<>();
        for (String k : props.stringPropertyNames()) {
            String lower = k.toLowerCase(Locale.ROOT);
            boolean secret = lower.contains("password") || lower.contains("secret") || lower.contains("token");
            out.put(k, secret ? "****" : props.getProperty(k));
        }
        return out;
    }
}
