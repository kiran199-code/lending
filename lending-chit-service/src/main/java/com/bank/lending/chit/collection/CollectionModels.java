package com.bank.lending.chit.collection;

import com.bank.lending.chit.calc.DemandFigures;
import com.bank.lending.chit.domain.AllocationBucket;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Database rows of the collection tables. */
public final class CollectionModels {
    private CollectionModels() {
    }

    public record CycleDue(long id, int cycleNo, LocalDate dueDate) {
    }

    public record DemandRow(long id, String demandRef, long cycleId, int cycleNo, long ticketId, LocalDate dueDate, LocalDate raisedOn,
                            BigDecimal subscriptionAmount, BigDecimal dividendAdjustment, BigDecimal subscriptionPaid,
                            BigDecimal lateFeeAccrued, BigDecimal lateFeePaid, String status) {
        public DemandFigures figures() {
            return new DemandFigures(subscriptionAmount, dividendAdjustment, subscriptionPaid, lateFeeAccrued, lateFeePaid);
        }
    }

    public record PaymentRow(long id, String paymentRef, long groupId, long ticketId, BigDecimal amount, String channel, String status,
                             LocalDate businessDate, String externalRef, String reversalReason, OffsetDateTime reversedAt,
                             String reversedBy, OffsetDateTime createdAt, String createdBy) {
    }

    public record AllocationRow(long id, long paymentId, Long demandId, AllocationBucket bucket, BigDecimal amount, boolean reversed,
                                boolean fromAdvance, BigDecimal advanceUsed) {
    }

    /** An ADVANCE allocation with what is still unused on it. */
    public record AdvanceRow(long allocationId, long paymentId, BigDecimal amount, BigDecimal used) {
        public BigDecimal available() {
            return amount.subtract(used);
        }
    }

    public record DividendRow(long id, long cycleId, int cycleNo, long ticketId, BigDecimal amount) {
    }

    public record DefaultCaseRow(long id, long ticketId, LocalDate openedOn, String status, BigDecimal shortfall, String reason,
                                 Integer missedInstalments, Integer daysOverdue, LocalDate closedOn, BigDecimal foremanCovered,
                                 String openedBy) {
    }

    /** One cycle's totals across its demands. */
    public record CycleTotals(int cycleNo, String status, LocalDate dueDate, int demands, int settledDemands, BigDecimal due,
                              BigDecimal collected, BigDecimal lateFeeAccrued, BigDecimal lateFeePaid) {
    }
}
