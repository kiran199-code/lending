package com.bank.lending.ledger.service;

import com.bank.lending.ledger.domain.LedgerOutboxEntry;
import com.bank.lending.ledger.repository.LedgerOutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Outbox writer + poller. recordEvent must be called from within the same transaction
 * as the business change it describes (disbursement, EMI posting, etc.) so the outbox
 * row commits atomically with that change. pushPendingEntries is the poller half —
 * wire it to a scheduler to actually drain PENDING rows to the ledger.
 */
@Service
@RequiredArgsConstructor
public class LedgerOutboxService {

    private static final int MAX_RETRY = 5;

    private final LedgerOutboxRepository ledgerOutboxRepository;
    private final LedgerGatewayClient ledgerGatewayClient;
    private final ObjectMapper objectMapper;

    @Transactional
    public LedgerOutboxEntry recordEvent(Long accountId, LedgerOutboxEntry.EventType eventType, Map<String, ?> payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize ledger outbox payload for account " + accountId, e);
        }

        LedgerOutboxEntry entry = LedgerOutboxEntry.builder()
                .accountId(accountId)
                .eventType(eventType)
                .payload(json)
                .postingStatus(LedgerOutboxEntry.PostingStatus.PENDING)
                .retryCount(0)
                .build();
        return ledgerOutboxRepository.save(entry);
    }

    @Transactional
    public void pushPendingEntries(int batchSize) {
        List<LedgerOutboxEntry> pending = ledgerOutboxRepository.findPendingOrderByCreatedAt(PageRequest.of(0, batchSize));
        for (LedgerOutboxEntry entry : pending) {
            entry.setLastAttemptedAt(OffsetDateTime.now());
            boolean posted;
            try {
                posted = ledgerGatewayClient.post(entry);
            } catch (RuntimeException e) {
                posted = false;
            }
            if (posted) {
                entry.setPostingStatus(LedgerOutboxEntry.PostingStatus.POSTED);
            } else {
                entry.setRetryCount(entry.getRetryCount() + 1);
                if (entry.getRetryCount() >= MAX_RETRY) {
                    entry.setPostingStatus(LedgerOutboxEntry.PostingStatus.FAILED);
                }
            }
            ledgerOutboxRepository.save(entry);
        }
    }

    /** Read accessor for the REST layer — PENDING entries oldest-first, without pushing them. */
    @Transactional(readOnly = true)
    public List<LedgerOutboxEntry> getPending(int batchSize) {
        return ledgerOutboxRepository.findPendingOrderByCreatedAt(PageRequest.of(0, batchSize));
    }
}
