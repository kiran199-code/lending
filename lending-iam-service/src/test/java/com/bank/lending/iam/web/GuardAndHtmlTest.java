package com.bank.lending.iam.web;

import com.bank.lending.iam.common.Caller;
import com.bank.lending.iam.common.IamException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GuardAndHtmlTest {
    @Test
    void claimsBecomeACaller() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "alice");
        claims.put("typ", "STAFF");
        claims.put("perms", List.of("iam.user.read", "iam.user.create"));
        claims.put("mfa_at", 1_760_000_000L);
        claims.put("iat", Instant.parse("2026-10-10T10:00:00Z"));
        Caller c = Guard.fromClaims(claims);
        assertThat(c.name()).isEqualTo("alice");
        assertThat(c.kind()).isEqualTo("STAFF");
        assertThat(c.has("iam.user.create")).isTrue();
        assertThat(c.has("iam.role.manage")).isFalse();
        assertThat(c.mfaAt()).isEqualTo(Instant.ofEpochSecond(1_760_000_000L));
        assertThat(c.issuedAt()).isEqualTo(Instant.parse("2026-10-10T10:00:00Z"));
    }

    @Test
    void claimsWithoutSubjectOrTypeAreRejectedSoAnIdTokenCannotBeUsedAsAnAccessToken() {
        Map<String, Object> idToken = new HashMap<>();
        idToken.put("sub", "alice");
        idToken.put("aud", "app");
        assertThatThrownBy(() -> Guard.fromClaims(idToken)).isInstanceOf(IamException.class);
        Map<String, Object> noSub = new HashMap<>();
        noSub.put("typ", "STAFF");
        assertThatThrownBy(() -> Guard.fromClaims(noSub)).isInstanceOf(IamException.class);
    }

    @Test
    void serviceTokensHaveNoMfaTimeAndAbsentPermsMeanNone() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "lending-batch");
        claims.put("typ", "SERVICE");
        Caller c = Guard.fromClaims(claims);
        assertThat(c.isService()).isTrue();
        assertThat(c.permissions()).isEmpty();
        assertThat(c.mfaAt()).isNull();
    }

    @Test
    void htmlEscapesEveryMarkupCharacter() {
        assertThat(Html.esc("<script>alert('x')</script>")).isEqualTo("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;");
        assertThat(Html.esc("a & b \"q\"")).isEqualTo("a &amp; b &quot;q&quot;");
        assertThat(Html.esc(null)).isEqualTo("");
    }

    @Test
    void pagesNeverPutRawValuesIntoMarkup() {
        String evil = "\"><img src=x onerror=alert(1)>";
        String page = Html.page(evil, Html.message("err", evil) + Html.field("User", "username", "text", evil, "username") + Html.list(List.of(evil)) + Html.csrf("_csrf", evil));
        assertThat(page.contains("<img")).isFalse();
        assertThat(page.contains("&lt;img")).isTrue();
        assertThat(page.contains("<script")).isFalse();
    }
}
