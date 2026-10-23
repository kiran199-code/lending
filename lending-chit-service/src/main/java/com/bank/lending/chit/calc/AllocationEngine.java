package com.bank.lending.chit.calc;

import com.bank.lending.chit.common.ChitException;
import com.bank.lending.chit.common.Money;
import com.bank.lending.chit.domain.AllocationBucket;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/**
 * Applies one payment to what a ticket owes. The order of the buckets is configuration (default: late fees, then
 * oldest arrears, then the current instalment); whatever is left is an ADVANCE. Partial payments simply run out part
 * way down the list. Within a bucket the oldest cycle is paid first.
 *
 * <p>Buckets: LATE_FEES is the late fee outstanding on any demand; ARREARS is the subscription outstanding on cycles
 * before the current one; CURRENT is the subscription outstanding on the current cycle.
 */
public final class AllocationEngine {

    /** What one demand still owes. */
    public record DemandDue(long demandId, int cycleNo, BigDecimal subscriptionOutstanding, BigDecimal lateFeeOutstanding) {
    }

    /** One slice of the payment. {@code demandId} and {@code cycleNo} are null for the ADVANCE slice. */
    public record Line(Long demandId, Integer cycleNo, AllocationBucket bucket, BigDecimal amount) {
    }

    public record Result(List<Line> lines, BigDecimal allocated, BigDecimal advance) {
    }

    private AllocationEngine() {
    }

    public static Result allocate(BigDecimal payment, int currentCycleNo, List<DemandDue> dues, List<AllocationBucket> order) {
        if (payment == null || payment.signum() <= 0) {
            throw new ChitException("PAYMENT_NOT_POSITIVE", "A payment must be greater than zero");
        }
        if (order == null || order.size() != 3
                || !EnumSet.copyOf(order).equals(EnumSet.of(AllocationBucket.LATE_FEES, AllocationBucket.ARREARS, AllocationBucket.CURRENT))) {
            throw new ChitException("ALLOCATION_ORDER_INVALID", "The order must list LATE_FEES, ARREARS and CURRENT once each");
        }
        BigDecimal left = Money.scale(payment);
        List<DemandDue> oldestFirst = new ArrayList<>(dues);
        oldestFirst.sort(Comparator.comparingInt(DemandDue::cycleNo).thenComparingLong(DemandDue::demandId));
        List<Line> lines = new ArrayList<>();
        for (AllocationBucket bucket : order) {
            for (DemandDue due : oldestFirst) {
                if (left.signum() == 0) {
                    break;
                }
                BigDecimal owed = owedIn(bucket, due, currentCycleNo);
                if (owed.signum() <= 0) {
                    continue;
                }
                BigDecimal part = Money.min(owed, left);
                lines.add(new Line(due.demandId(), due.cycleNo(), bucket, part));
                left = left.subtract(part);
            }
        }
        BigDecimal advance = left;
        if (advance.signum() > 0) {
            lines.add(new Line(null, null, AllocationBucket.ADVANCE, advance));
        }
        return new Result(List.copyOf(lines), Money.scale(payment).subtract(advance), advance);
    }

    private static BigDecimal owedIn(AllocationBucket bucket, DemandDue due, int currentCycleNo) {
        return switch (bucket) {
            case LATE_FEES -> Money.nz(due.lateFeeOutstanding());
            case ARREARS -> due.cycleNo() < currentCycleNo ? Money.nz(due.subscriptionOutstanding()) : Money.ZERO;
            case CURRENT -> due.cycleNo() == currentCycleNo ? Money.nz(due.subscriptionOutstanding()) : Money.ZERO;
            case ADVANCE -> Money.ZERO;
        };
    }
}
