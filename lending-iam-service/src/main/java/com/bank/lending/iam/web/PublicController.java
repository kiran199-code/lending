package com.bank.lending.iam.web;

import com.bank.lending.iam.auth.CredentialService;
import com.bank.lending.iam.customer.ConsentService;
import com.bank.lending.iam.web.ApiDtos.ActivateRequest;
import com.bank.lending.iam.web.ApiDtos.Message;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** The only endpoints that need no token: activation with a one-time token, and the consent text a customer must accept to activate. */
@RestController
@RequestMapping("/api/v1/public")
public class PublicController {
    private final CredentialService credentials;
    private final ConsentService consents;

    public PublicController(CredentialService credentials, ConsentService consents) {
        this.credentials = credentials;
        this.consents = consents;
    }

    @PostMapping("/activate")
    public Message activate(@Valid @RequestBody ActivateRequest r) {
        credentials.activate(r.token(), r.newPassword(), r.acceptedPurposes() == null ? List.of() : r.acceptedPurposes());
        return new Message("Your account is active. You can now sign in.");
    }

    /** Purposes shown on the activation page before the person has a token-bearing session. Not in the endpoint list of the spec: see README. */
    @GetMapping("/consent-purposes")
    public List<Map<String, Object>> purposes() {
        return consents.activePurposes().stream().map(consents::purposeView).toList();
    }
}
