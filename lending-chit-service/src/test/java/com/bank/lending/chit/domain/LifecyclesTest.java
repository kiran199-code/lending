package com.bank.lending.chit.domain;

import com.bank.lending.chit.common.ChitException;
import com.bank.lending.chit.domain.state.Lifecycles;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LifecyclesTest {

    @Test
    void groupHappyPath() {
        Lifecycles.GROUP.require(GroupStatus.DRAFT, GroupStatus.OPEN_FOR_ENROLMENT);
        Lifecycles.GROUP.require(GroupStatus.OPEN_FOR_ENROLMENT, GroupStatus.FULL);
        Lifecycles.GROUP.require(GroupStatus.FULL, GroupStatus.ACTIVE);
        Lifecycles.GROUP.require(GroupStatus.ACTIVE, GroupStatus.CLOSING);
        Lifecycles.GROUP.require(GroupStatus.CLOSING, GroupStatus.CLOSED);
    }

    @Test
    void groupCanBeCancelledOnlyBeforeItIsActive() {
        assertTrue(Lifecycles.GROUP.canMove(GroupStatus.DRAFT, GroupStatus.CANCELLED));
        assertTrue(Lifecycles.GROUP.canMove(GroupStatus.OPEN_FOR_ENROLMENT, GroupStatus.CANCELLED));
        assertTrue(Lifecycles.GROUP.canMove(GroupStatus.FULL, GroupStatus.CANCELLED));
        assertFalse(Lifecycles.GROUP.canMove(GroupStatus.ACTIVE, GroupStatus.CANCELLED));
        assertFalse(Lifecycles.GROUP.canMove(GroupStatus.CLOSING, GroupStatus.CANCELLED));
    }

    @Test
    void groupCannotSkipOrGoBack() {
        assertFalse(Lifecycles.GROUP.canMove(GroupStatus.DRAFT, GroupStatus.ACTIVE));
        assertFalse(Lifecycles.GROUP.canMove(GroupStatus.OPEN_FOR_ENROLMENT, GroupStatus.ACTIVE));
        assertFalse(Lifecycles.GROUP.canMove(GroupStatus.ACTIVE, GroupStatus.FULL));
        assertFalse(Lifecycles.GROUP.canMove(GroupStatus.CLOSED, GroupStatus.ACTIVE));
        assertTrue(Lifecycles.GROUP.isTerminal(GroupStatus.CLOSED));
        assertTrue(Lifecycles.GROUP.isTerminal(GroupStatus.CANCELLED));
        assertFalse(Lifecycles.GROUP.isTerminal(GroupStatus.ACTIVE));
    }

    @Test
    void illegalMoveIsRefusedWithACode() {
        ChitException e = assertThrows(ChitException.class,
                () -> Lifecycles.GROUP.require(GroupStatus.DRAFT, GroupStatus.CLOSED));
        assertEquals("ILLEGAL_TRANSITION", e.code());
    }

    @Test
    void ticketPath() {
        Lifecycles.TICKET.require(TicketStatus.ENROLLED, TicketStatus.ACTIVE);
        Lifecycles.TICKET.require(TicketStatus.ACTIVE, TicketStatus.PRIZED);
        Lifecycles.TICKET.require(TicketStatus.PRIZED, TicketStatus.COMPLETED);
        assertFalse(Lifecycles.TICKET.canMove(TicketStatus.ACTIVE, TicketStatus.COMPLETED)); // must win first
        assertFalse(Lifecycles.TICKET.canMove(TicketStatus.PRIZED, TicketStatus.EXITED)); // a prized ticket cannot exit
        assertFalse(Lifecycles.TICKET.canMove(TicketStatus.ENROLLED, TicketStatus.PRIZED));
    }

    @Test
    void ticketDefaultAndCure() {
        Lifecycles.TICKET.require(TicketStatus.ACTIVE, TicketStatus.DEFAULTED);
        Lifecycles.TICKET.require(TicketStatus.PRIZED, TicketStatus.DEFAULTED);
        assertEquals(TicketStatus.ACTIVE, Lifecycles.restoreAfterDefault(false));
        assertEquals(TicketStatus.PRIZED, Lifecycles.restoreAfterDefault(true));
        Lifecycles.TICKET.require(TicketStatus.DEFAULTED, Lifecycles.restoreAfterDefault(false));
        Lifecycles.TICKET.require(TicketStatus.DEFAULTED, Lifecycles.restoreAfterDefault(true));
    }

    @Test
    void ticketTransferReturnsToWhereItStarted() {
        Lifecycles.TICKET.require(TicketStatus.ACTIVE, TicketStatus.TRANSFERRED);
        Lifecycles.TICKET.require(TicketStatus.TRANSFERRED, TicketStatus.ACTIVE);
        Lifecycles.TICKET.require(TicketStatus.PRIZED, TicketStatus.TRANSFERRED);
        Lifecycles.TICKET.require(TicketStatus.TRANSFERRED, TicketStatus.PRIZED);
    }

    @Test
    void ticketEndStatesHaveNoWayOut() {
        assertTrue(Lifecycles.TICKET.isTerminal(TicketStatus.EXITED));
        assertTrue(Lifecycles.TICKET.isTerminal(TicketStatus.COMPLETED));
    }

    @Test
    void cycleFollowsTheFixedOrder() {
        CycleStatus[] order = {CycleStatus.SCHEDULED, CycleStatus.COLLECTION_OPEN, CycleStatus.AUCTION_OPEN,
                CycleStatus.AUCTION_CLOSED, CycleStatus.PAYOUT_PENDING, CycleStatus.PAYOUT_DONE, CycleStatus.SETTLED};
        for (int i = 0; i < order.length - 1; i++) {
            Lifecycles.CYCLE.require(order[i], order[i + 1]);
        }
        assertTrue(Lifecycles.CYCLE.isTerminal(CycleStatus.SETTLED));
        // no jumping ahead, no going back (except the explicit roll-over)
        assertFalse(Lifecycles.CYCLE.canMove(CycleStatus.COLLECTION_OPEN, CycleStatus.AUCTION_CLOSED));
        assertFalse(Lifecycles.CYCLE.canMove(CycleStatus.AUCTION_CLOSED, CycleStatus.AUCTION_OPEN));
        assertFalse(Lifecycles.CYCLE.canMove(CycleStatus.PAYOUT_DONE, CycleStatus.PAYOUT_PENDING));
    }

    @Test
    void rollOverSendsAnOpenAuctionBackToCollection() {
        assertTrue(Lifecycles.CYCLE.canMove(CycleStatus.AUCTION_OPEN, CycleStatus.COLLECTION_OPEN));
    }

    @Test
    void everyStatusIsCoveredByATableEntryOrIsTerminal() {
        for (GroupStatus s : GroupStatus.values()) {
            assertTrue(!Lifecycles.GROUP.next(s).isEmpty() || Lifecycles.GROUP.isTerminal(s));
        }
        for (CycleStatus s : CycleStatus.values()) {
            assertTrue(!Lifecycles.CYCLE.next(s).isEmpty() || Lifecycles.CYCLE.isTerminal(s));
        }
    }
}
