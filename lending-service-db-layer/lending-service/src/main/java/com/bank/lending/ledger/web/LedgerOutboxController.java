package com.bank.lending.ledger.web;

import com.bank.lending.common.dto.BatchOperationResponse;
import com.bank.lending.ledger.dto.LedgerOutboxEntryResponse;
import com.bank.lending.ledger.service.LedgerOutboxService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/ledger-outbox")
@RequiredArgsConstructor
@Tag(name = "Ledger Outbox", description = "Poller for pushing pending ledger entries to core banking (currently a logging stub)")
public class LedgerOutboxController {

    private final LedgerOutboxService ledgerOutboxService;

    @Operation(summary = "Batch: push up to batchSize PENDING outbox entries to the ledger gateway")
    @PostMapping("/push")
    public BatchOperationResponse push(@RequestParam(defaultValue = "50") int batchSize) {
        ledgerOutboxService.pushPendingEntries(batchSize);
        return BatchOperationResponse.completed("Pushed up to " + batchSize + " pending ledger outbox entries");
    }

    @Operation(summary = "List PENDING outbox entries, oldest first")
    @GetMapping("/pending")
    public List<LedgerOutboxEntryResponse> pending(@RequestParam(defaultValue = "50") int batchSize) {
        return ledgerOutboxService.getPending(batchSize).stream()
                .map(LedgerOutboxEntryResponse::from)
                .toList();
    }
}
