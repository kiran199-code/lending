package com.bank.lending.iam.client;

import com.bank.lending.iam.common.Db;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Repository
public class ClientRepository {
    private static final String COLS = "client_id, client_name, client_kind, secret_hash, previous_secret_hash, previous_valid_until, grant_types, "
            + "redirect_uris, post_logout_uris, scopes, access_ttl_seconds, refresh_ttl_seconds, status, tokens_valid_after, created_at, created_by";

    static final RowMapper<ClientRow> MAP = (rs, i) -> new ClientRow(rs.getString("client_id"), rs.getString("client_name"), rs.getString("client_kind"),
            rs.getString("secret_hash"), rs.getString("previous_secret_hash"), Db.instant(rs, "previous_valid_until"),
            split(rs.getString("grant_types"), ","), split(rs.getString("redirect_uris"), "\n"), split(rs.getString("post_logout_uris"), "\n"),
            split(rs.getString("scopes"), " "), rs.getInt("access_ttl_seconds"), rs.getInt("refresh_ttl_seconds"), rs.getString("status"),
            Db.instant(rs, "tokens_valid_after"), Db.instant(rs, "created_at"), rs.getString("created_by"));

    static List<String> split(String s, String sep) {
        if (s == null || s.isBlank()) return List.of();
        return Arrays.stream(s.split(java.util.regex.Pattern.quote(sep))).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    private final JdbcTemplate jdbc;

    public ClientRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ClientRow> list() {
        return jdbc.query("SELECT " + COLS + " FROM oauth_client ORDER BY client_id", MAP);
    }

    public Optional<ClientRow> find(String clientId) {
        return Db.one(jdbc, "SELECT " + COLS + " FROM oauth_client WHERE client_id = ?", MAP, clientId);
    }

    public void insert(String clientId, String name, String kind, String secretHash, List<String> grants, List<String> redirects,
                       List<String> postLogout, List<String> scopes, int accessTtl, int refreshTtl, String createdBy) {
        jdbc.update("INSERT INTO oauth_client (client_id, client_name, client_kind, secret_hash, grant_types, redirect_uris, post_logout_uris, scopes, "
                        + "access_ttl_seconds, refresh_ttl_seconds, created_by) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                clientId, name, kind, secretHash, String.join(",", grants), String.join("\n", redirects), String.join("\n", postLogout),
                String.join(" ", scopes), accessTtl, refreshTtl, createdBy);
    }

    public void update(String clientId, String name, List<String> redirects, List<String> postLogout, int accessTtl, int refreshTtl) {
        jdbc.update("UPDATE oauth_client SET client_name = ?, redirect_uris = ?, post_logout_uris = ?, access_ttl_seconds = ?, refresh_ttl_seconds = ?, updated_at = now() WHERE client_id = ?",
                name, String.join("\n", redirects), String.join("\n", postLogout), accessTtl, refreshTtl, clientId);
    }

    /** New secret becomes current; the old one stays valid until {@code previousValidUntil}. */
    public void rotateSecret(String clientId, String newHash, Instant previousValidUntil) {
        jdbc.update("UPDATE oauth_client SET previous_secret_hash = secret_hash, previous_valid_until = ?, secret_hash = ?, updated_at = now() WHERE client_id = ?",
                Db.ts(previousValidUntil), newHash, clientId);
    }

    public void setStatus(String clientId, String status, Instant tokensValidAfter) {
        jdbc.update("UPDATE oauth_client SET status = ?, tokens_valid_after = coalesce(?, tokens_valid_after), updated_at = now() WHERE client_id = ?",
                status, Db.ts(tokensValidAfter), clientId);
    }

    public void replaceRoles(String clientId, List<String> roles) {
        jdbc.update("DELETE FROM oauth_client_role WHERE client_id = ?", clientId);
        for (String r : roles) jdbc.update("INSERT INTO oauth_client_role (client_id, role_code) VALUES (?,?)", clientId, r);
    }

    public List<String> roles(String clientId) {
        return jdbc.query("SELECT role_code FROM oauth_client_role WHERE client_id = ? ORDER BY role_code", (rs, i) -> rs.getString("role_code"), clientId);
    }
}
