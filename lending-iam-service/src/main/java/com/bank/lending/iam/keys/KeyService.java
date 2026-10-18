package com.bank.lending.iam.keys;

import com.bank.lending.iam.audit.AuditService;
import com.bank.lending.iam.common.IamException;
import com.bank.lending.iam.config.IamSettings;
import com.bank.lending.iam.crypto.SecretCrypto;
import com.bank.lending.iam.domain.SigningKeys;
import com.bank.lending.iam.keys.KeyRepository.KeyRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Token signing keys: the active key signs; retired keys stay published for a while so older tokens still verify. */
@Service
public class KeyService {
    public record KeyView(String keyId, String status, Instant createdAt, Instant retiredAt, Instant publishedUntil, String publicPem) {}

    /** What the JWK source needs. {@code privateKey} is null for retired keys. */
    public record Published(String keyId, PublicKey publicKey, PrivateKey privateKey, boolean active) {}

    private record Snapshot(List<Published> keys, long loadedAtMillis) {}

    private final KeyRepository repo;
    private final SecretCrypto crypto;
    private final AuditService audit;
    private final IamSettings settings;
    private final Clock clock;
    private volatile Snapshot cache;

    public KeyService(KeyRepository repo, SecretCrypto crypto, AuditService audit, IamSettings settings, Clock clock) {
        this.repo = repo;
        this.crypto = crypto;
        this.audit = audit;
        this.settings = settings;
        this.clock = clock;
    }

    /** At start-up: make sure there is an active key. Safe when several instances start together (the unique index lets one win). */
    @Transactional
    public void ensureActiveKey() {
        if (repo.active().isPresent()) return;
        repo.lock();
        if (repo.active().isPresent()) return;   // another instance created it while this one waited
        SigningKeys.Generated g = SigningKeys.generate(crypto);
        repo.insertActive(g.keyId(), g.publicPem(), g.privateEnc());
        audit.recordAs("SYSTEM", "key-service", "SIGNING_KEY_CREATED", "SIGNING_KEY", g.keyId(), "SUCCESS", "reason", "first start");
        cache = null;
    }

    @Transactional
    public KeyView rotate() {
        repo.lock();
        Instant now = Instant.now(clock);
        KeyRow old = repo.active().orElse(null);
        if (old != null) repo.retire(old.keyId(), now, now.plus(Duration.ofHours(settings.retiredKeyPublishedHours)));
        SigningKeys.Generated g = SigningKeys.generate(crypto);
        repo.insertActive(g.keyId(), g.publicPem(), g.privateEnc());
        audit.record("SIGNING_KEY_ROTATED", "SIGNING_KEY", g.keyId(), "SUCCESS", "retired", old == null ? null : old.keyId(),
                "retiredKeyPublishedUntil", old == null ? null : now.plus(Duration.ofHours(settings.retiredKeyPublishedHours)).toString());
        cache = null;
        return view(repo.active().orElseThrow());
    }

    public List<KeyView> list() {
        return repo.all().stream().map(KeyService::view).toList();
    }

    private static KeyView view(KeyRow k) {
        return new KeyView(k.keyId(), k.status(), k.createdAt(), k.retiredAt(), k.retireAfter(), k.publicPem());
    }

    /** Published keys through a short cache; the cache is cleared on rotation. */
    public List<Published> published() {
        Snapshot s = cache;
        long now = clock.millis();
        if (s != null && now - s.loadedAtMillis() < settings.keyCacheSeconds * 1000L) return s.keys();
        List<Published> out = new ArrayList<>();
        for (KeyRow k : repo.published(Instant.now(clock))) {
            boolean active = "ACTIVE".equals(k.status());
            out.add(new Published(k.keyId(), SigningKeys.publicKey(k.publicPem()), active ? SigningKeys.privateKey(crypto, k.keyId(), k.privateEnc()) : null, active));
        }
        if (out.stream().noneMatch(Published::active)) throw new IllegalStateException("There is no active signing key");
        cache = new Snapshot(out, now);
        return out;
    }

    public Published active() {
        return published().stream().filter(Published::active).findFirst().orElseThrow(() -> IamException.conflict("NO_ACTIVE_KEY", "There is no active signing key"));
    }

    /** Housekeeping: the private part of a retired key is no longer needed once it stops being published. */
    @Transactional
    public int dropExpired() {
        int n = repo.dropExpiredPrivate(Instant.now(clock));
        if (n > 0) cache = null;
        return n;
    }
}
