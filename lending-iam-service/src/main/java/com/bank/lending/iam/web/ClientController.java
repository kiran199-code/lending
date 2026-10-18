package com.bank.lending.iam.web;

import com.bank.lending.iam.client.ClientDtos.*;
import com.bank.lending.iam.client.ClientService;
import com.bank.lending.iam.common.Caller;
import com.bank.lending.iam.keys.KeyService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Clients and service accounts, and the signing keys that sign what they receive. */
@RestController
@RequestMapping("/api/v1")
public class ClientController {
    private final Guard guard;
    private final ClientService clients;
    private final KeyService keys;

    public ClientController(Guard guard, ClientService clients, KeyService keys) {
        this.guard = guard;
        this.clients = clients;
        this.keys = keys;
    }

    @GetMapping("/clients")
    public List<ClientView> list(Authentication auth) {
        guard.require(auth, "iam.client.read");
        return clients.list();
    }

    @GetMapping("/clients/{clientId}")
    public ClientView get(Authentication auth, @PathVariable String clientId) {
        guard.require(auth, "iam.client.read");
        return clients.get(clientId);
    }

    @PostMapping("/clients")
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedClient create(Authentication auth, @Valid @RequestBody CreateClientRequest r) {
        Caller c = guard.require(auth, "iam.client.manage");
        return clients.create(r, c.name());
    }

    @PutMapping("/clients/{clientId}")
    public ClientView update(Authentication auth, @PathVariable String clientId, @Valid @RequestBody UpdateClientRequest r) {
        guard.require(auth, "iam.client.manage");
        return clients.update(clientId, r);
    }

    @PutMapping("/clients/{clientId}/roles")
    public ClientView replaceRoles(Authentication auth, @PathVariable String clientId, @Valid @RequestBody ReplaceClientRolesRequest r) {
        guard.require(auth, "iam.client.manage");
        return clients.replaceRoles(clientId, r);
    }

    @PostMapping("/clients/{clientId}/rotate-secret")
    public RotatedSecret rotateSecret(Authentication auth, @PathVariable String clientId) {
        guard.require(auth, "iam.client.manage");
        return clients.rotateSecret(clientId);
    }

    @PostMapping("/clients/{clientId}/disable")
    public ClientView disable(Authentication auth, @PathVariable String clientId) {
        guard.require(auth, "iam.client.manage");
        return clients.disable(clientId);
    }

    @PostMapping("/clients/{clientId}/enable")
    public ClientView enable(Authentication auth, @PathVariable String clientId) {
        guard.require(auth, "iam.client.manage");
        return clients.enable(clientId);
    }

    @GetMapping("/keys")
    public List<KeyService.KeyView> keys(Authentication auth) {
        guard.require(auth, "iam.key.manage");
        return keys.list();
    }

    @PostMapping("/keys/rotate")
    @ResponseStatus(HttpStatus.CREATED)
    public KeyService.KeyView rotate(Authentication auth) {
        guard.require(auth, "iam.key.manage");
        return keys.rotate();
    }
}
