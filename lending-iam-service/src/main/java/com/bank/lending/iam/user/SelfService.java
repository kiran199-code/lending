package com.bank.lending.iam.user;

import com.bank.lending.iam.access.AccessService;
import com.bank.lending.iam.access.RbacRepository;
import com.bank.lending.iam.common.Caller;
import com.bank.lending.iam.common.IamException;
import com.bank.lending.iam.customer.ConsentService;
import com.bank.lending.iam.domain.PasswordPolicy;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** What a signed-in person sees about themselves (GET /me). */
@Service
public class SelfService {
    public record MeView(String username, String kind, String displayName, String email, String mobile, String customerId, String status,
                         List<String> roles, List<String> permissions, Map<String, List<String>> scopes, boolean mfaEnabled, boolean mfaRequiredByRole,
                         Instant passwordChangedAt, List<String> consentsNeedingAcceptance, Instant tokenMfaAt) {}

    private final UserRepository users;
    private final RbacRepository rbac;
    private final AccessService access;
    private final ConsentService consents;
    private final Clock clock;

    public SelfService(UserRepository users, RbacRepository rbac, AccessService access, ConsentService consents, Clock clock) {
        this.users = users;
        this.rbac = rbac;
        this.access = access;
        this.consents = consents;
        this.clock = clock;
    }

    public MeView me(Caller caller) {
        if (caller.isService()) throw IamException.conflict("NOT_A_PERSON", "This call is for people; a service account has no profile");
        UserRow u = users.findByUsername(caller.name()).orElseThrow(() -> IamException.notFound("User", caller.name()));
        AccessService.Snapshot s = access.forUserRow(u);
        Map<String, List<String>> scopes = new TreeMap<>();
        s.scopes().forEach((k, v) -> scopes.put(k, new ArrayList<>(v)));
        boolean requires = rbac.requiresMfa(u.id(), LocalDate.now(clock.withZone(ZoneOffset.UTC)));
        return new MeView(u.username(), u.kind(), u.displayName(), u.email(), u.mobile(), u.customerId(), u.status(), new ArrayList<>(s.roles()),
                new ArrayList<>(s.permissions()), scopes, u.mfaEnabled(), requires, u.passwordChangedAt(),
                u.customer() ? consents.purposesNeedingAcceptance(u.id()) : List.of(), caller.mfaAt());
    }

    public List<ConsentService.ConsentView> consents(Caller caller) {
        UserRow u = person(caller);
        return consents.viewFor(u.id());
    }

    public ConsentService.ConsentView setConsent(Caller caller, String purpose, boolean granted) {
        UserRow u = person(caller);
        if (!u.customer()) throw IamException.conflict("NOT_A_CUSTOMER", "Consents are recorded for customers only");
        return consents.set(u.username(), purpose, granted, null);
    }

    private UserRow person(Caller caller) {
        if (caller.isService()) throw IamException.conflict("NOT_A_PERSON", "This call is for people; a service account has no profile");
        return users.findByUsername(caller.name()).orElseThrow(() -> IamException.notFound("User", caller.name()));
    }
}
