package com.bank.lending.chit.enrolment;

import com.bank.lending.chit.domain.TicketStatus;
import com.bank.lending.chit.enrolment.ActivationCheck.Blocker;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class EnrolmentDtos {
    private EnrolmentDtos() {
    }

    /**
     * One enrolment. The amounts are what was actually received and must equal the quote exactly (see
     * {@code GET /groups/{code}/enrolment-quote}); leave out what is not due. A customer may hold several tickets
     * by enrolling several times.
     */
    public record EnrolRequest(Long customerId, String payoutAccountRef, BigDecimal enrolmentFeeReceived,
                               BigDecimal registrationFeeReceived, BigDecimal securityDepositReceived,
                               Long guarantorCustomerId, String lienReference) {
    }

    public record QuoteView(BigDecimal enrolmentFee, BigDecimal registrationFee, BigDecimal securityDeposit, BigDecimal lienAmount,
                            boolean guarantorRequired, BigDecimal cashDue) {
    }

    public record FeeView(String kind, BigDecimal amount, String status, LocalDate receivedOn, LocalDate refundedOn) {
    }

    public record SecurityView(String kind, BigDecimal amount, Long guarantorCustomerId, String reference, String status,
                               LocalDate receivedOn, String verifiedBy) {
    }

    public record TicketView(long ticketId, int ticketNo, long customerId, TicketStatus status, String payoutAccountRef,
                             List<FeeView> fees, SecurityView security, String exitReason, LocalDate exitedOn, Boolean replayed) {
        public TicketView asReplay() {
            return new TicketView(ticketId, ticketNo, customerId, status, payoutAccountRef, fees, security, exitReason, exitedOn,
                    Boolean.TRUE);
        }
    }

    public record WithdrawRequest(String reason) {
    }

    public record CancelRequest(String reason) {
    }

    public record RefundLine(String kind, int ticketNo, BigDecimal amount) {
    }

    /** What a withdrawal or cancellation handed back. {@code cashRefunded} excludes liens, which are released, not paid. */
    public record RefundSummary(List<RefundLine> lines, BigDecimal cashRefunded) {
    }

    public record WithdrawResult(TicketView ticket, RefundSummary refund, String groupStatus, Boolean replayed) {
        public WithdrawResult asReplay() {
            return new WithdrawResult(ticket, refund, groupStatus, Boolean.TRUE);
        }
    }

    public record CancelResult(String groupCode, String status, String reason, RefundSummary refund, Boolean replayed) {
        public CancelResult asReplay() {
            return new CancelResult(groupCode, status, reason, refund, Boolean.TRUE);
        }
    }

    public record ActivationResult(com.bank.lending.chit.group.GroupDtos.GroupView group,
                                   List<com.bank.lending.chit.group.GroupDtos.CycleView> cycles, Boolean replayed) {
        public ActivationResult asReplay() {
            return new ActivationResult(group.asReplay(), cycles, Boolean.TRUE);
        }
    }

    public record Readiness(boolean ready, List<Blocker> blockers, LocalDate firstDueDate) {
    }
}
