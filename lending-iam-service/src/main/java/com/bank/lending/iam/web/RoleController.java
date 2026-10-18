package com.bank.lending.iam.web;

import com.bank.lending.iam.access.RoleDtos.*;
import com.bank.lending.iam.access.RoleService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class RoleController {
    private final Guard guard;
    private final RoleService roles;

    public RoleController(Guard guard, RoleService roles) {
        this.guard = guard;
        this.roles = roles;
    }

    @GetMapping("/permissions")
    public List<PermissionView> permissions(Authentication auth) {
        guard.require(auth, "iam.role.read");
        return roles.permissions();
    }

    @GetMapping("/roles")
    public List<RoleView> roles(Authentication auth) {
        guard.require(auth, "iam.role.read");
        return roles.roles();
    }

    @GetMapping("/roles/{code}")
    public RoleView role(Authentication auth, @PathVariable String code) {
        guard.require(auth, "iam.role.read");
        return roles.role(code);
    }

    @PostMapping("/roles")
    @ResponseStatus(HttpStatus.CREATED)
    public RoleView create(Authentication auth, @Valid @RequestBody CreateRoleRequest r) {
        guard.require(auth, "iam.role.manage");
        return roles.create(r);
    }

    @PutMapping("/roles/{code}")
    public RoleView update(Authentication auth, @PathVariable String code, @Valid @RequestBody UpdateRoleRequest r) {
        guard.require(auth, "iam.role.manage");
        return roles.update(code, r);
    }

    @PutMapping("/roles/{code}/permissions")
    public RoleView replacePermissions(Authentication auth, @PathVariable String code, @Valid @RequestBody ReplacePermissionsRequest r) {
        guard.require(auth, "iam.role.manage");
        return roles.replacePermissions(code, r);
    }

    @PutMapping("/roles/{code}/limits")
    public RoleView replaceLimits(Authentication auth, @PathVariable String code, @Valid @RequestBody ReplaceLimitsRequest r) {
        guard.require(auth, "iam.role.manage");
        return roles.replaceLimits(code, r);
    }

    @GetMapping("/sod-rules")
    public List<SodRuleView> sodRules(Authentication auth) {
        guard.require(auth, "iam.role.read");
        return roles.sodRules();
    }

    @PostMapping("/sod-rules")
    @ResponseStatus(HttpStatus.CREATED)
    public SodRuleView addSodRule(Authentication auth, @Valid @RequestBody CreateSodRuleRequest r) {
        guard.require(auth, "iam.role.manage");
        return roles.addSodRule(r);
    }

    @DeleteMapping("/sod-rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSodRule(Authentication auth, @PathVariable long id) {
        guard.require(auth, "iam.role.manage");
        roles.deleteSodRule(id);
    }
}
