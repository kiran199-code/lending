package com.bank.lending.chit.collection;

import com.bank.lending.chit.calc.LateFeeCalculator.DailyAccrual;
import com.bank.lending.chit.domain.AllocationBucket;
import com.bank.lending.chit.domain.TicketStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Requests and responses of the collection endpoints. */
public final class CollectionDtos {
    private CollectionDtos() {
    }

    /** Money received for one ticket. {@code channel}: UPI, BANK_TRANSFER, CASH_AT_FOREMAN or AUTO_DEBIT. {@code externalRef}: the payer's or bank's reference. */
    public record RecordPaymentRequest(BigDecimal amount, String channel, String externalRef) {
    }

    public record ReversePaymentRequest(String reason) {
    }

    /** One slice of a payment. {@code demandRef} and {@code cycleNo} are null for the ADVANCE slice. */
    public record AllocationView(Integer cycleNo, String demandRef, AllocationBucket bucket, BigDecimal amount, boolean fromAdvance) {
    }

    public record PaymentView(String paymentRef, String groupCode, int ticketNo, long customerId, BigDecimal amount, String channel,
                              String status, LocalDate businessDate, String externalRef, BigDecimal allocated, BigDecimal advance,
                              List<AllocationView> allocations, String reversalReason, OffsetDateTime reversedAt, String reversedBy,
                              OffsetDateTime createdAt, String createdBy, Boolean replayed) {
        public PaymentView asReplay() {
            return new PaymentView(paymentRef, groupCode, ticketNo, customerId, amount, channel, status, businessDate, externalRef, allocated,
                    advance, allocations, reversalReason, reversedAt, reversedBy, createdAt, createdBy, Boolean.TRUE);
        }
    }

    public record DemandView(String demandRef, int cycleNo, int ticketNo, LocalDate dueDate, LocalDate raisedOn, BigDecimal subscriptionAmount,
                             BigDecimal dividendAdjustment, BigDecimal subscriptionPaid, BigDecimal subscriptionOutstanding,
                             BigDecimal lateFeeAccrued, BigDecimal lateFeePaid, BigDecimal lateFeeOutstanding, BigDecimal totalOutstanding,
                             long daysOverdue, String status) {
    }

    public record DefaultCaseView(int ticketNo, long customerId, String status, LocalDate openedOn, BigDecimal shortfall, String reason,
                                  Integer missedInstalments, Integer daysOverdue, LocalDate closedOn, BigDecimal foremanCovered,
                                  String openedBy) {
    }

    public record TicketStatement(String groupCode, int ticketNo, long customerId, TicketStatus ticketStatus, LocalDate asOf,
                                  BigDecimal subscriptionOutstanding, BigDecimal lateFeeOutstanding, BigDecimal totalOutstanding,
                                  BigDecimal advanceHeld, BigDecimal totalPaid, LocalDate nextDueDate, DefaultCaseView openDefaultCase,
                                  List<DemandView> demands, List<PaymentView> payments) {
    }

    public record CycleLine(int cycleNo, String status, LocalDate dueDate, int demands, int settledDemands, BigDecimal due,
                            BigDecimal collected, BigDecimal outstanding, BigDecimal collectedPct, BigDecimal lateFeeAccrued,
                            BigDecimal lateFeePaid) {
    }

    public record DefaulterLine(int ticketNo, long customerId, BigDecimal subscriptionOutstanding, BigDecimal lateFeeOutstanding,
                                int overdueInstalments, long oldestDaysOverdue) {
    }

    public record GroupStatement(String groupCode, String groupName, LocalDate asOf, List<CycleLine> cycles, BigDecimal totalDue,
                                 BigDecimal totalCollected, BigDecimal totalOutstanding, BigDecimal lateFeeOutstanding,
                                 List<DefaulterLine> overdueTickets) {
    }

    /** A line of the dues screens. {@code demandRef} is null for an instalment that has not been raised yet (UPCOMING). */
    public record DueRow(String view, String groupCode, String groupName, int ticketNo, long customerId, String ticketStatus, int cycleNo,
                         String demandRef, LocalDate dueDate, long daysOverdue, BigDecimal subscriptionOutstanding,
                         BigDecimal lateFeeOutstanding, BigDecimal totalOutstanding) {
    }

    public record DuesPage(String view, LocalDate asOf, List<DueRow> rows, int limit, int offset) {
    }

    public record LateFeeProjection(String demandRef, int cycleNo, LocalDate dueDate, BigDecimal overdue, BigDecimal accruedSoFar,
                                    List<DailyAccrual> days) {
    }

    public record LateFeePreviewView(String groupCode, int ticketNo, LocalDate from, int days, String rule, List<LateFeeProjection> demands) {
    }

    public record GroupFailure(String groupCode, String code, String message) {
    }

    /**
     * What a daily run did. {@code figures} are counts and totals specific to the job: demandsRaised, cyclesOpened,
     * advanceUsed (demand run); lateFeesPosted, lateFeeTotal (late-fee run); ticketsDefaulted (default run).
     * A rerun on the same day changes nothing and says so (all figures zero).
     */
    public record RunSummary(String job, LocalDate businessDate, int groupsProcessed, int groupsFailed, Map<String, BigDecimal> figures,
                             List<GroupFailure> failures) {
    }
}
