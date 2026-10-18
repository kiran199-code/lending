package com.bank.lending.iam.access;

import com.bank.lending.iam.access.RbacModels.*;
import com.bank.lending.iam.client.ClientRepository;
import com.bank.lending.iam.client.ClientRow;
import com.bank.lending.iam.customer.CustomerRepository;
import com.bank.lending.iam.domain.AuthzDecisionEngine;
import com.bank.lending.iam.user.UserRepository;
import com.bank.lending.iam.user.UserRow;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds what the system knows about who is acting: roles, permissions, ceilings, scopes and links, as of now.
 * Used for token claims (always read fresh) and for decisions (read through a short cache that is cleared whenever
 * access changes, so a change is visible to the next check).
 */
@Service
public class AccessService {
    private static final long CACHE_MILLIS = 5_000;

    /** Everything about one identity at one moment. {@code user} is null for service accounts. */
    public record Snapshot(String subject, String kind, long id, UserRow user, boolean active, String customerId, Set<String> roles, Set<String> permissions,
                           Map<String, List<Map<String, BigDecimal>>> ceilings, Map<String, Set<String>> scopes, List<AuthzDecisionEngine.Link> links) {

        public AuthzDecisionEngine.Subject toSubject() {
            return new AuthzDecisionEngine.Subject(kind, active, customerId, permissions, ceilings, scopes, links);
        }

        public Set<String> scope(String type) {
            return scopes.getOrDefault(type, Set.of());
        }
    }

    private record Cached(Snapshot snapshot, long at) {}

    private final UserRepository users;
    private final RbacRepository rbac;
    private final ClientRepository clients;
    private final CustomerRepository customers;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public AccessService(UserRepository users, RbacRepository rbac, ClientRepository clients, CustomerRepository customers, Clock clock) {
        this.users = users;
        this.rbac = rbac;
        this.clients = clients;
        this.customers = customers;
        this.clock = clock;
    }

    public void invalidateAll() {
        cache.clear();
    }

    /** A person (staff or customer), read from the database now. Empty when the username does not exist. */
    public Optional<Snapshot> forUser(String username) {
        return users.findByUsername(username).map(this::forUserRow);
    }

    public Snapshot forUserRow(UserRow u) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        boolean active = "ACTIVE".equals(u.status());
        RbacRepository.EffectiveAccess eff = active ? rbac.effectiveForUser(u.id(), u.mfaEnabled(), today)
                : new RbacRepository.EffectiveAccess(Set.of(), Set.of(), Map.of());
        Map<String, Set<String>> scopes = new HashMap<>();
        for (ScopeRow s : rbac.scopes(u.id())) scopes.computeIfAbsent(s.type(), k -> new TreeSet<>()).add(s.value());
        List<AuthzDecisionEngine.Link> links = u.customer() ? customers.engineLinks(u.id()) : List.of();
        return new Snapshot(u.username(), u.kind(), u.id(), u, active, u.customerId(), eff.roles(), eff.permissions(), eff.ceilings(), scopes, links);
    }

    /** A service account (client credentials). */
    public Optional<Snapshot> forClient(String clientId) {
        Optional<ClientRow> c = clients.find(clientId);
        if (c.isEmpty()) return Optional.empty();
        boolean active = c.get().active();
        RbacRepository.EffectiveAccess eff = active ? rbac.effectiveForRoles(rbac.clientRoles(clientId))
                : new RbacRepository.EffectiveAccess(Set.of(), Set.of(), Map.of());
        return Optional.of(new Snapshot(clientId, "SERVICE", 0, null, active, null, eff.roles(), eff.permissions(), eff.ceilings(), Map.of(), List.of()));
    }

    /** Person or, failing that, service account, through the short cache (for decisions). */
    public Optional<Snapshot> cached(String subject) {
        long now = clock.millis();
        Cached c = cache.get(subject);
        if (c != null && now - c.at() < CACHE_MILLIS) return Optional.of(c.snapshot());
        Optional<Snapshot> s = forUser(subject);
        if (s.isEmpty()) s = forClient(subject);
        s.ifPresent(v -> cache.put(subject, new Cached(v, now)));
        return s;
    }

    public Instant now() {
        return Instant.now(clock);
    }
}
