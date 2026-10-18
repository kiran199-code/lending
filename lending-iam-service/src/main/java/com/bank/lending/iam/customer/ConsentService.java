package com.bank.lending.iam.customer;

import com.bank.lending.iam.audit.AuditService;
import com.bank.lending.iam.common.IamException;
import com.bank.lending.iam.common.RequestContext;
import com.bank.lending.iam.customer.CustomerModels.*;
import com.bank.lending.iam.events.EventService;
import com.bank.lending.iam.user.UserRepository;
import com.bank.lending.iam.user.UserRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Consent purposes (versioned data) and the append-only record of what each customer granted or withdrew. */
@Service
public class ConsentService {
    public record ConsentView(String purpose, String description, int currentVersion, boolean mandatory, Boolean granted, Integer grantedVersion,
                              boolean needsAcceptance) {}

    private final CustomerRepository customers;
    private final UserRepository users;
    private final AuditService audit;
    private final EventService events;

    public ConsentService(CustomerRepository customers, UserRepository users, AuditService audit, EventService events) {
        this.customers = customers;
        this.users = users;
        this.audit = audit;
        this.events = events;
    }

    public List<PurposeRow> activePurposes() {
        return customers.purposes().stream().filter(PurposeRow::active).toList();
    }

    /** At activation: every mandatory active purpose must be among the accepted codes. */
    public void requireMandatory(List<String> accepted) {
        Set<String> got = new LinkedHashSet<>(accepted == null ? List.of() : accepted);
        List<String> missing = new ArrayList<>();
        for (PurposeRow p : activePurposes()) if (p.mandatory() && !got.contains(p.code())) missing.add(p.code());
        if (!missing.isEmpty()) {
            throw IamException.badRequest("CONSENT_REQUIRED", "Accept every mandatory consent: " + String.join(", ", missing));
        }
        for (String c : got) {
            if (customers.purpose(c).filter(PurposeRow::active).isEmpty()) throw IamException.badRequest("UNKNOWN_PURPOSE", "Unknown consent purpose");
        }
    }

    /** Records the grants for every accepted purpose at its current version. */
    @Transactional
    public void recordAll(long userId, List<String> accepted, String clientId) {
        String ip = RequestContext.current().ip();
        for (String code : new LinkedHashSet<>(accepted == null ? List.<String>of() : accepted)) {
            PurposeRow p = customers.purpose(code).orElseThrow(() -> IamException.badRequest("UNKNOWN_PURPOSE", "Unknown consent purpose"));
            customers.insertConsent(userId, p.code(), p.version(), true, clientId, ip);
        }
    }

    /** True when a mandatory purpose has a newer version than the one the customer accepted (or none). */
    public List<String> purposesNeedingAcceptance(long userId) {
        Map<String, ConsentState> latest = customers.latestConsents(userId);
        List<String> need = new ArrayList<>();
        for (PurposeRow p : activePurposes()) {
            if (!p.mandatory()) continue;
            ConsentState s = latest.get(p.code());
            if (s == null || !s.granted() || s.version() < p.version()) need.add(p.code());
        }
        return need;
    }

    public List<ConsentView> viewFor(long userId) {
        Map<String, ConsentState> latest = customers.latestConsents(userId);
        List<ConsentView> out = new ArrayList<>();
        for (PurposeRow p : activePurposes()) {
            ConsentState s = latest.get(p.code());
            boolean needs = p.mandatory() && (s == null || !s.granted() || s.version() < p.version());
            out.add(new ConsentView(p.code(), p.description(), p.version(), p.mandatory(), s == null ? null : s.granted(), s == null ? null : s.version(), needs));
        }
        return out;
    }

    /** A customer grants or withdraws one consent. A mandatory consent cannot be withdrawn while the user is ACTIVE. */
    @Transactional
    public ConsentView set(String username, String purposeCode, boolean granted, String clientId) {
        UserRow u = users.findByUsername(username).orElseThrow(() -> IamException.notFound("User", username));
        PurposeRow p = customers.purpose(purposeCode).filter(PurposeRow::active).orElseThrow(() -> IamException.notFound("Consent purpose", purposeCode));
        if (!granted && p.mandatory() && "ACTIVE".equals(u.status())) {
            throw IamException.conflict("CONSENT_MANDATORY", "This consent is required to use the service and cannot be withdrawn");
        }
        customers.insertConsent(u.id(), p.code(), p.version(), granted, clientId, RequestContext.current().ip());
        audit.record(granted ? "CONSENT_GRANTED" : "CONSENT_WITHDRAWN", "USER", u.username(), "SUCCESS", "purpose", p.code(), "version", p.version());
        events.publish(EventService.CONSENT_CHANGED, "user:" + u.username(), "username", u.username(), "purpose", p.code(), "version", p.version(), "granted", granted);
        return viewFor(u.id()).stream().filter(v -> v.purpose().equals(p.code())).findFirst().orElseThrow();
    }

    public List<ConsentRecord> history(long userId) {
        return customers.consentHistory(userId);
    }

    public Map<String, Object> purposeView(PurposeRow p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("purpose", p.code());
        m.put("description", p.description());
        m.put("version", p.version());
        m.put("mandatory", p.mandatory());
        return m;
    }
}
