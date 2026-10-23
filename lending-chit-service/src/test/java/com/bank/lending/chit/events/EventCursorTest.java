package com.bank.lending.chit.events;

import com.bank.lending.chit.common.ChitException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EventCursorTest {

    @Test
    void noCursorStartsAtTheBeginning() {
        long[] c = EventFeed.parseCursor(null);
        assertEquals(0L, c[0]);
        assertEquals(0L, c[1]);
        assertEquals(0L, EventFeed.parseCursor("  ")[0]);
    }

    @Test
    void aCursorIsTransactionIdAndEventId() {
        long[] c = EventFeed.parseCursor("1234-56");
        assertEquals(1234L, c[0]);
        assertEquals(56L, c[1]);
    }

    @Test
    void junkIsABadRequest() {
        for (String bad : new String[]{"abc", "1-2-3", "1-x", "-5", "12"}) {
            assertEquals("INVALID_CURSOR", assertThrows(ChitException.class, () -> EventFeed.parseCursor(bad)).code(), bad);
        }
    }
}
