package com.bank.lending.chit.ledger;

import com.bank.lending.chit.common.ChitException;

/** An account of the current group: its type, and the ticket id for the per-ticket types. */
public record AccountRef(AccountType type, Long ticketId) {

    public AccountRef {
        if (type == null) {
            throw new ChitException("ACCOUNT_INVALID", "Account type is required");
        }
        if (type.perTicket() && ticketId == null) {
            throw new ChitException("ACCOUNT_INVALID", type + " is a per-ticket account and needs a ticket id");
        }
        if (!type.perTicket() && ticketId != null) {
            throw new ChitException("ACCOUNT_INVALID", type + " is a group-level account and takes no ticket id");
        }
    }

    public static AccountRef of(AccountType type) {
        return new AccountRef(type, null);
    }

    public static AccountRef of(AccountType type, long ticketId) {
        return new AccountRef(type, ticketId);
    }
}
