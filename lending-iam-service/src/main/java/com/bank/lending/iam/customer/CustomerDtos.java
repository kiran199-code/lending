package com.bank.lending.iam.customer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;

public final class CustomerDtos {
    private CustomerDtos() {}

    public record CreateLinkRequest(@NotBlank String linkType, @NotBlank String relationship, @Size(max = 40) String accountRef, @Size(max = 40) String customerId,
                                    LocalDate validFrom, LocalDate validTo) {}

    public record ReasonRequest(@NotBlank @Size(max = 300) String reason) {}

    public record LinkView(long id, long userId, String username, String customerId, String linkType, String accountRef, String relationship, String status,
                           LocalDate validFrom, LocalDate validTo, String createdBy, Instant createdAt, String approvedBy, String revokedBy, String reason) {}
}
