package com.bank.lending.iam.web;

import com.bank.lending.iam.auth.CredentialService;
import com.bank.lending.iam.auth.MfaService;
import com.bank.lending.iam.common.Caller;
import com.bank.lending.iam.customer.ConsentService;
import com.bank.lending.iam.user.SelfService;
import com.bank.lending.iam.web.ApiDtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** What a signed-in person can do about themselves. Any identity may call these; none needs a permission. */
@RestController
@RequestMapping("/api/v1")
public class SelfController {
    private final Guard guard;
    private final SelfService self;
    private final CredentialService credentials;
    private final MfaService mfa;

    public SelfController(Guard guard, SelfService self, CredentialService credentials, MfaService mfa) {
        this.guard = guard;
        this.self = self;
        this.credentials = credentials;
        this.mfa = mfa;
    }

    @GetMapping("/me")
    public SelfService.MeView me(Authentication auth) {
        return self.me(guard.person(auth));
    }

    @PostMapping("/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(Authentication auth, @Valid @RequestBody ChangePasswordRequest r) {
        Caller c = guard.person(auth);
        credentials.changeOwnPassword(c.name(), r.currentPassword(), r.newPassword());
    }

    @PostMapping("/me/mfa/enrol")
    public EnrolmentView enrol(Authentication auth) {
        Caller c = guard.person(auth);
        MfaService.Enrolment e = mfa.enrol(c.name());
        return new EnrolmentView(e.secret(), e.otpauthUri());
    }

    @PostMapping("/me/mfa/confirm")
    public RecoveryCodesView confirm(Authentication auth, @Valid @RequestBody CodeRequest r) {
        Caller c = guard.person(auth);
        return new RecoveryCodesView(mfa.confirm(c.name(), r.code()));
    }

    @PostMapping("/me/mfa/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(Authentication auth, @Valid @RequestBody CodeRequest r) {
        Caller c = guard.person(auth);
        mfa.disable(c.name(), r.code());
    }

    @PostMapping("/me/mfa/recovery-codes")
    public RecoveryCodesView recoveryCodes(Authentication auth, @Valid @RequestBody CodeRequest r) {
        Caller c = guard.person(auth);
        return new RecoveryCodesView(mfa.replaceRecoveryCodes(c.name(), r.code()));
    }

    @GetMapping("/me/consents")
    public List<ConsentService.ConsentView> consents(Authentication auth) {
        return self.consents(guard.person(auth));
    }

    @PutMapping("/me/consents/{purpose}")
    public ConsentService.ConsentView setConsent(Authentication auth, @PathVariable String purpose, @Valid @RequestBody ConsentChangeRequest r) {
        return self.setConsent(guard.person(auth), purpose, r.granted());
    }
}
