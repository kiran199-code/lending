package com.bank.lending.iam.config;

import com.bank.lending.iam.client.ClientRepository;
import com.bank.lending.iam.client.ClientRow;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Presents the rows of oauth_client to the authorization server. Clients are managed through the admin API only, so
 * {@link #save} refuses (the library would otherwise try to re-encode secrets behind our back). A disabled client is not
 * found, which makes every request from it fail as an unknown client. The registered id is the client id, so rows of
 * oauth2_authorization can be matched to clients by the same value.
 */
public class IamRegisteredClientRepository implements RegisteredClientRepository {
    private final ClientRepository clients;
    private final Clock clock;
    private final int authCodeSeconds;

    public IamRegisteredClientRepository(ClientRepository clients, Clock clock, int authCodeSeconds) {
        this.clients = clients;
        this.clock = clock;
        this.authCodeSeconds = authCodeSeconds;
    }

    @Override
    public void save(RegisteredClient registeredClient) {
        throw new UnsupportedOperationException("Clients are managed through /api/v1/clients");
    }

    @Override
    public RegisteredClient findById(String id) {
        return findByClientId(id);
    }

    @Override
    public RegisteredClient findByClientId(String clientId) {
        if (clientId == null || clientId.isBlank()) return null;
        return clients.find(clientId).filter(ClientRow::active).map(this::toRegistered).orElse(null);
    }

    RegisteredClient toRegistered(ClientRow c) {
        RegisteredClient.Builder b = RegisteredClient.withId(c.clientId()).clientId(c.clientId()).clientName(c.name());
        if (c.isPublic()) {
            b.clientAuthenticationMethod(ClientAuthenticationMethod.NONE);
        } else {
            b.clientSecret(secretFor(c));
            b.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
            b.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST);
        }
        for (String g : c.grantTypes()) b.authorizationGrantType(new AuthorizationGrantType(g));
        c.redirectUris().forEach(b::redirectUri);
        c.postLogoutUris().forEach(b::postLogoutRedirectUri);
        c.scopes().forEach(b::scope);
        b.clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(false).build());
        b.tokenSettings(TokenSettings.builder()
                .accessTokenFormat(OAuth2TokenFormat.SELF_CONTAINED)
                .accessTokenTimeToLive(Duration.ofSeconds(c.accessTtlSeconds()))
                .refreshTokenTimeToLive(Duration.ofSeconds(c.refreshTtlSeconds()))
                .authorizationCodeTimeToLive(Duration.ofSeconds(authCodeSeconds))
                .reuseRefreshTokens(false)
                .idTokenSignatureAlgorithm(SignatureAlgorithm.RS256)
                .build());
        return b.build();
    }

    /** During the grace period after a rotation both hashes are offered; see {@link ClientSecretEncoder}. */
    String secretFor(ClientRow c) {
        boolean grace = c.previousSecretHash() != null && c.previousValidUntil() != null && Instant.now(clock).isBefore(c.previousValidUntil());
        return grace ? ClientSecretEncoder.DUAL + c.secretHash() + "~" + c.previousSecretHash() : c.secretHash();
    }

}
