package com.bank.lending.chit.calc;

import com.bank.lending.chit.common.ChitException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DrawTest {

    private static List<Integer> tickets(int n) {
        List<Integer> l = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            l.add(i);
        }
        return l;
    }

    @Test
    void theSameSeedAndCandidatesGiveTheSameWinner() {
        Draw.Outcome a = Draw.pick(42L, tickets(20));
        Draw.Outcome b = Draw.pick(42L, tickets(20));
        assertEquals(a, b);
        assertEquals(Draw.ALGORITHM, a.algorithm());
        assertEquals(1, a.result().size());
    }

    @Test
    void candidateOrderDoesNotMatter() {
        List<Integer> shuffled = tickets(20);
        Collections.reverse(shuffled);
        assertEquals(Draw.pick(7L, tickets(20)).result(), Draw.pick(7L, shuffled).result());
    }

    @Test
    void theWinnerIsAlwaysACandidate() {
        for (long seed = 0; seed < 500; seed++) {
            List<Integer> c = List.of(3, 8, 12, 19);
            assertTrue(c.contains(Draw.pick(seed, c).result().get(0)), "seed " + seed);
        }
    }

    @Test
    void differentSeedsSpreadAcrossTheCandidates() {
        Set<Integer> seen = new HashSet<>();
        for (long seed = 0; seed < 400; seed++) {
            seen.add(Draw.pick(seed, tickets(10)).result().get(0));
        }
        assertEquals(10, seen.size()); // every ticket wins for some seed
    }

    @Test
    void aSingleCandidateAlwaysWins() {
        assertEquals(List.of(5), Draw.pick(1L, List.of(5)).result());
    }

    @Test
    void orderIsAFullPermutationAndReplayable() {
        Draw.Outcome o = Draw.order(2026L, tickets(20));
        assertEquals(20, o.result().size());
        assertEquals(new HashSet<>(tickets(20)), new HashSet<>(o.result()));
        assertEquals(o, Draw.order(2026L, tickets(20)));
        assertNotEquals(o.result(), Draw.order(2027L, tickets(20)).result());
        assertNotEquals(tickets(20), o.result()); // astronomically unlikely to be the identity for this seed
    }

    @Test
    void theRecordKeepsWhatIsNeededToReplayIt() {
        Draw.Outcome o = Draw.pick(99L, List.of(9, 2, 4));
        assertEquals(99L, o.seed());
        assertEquals(List.of(2, 4, 9), o.candidates());
        assertEquals(o.result(), Draw.pick(o.seed(), o.candidates()).result());
    }

    @Test
    void refusesNoCandidatesOrDuplicates() {
        assertEquals("NO_CANDIDATES", assertThrows(ChitException.class, () -> Draw.pick(1L, List.of())).code());
        assertEquals("DUPLICATE_TICKET", assertThrows(ChitException.class, () -> Draw.pick(1L, List.of(1, 1))).code());
    }
}
