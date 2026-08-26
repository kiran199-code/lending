package com.bank.lending.ledger.service;

import com.bank.lending.ledger.domain.LedgerOutboxEntry;

/**
 * Port to the core banking ledger/GL. Implement this against the real integration
 * (message queue, REST client, whatever the core banking platform exposes) and
 * replace the LoggingLedgerGatewayClient stub bean before going live.
 */
public interface LedgerGatewayClient {

    /** @return true if the entry was accepted by the ledger, false if it should be retried later. */
    boolean post(LedgerOutboxEntry entry);
}
