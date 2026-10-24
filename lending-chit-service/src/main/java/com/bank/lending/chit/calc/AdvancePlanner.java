package com.bank.lending.chit.calc;

import com.bank.lending.chit.common.Money;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Uses advance money (payments received ahead of any demand) against a new demand, oldest advance first.
 * A slice is one ADVANCE allocation row with what is still unused on it.
 */
public final class AdvancePlanner {

    public record Slice(long allocationId, long paymentId, BigDecimal available) {
    }

    /** Take {@code amount} from the slice with {@code allocationId}. */
    public record Use(long allocationId, long paymentId, BigDecimal amount) {
    }

    private AdvancePlanner() {
    }

    /** At most {@code needed}; fewer if the slices run out. Slices must be given oldest first. */
    public static List<Use> consume(List<Slice> slices, BigDecimal needed) {
        List<Use> uses = new ArrayList<>();
        BigDecimal left = Money.nz(needed);
        for (Slice s : slices) {
            if (left.signum() <= 0) {
                break;
            }
            BigDecimal avail = Money.nz(s.available());
            if (avail.signum() <= 0) {
                continue;
            }
            BigDecimal take = Money.min(avail, left);
            uses.add(new Use(s.allocationId(), s.paymentId(), take));
            left = left.subtract(take);
        }
        return uses;
    }

    public static BigDecimal total(List<Use> uses) {
        return uses.stream().map(Use::amount).reduce(Money.ZERO, BigDecimal::add);
    }
}
