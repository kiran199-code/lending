package com.bank.lending.iam.web;

import com.bank.lending.iam.common.Caller;
import com.bank.lending.iam.customer.ConsentService;
import com.bank.lending.iam.user.UserDtos.*;
import com.bank.lending.iam.user.UserService;
import com.bank.lending.iam.customer.CustomerModels;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {
    private final Guard guard;
    private final UserService users;
    private final ConsentService consents;

    public UserController(Guard guard, UserService users, ConsentService consents) {
        this.guard = guard;
        this.users = users;
        this.consents = consents;
    }

    @GetMapping
    public PageView<UserView> search(Authentication auth, @RequestParam(required = false) String kind, @RequestParam(required = false) String status,
                                     @RequestParam(required = false) String q, @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        guard.require(auth, "iam.user.read");
        return users.search(kind, status, q, page, size);
    }

    @GetMapping("/{id}")
    public UserView get(Authentication auth, @PathVariable long id) {
        guard.require(auth, "iam.user.read");
        return users.view(users.row(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedUser create(Authentication auth, @Valid @RequestBody CreateUserRequest r) {
        Caller c = guard.require(auth, "iam.user.create");
        return users.create(r, c.name());
    }

    @PutMapping("/{id}")
    public UserView update(Authentication auth, @PathVariable long id, @Valid @RequestBody UpdateUserRequest r) {
        guard.require(auth, "iam.user.update");
        return users.update(id, r);
    }

    @PostMapping("/{id}/suspend")
    public UserView suspend(Authentication auth, @PathVariable long id) {
        Caller c = guard.require(auth, "iam.user.lifecycle");
        return users.suspend(id, c.name());
    }

    @PostMapping("/{id}/reinstate")
    public UserView reinstate(Authentication auth, @PathVariable long id) {
        guard.require(auth, "iam.user.lifecycle");
        return users.reinstate(id);
    }

    @PostMapping("/{id}/unlock")
    public UserView unlock(Authentication auth, @PathVariable long id) {
        guard.require(auth, "iam.user.lifecycle");
        return users.unlock(id);
    }

    @PostMapping("/{id}/offboard")
    public UserView offboard(Authentication auth, @PathVariable long id) {
        Caller c = guard.require(auth, "iam.user.lifecycle");
        return users.offboard(id, c.name());
    }

    @PostMapping("/{id}/expire-password")
    public UserView expirePassword(Authentication auth, @PathVariable long id) {
        guard.require(auth, "iam.user.lifecycle");
        return users.expirePassword(id);
    }

    @PostMapping("/{id}/reset-password")
    public SecretView resetPassword(Authentication auth, @PathVariable long id) {
        Caller c = guard.require(auth, "iam.user.lifecycle");
        return users.resetPassword(id, c.name());
    }

    @PostMapping("/{id}/reset-mfa")
    public UserView resetMfa(Authentication auth, @PathVariable long id) {
        Caller c = guard.require(auth, "iam.user.lifecycle");
        return users.resetMfa(id, c.name());
    }

    @PutMapping("/{id}/roles")
    public UserView assignRoles(Authentication auth, @PathVariable long id, @Valid @RequestBody AssignRolesRequest r) {
        Caller c = guard.require(auth, "iam.assignment.manage");
        return users.assignRoles(id, r, c.name());
    }

    @PutMapping("/{id}/scopes")
    public UserView assignScopes(Authentication auth, @PathVariable long id, @Valid @RequestBody AssignScopesRequest r) {
        Caller c = guard.require(auth, "iam.assignment.manage");
        return users.assignScopes(id, r, c.name());
    }

    @GetMapping("/{id}/access")
    public AccessView access(Authentication auth, @PathVariable long id) {
        guard.require(auth, "iam.user.read");
        return users.accessOf(id);
    }

    @GetMapping("/{id}/consents")
    public List<CustomerModels.ConsentRecord> consentHistory(Authentication auth, @PathVariable long id) {
        guard.require(auth, "iam.user.read");
        return consents.history(users.row(id).id());
    }
}
