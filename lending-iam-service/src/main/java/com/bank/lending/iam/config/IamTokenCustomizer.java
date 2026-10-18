package com.bank.lending.iam.config;

import com.bank.lending.iam.access.AccessService;
import com.bank.lending.iam.keys.KeyService;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Puts the claims of section 5.1 into every access token, read from the database at the moment of issue or refresh
 * (so a refresh picks up changed roles, and a user who is no longer ACTIVE gets nothing). The key id goes into the header:
 * with more than one published key the encoder cannot choose otherwise.
 */
public class IamTokenCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {
    public static final String MFA_AT_PREFIX = "MFA_AT_";

    private final AccessService access;
    private final KeyService keys;

    public IamTokenCustomizer(AccessService access, KeyService keys) {
        this.access = access;
        this.keys = keys;
    }

    @Override
    public void customize(JwtEncodingContext ctx) {
        ctx.getJwsHeader().keyId(keys.active().keyId());
        if (!OAuth2TokenType.ACCESS_TOKEN.equals(ctx.getTokenType())) return;

        boolean service = AuthorizationGrantType.CLIENT_CREDENTIALS.equals(ctx.getAuthorizationGrantType());
        AccessService.Snapshot snap = service
                ? access.forClient(ctx.getRegisteredClient().getClientId()).orElseThrow(IamTokenCustomizer::invalid)
                : access.forUser(ctx.getPrincipal().getName()).orElseThrow(IamTokenCustomizer::invalid);
        if (!snap.active()) throw invalid();

        var claims = ctx.getClaims();
        claims.claim("typ", snap.kind());
        claims.claim("roles", sorted(snap.roles()));
        claims.claim("perms", sorted(snap.permissions()));
        if (!service) {
            claims.claim("uid", snap.id());
            if (snap.customerId() != null) claims.claim("customer_id", snap.customerId());
            // an absent scope claim means "unrestricted" for that dimension; "*" is kept as written
            putIfPresent(claims, "branches", snap.scope("BRANCH"));
            putIfPresent(claims, "products", snap.scope("PRODUCT"));
            putIfPresent(claims, "currencies", snap.scope("CURRENCY"));
            Long mfaAt = mfaAt(ctx.getPrincipal().getAuthorities());
            List<String> amr = new ArrayList<>(List.of("pwd"));
            if (mfaAt != null) {
                amr.add("otp");
                claims.claim("mfa_at", mfaAt);   // epoch seconds
            }
            claims.claim("amr", amr);
        }
    }

    static Long mfaAt(java.util.Collection<? extends GrantedAuthority> authorities) {
        for (GrantedAuthority a : authorities) {
            String s = a.getAuthority();
            if (s != null && s.startsWith(MFA_AT_PREFIX)) {
                try {
                    return Long.parseLong(s.substring(MFA_AT_PREFIX.length()));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static List<String> sorted(Set<String> s) {
        return new ArrayList<>(new TreeSet<>(s));
    }

    private static void putIfPresent(org.springframework.security.oauth2.jwt.JwtClaimsSet.Builder claims, String name, Set<String> values) {
        if (values != null && !values.isEmpty()) claims.claim(name, sorted(values));
    }

    private static OAuth2AuthenticationException invalid() {
        return new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_GRANT, "The identity is not available", null));
    }
}
