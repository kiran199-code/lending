package com.bank.lending.iam.client;

import com.bank.lending.iam.access.AccessService;
import com.bank.lending.iam.access.RbacModels.RoleRow;
import com.bank.lending.iam.access.RbacRepository;
import com.bank.lending.iam.audit.AuditService;
import com.bank.lending.iam.auth.RevocationService;
import com.bank.lending.iam.client.ClientDtos.*;
import com.bank.lending.iam.common.Hashing;
import com.bank.lending.iam.common.IamException;
import com.bank.lending.iam.common.SecretHasher;
import com.bank.lending.iam.common.Validate;
import com.bank.lending.iam.config.IamSettings;
import com.bank.lending.iam.domain.SodChecker;
import com.bank.lending.iam.events.EventService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Service accounts and interactive (browser) clients. */
@Service
public class ClientService {
    private static final Pattern CLIENT_ID = Pattern.compile("^[a-z0-9][a-z0-9._-]{2,79}$");
    private static final Set<String> GRANTS = Set.of("authorization_code", "refresh_token", "client_credentials");

    private final ClientRepository clients;
    private final RbacRepository rbac;
    private final SecretHasher hasher;
    private final RevocationService revocation;
    private final AccessService access;
    private final AuditService audit;
    private final EventService events;
    private final IamSettings settings;
    private final Clock clock;

    public ClientService(ClientRepository clients, RbacRepository rbac, SecretHasher hasher, RevocationService revocation, AccessService access,
                         AuditService audit, EventService events, IamSettings settings, Clock clock) {
        this.clients = clients;
        this.rbac = rbac;
        this.hasher = hasher;
        this.revocation = revocation;
        this.access = access;
        this.audit = audit;
        this.events = events;
        this.settings = settings;
        this.clock = clock;
    }

    public ClientView view(ClientRow c) {
        return new ClientView(c.clientId(), c.name(), c.kind(), c.grantTypes(), c.redirectUris(), c.postLogoutUris(), c.scopes(), c.accessTtlSeconds(),
                c.refreshTtlSeconds(), c.status(), clients.roles(c.clientId()),
                c.previousValidUntil() != null && c.previousValidUntil().isAfter(Instant.now(clock)) ? c.previousValidUntil() : null, c.createdAt(), c.createdBy());
    }

    public List<ClientView> list() {
        return clients.list().stream().map(this::view).toList();
    }

    public ClientView get(String clientId) {
        return view(clients.find(clientId).orElseThrow(() -> IamException.notFound("Client", clientId)));
    }

    /** Redirect addresses must match exactly; https, or http only for a local machine. No wildcards or fragments. */
    static List<String> checkRedirects(List<String> uris, boolean required) {
        List<String> out = new ArrayList<>();
        if (uris != null) {
            for (String raw : new LinkedHashSet<>(uris)) {
                String u = raw == null ? "" : raw.trim();
                URI uri;
                try {
                    uri = URI.create(u);
                } catch (IllegalArgumentException e) {
                    throw IamException.badRequest("REDIRECT_NOT_ALLOWED", "Not a valid redirect address");
                }
                String scheme = uri.getScheme() == null ? "" : uri.getScheme();
                boolean local = "http".equals(scheme) && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
                if (!("https".equals(scheme) || local) || uri.getHost() == null || uri.getFragment() != null || u.contains("*") || uri.getUserInfo() != null) {
                    throw IamException.badRequest("REDIRECT_NOT_ALLOWED", "Redirect addresses must be exact https addresses (http only for localhost), with no wildcard or fragment");
                }
                out.add(u);
            }
        }
        if (required && out.isEmpty()) throw IamException.badRequest("REDIRECT_REQUIRED", "At least one redirect address is required for the authorization code flow");
        return out;
    }

    private int accessTtl(Integer requested, boolean service, boolean customerFacing) {
        int v = requested != null ? requested : service ? settings.serviceAccessSeconds : settings.staffAccessSeconds;
        if (v < 60 || v > 3600) throw IamException.badRequest("VALIDATION_FAILED", "accessTokenSeconds must be 60 to 3600");
        return v;
    }

    private int refreshTtl(Integer requested) {
        int v = requested != null ? requested : settings.staffRefreshSeconds;
        if (v < 300 || v > 30 * 24 * 3600) throw IamException.badRequest("VALIDATION_FAILED", "refreshTokenSeconds must be 300 to 2592000");
        return v;
    }

    @Transactional
    public CreatedClient create(CreateClientRequest r, String actor) {
        String clientId = r.clientId() == null ? "" : r.clientId().trim();
        if (!CLIENT_ID.matcher(clientId).matches()) throw IamException.badRequest("VALIDATION_FAILED", "clientId must be 3 to 80 lower case letters, digits, dot, underscore, hyphen");
        if (clients.find(clientId).isPresent()) throw IamException.conflict("CLIENT_EXISTS", "A client with this id exists");
        String kind = Validate.oneOf(r.kind(), "kind", "CONFIDENTIAL", "PUBLIC");
        Set<String> grants = new LinkedHashSet<>();
        for (String g : r.grantTypes()) {
            if (!GRANTS.contains(g)) throw IamException.badRequest("VALIDATION_FAILED", "Unsupported grant type " + g);
            grants.add(g);
        }
        boolean cc = grants.contains("client_credentials");
        boolean code = grants.contains("authorization_code");
        if (grants.isEmpty() || (!cc && !code)) throw IamException.badRequest("VALIDATION_FAILED", "Choose authorization_code or client_credentials");
        if (cc && code) throw IamException.badRequest("VALIDATION_FAILED", "A client is either a service account (client_credentials) or interactive (authorization_code), not both");
        if ("PUBLIC".equals(kind) && cc) throw IamException.badRequest("PUBLIC_CLIENT_NO_CC", "A public client cannot use client credentials");
        if (cc) grants.remove("refresh_token");   // service accounts get no refresh token
        else grants.add("refresh_token");
        List<String> redirects = checkRedirects(r.redirectUris(), code);
        List<String> postLogout = checkRedirects(r.postLogoutUris(), false);
        String secret = "PUBLIC".equals(kind) ? null : Hashing.randomToken(32);
        clients.insert(clientId, Validate.text(r.name(), "name", 120, true), kind, secret == null ? null : hasher.hash(secret), new ArrayList<>(grants), redirects, postLogout,
                code ? List.of("openid", "profile") : List.of("service"), accessTtl(r.accessTokenSeconds(), cc, false), refreshTtl(r.refreshTokenSeconds()), actor);
        if (cc && r.roles() != null && !r.roles().isEmpty()) setRoles(clientId, r.roles());
        audit.record("CLIENT_CREATED", "CLIENT", clientId, "SUCCESS", "kind", kind, "grants", new ArrayList<>(grants), "roles", r.roles() == null ? List.of() : r.roles());
        events.publish(EventService.CLIENT_CHANGED, "client:" + clientId, "clientId", clientId, "change", "created");
        return new CreatedClient(get(clientId), secret);
    }

    @Transactional
    public ClientView update(String clientId, UpdateClientRequest r) {
        ClientRow c = clients.find(clientId).orElseThrow(() -> IamException.notFound("Client", clientId));
        boolean code = c.grantTypes().contains("authorization_code");
        List<String> redirects = r.redirectUris() == null ? c.redirectUris() : checkRedirects(r.redirectUris(), code);
        List<String> postLogout = r.postLogoutUris() == null ? c.postLogoutUris() : checkRedirects(r.postLogoutUris(), false);
        clients.update(clientId, Validate.text(r.name(), "name", 120, true), redirects, postLogout,
                r.accessTokenSeconds() == null ? c.accessTtlSeconds() : accessTtl(r.accessTokenSeconds(), !code, false),
                r.refreshTokenSeconds() == null ? c.refreshTtlSeconds() : refreshTtl(r.refreshTokenSeconds()));
        audit.record("CLIENT_UPDATED", "CLIENT", clientId, "SUCCESS", "redirectUris", redirects, "accessTokenSeconds", r.accessTokenSeconds(), "refreshTokenSeconds", r.refreshTokenSeconds());
        events.publish(EventService.CLIENT_CHANGED, "client:" + clientId, "clientId", clientId, "change", "updated");
        return get(clientId);
    }

    private void setRoles(String clientId, List<String> roles) {
        Set<String> codes = new TreeSet<>(roles);
        Map<String, Set<String>> byRole = rbac.permissionsByRole();
        for (String code : codes) {
            RoleRow role = rbac.role(code).orElseThrow(() -> IamException.unprocessable("UNKNOWN_ROLE", "Unknown role " + code));
            if (!"SERVICE".equals(role.kind())) throw IamException.unprocessable("ROLE_KIND_MISMATCH", "Role " + code + " is not a SERVICE role");
            if (!"ACTIVE".equals(role.status())) throw IamException.unprocessable("ROLE_RETIRED", "Role " + code + " is retired");
        }
        List<SodChecker.Violation> v = SodChecker.violations(SodChecker.union(codes, byRole), rbac.sodRules());
        if (!v.isEmpty()) throw IamException.conflict("SOD_CONFLICT", "These roles together break a separation-of-duties rule: " + SodChecker.describe(v));
        clients.replaceRoles(clientId, new ArrayList<>(codes));
    }

    @Transactional
    public ClientView replaceRoles(String clientId, ReplaceClientRolesRequest r) {
        ClientRow c = clients.find(clientId).orElseThrow(() -> IamException.notFound("Client", clientId));
        if (!c.grantTypes().contains("client_credentials")) throw IamException.conflict("NOT_A_SERVICE_ACCOUNT", "Roles are for service accounts (client_credentials)");
        List<String> before = clients.roles(clientId);
        setRoles(clientId, r.roles());
        audit.record("CLIENT_ROLES_CHANGED", "CLIENT", clientId, "SUCCESS", "before", before, "after", clients.roles(clientId));
        events.publish(EventService.CLIENT_CHANGED, "client:" + clientId, "clientId", clientId, "change", "roles");
        access.invalidateAll();
        return get(clientId);
    }

    @Transactional
    public RotatedSecret rotateSecret(String clientId) {
        ClientRow c = clients.find(clientId).orElseThrow(() -> IamException.notFound("Client", clientId));
        if (c.isPublic()) throw IamException.conflict("NO_SECRET", "A public client has no secret");
        String secret = Hashing.randomToken(32);
        Instant graceEnds = Instant.now(clock).plus(Duration.ofMinutes(settings.clientSecretGraceMinutes));
        clients.rotateSecret(clientId, hasher.hash(secret), graceEnds);
        audit.record("CLIENT_SECRET_ROTATED", "CLIENT", clientId, "SUCCESS", "previousValidUntil", graceEnds.toString());
        events.publish(EventService.CLIENT_CHANGED, "client:" + clientId, "clientId", clientId, "change", "secret-rotated");
        return new RotatedSecret(clientId, secret, graceEnds);
    }

    @Transactional
    public ClientView disable(String clientId) {
        ClientRow c = clients.find(clientId).orElseThrow(() -> IamException.notFound("Client", clientId));
        if (!c.active()) throw IamException.conflict("INVALID_STATE", "The client is already disabled");
        clients.setStatus(clientId, "DISABLED", Instant.now(clock));
        int revoked = revocation.revokeClient(clientId);
        audit.record("CLIENT_DISABLED", "CLIENT", clientId, "SUCCESS", "authorizationsRevoked", revoked);
        events.publish(EventService.CLIENT_CHANGED, "client:" + clientId, "clientId", clientId, "change", "disabled");
        access.invalidateAll();
        return get(clientId);
    }

    @Transactional
    public ClientView enable(String clientId) {
        ClientRow c = clients.find(clientId).orElseThrow(() -> IamException.notFound("Client", clientId));
        if (c.active()) throw IamException.conflict("INVALID_STATE", "The client is already enabled");
        clients.setStatus(clientId, "ACTIVE", null);
        audit.record("CLIENT_ENABLED", "CLIENT", clientId, "SUCCESS");
        events.publish(EventService.CLIENT_CHANGED, "client:" + clientId, "clientId", clientId, "change", "enabled");
        access.invalidateAll();
        return get(clientId);
    }
}
