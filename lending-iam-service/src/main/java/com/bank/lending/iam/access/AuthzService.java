package com.bank.lending.iam.access;

import com.bank.lending.iam.access.RbacModels.PermissionRow;
import com.bank.lending.iam.audit.AuditService;
import com.bank.lending.iam.common.Caller;
import com.bank.lending.iam.common.IamException;
import com.bank.lending.iam.common.MetricsSink;
import com.bank.lending.iam.config.IamSettings;
import com.bank.lending.iam.domain.AuthzDecisionEngine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The decision endpoint: "may this subject do this, here, for this amount?" */
@Service
public class AuthzService {
    public record CheckRequest(String permission, String subject, String branch, String product, String currency, BigDecimal amount,
                               String accountRef, String accountCustomerId, Instant mfaAt) {}

    private final AccessService access;
    private final RbacRepository rbac;
    private final AuditService audit;
    private final MetricsSink metrics;
    private final IamSettings settings;
    private final Clock clock;
    private volatile Map<String, AuthzDecisionEngine.PermissionInfo> catalogue;
    private volatile Map<String, PermissionRow> catalogueRows;

    public AuthzService(AccessService access, RbacRepository rbac, AuditService audit, MetricsSink metrics, IamSettings settings, Clock clock) {
        this.access = access;
        this.rbac = rbac;
        this.audit = audit;
        this.metrics = metrics;
        this.settings = settings;
        this.clock = clock;
    }

    /** The catalogue never changes while the service runs (it changes by migration), so it is read once. */
    public Map<String, PermissionRow> permissions() {
        Map<String, PermissionRow> c = catalogueRows;
        if (c == null) {
            Map<String, PermissionRow> rows = new HashMap<>();
            Map<String, AuthzDecisionEngine.PermissionInfo> info = new HashMap<>();
            for (PermissionRow p : rbac.permissions()) {
                rows.put(p.code(), p);
                info.put(p.code(), new AuthzDecisionEngine.PermissionInfo(p.code(), p.requiresMfa(), p.risk()));
            }
            catalogue = info;
            catalogueRows = c = rows;
        }
        return c;
    }

    public AuthzDecisionEngine engine() {
        permissions();
        return new AuthzDecisionEngine(Duration.ofMinutes(settings.stepUpWindowMinutes), settings.accountBoundPermissions, catalogue);
    }

    @Transactional
    public AuthzDecisionEngine.Decision check(Caller caller, CheckRequest req) {
        if (req.permission() == null || req.permission().isBlank()) throw IamException.badRequest("PERMISSION_REQUIRED", "permission is required");
        PermissionRow p = permissions().get(req.permission());
        if (p == null) throw IamException.badRequest("UNKNOWN_PERMISSION", "Unknown permission code");

        boolean onBehalf = req.subject() != null && !req.subject().isBlank() && !req.subject().equalsIgnoreCase(caller.name());
        String subject = onBehalf ? req.subject().trim() : caller.name();
        Instant mfaAt = caller.mfaAt();
        if (onBehalf) {
            // Only a service that holds iam.authz.check may ask about someone else. Everyone else gets the same answer
            // whether or not the subject exists, so the endpoint cannot be used to find users.
            if (!caller.isService() || !caller.permissions().contains("iam.authz.check")) {
                throw IamException.forbidden("FORBIDDEN", "This caller may not ask about another subject");
            }
            mfaAt = req.mfaAt();
        }

        Optional<AccessService.Snapshot> snap = access.cached(subject);
        Instant now = Instant.now(clock);
        AuthzDecisionEngine.Decision d;
        if (snap.isEmpty()) {
            d = new AuthzDecisionEngine.Decision(false, AuthzDecisionEngine.USER_NOT_ACTIVE, false, null);
        } else {
            AuthzDecisionEngine.Check c = new AuthzDecisionEngine.Check(req.permission(), clean(req.branch()), clean(req.product()), clean(req.currency()),
                    req.amount(), clean(req.accountRef()), clean(req.accountCustomerId()), mfaAt);
            d = engine().decide(snap.get().toSubject(), c, now, LocalDate.now(clock.withZone(ZoneOffset.UTC)));
        }
        metrics.count("iam.authz.decisions", "code", d.code());
        if (!d.allowed() && settings.auditDenials && ("HIGH".equals(p.risk()) || "CRITICAL".equals(p.risk()))) {
            audit.record("AUTHZ_DENIED", "PERMISSION", req.permission(), "DENIED", "subject", subject, "code", d.code(),
                    "branch", req.branch(), "product", req.product(), "currency", req.currency(), "amount", req.amount() == null ? null : req.amount().toPlainString());
        }
        return d;
    }

    private static String clean(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public List<PermissionRow> catalogueList() {
        return rbac.permissions();
    }
}
