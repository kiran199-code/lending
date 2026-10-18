package com.bank.lending.lcm.utilisation;

import com.bank.lending.lcm.common.Actor;
import com.bank.lending.lcm.utilisation.UtilisationDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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

/**
 * System-to-system API used by lending-service. Every mutating call needs an Idempotency-Key header;
 * retrying with the same key and body returns the stored result with replayed=true.
 */
@RestController
@RequestMapping("/api/v1/lcm/utilisations")
@Tag(name = "Utilisation", description = "Check, reserve, confirm, release, repay and close against facility limits")
public class UtilisationController {

    private final UtilisationService service;

    public UtilisationController(UtilisationService service) {
        this.service = service;
    }

    @PostMapping("/check")
    @Operation(summary = "Would a drawdown of this size be allowed? Non-binding (lcm.utilisation.check)")
    public CheckResponse check(@Valid @RequestBody CheckRequest req) {
        return service.check(req);
    }

    @PostMapping("/reserve")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Reserve limit for a pending drawdown; enforce=false is shadow mode (lcm.utilisation.reserve)")
    public ReservationView reserve(@Valid @RequestBody ReserveRequest req,
                                   @RequestHeader("Idempotency-Key") String key,
                                   @RequestHeader(value = "X-Actor", required = false) String actor) {
        return service.reserve(req, key, Actor.orService(actor));
    }

    @GetMapping("/reservations/{ref}")
    @Operation(summary = "Read a reservation (lcm.utilisation.read)")
    public ReservationView reservation(@PathVariable("ref") String ref) {
        return service.reservation(ref);
    }

    @PostMapping("/reservations/{ref}/confirm")
    @Operation(summary = "Turn a reservation into utilisation, optionally for a smaller actual amount (lcm.utilisation.confirm)")
    public ReservationView confirm(@PathVariable("ref") String ref, @Valid @RequestBody(required = false) ConfirmRequest req,
                                   @RequestHeader("Idempotency-Key") String key,
                                   @RequestHeader(value = "X-Actor", required = false) String actor) {
        return service.confirm(ref, req == null ? null : req.amount(), key, Actor.orService(actor));
    }

    @PostMapping("/reservations/{ref}/release")
    @Operation(summary = "Give the reserved limit back, e.g. when the disbursement failed (lcm.utilisation.release)")
    public ReservationView release(@PathVariable("ref") String ref,
                                   @RequestHeader("Idempotency-Key") String key,
                                   @RequestHeader(value = "X-Actor", required = false) String actor) {
        return service.release(ref, key, Actor.orService(actor));
    }

    @PostMapping("/repay")
    @Operation(summary = "Principal repayment: restores the limit on a revolving facility (lcm.utilisation.repay)")
    public UtilisationResult repay(@Valid @RequestBody RepayRequest req, @RequestHeader("Idempotency-Key") String key,
                                   @RequestHeader(value = "X-Actor", required = false) String actor) {
        return service.repay(req, key, Actor.orService(actor));
    }

    @PostMapping("/close")
    @Operation(summary = "Release whatever is still outstanding on closure, foreclosure or write-off (lcm.utilisation.close)")
    public UtilisationResult close(@Valid @RequestBody CloseRequest req, @RequestHeader("Idempotency-Key") String key,
                                   @RequestHeader(value = "X-Actor", required = false) String actor) {
        return service.close(req, key, Actor.orService(actor));
    }

    @GetMapping("/positions")
    @Operation(summary = "Position of one drawdown (loan account) (lcm.utilisation.read)")
    public PositionView position(@RequestParam("referenceType") String referenceType, @RequestParam("referenceId") String referenceId) {
        return service.position(referenceType, referenceId);
    }
}
