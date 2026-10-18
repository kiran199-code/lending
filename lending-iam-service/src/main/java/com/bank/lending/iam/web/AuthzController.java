package com.bank.lending.iam.web;

import com.bank.lending.iam.access.AuthzService;
import com.bank.lending.iam.common.Caller;
import com.bank.lending.iam.domain.AuthzDecisionEngine;
import com.bank.lending.iam.web.ApiDtos.CheckBody;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The decision endpoint. Any identity may ask about itself; asking about someone else needs a service token with iam.authz.check. */
@RestController
@RequestMapping("/api/v1/authz")
public class AuthzController {
    private final Guard guard;
    private final AuthzService authz;

    public AuthzController(Guard guard, AuthzService authz) {
        this.guard = guard;
        this.authz = authz;
    }

    @PostMapping("/check")
    public AuthzDecisionEngine.Decision check(Authentication auth, @Valid @RequestBody CheckBody b) {
        Caller c = guard.authenticated(auth);
        return authz.check(c, new AuthzService.CheckRequest(b.permission(), b.subject(), b.branch(), b.product(), b.currency(), b.amount(),
                b.accountRef(), b.accountCustomerId(), b.mfaAt()));
    }
}
