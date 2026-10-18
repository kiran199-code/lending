package com.bank.lending.lcm.facility;

import com.bank.lending.lcm.common.Actor;
import com.bank.lending.lcm.facility.FacilityDtos.*;
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
@RequestMapping("/api/v1/lcm/facilities")
@Tag(name = "Facilities", description = "Credit facilities (limits), hierarchy, maker-checker changes and temporary excess")
public class FacilityController {

    private final FacilityService service;

    public FacilityController(FacilityService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a facility in DRAFT (permission lcm.facility.create)")
    public FacilityView create(@Valid @RequestBody CreateFacilityRequest req, @RequestHeader(value = "X-Actor", required = false) String actor) {
        return service.create(req, Actor.required(actor));
    }

    @GetMapping
    @Operation(summary = "Search facilities (lcm.facility.read)")
    public List<FacilityView> search(@RequestParam(value = "customerId", required = false) Long customerId,
                                     @RequestParam(value = "status", required = false) String status,
                                     @RequestParam(value = "parentRef", required = false) String parentRef) {
        return service.search(customerId, status, parentRef);
    }

    @GetMapping("/changes")
    @Operation(summary = "List maker-checker changes, e.g. status=PENDING (lcm.facility.read)")
    public List<ChangeView> changes(@RequestParam(value = "status", required = false) String status,
                                    @RequestParam(value = "facilityRef", required = false) String facilityRef) {
        return service.changes(status, facilityRef);
    }

    @PostMapping("/changes/{id}/approve")
    @Operation(summary = "Approve a change as checker (lcm.facility.approve)")
    public ChangeView approve(@PathVariable("id") long id, @RequestBody(required = false) DecisionRequest req,
                              @RequestHeader(value = "X-Actor", required = false) String actor) {
        return service.approve(id, Actor.required(actor), req == null ? null : req.note());
    }

    @PostMapping("/changes/{id}/reject")
    @Operation(summary = "Reject a change as checker (lcm.facility.approve)")
    public ChangeView reject(@PathVariable("id") long id, @RequestBody(required = false) DecisionRequest req,
                             @RequestHeader(value = "X-Actor", required = false) String actor) {
        return service.reject(id, Actor.required(actor), req == null ? null : req.note());
    }

    @GetMapping("/excess-requests")
    @Operation(summary = "List temporary excess requests of all facilities, e.g. status=PENDING (lcm.facility.read)")
    public List<ExcessView> excessRequests(@RequestParam(value = "status", required = false) String status) {
        return service.excessByStatus(status);
    }

    @PostMapping("/excess/{id}/approve")
    @Operation(summary = "Approve a temporary excess as checker (lcm.excess.approve)")
    public ExcessView approveExcess(@PathVariable("id") long id, @RequestHeader(value = "X-Actor", required = false) String actor) {
        return service.approveExcess(id, Actor.required(actor));
    }

    @PostMapping("/excess/{id}/reject")
    @Operation(summary = "Reject a temporary excess as checker (lcm.excess.approve)")
    public ExcessView rejectExcess(@PathVariable("id") long id, @RequestHeader(value = "X-Actor", required = false) String actor) {
        return service.rejectExcess(id, Actor.required(actor));
    }

    @GetMapping("/{ref}")
    @Operation(summary = "Read one facility (lcm.facility.read)")
    public FacilityView get(@PathVariable("ref") String ref) {
        return service.get(ref);
    }

    @GetMapping("/{ref}/availability")
    @Operation(summary = "Limit, utilisation and availability including the whole parent chain (lcm.facility.read)")
    public AvailabilityView availability(@PathVariable("ref") String ref,
                                         @RequestParam(value = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return service.availability(ref, asOf);
    }

    @GetMapping("/{ref}/tree")
    @Operation(summary = "Facility with all sub-limits and their availability (lcm.facility.read)")
    public TreeNode tree(@PathVariable("ref") String ref,
                         @RequestParam(value = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return service.tree(ref, asOf);
    }

    @GetMapping("/{ref}/transactions")
    @Operation(summary = "Ledger of limit movements, newest first (lcm.facility.read)")
    public List<TxnView> transactions(@PathVariable("ref") String ref,
                                      @RequestParam(value = "beforeId", required = false) Long beforeId,
                                      @RequestParam(value = "limit", defaultValue = "100") int limit) {
        return service.transactions(ref, beforeId, limit);
    }

    @PostMapping("/{ref}/changes")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Propose ACTIVATE / AMEND_LIMIT / EXTEND_EXPIRY / SUSPEND / REINSTATE / FREEZE / CLOSE (lcm.facility.change)")
    public ChangeView propose(@PathVariable("ref") String ref, @Valid @RequestBody ProposeChangeRequest req,
                              @RequestHeader(value = "X-Actor", required = false) String actor) {
        return service.propose(ref, req, Actor.required(actor));
    }

    @PostMapping("/{ref}/excess")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Propose a temporary excess over the sanctioned limit (lcm.excess.create)")
    public ExcessView proposeExcess(@PathVariable("ref") String ref, @Valid @RequestBody ExcessRequest req,
                                    @RequestHeader(value = "X-Actor", required = false) String actor) {
        return service.proposeExcess(ref, req, Actor.required(actor));
    }

    @GetMapping("/{ref}/excess")
    @Operation(summary = "Temporary excess history of a facility (lcm.facility.read)")
    public List<ExcessView> excess(@PathVariable("ref") String ref) {
        return service.excess(ref);
    }
}
