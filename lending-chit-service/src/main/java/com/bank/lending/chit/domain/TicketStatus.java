package com.bank.lending.chit.domain;

/**
 * Ticket lifecycle. See Lifecycles.TICKET.
 */
public enum TicketStatus {
    ENROLLED,
    ACTIVE,
    PRIZED,
    COMPLETED,
    DEFAULTED,
    TRANSFERRED,
    EXITED
}
