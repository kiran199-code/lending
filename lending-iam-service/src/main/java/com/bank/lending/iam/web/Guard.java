package com.bank.lending.iam.web;

import com.bank.lending.iam.access.AuthzService;
import com.bank.lending.iam.audit.AuditService;
import com.bank.lending.iam.client.ClientRepository;
import com.bank.lending.iam.client.ClientRow;
import com.bank.lending.iam.common.Caller;
import com.bank.lending.iam.common.IamException;
import com.bank.lending.iam.common.RequestContext;
import com.bank.lending.iam.config.IamSettings;
import com.bank.lending.iam.user.UserRepository;
import com.bank.lending.iam.user.UserRow;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Turns the validated token into a {@link Caller} and enforces the permission of each endpoint:
 * token still good (user ACTIVE, nothing revoked since it was issued), permission present, and for
 * step-up permissions a second factor within the window.
 */
@Component
public class Guard {
    private final UserRepository users;
    private final ClientRepository clients;
    private final AuthzService authz;
    private final AuditService audit;
    private final IamSettings settings;
    private final Clock clock;

    public Guard(UserRepository users, ClientRepository clients, AuthzService authz, AuditService audit, IamSettings settings, Clock clock) {
        this.users = users;
        this.clients = clients;
        this.authz = authz;
        this.audit = audit;
        this.settings = settings;
        this.clock = clock;
    }

    /** Reads the claims of section 5.1. Instants arrive as Instant (decoded token) or as epoch seconds (tests). */
    public static Caller fromClaims(Map<String, Object> claims) {
        String sub = str(claims.get("sub"));
        String typ = str(claims.get("typ"));
        if (sub == null || typ == null) throw IamException.unauthorized("TOKEN_INVALID", "The token has no subject or type");
        Set<String> perms = new HashSet<>();
        Object p = claims.get("perms");
        if (p instanceof Collection<?> c) for (Object o : c) perms.add(String.valueOf(o));
        return new Caller(sub, typ, perms, instant(claims.get("mfa_at")), instant(claims.get("iat")));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Instant instant(Object o) {
        if (o == null) return null;
        if (o instanceof Instant i) return i;
        if (o instanceof Number n) return Instant.ofEpochSecond(n.longValue());
        return null;
    }

    /** Any signed-in identity whose token is still good. */
    public Caller authenticated(Authentication auth) {
        if (!(auth instanceof JwtAuthenticationToken jwt)) throw IamException.unauthorized("UNAUTHORIZED", "A token is required");
        Caller c = fromClaims(jwt.getToken().getClaims());
        Instant issued = c.issuedAt() == null ? Instant.EPOCH : c.issuedAt();
        Instant revokedBefore;
        if (c.isService()) {
            ClientRow cl = clients.find(c.name()).orElseThrow(() -> IamException.unauthorized("TOKEN_REVOKED", "The token is no longer valid"));
            if (!cl.active()) throw IamException.unauthorized("TOKEN_REVOKED", "The token is no longer valid");
            revokedBefore = cl.tokensValidAfter();
        } else {
            UserRow u = users.findByUsername(c.name()).orElseThrow(() -> IamException.unauthorized("TOKEN_REVOKED", "The token is no longer valid"));
            if (!"ACTIVE".equals(u.status())) throw IamException.unauthorized("TOKEN_REVOKED", "The token is no longer valid");
            revokedBefore = u.tokensValidAfter();
        }
        if (revokedBefore != null && issued.isBefore(revokedBefore.truncatedTo(ChronoUnit.SECONDS))) {
            throw IamException.unauthorized("TOKEN_REVOKED", "The token is no longer valid");
        }
        RequestContext.current().as(c.actorType(), c.name());
        return c;
    }

    /** Signed-in person (staff or customer), not a service account. */
    public Caller person(Authentication auth) {
        Caller c = authenticated(auth);
        if (c.isService()) throw IamException.forbidden("NOT_A_PERSON", "This call is for people, not service accounts");
        return c;
    }

    public Caller require(Authentication auth, String permission) {
        Caller c = authenticated(auth);
        var info = authz.permissions().get(permission);
        if (info == null) throw new IllegalStateException("Endpoint uses a permission that is not in the catalogue: " + permission);
        if (!c.has(permission)) {
            deny(c, info.risk(), permission, "NOT_GRANTED");
            throw IamException.forbidden("NOT_GRANTED", "You do not hold the permission " + permission);
        }
        if (info.requiresMfa() && !c.isService()) {
            Instant now = Instant.now(clock);
            boolean fresh = c.mfaAt() != null && !c.mfaAt().plus(Duration.ofMinutes(settings.stepUpWindowMinutes)).isBefore(now);
            if (!fresh) {
                deny(c, info.risk(), permission, "STEP_UP_REQUIRED");
                throw IamException.forbidden("STEP_UP_REQUIRED", "This action needs a recent second factor; sign in again with prompt=login");
            }
        }
        return c;
    }

    private void deny(Caller c, String risk, String permission, String code) {
        if (settings.auditDenials && ("HIGH".equals(risk) || "CRITICAL".equals(risk))) {
            audit.record("ACCESS_DENIED", "PERMISSION", permission, "DENIED", "code", code, "caller", c.name());
        }
    }
}
