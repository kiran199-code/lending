package com.bank.lending.chit.calc;

import com.bank.lending.chit.common.ChitException;
import com.bank.lending.chit.common.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.SortedMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LargestRemainderTest {

    private static List<Integer> tickets(int n) {
        List<Integer> l = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            l.add(i);
        }
        return l;
    }

    private static BigDecimal sum(SortedMap<Integer, BigDecimal> m) {
        return m.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void evenSplitNeedsNoLeftover() {
        SortedMap<Integer, BigDecimal> s = LargestRemainder.equalShares(new BigDecimal("15000.00"), tickets(20));
        assertEquals(20, s.size());
        for (BigDecimal v : s.values()) {
            assertEquals(new BigDecimal("750.00"), v);
        }
    }

    @Test
    void fixtureB_unevenPoolGivesExtraPaiseToLowestTicketNumbers() {
        // 15,000.07 over 20 tickets: 750.00 each and 7 paise left; tickets 1..7 get 750.01
        SortedMap<Integer, BigDecimal> s = LargestRemainder.equalShares(new BigDecimal("15000.07"), tickets(20));
        for (int t = 1; t <= 7; t++) {
            assertEquals(new BigDecimal("750.01"), s.get(t), "ticket " + t);
        }
        for (int t = 8; t <= 20; t++) {
            assertEquals(new BigDecimal("750.00"), s.get(t), "ticket " + t);
        }
        assertEquals(new BigDecimal("15000.07"), sum(s));
    }

    @Test
    void sharesAreOnlyForTheListedTickets() {
        SortedMap<Integer, BigDecimal> s = LargestRemainder.equalShares(new BigDecimal("100.00"), List.of(4, 9, 11));
        assertEquals(3, s.size());
        assertEquals(new BigDecimal("33.34"), s.get(4));
        assertEquals(new BigDecimal("33.33"), s.get(9));
        assertEquals(new BigDecimal("33.33"), s.get(11));
    }

    @Test
    void weightedSplitUsesLargestRemainderThenLowerTicketNumber() {
        Map<Integer, BigDecimal> w = new HashMap<>();
        w.put(1, new BigDecimal("1"));
        w.put(2, new BigDecimal("2"));
        // 1.00 split 1:2 -> 33.333 and 66.666 paise: floors 33 and 66, one paisa left, remainder .666 vs .333 -> ticket 2
        SortedMap<Integer, BigDecimal> s = LargestRemainder.allocate(new BigDecimal("1.00"), w);
        assertEquals(new BigDecimal("0.33"), s.get(1));
        assertEquals(new BigDecimal("0.67"), s.get(2));
    }

    @Test
    void zeroTotalGivesZeroShares() {
        SortedMap<Integer, BigDecimal> s = LargestRemainder.equalShares(Money.ZERO, tickets(3));
        assertEquals(new BigDecimal("0.00"), sum(s));
    }

    @Test
    void refusesNothingToShareWith() {
        assertThrows(ChitException.class, () -> LargestRemainder.equalShares(new BigDecimal("10.00"), List.of()));
        assertThrows(ChitException.class, () -> LargestRemainder.equalShares(new BigDecimal("10.00"), List.of(1, 1)));
        assertThrows(ChitException.class, () -> LargestRemainder.equalShares(new BigDecimal("-1.00"), tickets(2)));
    }

    @Test
    void propertySharesAlwaysAddUpExactlyAndDifferByAtMostOnePaisaWhenEqual() {
        Random random = new Random(20261023L); // fixed seed so a failure can be replayed
        for (int run = 0; run < 2000; run++) {
            int n = 1 + random.nextInt(40);
            long paise = random.nextInt(5_000_000);
            BigDecimal total = Money.ofPaise(paise);
            SortedMap<Integer, BigDecimal> s = LargestRemainder.equalShares(total, tickets(n));
            assertEquals(total, sum(s), "run " + run);
            BigDecimal min = s.values().stream().min(BigDecimal::compareTo).get();
            BigDecimal max = s.values().stream().max(BigDecimal::compareTo).get();
            assertTrue(max.subtract(min).compareTo(new BigDecimal("0.01")) <= 0, "spread at run " + run);
        }
    }

    @Test
    void propertyWeightedSharesAddUpExactly() {
        Random random = new Random(7L);
        for (int run = 0; run < 1000; run++) {
            int n = 1 + random.nextInt(15);
            Map<Integer, BigDecimal> w = new HashMap<>();
            for (int i = 1; i <= n; i++) {
                w.put(i, new BigDecimal(random.nextInt(10)).add(i == 1 ? BigDecimal.ONE : BigDecimal.ZERO));
            }
            BigDecimal total = Money.ofPaise(random.nextInt(2_000_000));
            assertEquals(total, sum(LargestRemainder.allocate(total, w)), "run " + run);
        }
    }
}
