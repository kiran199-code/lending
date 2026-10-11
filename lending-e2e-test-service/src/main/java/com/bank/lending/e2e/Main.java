package com.bank.lending.e2e;

import com.bank.lending.e2e.config.E2eConfig;
import com.bank.lending.e2e.config.EnvironmentGuard;
import com.bank.lending.e2e.run.E2eRunner;
import com.bank.lending.e2e.server.TriggerServer;
import com.bank.lending.e2e.teardown.Teardown;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * lending-e2e-test-service CLI.
 *
 * <pre>
 *   java -jar lending-e2e-test.jar run        [--profile=sit] [--accounts=20] [--key=value ...]
 *   java -jar lending-e2e-test.jar dry-run    [...]      read-only: guard, connectivity, scenario/product fit, plan
 *   java -jar lending-e2e-test.jar teardown   &lt;runId&gt; [...]
 *   java -jar lending-e2e-test.jar guard      [...]      print the production-guard verdict and exit
 *   java -jar lending-e2e-test.jar serve      [...]      REST trigger (POST /runs)
 * </pre>
 * Exit codes: 0 pass, 1 checks failed at/above e2e.fail-on-severity, 2 preflight problems, 3 refused (production guard), 4 error.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        int code = run(args);
        if (code >= 0) {
            System.exit(code);
        }
    }

    static int run(String[] args) {
        if (args.length == 0 || args[0].equals("help") || args[0].equals("--help")) {
            System.out.println("usage: run | dry-run | teardown <runId> | guard | serve   [--profile=local|dev|sit|uat] [--accounts=N] [--<e2e.key>=value]");
            return 0;
        }
        String cmd = args[0];
        Map<String, String> overrides = new LinkedHashMap<>();
        String positional = null;
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--") && a.contains("=")) {
                String k = a.substring(2, a.indexOf('='));
                overrides.put(k.startsWith("e2e.") ? k : "e2e." + k, a.substring(a.indexOf('=') + 1));
            } else {
                positional = a;
            }
        }
        try {
            E2eConfig cfg = E2eConfig.load(overrides);
            switch (cmd) {
                case "guard": {
                    EnvironmentGuard.Verdict v = EnvironmentGuard.evaluate(cfg);
                    System.out.println(v.allowed() ? "ALLOWED for profile " + cfg.profile() : "REFUSED: " + String.join("; ", v.reasons()));
                    return v.allowed() ? 0 : 3;
                }
                case "dry-run":
                case "run": {
                    E2eRunner.Outcome o = new E2eRunner(cfg, System.out::println).execute(cmd.equals("dry-run"));
                    System.out.println(o.summary());
                    return o.exitCode();
                }
                case "teardown":
                    if (positional == null) {
                        System.err.println("teardown needs a run id, e.g. teardown E2E-261101093000-A1B");
                        return 4;
                    }
                    return Teardown.run(cfg, positional, System.out::println);
                case "serve":
                    EnvironmentGuard.enforce(cfg);
                    new TriggerServer(cfg).start();
                    return -1; // keep JVM alive
                default:
                    System.err.println("unknown command " + cmd);
                    return 4;
            }
        } catch (EnvironmentGuard.ProductionRefusedException e) {
            System.err.println(e.getMessage());
            return 3;
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            if (System.getProperty("e2e.debug") != null) {
                e.printStackTrace();
            }
            return 4;
        }
    }
}
