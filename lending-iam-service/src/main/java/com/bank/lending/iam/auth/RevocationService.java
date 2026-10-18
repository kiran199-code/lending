package com.bank.lending.iam.auth;

import com.bank.lending.iam.client.ClientRepository;
import com.bank.lending.iam.user.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Ends an identity's tokens: access tokens issued before the moment recorded here are refused by IAM's own API
 * (and by services that introspect), and the framework's authorization rows (refresh tokens) are deleted so
 * no refresh can succeed.
 */
@Service
public class RevocationService {
    private final UserRepository users;
    private final ClientRepository clients;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public RevocationService(UserRepository users, ClientRepository clients, JdbcTemplate jdbc, Clock clock) {
        this.users = users;
        this.clients = clients;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** @return the number of authorizations removed */
    @Transactional
    public int revokeUser(long userId, String username) {
        users.tokensValidAfter(userId, Instant.now(clock));
        return jdbc.update("DELETE FROM oauth2_authorization WHERE principal_name = ?", username);
    }

    @Transactional
    public int revokeClient(String clientId) {
        clients.setStatus(clientId, clients.find(clientId).map(c -> c.status()).orElse("ACTIVE"), Instant.now(clock));
        return jdbc.update("DELETE FROM oauth2_authorization WHERE registered_client_id = ?", clientId);
    }

    /** Removes authorizations whose newest token expired before the cutoff (the library never deletes them). */
    @Transactional
    public int purgeExpired(Instant cutoff) {
        return jdbc.update("DELETE FROM oauth2_authorization WHERE coalesce(refresh_token_expires_at, access_token_expires_at, authorization_code_expires_at, TIMESTAMP 'epoch') < ?",
                java.sql.Timestamp.from(cutoff));
    }
}
