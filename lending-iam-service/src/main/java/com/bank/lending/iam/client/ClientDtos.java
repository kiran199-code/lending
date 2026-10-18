package com.bank.lending.iam.client;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class ClientDtos {
    private ClientDtos() {}

    public record ClientView(String clientId, String name, String kind, List<String> grantTypes, List<String> redirectUris, List<String> postLogoutUris,
                             List<String> scopes, int accessTokenSeconds, int refreshTokenSeconds, String status, List<String> roles,
                             Instant previousSecretValidUntil, Instant createdAt, String createdBy) {}

    public record CreateClientRequest(@NotBlank @Size(max = 80) String clientId, @NotBlank @Size(max = 120) String name, @NotBlank String kind,
                                      @NotNull List<String> grantTypes, List<String> redirectUris, List<String> postLogoutUris, List<String> roles,
                                      Integer accessTokenSeconds, Integer refreshTokenSeconds) {}

    public record UpdateClientRequest(@NotBlank @Size(max = 120) String name, List<String> redirectUris, List<String> postLogoutUris,
                                      Integer accessTokenSeconds, Integer refreshTokenSeconds) {}

    public record ReplaceClientRolesRequest(@NotNull List<String> roles) {}

    /** The secret appears here once; it cannot be read again. {@code secret} is null for a public client. */
    public record CreatedClient(ClientView client, String secret) {}

    public record RotatedSecret(String clientId, String secret, Instant previousSecretValidUntil) {}
}
