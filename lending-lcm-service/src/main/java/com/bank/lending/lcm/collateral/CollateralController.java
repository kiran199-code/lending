package com.bank.lending.lcm.collateral;

import com.bank.lending.lcm.collateral.CollateralDtos.*;
import com.bank.lending.lcm.common.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/lcm")
@Tag(name = "Collateral", description = "Collateral register, valuations, allocations to facilities and drawing power")
public class CollateralController {

    private final CollateralService service;

    public CollateralController(CollateralService service) {
        this.service = service;
    }

    @GetMapping("/collateral-types")
    @Operation(summary = "Collateral types with default haircut, LTV, validity and requirements (lcm.collateral.read)")
    public List<TypeConfigView> types() {
        return service.types();
    }

    @PostMapping("/collateral")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register collateral in DRAFT (lcm.collateral.create)")
    public CollateralView create(@Valid @RequestBody CreateCollateralRequest req, @RequestHeader(value = "X-Actor", required = false) String actor) {
        return service.create(req, Actor.required(actor));
    }

    @GetMapping("/collateral")
    @Operation(summary = "Search collateral (lcm.collateral.read)")
    public List<CollateralView> search(@RequestParam(value = "customerId", required = false) Long customerId,
                                       @RequestParam(value = "status", required = false) String status) {
        return service.search(customerId, status);
    }

    @GetMapping("/collateral/{ref}")
    @Operation(summary = "Read collateral with latest valuation, lendable value and unmet activation requirements (lcm.collateral.read)")
    public CollateralView get(@PathVariable("ref") String ref) {
        return service.get(ref);
    }

    @PostMapping("/collateral/{ref}/valuations")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Propose a valuation; a different user must approve (lcm.valuation.create)")
    public ValuationView propose(@PathVariable("ref") String ref, @Valid @RequestBody ValuationRequest req,
                                 @RequestHeader(value = "X-Actor", required = false) String actor) {
        return service.proposeValuation(ref, req, Actor.required(actor));
    }

    @GetMapping("/collateral/{ref}/valuations")
    @Operation(summary = "Valuation history (lcm.collateral.read)")
    public List<ValuationView> valuations(@PathVariable("ref") String ref) {
        return service.valuations(ref);
    }

    @PostMapping("/valuations/{id}/approve")
    @Operation(summary = "Approve a valuation as checker (lcm.valuation.approve)")
    public ValuationView approve(@PathVariable("id") long id, @RequestHeader(value = "X-Actor", required = false) String actor) {
        return service.approveValuation(id, Actor.required(actor));
    }

    @PostMapping("/valuations/{id}/reject")
    @Operation(summary = "Reject a valuation as checker (lcm.valuation.approve)")
    public ValuationView reject(@PathVariable("id") long id, @RequestHeader(value = "X-Actor", required = false) String actor) {
        return service.rejectValuation(id, Actor.required(actor));
    }

    @PostMapping("/collateral/{ref}/legal-verification")
    @Operation(summary = "Record the legal verification outcome PENDING / VERIFIED / FAILED (lcm.collateral.legal)")
    public CollateralView legal(@PathVariable("ref") String ref, @Valid @RequestBody LegalRequest req,
                                @RequestHeader(value = "X-Actor", required = false) String actor) {
        return service.setLegalStatus(ref, req.status(), Actor.required(actor));
    }

    @PostMapping("/collateral/{ref}/charge")
    @Operation(summary = "Record charge registration and any prior charge (lcm.collateral.charge)")
    public CollateralView charge(@PathVariable("ref") String ref, @Valid @RequestBody ChargeRequest req,
                                 @RequestHeader(value = "X-Actor", required = false) String actor) {
        return service.registerCharge(ref, req, Actor.required(actor));
    }

    @PostMapping("/collateral/{ref}/insurance")
    @Operation(summary = "Record insurance expiry (lcm.collateral.insurance)")
    public CollateralView insurance(@PathVariable("ref") String ref, @Valid @RequestBody InsuranceRequest req,
                                    @RequestHeader(value = "X-Actor", required = false) String actor) {
        return service.setInsurance(ref, req, Actor.required(actor));
    }

    @PostMapping("/collateral/{ref}/activate")
    @Operation(summary = "Activate once legal, valuation, charge and insurance requirements are met (lcm.collateral.activate)")
    public CollateralView activate(@PathVariable("ref") String ref, @RequestHeader(value = "X-Actor", required = false) String actor) {
        return service.activate(ref, Actor.required(actor));
    }

    @PostMapping("/collateral/{ref}/release")
    @Operation(summary = "Release collateral that has no active allocations (lcm.collateral.release)")
    public CollateralView release(@PathVariable("ref") String ref, @RequestHeader(value = "X-Actor", required = false) String actor) {
        return service.release(ref, Actor.required(actor));
    }

    @PostMapping("/collateral/{ref}/allocations")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Allocate a share of the collateral to a facility (lcm.allocation.manage)")
    public AllocationView allocate(@PathVariable("ref") String ref, @Valid @RequestBody AllocationRequest req,
                                   @RequestHeader(value = "X-Actor", required = false) String actor) {
        return service.allocate(ref, req, Actor.required(actor));
    }

    @GetMapping("/collateral/{ref}/allocations")
    @Operation(summary = "Allocations of a collateral (lcm.collateral.read)")
    public List<AllocationView> allocations(@PathVariable("ref") String ref) {
        return service.allocations(ref);
    }

    @PostMapping("/allocations/{id}/release")
    @Operation(summary = "Release an allocation (lcm.allocation.manage)")
    public AllocationView releaseAllocation(@PathVariable("id") long id, @RequestHeader(value = "X-Actor", required = false) String actor) {
        return service.releaseAllocation(id, Actor.required(actor));
    }

    @GetMapping("/facilities/{ref}/drawing-power")
    @Operation(summary = "Drawing power of a facility with the contribution of each allocated collateral (lcm.facility.read)")
    public DrawingPowerView drawingPower(@PathVariable("ref") String ref,
                                         @RequestParam(value = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return service.drawingPowerView(ref, asOf);
    }
}
