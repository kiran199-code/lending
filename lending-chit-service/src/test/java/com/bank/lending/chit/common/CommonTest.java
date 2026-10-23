package com.bank.lending.chit.common;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommonTest {

    @Test
    void humanWorkflowsRequireAnActor() {
        assertEquals("ACTOR_REQUIRED", assertThrows(ChitException.class, () -> Actor.required(null)).code());
        assertEquals("ACTOR_REQUIRED", assertThrows(ChitException.class, () -> Actor.required("  ")).code());
        assertEquals("maker1", Actor.required(" maker1 "));
    }

    @Test
    void serviceCallsFallBackToTheServiceName() {
        assertEquals("lending-service", Actor.orService(null));
        assertEquals("lending-service", Actor.orService(" "));
        assertEquals("batch", Actor.orService("batch"));
    }

    @Test
    void anActorLongerThanTheColumnIsTrimmed() {
        assertEquals(60, Actor.required("a".repeat(100)).length());
    }

    @Test
    void sha256IsStableAndSensitiveToInput() {
        assertEquals(Hashing.sha256("a"), Hashing.sha256("a"));
        assertTrue(!Hashing.sha256("a").equals(Hashing.sha256("b")));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Hashing.sha256("abc"));
    }

    @Test
    void jsonObjectDropsNullsAndWritesAmountsAsPlainStrings() {
        String json = Json.obj("cycle", 3, "skipped", null, "amount", new BigDecimal("1E+3").setScale(2));
        Map<String, Object> back = Json.readMap(json);
        assertEquals(3, back.get("cycle"));
        assertFalse(back.containsKey("skipped"));
        assertEquals("1000.00", back.get("amount"));
    }

    @Test
    void jsonReadMapOfNothingIsEmpty() {
        assertTrue(Json.readMap(null).isEmpty());
        assertTrue(Json.readMap(" ").isEmpty());
    }

    @Test
    void notFoundCarriesTheStableCode() {
        ChitException e = ChitException.notFound("Group", "G-9");
        assertEquals("NOT_FOUND", e.code());
        assertTrue(e.getMessage().contains("G-9"));
    }
}
