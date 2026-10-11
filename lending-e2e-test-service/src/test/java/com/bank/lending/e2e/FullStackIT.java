package com.bank.lending.e2e;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bank.lending.e2e.config.E2eConfig;
import com.bank.lending.e2e.run.E2eRunner;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.DockerComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * OPTIONAL hermetic run (mvn -Ptestcontainers verify): brings up the platform's own docker-compose stack
 * (postgres, kafka, lending-service, batch, admin), runs a short slice of the lifecycle suite against it and tears the
 * stack down. This is a convenience for developers without a shared environment; it needs Docker and is NOT part of the
 * default build. It never touches any shared environment, so the production guard is satisfied by the 'local' profile.
 *
 * Not verified in the authoring sandbox (no Docker / Maven Central there) - see README "Verification status".
 */
@Testcontainers
class FullStackIT {

    @Container
    static final DockerComposeContainer<?> STACK = new DockerComposeContainer<>(new File("../docker-compose.yml"))
            .withExposedService("lending-service", 8080, Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(8)))
            .withExposedService("lending-batch-service", 8081, Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(8)))
            .withExposedService("lending-admin-service", 8082, Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(8)));

    @Test
    void shortLifecycleSliceAgainstTheComposeStack() throws Exception {
        Map<String, String> o = new HashMap<>();
        o.put("e2e.profile", "local");
        o.put("e2e.lending.base-url", "http://" + STACK.getServiceHost("lending-service", 8080) + ":" + STACK.getServicePort("lending-service", 8080));
        o.put("e2e.batch.base-url", "http://" + STACK.getServiceHost("lending-batch-service", 8081) + ":" + STACK.getServicePort("lending-batch-service", 8081));
        o.put("e2e.admin.base-url", "http://" + STACK.getServiceHost("lending-admin-service", 8082) + ":" + STACK.getServicePort("lending-admin-service", 8082));
        o.put("e2e.batch.password", "changeme");   // docker-compose.yml's documented local credentials
        o.put("e2e.admin.password", "changeme");
        o.put("e2e.accounts", "6");
        o.put("e2e.max-sim-days", "45");
        o.put("e2e.fail-on-severity", "NONE");     // the point here is that the run completes and reports, not that today's gaps are absent
        Path out = Files.createTempDirectory("e2e-it");
        o.put("e2e.output-dir", out.toString());
        E2eRunner.Outcome outcome = new E2eRunner(E2eConfig.load(o), System.out::println).execute(false);
        assertTrue(outcome.exitCode() != 4 && outcome.exitCode() != 2, outcome.summary());
        assertTrue(Files.exists(outcome.outputDir().resolve("run-report.md")));
        assertTrue(Files.exists(outcome.outputDir().resolve("audit-trail.csv")));
    }
}
