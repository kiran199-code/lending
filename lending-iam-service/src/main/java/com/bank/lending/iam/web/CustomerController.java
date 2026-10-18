package com.bank.lending.iam.web;

import com.bank.lending.iam.common.Caller;
import com.bank.lending.iam.customer.ConsentService;
import com.bank.lending.iam.customer.CustomerDtos.*;
import com.bank.lending.iam.customer.CustomerService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class CustomerController {
    private final Guard guard;
    private final CustomerService customers;
    private final ConsentService consents;

    public CustomerController(Guard guard, CustomerService customers, ConsentService consents) {
        this.guard = guard;
        this.customers = customers;
        this.consents = consents;
    }

    @GetMapping("/customers/{userId}/links")
    public List<LinkView> links(Authentication auth, @PathVariable long userId) {
        guard.require(auth, "iam.user.read");
        return customers.linksOf(userId);
    }

    @PostMapping("/customers/{userId}/links")
    @ResponseStatus(HttpStatus.CREATED)
    public LinkView createLink(Authentication auth, @PathVariable long userId, @Valid @RequestBody CreateLinkRequest r) {
        Caller c = guard.require(auth, "iam.customer.link");
        return customers.create(userId, r, c.name());
    }

    @GetMapping("/customer-links/pending")
    public List<LinkView> pending(Authentication auth) {
        guard.require(auth, "iam.customer.link.approve");
        return customers.pending();
    }

    @PostMapping("/customer-links/{id}/approve")
    public LinkView approve(Authentication auth, @PathVariable long id) {
        Caller c = guard.require(auth, "iam.customer.link.approve");
        return customers.approve(id, c.name());
    }

    @PostMapping("/customer-links/{id}/reject")
    public LinkView reject(Authentication auth, @PathVariable long id, @Valid @RequestBody ReasonRequest r) {
        Caller c = guard.require(auth, "iam.customer.link.approve");
        return customers.reject(id, r.reason(), c.name());
    }

    @PostMapping("/customer-links/{id}/revoke")
    public LinkView revoke(Authentication auth, @PathVariable long id, @Valid @RequestBody ReasonRequest r) {
        Caller c = guard.require(auth, "iam.customer.link");
        return customers.revoke(id, r.reason(), c.name());
    }

    @GetMapping("/consent-purposes")
    public List<Map<String, Object>> purposes(Authentication auth) {
        guard.authenticated(auth);
        return consents.activePurposes().stream().map(consents::purposeView).toList();
    }
}
