package com.bank.lending.iam.config;

import com.bank.lending.iam.auth.Authenticator;
import org.springframework.security.core.AuthenticationException;

/** The password was right but the sign-in cannot finish yet: a page must take the person through the missing step. */
public class IamAuthException extends AuthenticationException {
    private final transient Authenticator.Outcome outcome;
    private final String username;

    public IamAuthException(Authenticator.Outcome outcome, String username) {
        super(outcome.name());
        this.outcome = outcome;
        this.username = username;
    }

    public Authenticator.Outcome outcome() { return outcome; }
    public String username() { return username; }
}
