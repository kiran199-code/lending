package com.bank.lending.iam.common;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommonTest {
    @Test
    void canonicalJsonIsIndependentOfKeyOrderAndSpacing() {
        String a = Json.canonical("{ \"b\" : 1, \"a\" : { \"d\": 2, \"c\": [3, {\"z\":1, \"y\":2}] } }");
        String b = Json.canonical("{\"a\":{\"c\":[3,{\"y\":2,\"z\":1}],\"d\":2},\"b\":1}");
        assertThat(a).isEqualTo("{\"a\":{\"c\":[3,{\"y\":2,\"z\":1}],\"d\":2},\"b\":1}");
        assertThat(a).isEqualTo(b);
        assertThat(Hashing.sha256Hex(a)).isEqualTo(Hashing.sha256Hex(b));
    }

    @Test
    void canonicalJsonKeepsArrayOrderAndRejectsBadText() {
        assertThat(Json.canonical("[3,1,2]")).isEqualTo("[3,1,2]");
        assertThatThrownBy(() -> Json.canonical("{not json")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Json.canonical("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void jsonObjectsRoundTripThroughText() {
        Map<String, Object> m = Json.readMap(Json.obj("a", 1, "b", "two"));
        assertThat(m.get("b")).isEqualTo("two");
        assertThat(Json.readMap(null).isEmpty()).isTrue();
    }

    @Test
    void hashingKnownValuesAndConstantTimeCompare() {
        assertThat(Hashing.sha256Hex("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        // RFC 4231 test case 2
        assertThat(Hashing.hmacSha256Hex("Jefe", "what do ya want for nothing?")).isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
        assertThat(Hashing.constantTimeEquals("a", "a")).isTrue();
        assertThat(Hashing.constantTimeEquals("a", "b")).isFalse();
        assertThat(Hashing.constantTimeEquals(null, "b")).isFalse();
        assertThat(Hashing.randomToken(32)).isNotEqualTo(Hashing.randomToken(32));
    }

    @Test
    void validationAcceptsGoodInputAndNamesTheField() {
        assertThat(Validate.username(" alice.b@bank ")).isEqualTo("alice.b@bank");
        assertThat(Validate.currency("SGD")).isEqualTo("SGD");
        assertThat(Validate.currency("*")).isEqualTo("*");
        assertThat(Validate.email("a@b.co", true)).isEqualTo("a@b.co");
        assertThat(Validate.email(" ", false)).isNull();
        assertThat(Validate.mobile("+65 9123 4567")).isEqualTo("+65 9123 4567");
        assertThat(Validate.page(null)).isEqualTo(0);
        assertThat(Validate.size(null)).isEqualTo(50);
        assertThat(Validate.size(5000)).isEqualTo(200);
        assertThat(Validate.page(-3)).isEqualTo(0);
    }

    @Test
    void validationRefusesBadInputWith400() {
        for (Runnable r : new Runnable[]{
                () -> Validate.username("ab"), () -> Validate.username("-bad"), () -> Validate.username("has space"),
                () -> Validate.currency("sgd"), () -> Validate.currency("SGDX"), () -> Validate.email("nope", true),
                () -> Validate.email(null, true), () -> Validate.mobile("abc"), () -> Validate.code("x", "role"),
                () -> Validate.scopeValue("a b"), () -> Validate.ref("", "ref"), () -> Validate.text(" ", "summary", 10, true),
                () -> Validate.text("x".repeat(11), "summary", 10, false), () -> Validate.oneOf("X", "kind", "A", "B")}) {
            assertThatThrownBy(r::run).isInstanceOf(IamException.class);
            try {
                r.run();
            } catch (IamException e) {
                assertThat(e.status().value()).isEqualTo(400);
                assertThat(e.code()).isEqualTo("VALIDATION_FAILED");
            }
        }
    }

    @Test
    void exceptionFactoriesCarryStatusAndCode() {
        assertThat(IamException.notFound("User", "x").status().value()).isEqualTo(404);
        assertThat(IamException.unauthorized("A", "m").status().value()).isEqualTo(401);
        assertThat(IamException.forbidden("A", "m").status().value()).isEqualTo(403);
        assertThat(IamException.conflict("A", "m").status().value()).isEqualTo(409);
        assertThat(IamException.unprocessable("A", "m", java.util.List.of("d")).details()).hasSize(1);
    }

    @Test
    void callerKnowsItsKind() {
        Caller c = new Caller("svc", "SERVICE", java.util.Set.of("a"), null, null);
        assertThat(c.isService()).isTrue();
        assertThat(c.actorType()).isEqualTo("CLIENT");
        assertThat(c.has("a")).isTrue();
        assertThat(new Caller("u", "STAFF", java.util.Set.of(), null, null).actorType()).isEqualTo("USER");
    }
}
