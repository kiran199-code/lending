package com.bank.lending.e2e.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EnvironmentGuardTest {

    private static E2eConfig cfg(String profile, String host, String... extra) {
        Map<String, String> o = new HashMap<>();
        o.put("e2e.profile", profile);
        o.put("e2e.env.name", profile);
        for (String k : new String[] {"lending", "batch", "admin"}) {
            o.put("e2e." + k + ".base-url", "https://" + host);
        }
        for (int i = 0; i + 1 < extra.length; i += 2) {
            o.put(extra[i], extra[i + 1]);
        }
        return E2eConfig.load(o);
    }

    @Test
    void localhostOnLocalProfileIsAllowed() {
        assertTrue(EnvironmentGuard.evaluate(cfg("local", "localhost:8080")).allowed());
    }

    @Test
    void sandboxHostOnDevIsAllowed() {
        assertTrue(EnvironmentGuard.evaluate(cfg("dev", "lending-service-5tqm.onrender.com")).allowed());
    }

    @Test
    void productionProfileIsRefused() {
        assertFalse(EnvironmentGuard.evaluate(cfg("prod", "localhost:8080")).allowed());
        assertFalse(EnvironmentGuard.evaluate(cfg("production", "localhost:8080")).allowed());
    }

    @Test
    void profileNotInAllowListIsRefused() {
        assertFalse(EnvironmentGuard.evaluate(cfg("staging", "localhost:8080")).allowed());
    }

    @Test
    void productionLookingHostIsRefusedEvenOnAnAllowedProfile() {
        assertFalse(EnvironmentGuard.evaluate(cfg("uat", "lending.prod.bank.example")).allowed());
        assertFalse(EnvironmentGuard.evaluate(cfg("sit", "api-live.bank.example")).allowed());
        assertFalse(EnvironmentGuard.evaluate(cfg("dev", "prod-lending.bank.example")).allowed());
    }

    @Test
    void environmentNameContainingProdIsRefused() {
        assertFalse(EnvironmentGuard.evaluate(cfg("uat", "uat.bank.example", "e2e.env.name", "uat-prod-mirror")).allowed());
    }

    @Test
    void allowListOfHostsIsEnforcedWhenSet() {
        assertTrue(EnvironmentGuard.evaluate(cfg("uat", "lending.uat.bank.example", "e2e.guard.allowed-host-patterns", "\\.uat\\.bank\\.example$")).allowed());
        assertFalse(EnvironmentGuard.evaluate(cfg("uat", "lending.other.bank.example", "e2e.guard.allowed-host-patterns", "\\.uat\\.bank\\.example$")).allowed());
    }

    @Test
    void noTargetUrlIsRefused() {
        Map<String, String> o = new HashMap<>();
        o.put("e2e.profile", "sit");
        o.put("e2e.lending.base-url", "");
        o.put("e2e.batch.base-url", "");
        o.put("e2e.admin.base-url", "");
        assertFalse(EnvironmentGuard.evaluate(E2eConfig.load(o)).allowed());
    }

    @Test
    void enforceThrowsAndThereIsNoOverrideSwitch() {
        assertThrows(EnvironmentGuard.ProductionRefusedException.class,
                () -> EnvironmentGuard.enforce(cfg("prod", "localhost", "e2e.guard.force", "true", "e2e.allow-production", "true")));
    }
}
