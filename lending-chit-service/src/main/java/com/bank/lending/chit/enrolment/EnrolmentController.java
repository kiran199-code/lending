package com.bank.lending.chit.enrolment;

import com.bank.lending.chit.common.Actor;
import com.bank.lending.chit.enrolment.EnrolmentDtos.EnrolRequest;
import com.bank.lending.chit.enrolment.EnrolmentDtos.TicketView;
import com.bank.lending.chit.enrolment.EnrolmentDtos.WithdrawRequest;
import com.bank.lending.chit.enrolment.EnrolmentDtos.WithdrawResult;
import com.bank.lending.chit.platform.BusinessDateSource;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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

import java.util.List;

/** Tickets of a group: enrol, list, withdraw before activation, verify security. */
@RestController
@RequestMapping("/api/v1/chit/groups/{code}/tickets")
@Tag(name = "Enrolment", description = "Enrol tickets, collect fees and security, withdraw, verify")
public class EnrolmentController {

    private final EnrolmentService service;
    private final BusinessDateSource dates;

    public EnrolmentController(EnrolmentService service, BusinessDateSource dates) {
        this.service = service;
        this.dates = dates;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Enrol one ticket and record the fees and security received (chit.enrolment.write)",
            description = "The next free number is assigned. A customer may enrol several times for several tickets. Amounts must equal "
                    + "GET /groups/{code}/enrolment-quote exactly. Fees and deposit are posted to the ledger. Needs Idempotency-Key; "
                    + "X-Actor defaults to the service name.")
    public TicketView enrol(@PathVariable String code, @RequestBody EnrolRequest request,
                            @RequestHeader("Idempotency-Key") String idempotencyKey,
                            @RequestHeader(value = "X-Actor", required = false) String actor) {
        return service.enrol(code, request, idempotencyKey, Actor.orService(actor), dates.today());
    }

    @GetMapping
    @Operation(summary = "Tickets of the group in number order; includeExited=true adds withdrawn ones (chit.enrolment.read)")
    public List<TicketView> list(@PathVariable String code,
                                 @RequestParam(value = "includeExited", defaultValue = "false") boolean includeExited) {
        return service.tickets(code, includeExited);
    }

    @GetMapping("/{ticketNo}")
    @Operation(summary = "One live ticket by number (chit.enrolment.read)")
    public TicketView get(@PathVariable String code, @PathVariable int ticketNo) {
        return service.ticket(code, ticketNo);
    }

    @PostMapping("/{ticketNo}/withdraw")
    @Operation(summary = "Withdraw a ticket before the group starts; the deposit is returned and the number is freed (chit.enrolment.write)",
            description = "Fees are kept unless chit.enrolment.withdrawal-refunds-fees is on. A FULL group goes back to OPEN_FOR_ENROLMENT. "
                    + "Needs Idempotency-Key.")
    public WithdrawResult withdraw(@PathVariable String code, @PathVariable int ticketNo,
                                   @RequestBody(required = false) WithdrawRequest request,
                                   @RequestHeader("Idempotency-Key") String idempotencyKey,
                                   @RequestHeader(value = "X-Actor", required = false) String actor) {
        return service.withdraw(code, ticketNo, request, idempotencyKey, Actor.orService(actor), dates.today());
    }

    @PostMapping("/{ticketNo}/security/verify")
    @Operation(summary = "Verify the security recorded for a ticket; a different person from whoever recorded it (chit.enrolment.verify)",
            description = "Needs Idempotency-Key and X-Actor. 403 MAKER_CHECKER_VIOLATION if the same actor recorded it and the check is on.")
    public TicketView verifySecurity(@PathVariable String code, @PathVariable int ticketNo,
                                     @RequestHeader("Idempotency-Key") String idempotencyKey,
                                     @RequestHeader(value = "X-Actor", required = false) String actor) {
        return service.verifySecurity(code, ticketNo, idempotencyKey, Actor.required(actor));
    }
}
