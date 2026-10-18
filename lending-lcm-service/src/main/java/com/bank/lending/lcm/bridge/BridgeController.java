package com.bank.lending.lcm.bridge;

import com.bank.lending.lcm.bridge.BridgeDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/lcm/bridge/lending")
@Tag(name = "Lending bridge", description = "One-off, idempotent load of existing lending mortgages, sanctions and loan balances")
public class BridgeController {

    private final BridgeService service;

    public BridgeController(BridgeService service) {
        this.service = service;
    }

    @PostMapping("/collateral")
    @Operation(summary = "Import lending mortgages as collateral with their valuations (lcm.bridge.run)")
    public BridgeResult collateral(@Valid @RequestBody MortgageImport req) {
        return service.importMortgages(req);
    }

    @PostMapping("/facilities")
    @Operation(summary = "Create an implicit ACTIVE facility SANC-<sanctionRefId> per lending sanction (lcm.bridge.run)")
    public BridgeResult facilities(@Valid @RequestBody SanctionImport req) {
        return service.importSanctions(req);
    }

    @PostMapping("/positions")
    @Operation(summary = "Load opening utilisation of existing loan accounts, without limit checks (lcm.bridge.run)")
    public BridgeResult positions(@Valid @RequestBody PositionImport req) {
        return service.importPositions(req);
    }
}
