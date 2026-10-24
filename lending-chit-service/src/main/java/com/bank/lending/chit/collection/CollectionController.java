package com.bank.lending.chit.collection;

import com.bank.lending.chit.collection.CollectionDtos.DefaultCaseView;
import com.bank.lending.chit.collection.CollectionDtos.DemandView;
import com.bank.lending.chit.collection.CollectionDtos.DuesPage;
import com.bank.lending.chit.collection.CollectionDtos.GroupStatement;
import com.bank.lending.chit.collection.CollectionDtos.LateFeePreviewView;
import com.bank.lending.chit.collection.CollectionDtos.PaymentView;
import com.bank.lending.chit.collection.CollectionDtos.RecordPaymentRequest;
import com.bank.lending.chit.collection.CollectionDtos.ReversePaymentRequest;
import com.bank.lending.chit.collection.CollectionDtos.TicketStatement;
import com.bank.lending.chit.common.Actor;
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

/** Payments, demands, statements and dues. The business date always comes from lending-service, never from the caller. */
@RestController
@RequestMapping("/api/v1/chit")
@Tag(name = "Collection", description = "Record payments, reverse them, and read demands, statements and dues")
public class CollectionController {

    private final PaymentService payments;
    private final CollectionReadService reads;
    private final BusinessDateSource dates;

    public CollectionController(PaymentService payments, CollectionReadService reads, BusinessDateSource dates) {
        this.payments = payments;
        this.reads = reads;
        this.dates = dates;
    }

    @PostMapping("/groups/{code}/tickets/{ticketNo}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record money received for a ticket (chit.payment.write)",
            description = "Applied in the group's allocation order (default: late fees, oldest arrears, current instalment); anything left is held "
                    + "as advance and used by the next demands. Refused (422 PAYMENT_EXCEEDS_OBLIGATION) if it is more than the ticket can owe. "
                    + "Needs Idempotency-Key; the same key and body returns the first receipt with replayed=true.")
    public PaymentView record(@PathVariable String code, @PathVariable int ticketNo, @RequestBody RecordPaymentRequest request,
                              @RequestHeader("Idempotency-Key") String idempotencyKey,
                              @RequestHeader(value = "X-Actor", required = false) String actor) {
        return payments.record(code, ticketNo, request, idempotencyKey, Actor.orService(actor), dates.today());
    }

    @PostMapping("/groups/{code}/payments/{paymentRef}/reverse")
    @Operation(summary = "Reverse a payment, e.g. a bounced cheque (chit.payment.reverse)",
            description = "The demands it paid are reopened and the ledger entry is mirrored; nothing is deleted. Refused if its advance has already "
                    + "been set against later instalments. Needs Idempotency-Key and X-Actor.")
    public PaymentView reverse(@PathVariable String code, @PathVariable String paymentRef, @RequestBody ReversePaymentRequest request,
                               @RequestHeader("Idempotency-Key") String idempotencyKey,
                               @RequestHeader(value = "X-Actor", required = false) String actor) {
        return payments.reverse(code, paymentRef, request, idempotencyKey, Actor.required(actor), dates.today());
    }

    @GetMapping("/groups/{code}/payments")
    @Operation(summary = "Payments of the group, newest first; ticketNo narrows to one ticket (chit.payment.read)")
    public List<PaymentView> payments(@PathVariable String code, @RequestParam(value = "ticketNo", required = false) Integer ticketNo,
                                      @RequestParam(value = "limit", defaultValue = "50") int limit,
                                      @RequestParam(value = "offset", defaultValue = "0") int offset) {
        return reads.payments(code, ticketNo, limit, offset);
    }

    @GetMapping("/groups/{code}/payments/{paymentRef}")
    @Operation(summary = "One payment with where every rupee went: the receipt (chit.payment.read)")
    public PaymentView payment(@PathVariable String code, @PathVariable String paymentRef) {
        return reads.payment(code, paymentRef);
    }

    @GetMapping("/groups/{code}/demands")
    @Operation(summary = "Demands of the group, newest cycle first; filter by ticketNo and/or status OPEN|PAID (chit.demand.read)")
    public List<DemandView> demands(@PathVariable String code, @RequestParam(value = "ticketNo", required = false) Integer ticketNo,
                                    @RequestParam(value = "status", required = false) String status,
                                    @RequestParam(value = "limit", defaultValue = "100") int limit,
                                    @RequestParam(value = "offset", defaultValue = "0") int offset) {
        return reads.demands(code, ticketNo, status, dates.today(), limit, offset);
    }

    @GetMapping("/groups/{code}/tickets/{ticketNo}/statement")
    @Operation(summary = "A ticket's account: what is owed, advance held, every demand and payment (chit.statement.read)")
    public TicketStatement ticketStatement(@PathVariable String code, @PathVariable int ticketNo) {
        return reads.ticketStatement(code, ticketNo, dates.today());
    }

    @GetMapping("/groups/{code}/tickets/{ticketNo}/late-fee-preview")
    @Operation(summary = "What late fee would accrue on the ticket's open demands over the next days, if nothing more is paid (chit.statement.read)")
    public LateFeePreviewView lateFeePreview(@PathVariable String code, @PathVariable int ticketNo,
                                             @RequestParam(value = "days", defaultValue = "7") int days) {
        return reads.lateFeePreview(code, ticketNo, dates.today(), days);
    }

    @GetMapping("/groups/{code}/statement")
    @Operation(summary = "Cycle-by-cycle collection of the group: due, collected, outstanding, and tickets with money overdue (chit.statement.read)")
    public GroupStatement groupStatement(@PathVariable String code) {
        return reads.groupStatement(code, dates.today());
    }

    @GetMapping("/groups/{code}/default-cases")
    @Operation(summary = "Default cases of the group, newest first; status OPEN|CURED|RECOVERED|WRITTEN_OFF (chit.default.read)")
    public List<DefaultCaseView> defaultCases(@PathVariable String code, @RequestParam(value = "status", required = false) String status) {
        return reads.defaultCases(code, status);
    }

    @GetMapping("/dues")
    @Operation(summary = "Dues across groups: view=UPCOMING (not yet raised, due within withinDays), OVERDUE (raised, past due) or OPEN (all raised and unpaid) (chit.demand.read)")
    public DuesPage dues(@RequestParam(value = "view", defaultValue = "OVERDUE") String view,
                         @RequestParam(value = "groupCode", required = false) String groupCode,
                         @RequestParam(value = "withinDays", defaultValue = "30") int withinDays,
                         @RequestParam(value = "limit", defaultValue = "100") int limit,
                         @RequestParam(value = "offset", defaultValue = "0") int offset) {
        return reads.dues(view, groupCode, dates.today(), withinDays, limit, offset);
    }
}
