package com.bank.lending.chit.enrolment;

import com.bank.lending.chit.domain.TicketStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Database rows of the enrolment tables. */
public final class EnrolmentModels {
    private EnrolmentModels() {
    }

    /** {@code customerId} is the current holder; a transfer (Phase 6) updates it together with the subscriber link. */
    public record TicketRow(long id, long groupId, int ticketNo, long customerId, TicketStatus status, String payoutAccountRef,
                            String exitReason, LocalDate exitedOn, OffsetDateTime createdAt, String createdBy) {
    }

    public record FeeRow(long id, long ticketId, String feeKind, BigDecimal amount, String status, LocalDate receivedOn,
                         LocalDate refundedOn) {
    }

    public record SecurityRow(long id, long ticketId, String kind, BigDecimal amount, Long guarantorCustomerId, String reference,
                              String status, LocalDate receivedOn, String verifiedBy, OffsetDateTime verifiedAt,
                              LocalDate returnedOn, String createdBy) {
    }
}
