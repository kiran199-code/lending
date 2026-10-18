package com.bank.lending.iam.config;

import com.bank.lending.iam.auth.Authenticator;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Form sign-in for people. All decisions are made by {@link Authenticator}; this class only adapts the result.
 * It is deliberately not a Spring bean: the framework would then also register it with the parent authentication manager
 * and every attempt would be counted twice against the lockout.
 *
 * The result uses only types the authorization server can store (standard user, plain authorities):
 * ROLE_&lt;kind&gt;, AMR_pwd, AMR_otp and MFA_AT_&lt;epoch seconds&gt;.
 */
public class IamAuthenticationProvider implements AuthenticationProvider {
    private final Authenticator authenticator;

    public IamAuthenticationProvider(Authenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String username = authentication.getName();
        String password = authentication.getCredentials() == null ? null : authentication.getCredentials().toString();
        Authenticator.Result r = authenticator.login(username, password, currentRequestParameter("totp"));
        switch (r.outcome()) {
            case SUCCESS:
                return success(r);
            case FAILED:
                throw new BadCredentialsException("Invalid credentials");
            default:
                throw new IamAuthException(r.outcome(), r.user().username());
        }
    }

    static Authentication success(Authenticator.Result r) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + r.user().kind()));
        authorities.add(new SimpleGrantedAuthority("AMR_pwd"));
        Instant mfaAt = r.mfaAt();
        if (mfaAt != null) {
            authorities.add(new SimpleGrantedAuthority("AMR_otp"));
            authorities.add(new SimpleGrantedAuthority(IamTokenCustomizer.MFA_AT_PREFIX + mfaAt.getEpochSecond()));
        }
        User principal = new User(r.user().username(), "", true, true, true, true, authorities);
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }

    private static String currentRequestParameter(String name) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a) {
            HttpServletRequest rq = a.getRequest();
            String v = rq.getParameter(name);
            return v == null ? null : v.trim();
        }
        return null;
    }
}
