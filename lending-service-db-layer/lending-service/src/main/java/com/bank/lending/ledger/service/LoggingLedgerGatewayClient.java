package com.bank.lending.ledger.service;

import com.bank.lending.ledger.domain.LedgerOutboxEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Placeholder LedgerGatewayClient — logs and reports failure so entries stay PENDING
 * and get retried rather than being silently dropped. Replace with a real adapter
 * to the core banking ledger before this goes anywhere near production traffic.
 */
@Slf4j
@Component
public class LoggingLedgerGatewayClient implements LedgerGatewayClient {

    @Override
    public boolean post(LedgerOutboxEntry entry) {
        log.warn("No core banking ledger integration configured — outbox entry {} (account={}, type={}) was not posted",
                entry.getOutboxId(), entry.getAccountId(), entry.getEventType());
        return false;
    }
}
