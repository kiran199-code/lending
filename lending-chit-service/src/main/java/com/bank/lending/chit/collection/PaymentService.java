package com.bank.lending.chit.collection;

import com.bank.lending.chit.calc.AllocationEngine;
import com.bank.lending.chit.calc.AllocationEngine.DemandDue;
import com.bank.lending.chit.calc.AllocationEngine.Line;
import com.bank.lending.chit.calc.DemandFigures;
import com.bank.lending.chit.calc.ObligationLimit;
import com.bank.lending.chit.collection.CollectionDtos.PaymentView;
import com.bank.lending.chit.collection.CollectionDtos.RecordPaymentRequest;
import com.bank.lending.chit.collection.CollectionDtos.ReversePaymentRequest;
import com.bank.lending.chit.collection.CollectionModels.AdvanceRow;
import com.bank.lending.chit.collection.CollectionModels.AllocationRow;
import com.bank.lending.chit.collection.CollectionModels.DemandRow;
import com.bank.lending.chit.collection.CollectionModels.PaymentRow;
import com.bank.lending.chit.common.Audit;
import com.bank.lending.chit.common.ChitException;
import com.bank.lending.chit.common.Hashing;
import com.bank.lending.chit.common.Json;
import com.bank.lending.chit.common.Money;
import com.bank.lending.chit.domain.AllocationBucket;
import com.bank.lending.chit.domain.GroupStatus;
import com.bank.lending.chit.domain.TicketStatus;
import com.bank.lending.chit.domain.rules.ChitRules;
import com.bank.lending.chit.domain.state.Lifecycles;
import com.bank.lending.chit.enrolment.EnrolmentModels.TicketRow;
import com.bank.lending.chit.enrolment.EnrolmentRepository;
import com.bank.lending.chit.group.GroupModels.GroupRow;
import com.bank.lending.chit.group.GroupService;
import com.bank.lending.chit.idempotency.IdempotencyCheck.Replay;
import com.bank.lending.chit.idempotency.IdempotencyStore;
import com.bank.lending.chit.ledger.JournalTemplates;
import com.bank.lending.chit.ledger.LedgerDtos.PostingContext;
import com.bank.lending.chit.ledger.LedgerRepository;
import com.bank.lending.chit.ledger.LedgerService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Records money received for a ticket and, if it bounces, takes it back.
 *
 * <p>A payment is applied by {@link AllocationEngine} in the group's configured order (default: late fees, oldest
 * arrears, the current instalment), and whatever is left becomes advance, used automatically by the next demands.
 * Everything happens in one transaction under the group lock: the payment, its allocation rows, the demand figures,
 * the journal and the events. A reversal undoes the same rows and posts the mirror journal; nothing is deleted.
 */
@Service
public class PaymentService {

    private static final String OP_PAYMENT = "PAYMENT";
    private static final String OP_REVERSE = "PAYMENT_REVERSE";
    private static final Set<String> CHANNELS = Set.of("UPI", "BANK_TRANSFER", "CASH_AT_FOREMAN", "AUTO_DEBIT");
    private static final Set<TicketStatus> PAYABLE = EnumSet.of(TicketStatus.ACTIVE, TicketStatus.PRIZED, TicketStatus.DEFAULTED, TicketStatus.TRANSFERRED);

    private final GroupService groupService;
    private final CollectionRepository repo;
    private final EnrolmentRepository tickets;
    private final LedgerService ledger;
    private final LedgerRepository ledgerRepo;
    private final IdempotencyStore idempotency;
    private final Audit audit;
    private final CollectionReadService reads;

    public PaymentService(GroupService groupService, CollectionRepository repo, EnrolmentRepository tickets, LedgerService ledger,
                          LedgerRepository ledgerRepo, IdempotencyStore idempotency, Audit audit, CollectionReadService reads) {
        this.groupService = groupService;
        this.repo = repo;
        this.tickets = tickets;
        this.ledger = ledger;
        this.ledgerRepo = ledgerRepo;
        this.idempotency = idempotency;
        this.audit = audit;
        this.reads = reads;
    }

    // ---- record ---------------------------------------------------------------------------------------------------

    @Transactional
    public PaymentView record(String code, int ticketNo, RecordPaymentRequest req, String idemKey, String actor, LocalDate today) {
        if (req == null || req.amount() == null) {
            throw ChitException.badRequest("VALIDATION_FAILED", "amount is required");
        }
        BigDecimal amount = cleanAmount(req.amount());
        String channel = req.channel() == null ? "" : req.channel().trim().toUpperCase();
        if (!CHANNELS.contains(channel)) {
            throw ChitException.badRequest("VALIDATION_FAILED", "channel must be one of " + new java.util.TreeSet<>(CHANNELS));
        }
        String externalRef = req.externalRef() == null || req.externalRef().isBlank() ? null : req.externalRef().trim();
        if (externalRef != null && externalRef.length() > 60) {
            throw ChitException.badRequest("VALIDATION_FAILED", "externalRef is longer than 60 characters");
        }
        Replay replay = idempotency.begin(idemKey, OP_PAYMENT,
                Hashing.sha256(code + "|" + ticketNo + "|" + amount.toPlainString() + "|" + channel + "|" + (externalRef == null ? "" : externalRef)));
        if (replay != null) {
            return reads.payment(code, replay.resultRef()).asReplay();
        }
        GroupRow g = groupService.lock(code);
        if (g.status() != GroupStatus.ACTIVE && g.status() != GroupStatus.CLOSING) {
            throw ChitException.conflict("GROUP_NOT_ACTIVE", "Group " + code + " is " + g.status() + "; payments are accepted while it is ACTIVE or CLOSING");
        }
        ChitRules rules = groupService.rulesOf(g);
        TicketRow t = tickets.findLive(g.id(), ticketNo).orElseThrow(() -> ChitException.notFound("Ticket", code + " #" + ticketNo));
        if (!PAYABLE.contains(t.status())) {
            throw ChitException.conflict("TICKET_NOT_PAYABLE", "Ticket " + ticketNo + " is " + t.status() + " and cannot receive payments");
        }

        List<DemandRow> open = repo.openDemandsOfTicket(t.id());
        BigDecimal owed = open.stream().map(d -> d.figures().totalOutstanding()).reduce(Money.ZERO, BigDecimal::add);
        BigDecimal advanceHeld = repo.openAdvance(t.id()).stream().map(AdvanceRow::available).reduce(Money.ZERO, BigDecimal::add);
        BigDecimal limit = ObligationLimit.maxAcceptable(owed, repo.countScheduledCycles(g.id()), g.subscription(), advanceHeld);
        if (amount.compareTo(limit) > 0) {
            throw new ChitException("PAYMENT_EXCEEDS_OBLIGATION", "Ticket " + ticketNo + " can take at most " + limit.toPlainString()
                    + " now (owed " + owed.toPlainString() + " plus the instalments still to fall due, less advance already held); "
                    + amount.toPlainString() + " was offered");
        }

        List<DemandDue> dues = open.stream().map(d -> new DemandDue(d.id(), d.cycleNo(), d.figures().subscriptionOutstanding(),
                d.figures().lateFeeOutstanding())).toList();
        AllocationEngine.Result alloc = AllocationEngine.allocate(amount, repo.latestStartedCycleNo(g.id()), dues, rules.allocationOrder());

        long id = repo.nextPaymentId();
        String ref = "RCPT-" + id;
        repo.insertPayment(id, ref, g.id(), t.id(), amount, channel, today, idemKey, externalRef, actor);

        Map<Long, DemandFigures> changed = new LinkedHashMap<>();
        Map<Long, DemandRow> rows = new LinkedHashMap<>();
        open.forEach(d -> rows.put(d.id(), d));
        BigDecimal feesPaid = Money.ZERO;
        BigDecimal subscriptionPaid = Money.ZERO;
        for (Line line : alloc.lines()) {
            repo.insertAllocation(id, line.demandId(), line.bucket(), line.amount(), false);
            if (line.demandId() == null) {
                continue;
            }
            DemandFigures f = changed.getOrDefault(line.demandId(), rows.get(line.demandId()).figures());
            if (line.bucket() == AllocationBucket.LATE_FEES) {
                f = f.payLateFee(line.amount());
                feesPaid = feesPaid.add(line.amount());
            } else {
                f = f.paySubscription(line.amount());
                subscriptionPaid = subscriptionPaid.add(line.amount());
            }
            changed.put(line.demandId(), f);
        }
        changed.forEach(repo::saveFigures);

        ledger.post(JournalTemplates.payment("PAYMENT:" + id, t.id(), amount),
                new PostingContext(g.id(), today, null, "PAYMENT", ref, actor));

        boolean cured = cureIfClear(g, t, today, actor);

        audit.log("PAYMENT", ref, "RECEIVED", actor, amount.toPlainString() + " via " + channel + " for " + code + " #" + ticketNo);
        audit.event("PaymentReceived", "TICKET", code + "/" + ticketNo,
                Json.obj("groupCode", code, "ticketNo", ticketNo, "customerId", t.customerId(), "paymentRef", ref, "amount", amount,
                        "channel", channel, "businessDate", today.toString(), "lateFeesPaid", feesPaid, "subscriptionPaid", subscriptionPaid,
                        "advance", alloc.advance(), "externalRef", externalRef, "defaultCured", cured ? Boolean.TRUE : null, "actor", actor));
        idempotency.complete(idemKey, ref);
        return reads.payment(code, ref);
    }

    // ---- reverse --------------------------------------------------------------------------------------------------

    @Transactional
    public PaymentView reverse(String code, String paymentRef, ReversePaymentRequest req, String idemKey, String actor, LocalDate today) {
        String reason = req == null || req.reason() == null ? "" : req.reason().trim();
        if (reason.isEmpty() || reason.length() > 120) {
            throw ChitException.badRequest("VALIDATION_FAILED", "reason is required and may be at most 120 characters (e.g. cheque bounced)");
        }
        Replay replay = idempotency.begin(idemKey, OP_REVERSE, Hashing.sha256(code + "|" + paymentRef + "|" + reason));
        if (replay != null) {
            return reads.payment(code, replay.resultRef()).asReplay();
        }
        GroupRow g = groupService.lock(code);
        PaymentRow p = repo.findPayment(g.id(), paymentRef).orElseThrow(() -> ChitException.notFound("Payment", paymentRef));
        if ("REVERSED".equals(p.status())) {
            throw ChitException.conflict("PAYMENT_ALREADY_REVERSED", "Payment " + paymentRef + " was already reversed");
        }
        List<AllocationRow> rows = repo.allocationsOf(p.id());
        for (AllocationRow a : rows) {
            if (!a.reversed() && a.bucket() == AllocationBucket.ADVANCE && a.advanceUsed().signum() > 0) {
                throw new ChitException("PAYMENT_ADVANCE_CONSUMED", "Payment " + paymentRef + " left an advance of " + a.amount().toPlainString()
                        + " of which " + a.advanceUsed().toPlainString() + " has already been set against later instalments; "
                        + "it can no longer be reversed as a whole. Take the matter to the foreman.");
            }
        }
        TicketRow t = tickets.findById(p.ticketId()).orElseThrow(() -> ChitException.notFound("Ticket", String.valueOf(p.ticketId())));
        Map<Long, DemandFigures> changed = new LinkedHashMap<>();
        for (AllocationRow a : rows) {
            if (a.reversed() || a.demandId() == null) {
                continue;
            }
            DemandFigures f = changed.get(a.demandId());
            if (f == null) {
                f = repo.findDemand(a.demandId()).orElseThrow(() -> ChitException.notFound("Demand", String.valueOf(a.demandId()))).figures();
            }
            f = a.bucket() == AllocationBucket.LATE_FEES ? f.payLateFee(a.amount().negate()) : f.paySubscription(a.amount().negate());
            changed.put(a.demandId(), f);
        }
        changed.forEach(repo::saveFigures);
        repo.markAllocationsReversed(p.id());
        repo.markPaymentReversed(p.id(), reason, actor);

        long journalId = ledgerRepo.findByPostingKey("PAYMENT:" + p.id())
                .orElseThrow(() -> new ChitException("DATA_CONFLICT", "The journal of payment " + paymentRef + " is missing")).id();
        ledger.reverse(journalId, "Payment reversed: " + reason, new PostingContext(g.id(), today, null, "PAYMENT_REVERSAL", paymentRef, actor));

        audit.log("PAYMENT", paymentRef, "REVERSED", actor, reason);
        audit.event("PaymentReversed", "TICKET", code + "/" + t.ticketNo(),
                Json.obj("groupCode", code, "ticketNo", t.ticketNo(), "customerId", t.customerId(), "paymentRef", paymentRef,
                        "amount", p.amount(), "reason", reason, "businessDate", today.toString(), "actor", actor));
        idempotency.complete(idemKey, paymentRef);
        return reads.payment(code, paymentRef);
    }

    // ---- default cure ---------------------------------------------------------------------------------------------

    /**
     * A defaulted ticket is cured when nothing overdue is left: no raised demand that is past its due date still owes
     * subscription or late fee. It goes back to ACTIVE, or PRIZED if it had won. A case where the foreman already
     * covered a shortfall (Phase 6) is not cured here.
     */
    private boolean cureIfClear(GroupRow g, TicketRow t, LocalDate today, String actor) {
        if (t.status() != TicketStatus.DEFAULTED) {
            return false;
        }
        boolean overdueLeft = repo.openDemandsOfTicket(t.id()).stream().anyMatch(d -> d.dueDate().isBefore(today));
        if (overdueLeft) {
            return false;
        }
        var open = repo.openCase(t.id());
        if (open.isPresent() && open.get().foremanCovered().signum() > 0) {
            return false;
        }
        TicketStatus back = Lifecycles.restoreAfterDefault(repo.hasWonPrize(t.id()));
        Lifecycles.TICKET.require(TicketStatus.DEFAULTED, back);
        tickets.setTicketStatus(t.id(), back, actor);
        open.ifPresent(c -> repo.closeCase(c.id(), "CURED", today));
        audit.log("TICKET", g.groupCode() + "#" + t.ticketNo(), "DEFAULT_CURED", actor, "overdue cleared, ticket is " + back);
        audit.event("TicketCured", "TICKET", g.groupCode() + "/" + t.ticketNo(),
                Json.obj("groupCode", g.groupCode(), "ticketNo", t.ticketNo(), "customerId", t.customerId(), "status", back.name(),
                        "curedOn", today.toString()));
        return true;
    }

    private static BigDecimal cleanAmount(BigDecimal raw) {
        if (raw.signum() <= 0) {
            throw ChitException.badRequest("VALIDATION_FAILED", "amount must be greater than zero");
        }
        if (raw.stripTrailingZeros().scale() > 2) {
            throw ChitException.badRequest("VALIDATION_FAILED", "amount may have at most two decimal places");
        }
        if (raw.compareTo(new BigDecimal("999999999999.99")) > 0) {
            throw ChitException.badRequest("VALIDATION_FAILED", "amount is too large");
        }
        return Money.scale(raw);
    }
}
