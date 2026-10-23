package com.bank.lending.chit.domain.state;

import com.bank.lending.chit.domain.CycleStatus;
import com.bank.lending.chit.domain.GroupStatus;
import com.bank.lending.chit.domain.TicketStatus;

/**
 * The three state machines. These tables are the single place that decides what may follow what.
 *
 * <p>Assumptions to confirm (also in BUILD_STATUS.md):
 * <ul>
 *   <li>GROUP: a FULL group may go back to OPEN_FOR_ENROLMENT when a ticket is withdrawn before activation.
 *       Early termination of an ACTIVE group goes through CLOSING. CANCELLED is only reachable before ACTIVE.</li>
 *   <li>TICKET: TRANSFERRED is a transient state while a transfer awaits its maker-checker approval; the ticket then
 *       returns to the state it left (ACTIVE or PRIZED) with a new subscriber link. A DEFAULTED ticket returns to
 *       ACTIVE if it had not won and to PRIZED if it had; the transition table allows both and
 *       {@link #restoreAfterDefault(boolean)} gives the right one. EXITED and COMPLETED are final.</li>
 *   <li>CYCLE: ROLL_OVER_CYCLE sends an AUCTION_OPEN cycle back to COLLECTION_OPEN (calendar extended by the service).</li>
 * </ul>
 */
public final class Lifecycles {

    public static final StateMachine<GroupStatus> GROUP = StateMachine.of("Chit group", GroupStatus.class)
            .from(GroupStatus.DRAFT, GroupStatus.OPEN_FOR_ENROLMENT, GroupStatus.CANCELLED)
            .from(GroupStatus.OPEN_FOR_ENROLMENT, GroupStatus.FULL, GroupStatus.CANCELLED)
            .from(GroupStatus.FULL, GroupStatus.ACTIVE, GroupStatus.OPEN_FOR_ENROLMENT, GroupStatus.CANCELLED)
            .from(GroupStatus.ACTIVE, GroupStatus.CLOSING)
            .from(GroupStatus.CLOSING, GroupStatus.CLOSED)
            .build();

    public static final StateMachine<TicketStatus> TICKET = StateMachine.of("Ticket", TicketStatus.class)
            .from(TicketStatus.ENROLLED, TicketStatus.ACTIVE, TicketStatus.EXITED)
            .from(TicketStatus.ACTIVE, TicketStatus.PRIZED, TicketStatus.DEFAULTED, TicketStatus.TRANSFERRED, TicketStatus.EXITED)
            .from(TicketStatus.PRIZED, TicketStatus.COMPLETED, TicketStatus.DEFAULTED, TicketStatus.TRANSFERRED)
            .from(TicketStatus.DEFAULTED, TicketStatus.ACTIVE, TicketStatus.PRIZED, TicketStatus.EXITED)
            .from(TicketStatus.TRANSFERRED, TicketStatus.ACTIVE, TicketStatus.PRIZED)
            .build();

    public static final StateMachine<CycleStatus> CYCLE = StateMachine.of("Cycle", CycleStatus.class)
            .from(CycleStatus.SCHEDULED, CycleStatus.COLLECTION_OPEN)
            .from(CycleStatus.COLLECTION_OPEN, CycleStatus.AUCTION_OPEN)
            .from(CycleStatus.AUCTION_OPEN, CycleStatus.AUCTION_CLOSED, CycleStatus.COLLECTION_OPEN)
            .from(CycleStatus.AUCTION_CLOSED, CycleStatus.PAYOUT_PENDING)
            .from(CycleStatus.PAYOUT_PENDING, CycleStatus.PAYOUT_DONE)
            .from(CycleStatus.PAYOUT_DONE, CycleStatus.SETTLED)
            .build();

    private Lifecycles() {
    }

    /** Where a cured defaulter goes back to: PRIZED if the ticket had won a prize, otherwise ACTIVE. */
    public static TicketStatus restoreAfterDefault(boolean hadWonPrize) {
        return hadWonPrize ? TicketStatus.PRIZED : TicketStatus.ACTIVE;
    }
}
