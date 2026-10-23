package com.bank.lending.chit.enrolment;

import com.bank.lending.chit.common.Audit;
import com.bank.lending.chit.common.ChitException;
import com.bank.lending.chit.common.Hashing;
import com.bank.lending.chit.common.Json;
import com.bank.lending.chit.common.Money;
import com.bank.lending.chit.config.ChitSettings;
import com.bank.lending.chit.domain.GroupStatus;
import com.bank.lending.chit.domain.SecurityRule;
import com.bank.lending.chit.domain.TicketStatus;
import com.bank.lending.chit.domain.rules.ChitRules;
import com.bank.lending.chit.domain.state.Lifecycles;
import com.bank.lending.chit.enrolment.EnrolmentDtos.CancelRequest;
import com.bank.lending.chit.enrolment.EnrolmentDtos.CancelResult;
import com.bank.lending.chit.enrolment.EnrolmentDtos.EnrolRequest;
import com.bank.lending.chit.enrolment.EnrolmentDtos.FeeView;
import com.bank.lending.chit.enrolment.EnrolmentDtos.QuoteView;
import com.bank.lending.chit.enrolment.EnrolmentDtos.RefundLine;
import com.bank.lending.chit.enrolment.EnrolmentDtos.RefundSummary;
import com.bank.lending.chit.enrolment.EnrolmentDtos.SecurityView;
import com.bank.lending.chit.enrolment.EnrolmentDtos.TicketView;
import com.bank.lending.chit.enrolment.EnrolmentDtos.WithdrawRequest;
import com.bank.lending.chit.enrolment.EnrolmentDtos.WithdrawResult;
import com.bank.lending.chit.enrolment.EnrolmentModels.FeeRow;
import com.bank.lending.chit.enrolment.EnrolmentModels.SecurityRow;
import com.bank.lending.chit.enrolment.EnrolmentModels.TicketRow;
import com.bank.lending.chit.enrolment.RefundPlan.Item;
import com.bank.lending.chit.enrolment.RefundPlan.TicketMoney;
import com.bank.lending.chit.group.GroupModels.GroupRow;
import com.bank.lending.chit.group.GroupRepository;
import com.bank.lending.chit.group.GroupService;
import com.bank.lending.chit.idempotency.IdempotencyCheck.Replay;
import com.bank.lending.chit.idempotency.IdempotencyStore;
import com.bank.lending.chit.ledger.JournalTemplates;
import com.bank.lending.chit.ledger.LedgerDtos.PostingContext;
import com.bank.lending.chit.ledger.LedgerService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Enrolling tickets, withdrawing before the group starts, verifying security and cancelling the group. Every change
 * takes the group's row lock first, posts its money movements to the ledger in the same transaction, and writes an
 * audit row and an event. The business date is passed in (read from lending-service before the transaction starts).
 */
@Service
public class EnrolmentService {

    static final String CANCEL_REASON = "GROUP_CANCELLED";

    private final GroupService groupService;
    private final GroupRepository groups;
    private final EnrolmentRepository repo;
    private final LedgerService ledger;
    private final IdempotencyStore idempotency;
    private final Audit audit;
    private final ChitSettings settings;

    public EnrolmentService(GroupService groupService, GroupRepository groups, EnrolmentRepository repo, LedgerService ledger,
                            IdempotencyStore idempotency, Audit audit, ChitSettings settings) {
        this.groupService = groupService;
        this.groups = groups;
        this.repo = repo;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.audit = audit;
        this.settings = settings;
    }

    // ---- reads ----------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public QuoteView quote(String code) {
        EnrolmentQuote q = EnrolmentQuote.from(groupService.rulesOf(groupService.require(code)));
        return new QuoteView(q.enrolmentFee(), q.registrationFee(), q.securityDeposit(), q.lienAmount(), q.guarantorRequired(), q.cashDue());
    }

    @Transactional(readOnly = true)
    public List<TicketView> tickets(String code, boolean includeExited) {
        GroupRow g = groupService.require(code);
        return viewsOf(g.id(), repo.tickets(g.id(), includeExited));
    }

    @Transactional(readOnly = true)
    public TicketView ticket(String code, int ticketNo) {
        GroupRow g = groupService.require(code);
        TicketRow t = repo.findLive(g.id(), ticketNo).orElseThrow(() -> ChitException.notFound("Ticket", code + "/" + ticketNo));
        return viewsOf(g.id(), List.of(t)).get(0);
    }

    // ---- enrol ----------------------------------------------------------------------------------------------------

    @Transactional
    public TicketView enrol(String code, EnrolRequest req, String idemKey, String actor, LocalDate today) {
        if (req == null) {
            throw ChitException.badRequest("VALIDATION_FAILED", "A request body is required");
        }
        Replay replay = idempotency.begin(idemKey, "TICKET_ENROL", Hashing.sha256(code + "|" + Json.write(req)));
        if (replay != null) {
            return viewOf(Long.parseLong(ref(replay))).asReplay();
        }
        if (req.customerId() == null || req.customerId() <= 0) {
            throw ChitException.badRequest("VALIDATION_FAILED", "customerId is required");
        }
        if (req.payoutAccountRef() != null && req.payoutAccountRef().length() > 60) {
            throw ChitException.badRequest("VALIDATION_FAILED", "payoutAccountRef must be at most 60 characters");
        }
        if (req.lienReference() != null && req.lienReference().length() > 60) {
            throw ChitException.badRequest("VALIDATION_FAILED", "lienReference must be at most 60 characters");
        }
        GroupRow g = groupService.lock(code);
        if (g.status() == GroupStatus.FULL) {
            throw ChitException.conflict("GROUP_FULL", "Every ticket of " + code + " is taken");
        }
        if (g.status() != GroupStatus.OPEN_FOR_ENROLMENT) {
            throw ChitException.conflict("ENROLMENT_NOT_OPEN", "Group " + code + " is " + g.status() + "; enrolment is open only while it is OPEN_FOR_ENROLMENT");
        }
        ChitRules rules = groupService.rulesOf(g);
        EnrolmentQuote quote = EnrolmentQuote.from(rules);
        quote.check(new EnrolmentQuote.Received(req.enrolmentFeeReceived(), req.registrationFeeReceived(), req.securityDepositReceived(),
                req.guarantorCustomerId(), req.lienReference()), req.customerId());

        int no = repo.lowestFreeNumber(g.id(), g.ticketCount())
                .orElseThrow(() -> ChitException.conflict("GROUP_FULL", "Every ticket of " + code + " is taken"));
        String payout = req.payoutAccountRef() == null || req.payoutAccountRef().isBlank() ? null : req.payoutAccountRef().trim();
        long ticketId = repo.insertTicket(g.id(), no, req.customerId(), payout, actor);
        repo.insertLink(ticketId, req.customerId(), today, "ENROLMENT", actor);
        PostingContext ctx = new PostingContext(g.id(), today, null, "TICKET", String.valueOf(ticketId), actor);

        if (Money.isPositive(quote.enrolmentFee())) {
            repo.insertFeeReceipt(ticketId, "ENROLMENT", quote.enrolmentFee(), today, actor);
            ledger.post(JournalTemplates.enrolmentFee("ENROL_FEE:" + ticketId + ":ENROLMENT", quote.enrolmentFee()), ctx);
        }
        if (Money.isPositive(quote.registrationFee())) {
            repo.insertFeeReceipt(ticketId, "REGISTRATION", quote.registrationFee(), today, actor);
            ledger.post(JournalTemplates.enrolmentFee("ENROL_FEE:" + ticketId + ":REGISTRATION", quote.registrationFee()), ctx);
        }
        SecurityRule rule = rules.securityRule() == null ? SecurityRule.NONE : rules.securityRule();
        switch (rule) {
            case DEPOSIT -> {
                repo.insertSecurity(ticketId, "DEPOSIT", quote.securityDeposit(), null, null, today, actor);
                ledger.post(JournalTemplates.securityReceived("SECURITY_RECV:" + ticketId, ticketId, quote.securityDeposit()), ctx);
            }
            case LIEN_ON_DEPOSIT -> repo.insertSecurity(ticketId, "LIEN", quote.lienAmount(), null, req.lienReference().trim(), today, actor);
            case GUARANTOR -> repo.insertSecurity(ticketId, "GUARANTOR", Money.ZERO, req.guarantorCustomerId(), null, today, actor);
            default -> {
            }
        }

        boolean nowFull = repo.countLive(g.id()) == g.ticketCount();
        if (nowFull) {
            Lifecycles.GROUP.require(g.status(), GroupStatus.FULL);
            groups.setStatus(g.id(), GroupStatus.FULL, actor);
        }
        audit.log("TICKET", code + "/" + no, "ENROLLED", actor, "customer " + req.customerId());
        audit.event("TicketEnrolled", "GROUP", code, Json.obj("groupCode", code, "ticketNo", no, "ticketId", ticketId,
                "customerId", req.customerId(), "cashReceived", quote.cashDue(), "actor", actor));
        if (nowFull) {
            audit.event("ChitGroupFull", "GROUP", code, Json.obj("groupCode", code, "tickets", g.ticketCount(), "actor", actor));
        }
        idempotency.complete(idemKey, String.valueOf(ticketId));
        return viewOf(ticketId);
    }

    // ---- withdraw -------------------------------------------------------------------------------------------------

    @Transactional
    public WithdrawResult withdraw(String code, int ticketNo, WithdrawRequest req, String idemKey, String actor, LocalDate today) {
        String reason = req == null || req.reason() == null || req.reason().isBlank() ? "WITHDRAWN" : req.reason().trim();
        if (reason.length() > 120) {
            throw ChitException.badRequest("VALIDATION_FAILED", "reason must be at most 120 characters");
        }
        if (CANCEL_REASON.equals(reason)) {
            throw ChitException.badRequest("VALIDATION_FAILED", "reason " + CANCEL_REASON + " is reserved for group cancellation");
        }
        Replay replay = idempotency.begin(idemKey, "TICKET_WITHDRAW", Hashing.sha256(code + "|" + ticketNo + "|" + reason));
        if (replay != null) {
            TicketRow t = repo.findById(Long.parseLong(ref(replay))).orElseThrow();
            return new WithdrawResult(viewOf(t.id()), summaryFor(t.groupId(), Set.of(t.id())), groupService.require(code).status().name(), Boolean.TRUE);
        }
        GroupRow g = groupService.lock(code);
        if (g.status() != GroupStatus.OPEN_FOR_ENROLMENT && g.status() != GroupStatus.FULL) {
            throw ChitException.conflict("WITHDRAWAL_NOT_ALLOWED", "A ticket can be withdrawn only before the group starts; "
                    + code + " is " + g.status());
        }
        TicketRow t = repo.findLive(g.id(), ticketNo).orElseThrow(() -> ChitException.notFound("Ticket", code + "/" + ticketNo));
        Lifecycles.TICKET.require(t.status(), TicketStatus.EXITED);

        List<Item> plan = RefundPlan.forWithdrawal(moneyOf(t), settings.withdrawalRefundsFees());
        applyRefunds(g, plan, today, actor);
        returnRemainingSecurity(t.id(), today);
        repo.exitTicket(t.id(), reason, today, actor);
        repo.closeLink(t.id(), today);

        GroupStatus after = g.status();
        if (g.status() == GroupStatus.FULL) {
            Lifecycles.GROUP.require(GroupStatus.FULL, GroupStatus.OPEN_FOR_ENROLMENT);
            groups.setStatus(g.id(), GroupStatus.OPEN_FOR_ENROLMENT, actor);
            after = GroupStatus.OPEN_FOR_ENROLMENT;
        }
        RefundSummary summary = summaryFor(g.id(), Set.of(t.id()));
        audit.log("TICKET", code + "/" + ticketNo, "WITHDRAWN", actor, reason);
        audit.event("TicketWithdrawn", "GROUP", code, Json.obj("groupCode", code, "ticketNo", ticketNo, "ticketId", t.id(),
                "customerId", t.customerId(), "reason", reason, "cashRefunded", summary.cashRefunded(), "actor", actor));
        idempotency.complete(idemKey, String.valueOf(t.id()));
        return new WithdrawResult(viewOf(t.id()), summary, after.name(), null);
    }

    // ---- verify security ------------------------------------------------------------------------------------------

    @Transactional
    public TicketView verifySecurity(String code, int ticketNo, String idemKey, String actor) {
        Replay replay = idempotency.begin(idemKey, "SECURITY_VERIFY", Hashing.sha256(code + "|" + ticketNo));
        if (replay != null) {
            return viewOf(Long.parseLong(ref(replay))).asReplay();
        }
        GroupRow g = groupService.lock(code);
        if (g.status() != GroupStatus.OPEN_FOR_ENROLMENT && g.status() != GroupStatus.FULL) {
            throw ChitException.conflict("ILLEGAL_TRANSITION", "Security is verified before the group starts; " + code + " is " + g.status());
        }
        TicketRow t = repo.findLive(g.id(), ticketNo).orElseThrow(() -> ChitException.notFound("Ticket", code + "/" + ticketNo));
        SecurityRow s = repo.liveSecurityOfTicket(t.id()).orElseThrow(() -> ChitException.notFound("Security", code + "/" + ticketNo));
        if (!"HELD".equals(s.status())) {
            throw ChitException.conflict("ILLEGAL_TRANSITION", "Security of ticket " + ticketNo + " is " + s.status() + ", not HELD");
        }
        if (settings.makerCheckerEnforce() && actor.equals(s.createdBy())) {
            throw ChitException.forbidden("MAKER_CHECKER_VIOLATION",
                    "The security was recorded by " + actor + "; a different person must verify it");
        }
        repo.verifySecurity(s.id(), actor);
        audit.log("TICKET", code + "/" + ticketNo, "SECURITY_VERIFIED", actor, s.kind());
        audit.event("SecurityVerified", "GROUP", code, Json.obj("groupCode", code, "ticketNo", ticketNo, "kind", s.kind(), "actor", actor));
        idempotency.complete(idemKey, String.valueOf(t.id()));
        return viewOf(t.id());
    }

    // ---- cancel ---------------------------------------------------------------------------------------------------

    @Transactional
    public CancelResult cancel(String code, CancelRequest req, String idemKey, String actor, LocalDate today) {
        if (req == null || req.reason() == null || req.reason().isBlank()) {
            throw ChitException.badRequest("VALIDATION_FAILED", "reason is required");
        }
        String reason = req.reason().trim();
        if (reason.length() > 200) {
            throw ChitException.badRequest("VALIDATION_FAILED", "reason must be at most 200 characters");
        }
        Replay replay = idempotency.begin(idemKey, "GROUP_CANCEL", Hashing.sha256(code + "|" + reason));
        if (replay != null) {
            GroupRow g = groupService.require(code);
            return new CancelResult(code, g.status().name(), g.cancelReason(), summaryFor(g.id(), cancelledTicketIds(g.id())), Boolean.TRUE);
        }
        GroupRow g = groupService.lock(code);
        Lifecycles.GROUP.require(g.status(), GroupStatus.CANCELLED);

        List<TicketRow> live = repo.tickets(g.id(), false);
        List<TicketMoney> money = new ArrayList<>();
        for (TicketRow t : live) {
            money.add(moneyOf(t));
        }
        applyRefunds(g, RefundPlan.forCancellation(money), today, actor);
        for (TicketRow t : live) {
            returnRemainingSecurity(t.id(), today);
            repo.exitTicket(t.id(), CANCEL_REASON, today, actor);
            repo.closeLink(t.id(), today);
        }
        groups.markCancelled(g.id(), reason, actor);
        RefundSummary summary = summaryFor(g.id(), cancelledTicketIds(g.id()));
        audit.log("GROUP", code, "CANCELLED", actor, reason);
        audit.event("ChitGroupCancelled", "GROUP", code, Json.obj("groupCode", code, "reason", reason, "tickets", live.size(),
                "cashRefunded", summary.cashRefunded(), "actor", actor));
        idempotency.complete(idemKey, code);
        return new CancelResult(code, GroupStatus.CANCELLED.name(), reason, summary, null);
    }

    // ---- refunds --------------------------------------------------------------------------------------------------

    private TicketMoney moneyOf(TicketRow t) {
        BigDecimal enrol = null;
        BigDecimal reg = null;
        for (FeeRow f : repo.feesOfTicket(t.id())) {
            if ("RECEIVED".equals(f.status())) {
                if ("ENROLMENT".equals(f.feeKind())) {
                    enrol = f.amount();
                } else {
                    reg = f.amount();
                }
            }
        }
        BigDecimal deposit = null;
        BigDecimal lien = null;
        SecurityRow s = repo.liveSecurityOfTicket(t.id()).orElse(null);
        if (s != null && "DEPOSIT".equals(s.kind())) {
            deposit = s.amount();
        } else if (s != null && "LIEN".equals(s.kind())) {
            lien = s.amount();
        }
        return new TicketMoney(t.id(), t.ticketNo(), enrol, reg, deposit, lien);
    }

    /** Posts each refund to the ledger and marks the receipt or security row it settles. */
    private void applyRefunds(GroupRow g, List<Item> plan, LocalDate today, String actor) {
        for (Item item : plan) {
            PostingContext ctx = new PostingContext(g.id(), today, null, "TICKET", String.valueOf(item.ticketId()), actor);
            switch (item.kind()) {
                case DEPOSIT -> {
                    ledger.post(JournalTemplates.securityRefund("SEC_REFUND:" + item.ticketId(), item.ticketId(), item.amount()), ctx);
                    repo.liveSecurityOfTicket(item.ticketId()).ifPresent(s -> repo.markSecurityReturned(s.id(), today));
                }
                case LIEN_RELEASE -> repo.liveSecurityOfTicket(item.ticketId()).ifPresent(s -> repo.markSecurityReturned(s.id(), today));
                case ENROLMENT_FEE -> refundFee(item, "ENROLMENT", ctx, today);
                case REGISTRATION_FEE -> refundFee(item, "REGISTRATION", ctx, today);
            }
        }
    }

    private void refundFee(Item item, String kind, PostingContext ctx, LocalDate today) {
        ledger.post(JournalTemplates.enrolmentFeeRefund("ENROL_FEE_REFUND:" + item.ticketId() + ":" + kind, item.amount()), ctx);
        for (FeeRow f : repo.feesOfTicket(item.ticketId())) {
            if (kind.equals(f.feeKind()) && "RECEIVED".equals(f.status())) {
                repo.markFeeRefunded(f.id(), today);
            }
        }
    }

    /** A guarantor row carries no money; it is simply released when the ticket leaves. */
    private void returnRemainingSecurity(long ticketId, LocalDate today) {
        repo.liveSecurityOfTicket(ticketId).ifPresent(s -> repo.markSecurityReturned(s.id(), today));
    }

    private List<Long> cancelledIds(long groupId) {
        return repo.tickets(groupId, true).stream().filter(t -> CANCEL_REASON.equals(t.exitReason())).map(TicketRow::id).toList();
    }

    private Set<Long> cancelledTicketIds(long groupId) {
        return Set.copyOf(cancelledIds(groupId));
    }

    /** What has been handed back for these tickets, worked out from the receipts and security rows themselves. */
    private RefundSummary summaryFor(long groupId, Set<Long> ticketIds) {
        Map<Long, TicketRow> byId = repo.tickets(groupId, true).stream().collect(Collectors.toMap(TicketRow::id, t -> t));
        List<FeeRow> fees = repo.feesOfGroup(groupId);
        List<SecurityRow> security = repo.allSecurityOfGroup(groupId);
        List<RefundLine> lines = new ArrayList<>();
        BigDecimal cash = Money.ZERO;
        List<Long> ordered = new ArrayList<>(ticketIds);
        ordered.sort((a, b) -> Integer.compare(byId.get(a).ticketNo(), byId.get(b).ticketNo()));
        for (long id : ordered) {
            int no = byId.get(id).ticketNo();
            for (SecurityRow s : security) {
                if (s.ticketId() == id && "RETURNED".equals(s.status()) && !"GUARANTOR".equals(s.kind())) {
                    boolean lien = "LIEN".equals(s.kind());
                    lines.add(new RefundLine(lien ? "LIEN_RELEASE" : "DEPOSIT", no, s.amount()));
                    if (!lien) {
                        cash = cash.add(s.amount());
                    }
                }
            }
            for (String kind : List.of("ENROLMENT", "REGISTRATION")) {
                for (FeeRow f : fees) {
                    if (f.ticketId() == id && kind.equals(f.feeKind()) && "REFUNDED".equals(f.status())) {
                        lines.add(new RefundLine(kind + "_FEE", no, f.amount()));
                        cash = cash.add(f.amount());
                    }
                }
            }
        }
        return new RefundSummary(lines, Money.scale(cash));
    }

    // ---- views ----------------------------------------------------------------------------------------------------

    private TicketView viewOf(long ticketId) {
        TicketRow t = repo.findById(ticketId).orElseThrow(() -> ChitException.notFound("Ticket", String.valueOf(ticketId)));
        return viewsOf(t.groupId(), List.of(t)).get(0);
    }

    private List<TicketView> viewsOf(long groupId, List<TicketRow> rows) {
        Map<Long, List<FeeRow>> fees = new HashMap<>();
        for (FeeRow f : repo.feesOfGroup(groupId)) {
            fees.computeIfAbsent(f.ticketId(), k -> new ArrayList<>()).add(f);
        }
        Map<Long, SecurityRow> latest = new HashMap<>();
        for (SecurityRow s : repo.allSecurityOfGroup(groupId)) {
            latest.put(s.ticketId(), s);
        }
        List<TicketView> out = new ArrayList<>();
        for (TicketRow t : rows) {
            List<FeeView> feeViews = fees.getOrDefault(t.id(), List.of()).stream()
                    .map(f -> new FeeView(f.feeKind(), f.amount(), f.status(), f.receivedOn(), f.refundedOn())).toList();
            SecurityRow s = latest.get(t.id());
            SecurityView sv = s == null ? null : new SecurityView(s.kind(), s.amount(), s.guarantorCustomerId(), s.reference(),
                    s.status(), s.receivedOn(), s.verifiedBy());
            out.add(new TicketView(t.id(), t.ticketNo(), t.customerId(), t.status(), t.payoutAccountRef(), feeViews, sv,
                    t.exitReason(), t.exitedOn(), null));
        }
        return out;
    }

    private static String ref(Replay replay) {
        if (replay.resultRef() == null) {
            throw ChitException.conflict("DATA_CONFLICT", "The first request with this idempotency key has not finished; retry shortly");
        }
        return replay.resultRef();
    }
}
