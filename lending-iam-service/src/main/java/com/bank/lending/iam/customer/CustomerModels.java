package com.bank.lending.iam.customer;

import java.time.Instant;
import java.time.LocalDate;

public final class CustomerModels {
    private CustomerModels() {}

    public record LinkRow(long id, long userId, String customerId, String linkType, String accountRef, String relationship, String status,
                          LocalDate validFrom, LocalDate validTo, String createdBy, Instant createdAt, String approvedBy, Instant approvedAt,
                          String revokedBy, Instant revokedAt, String reason) {}

    public record PurposeRow(String code, String description, int version, boolean mandatory, boolean active) {}

    public record ConsentState(String purposeCode, int version, boolean granted, Instant at) {}

    public record ConsentRecord(long id, String purposeCode, int version, boolean granted, Instant at, String clientId, String ip) {}
}
