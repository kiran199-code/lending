package com.bank.lending.chit.collection;

import com.bank.lending.chit.calc.LateFeeCalculator;
import com.bank.lending.chit.collection.CollectionDtos.AllocationView;
import com.bank.lending.chit.collection.CollectionDtos.CycleLine;
import com.bank.lending.chit.collection.CollectionDtos.DefaultCaseView;
import com.bank.lending.chit.collection.CollectionDtos.DefaulterLine;
import com.bank.lending.chit.collection.CollectionDtos.DemandView;
import com.bank.lending.chit.collection.CollectionDtos.DueRow;
import com.bank.lending.chit.collection.CollectionDtos.DuesPage;
import com.bank.lending.chit.collection.CollectionDtos.GroupStatement;
import com.bank.lending.chit.collection.CollectionDtos.LateFeePreviewView;
import com.bank.lending.chit.collection.CollectionDtos.LateFeeProjection;
import com.bank.lending.chit.collection.CollectionDtos.PaymentView;
import com.bank.lending.chit.collection.CollectionDtos.TicketStatement;
import com.bank.lending.chit.collection.CollectionModels.AdvanceRow;
import com.bank.lending.chit.collection.CollectionModels.AllocationRow;
import com.bank.lending.chit.collection.CollectionModels.CycleTotals;
import com.bank.lending.chit.collection.CollectionModels.DefaultCaseRow;
import com.bank.lending.chit.collection.CollectionModels.DemandRow;
import com.bank.lending.chit.collection.CollectionModels.PaymentRow;
import com.bank.lending.chit.common.ChitException;
import com.bank.lending.chit.common.Money;
import com.bank.lending.chit.domain.rules.ChitRules;
import com.bank.lending.chit.enrolment.EnrolmentModels.TicketRow;
import com.bank.lending.chit.enrolment.EnrolmentRepository;
import com.bank.lending.chit.group.GroupModels.CycleRow;
import com.bank.lending.chit.group.GroupModels.GroupRow;
import com.bank.lending.chit.group.GroupRepository;
import com.bank.lending.chit.group.GroupService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everything the collection screens read: payments and receipts, demands, statements, dues and default cases. Writes nothing. */
@Service
public class CollectionReadService {

    public static final String VIEW_UPCOMING = "UPCOMING";
    public static final String VIEW_OVERDUE = "OVERDUE";
    public static final String VIEW_OPEN = "OPEN";

    private final GroupService groupService;
    private final GroupRepository groups;
    private final EnrolmentRepository tickets;
    private final CollectionRepository repo;

    public CollectionReadService(GroupService groupService, GroupRepository groups, EnrolmentRepository tickets, CollectionRepository repo) {
        this.groupService = groupService;
        this.groups = groups;
        this.tickets = tickets;
        this.repo = repo;
    }

    // ---- payments -------------------------------------------------------------------------------------------------

    public PaymentView paymentView(PaymentRow p, String groupCode, TicketRow ticket) {
        Map<Long, DemandRow> demandCache = new HashMap<>();
        List<AllocationView> lines = new ArrayList<>();
        BigDecimal advance = Money.ZERO;
        for (AllocationRow a : repo.allocationsOf(p.id())) {
            if (a.fromAdvance()) {
                continue; // that is this payment's advance being used by a later demand; it is shown on the demand, not the receipt
            }
            DemandRow d = a.demandId() == null ? null : demandCache.computeIfAbsent(a.demandId(), id -> repo.findDemand(id).orElse(null));
            lines.add(new AllocationView(d == null ? null : d.cycleNo(), d == null ? null : d.demandRef(), a.bucket(), a.amount(), false));
            if (a.demandId() == null) {
                advance = advance.add(a.amount());
            }
        }
        return new PaymentView(p.paymentRef(), groupCode, ticket.ticketNo(), ticket.customerId(), p.amount(), p.channel(), p.status(),
                p.businessDate(), p.externalRef(), p.amount().subtract(advance), advance, lines, p.reversalReason(), p.reversedAt(),
                p.reversedBy(), p.createdAt(), p.createdBy(), null);
    }

    @Transactional(readOnly = true)
    public PaymentView payment(String code, String paymentRef) {
        GroupRow g = groupService.require(code);
        PaymentRow p = repo.findPayment(g.id(), paymentRef).orElseThrow(() -> ChitException.notFound("Payment", paymentRef));
        TicketRow t = tickets.findById(p.ticketId()).orElseThrow(() -> ChitException.notFound("Ticket", String.valueOf(p.ticketId())));
        return paymentView(p, code, t);
    }

    @Transactional(readOnly = true)
    public List<PaymentView> payments(String code, Integer ticketNo, int limit, int offset) {
        GroupRow g = groupService.require(code);
        Long ticketId = ticketNo == null ? null : liveTicket(g, ticketNo).id();
        Map<Long, TicketRow> byId = ticketsById(g);
        List<PaymentView> out = new ArrayList<>();
        for (PaymentRow p : repo.paymentsOfGroup(g.id(), ticketId, clamp(limit, 1, 200, 50), Math.max(0, offset))) {
            out.add(paymentView(p, code, byId.get(p.ticketId())));
        }
        return out;
    }

    // ---- demands --------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<DemandView> demands(String code, Integer ticketNo, String status, LocalDate today, int limit, int offset) {
        GroupRow g = groupService.require(code);
        Long ticketId = ticketNo == null ? null : liveTicket(g, ticketNo).id();
        String st = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        if (st != null && !st.equals("OPEN") && !st.equals("PAID") && !st.equals("CANCELLED")) {
            throw ChitException.badRequest("VALIDATION_FAILED", "status must be OPEN, PAID or CANCELLED");
        }
        Map<Long, TicketRow> byId = ticketsById(g);
        return repo.demandsOfGroup(g.id(), ticketId, st, clamp(limit, 1, 500, 100), Math.max(0, offset)).stream()
                .map(d -> demandView(d, byId.get(d.ticketId()).ticketNo(), today)).toList();
    }

    static DemandView demandView(DemandRow d, int ticketNo, LocalDate today) {
        var f = d.figures();
        long late = "OPEN".equals(d.status()) && f.totalOutstanding().signum() > 0 ? Math.max(0, ChronoUnit.DAYS.between(d.dueDate(), today)) : 0;
        return new DemandView(d.demandRef(), d.cycleNo(), ticketNo, d.dueDate(), d.raisedOn(), d.subscriptionAmount(), d.dividendAdjustment(),
                d.subscriptionPaid(), f.subscriptionOutstanding(), d.lateFeeAccrued(), d.lateFeePaid(), f.lateFeeOutstanding(),
                f.totalOutstanding(), late, d.status());
    }

    // ---- statements -----------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public TicketStatement ticketStatement(String code, int ticketNo, LocalDate today) {
        GroupRow g = groupService.require(code);
        TicketRow t = liveTicket(g, ticketNo);
        List<DemandRow> all = repo.demandsOfTicket(t.id());
        BigDecimal sub = Money.ZERO;
        BigDecimal fee = Money.ZERO;
        LocalDate next = null;
        for (DemandRow d : all) {
            if (!"OPEN".equals(d.status())) {
                continue;
            }
            sub = sub.add(d.figures().subscriptionOutstanding());
            fee = fee.add(d.figures().lateFeeOutstanding());
            if (d.figures().subscriptionOutstanding().signum() > 0 && (next == null || d.dueDate().isBefore(next))) {
                next = d.dueDate();
            }
        }
        if (next == null) {
            next = groups.cycles(g.id()).stream().filter(c -> "SCHEDULED".equals(c.status())).map(CycleRow::dueDate).findFirst().orElse(null);
        }
        BigDecimal advance = repo.openAdvance(t.id()).stream().map(AdvanceRow::available).reduce(Money.ZERO, BigDecimal::add);
        List<PaymentRow> pays = repo.paymentsOfTicket(t.id());
        BigDecimal paid = pays.stream().filter(p -> "RECEIVED".equals(p.status())).map(PaymentRow::amount).reduce(Money.ZERO, BigDecimal::add);
        List<PaymentView> payViews = pays.stream().limit(100).map(p -> paymentView(p, code, t)).toList();
        DefaultCaseView open = repo.openCase(t.id()).map(c -> caseView(c, t)).orElse(null);
        return new TicketStatement(code, t.ticketNo(), t.customerId(), t.status(), today, Money.scale(sub), Money.scale(fee),
                Money.scale(sub.add(fee)), Money.scale(advance), Money.scale(paid), next, open,
                all.stream().map(d -> demandView(d, t.ticketNo(), today)).toList(), payViews);
    }

    @Transactional(readOnly = true)
    public GroupStatement groupStatement(String code, LocalDate today) {
        GroupRow g = groupService.require(code);
        List<CycleLine> lines = new ArrayList<>();
        BigDecimal totalDue = Money.ZERO;
        BigDecimal totalCollected = Money.ZERO;
        for (CycleTotals c : repo.cycleTotals(g.id())) {
            BigDecimal outstanding = Money.max(Money.ZERO, c.due().subtract(c.collected()));
            BigDecimal pct = c.due().signum() == 0 ? Money.ZERO
                    : c.collected().multiply(Money.HUNDRED).divide(c.due(), 2, RoundingMode.HALF_UP);
            lines.add(new CycleLine(c.cycleNo(), c.status(), c.dueDate(), c.demands(), c.settledDemands(), Money.scale(c.due()),
                    Money.scale(c.collected()), Money.scale(outstanding), pct, Money.scale(c.lateFeeAccrued()), Money.scale(c.lateFeePaid())));
            totalDue = totalDue.add(c.due());
            totalCollected = totalCollected.add(c.collected());
        }
        Map<Long, TicketRow> byId = ticketsById(g);
        Map<Long, long[]> counters = new LinkedHashMap<>(); // ticketId -> {overdue instalments, oldest days}
        Map<Long, BigDecimal[]> owed = new LinkedHashMap<>();
        BigDecimal feeOutstanding = Money.ZERO;
        for (DemandRow d : repo.openDemandsOfGroup(g.id())) {
            var f = d.figures();
            feeOutstanding = feeOutstanding.add(f.lateFeeOutstanding());
            if (!d.dueDate().isBefore(today) || f.totalOutstanding().signum() <= 0) {
                continue;
            }
            long days = ChronoUnit.DAYS.between(d.dueDate(), today);
            long[] c = counters.computeIfAbsent(d.ticketId(), k -> new long[2]);
            if (f.subscriptionOutstanding().signum() > 0) {
                c[0]++;
            }
            c[1] = Math.max(c[1], days);
            BigDecimal[] o = owed.computeIfAbsent(d.ticketId(), k -> new BigDecimal[]{Money.ZERO, Money.ZERO});
            o[0] = o[0].add(f.subscriptionOutstanding());
            o[1] = o[1].add(f.lateFeeOutstanding());
        }
        List<DefaulterLine> overdue = new ArrayList<>();
        counters.forEach((ticketId, c) -> {
            TicketRow t = byId.get(ticketId);
            overdue.add(new DefaulterLine(t.ticketNo(), t.customerId(), Money.scale(owed.get(ticketId)[0]), Money.scale(owed.get(ticketId)[1]),
                    (int) c[0], c[1]));
        });
        overdue.sort((a, b) -> Integer.compare(a.ticketNo(), b.ticketNo()));
        return new GroupStatement(code, g.name(), today, lines, Money.scale(totalDue), Money.scale(totalCollected),
                Money.scale(Money.max(Money.ZERO, totalDue.subtract(totalCollected))), Money.scale(feeOutstanding), overdue);
    }

    // ---- dues across groups ---------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public DuesPage dues(String view, String groupCode, LocalDate today, int withinDays, int limit, int offset) {
        String v = view == null || view.isBlank() ? VIEW_OVERDUE : view.trim().toUpperCase();
        String group = groupCode == null || groupCode.isBlank() ? null : groupCode.trim();
        int size = clamp(limit, 1, 500, 100);
        int skip = Math.max(0, offset);
        List<CollectionRepository.DueRecord> records = switch (v) {
            case VIEW_UPCOMING -> repo.upcomingDues(today, today.plusDays(clamp(withinDays, 1, 366, 30)), group, size, skip);
            case VIEW_OVERDUE -> repo.openDues(today, group, size, skip);
            case VIEW_OPEN -> repo.openDues(null, group, size, skip);
            default -> throw ChitException.badRequest("VALIDATION_FAILED", "view must be UPCOMING, OVERDUE or OPEN");
        };
        List<DueRow> rows = records.stream().map(r -> {
            BigDecimal total = r.subscriptionOutstanding().add(r.lateFeeOutstanding());
            long days = r.demandRef() == null ? 0 : Math.max(0, ChronoUnit.DAYS.between(r.dueDate(), today));
            return new DueRow(v, r.groupCode(), r.groupName(), r.ticketNo(), r.customerId(), r.ticketStatus(), r.cycleNo(), r.demandRef(),
                    r.dueDate(), days, Money.scale(r.subscriptionOutstanding()), Money.scale(r.lateFeeOutstanding()), Money.scale(total));
        }).toList();
        return new DuesPage(v, today, rows, size, skip);
    }

    // ---- late fee preview -----------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public LateFeePreviewView lateFeePreview(String code, int ticketNo, LocalDate today, int days) {
        GroupRow g = groupService.require(code);
        ChitRules rules = groupService.rulesOf(g);
        TicketRow t = liveTicket(g, ticketNo);
        int span = clamp(days, 1, 90, 7);
        LocalDate from = today.plusDays(1);
        List<LateFeeProjection> out = new ArrayList<>();
        for (DemandRow d : repo.openDemandsOfTicket(t.id())) {
            BigDecimal overdue = d.figures().subscriptionOutstanding();
            if (overdue.signum() <= 0) {
                continue;
            }
            out.add(new LateFeeProjection(d.demandRef(), d.cycleNo(), d.dueDate(), overdue, d.lateFeeAccrued(),
                    LateFeeCalculator.preview(rules.lateFeeRule(), overdue, d.dueDate(), from, span, rules.graceDays(), d.lateFeeAccrued())));
        }
        return new LateFeePreviewView(code, ticketNo, from, span, rules.lateFeeRule().kind().name(), out);
    }

    // ---- default cases --------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<DefaultCaseView> defaultCases(String code, String status) {
        GroupRow g = groupService.require(code);
        String st = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        Map<Long, TicketRow> byId = ticketsById(g);
        return repo.casesOfGroup(g.id(), st).stream().map(c -> caseView(c, byId.get(c.ticketId()))).toList();
    }

    static DefaultCaseView caseView(DefaultCaseRow c, TicketRow t) {
        return new DefaultCaseView(t.ticketNo(), t.customerId(), c.status(), c.openedOn(), c.shortfall(), c.reason(), c.missedInstalments(),
                c.daysOverdue(), c.closedOn(), c.foremanCovered(), c.openedBy());
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    TicketRow liveTicket(GroupRow g, int ticketNo) {
        return tickets.findLive(g.id(), ticketNo).orElseThrow(() -> ChitException.notFound("Ticket", g.groupCode() + " #" + ticketNo));
    }

    private Map<Long, TicketRow> ticketsById(GroupRow g) {
        Map<Long, TicketRow> m = new HashMap<>();
        tickets.tickets(g.id(), true).forEach(t -> m.put(t.id(), t));
        return m;
    }

    private static int clamp(int v, int min, int max, int dflt) {
        if (v <= 0) {
            return dflt;
        }
        return Math.max(min, Math.min(max, v));
    }
}
