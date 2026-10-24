package com.bank.lending.chit.collection;

import com.bank.lending.chit.calc.AdvancePlanner;
import com.bank.lending.chit.calc.AdvancePlanner.Slice;
import com.bank.lending.chit.calc.AdvancePlanner.Use;
import com.bank.lending.chit.calc.DemandFigures;
import com.bank.lending.chit.collection.CollectionModels.AdvanceRow;
import com.bank.lending.chit.collection.CollectionModels.CycleDue;
import com.bank.lending.chit.collection.CollectionModels.DividendRow;
import com.bank.lending.chit.common.Audit;
import com.bank.lending.chit.common.Json;
import com.bank.lending.chit.common.Money;
import com.bank.lending.chit.domain.AllocationBucket;
import com.bank.lending.chit.domain.CycleStatus;
import com.bank.lending.chit.domain.GroupStatus;
import com.bank.lending.chit.domain.TicketStatus;
import com.bank.lending.chit.domain.state.Lifecycles;
import com.bank.lending.chit.enrolment.EnrolmentModels.TicketRow;
import com.bank.lending.chit.enrolment.EnrolmentRepository;
import com.bank.lending.chit.group.GroupModels.GroupRow;
import com.bank.lending.chit.group.GroupService;
import com.bank.lending.chit.ledger.JournalTemplates;
import com.bank.lending.chit.ledger.LedgerDtos.PostingContext;
import com.bank.lending.chit.ledger.LedgerService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Raises the instalments of one group, in one transaction under the group lock. Every cycle that is still SCHEDULED
 * and whose due date has been reached is opened (in cycle order) and gets one demand per ticket that owes it, so a
 * missed day is caught up by the next run and a rerun finds nothing to do.
 *
 * <p>Tickets that owe an instalment: ACTIVE, PRIZED, DEFAULTED and TRANSFERRED (a prized ticket keeps paying the full
 * subscription; a defaulted one keeps accruing). Advance money held for the ticket is used first, oldest first, and a
 * declared dividend (ADJUST_NEXT_INSTALMENT) lowers the demand before the advance is applied.
 *
 * <p>The ledger entry for a demand is dated with the run's business date, not the instalment's due date, so books
 * for a closed day are never touched; the demand itself keeps the real due date, from which late fees count.
 */
@Service
public class DemandWriter {

    static final Set<TicketStatus> OWES_INSTALMENT = EnumSet.of(TicketStatus.ACTIVE, TicketStatus.PRIZED, TicketStatus.DEFAULTED, TicketStatus.TRANSFERRED);

    private final GroupService groupService;
    private final CollectionRepository repo;
    private final EnrolmentRepository tickets;
    private final LedgerService ledger;
    private final Audit audit;

    public DemandWriter(GroupService groupService, CollectionRepository repo, EnrolmentRepository tickets, LedgerService ledger,
                        Audit audit) {
        this.groupService = groupService;
        this.repo = repo;
        this.tickets = tickets;
        this.ledger = ledger;
        this.audit = audit;
    }

    @Transactional
    public Map<String, BigDecimal> raise(String groupCode, LocalDate today, String actor) {
        Map<String, BigDecimal> figures = new LinkedHashMap<>();
        figures.put("cyclesOpened", BigDecimal.ZERO);
        figures.put("demandsRaised", BigDecimal.ZERO);
        figures.put("advanceUsed", BigDecimal.ZERO);
        figures.put("dividendApplied", BigDecimal.ZERO);

        GroupRow g = groupService.lock(groupCode);
        if (g.status() != GroupStatus.ACTIVE && g.status() != GroupStatus.CLOSING) {
            return figures;
        }
        List<CycleDue> due = repo.scheduledCyclesDue(g.id(), today);
        if (due.isEmpty()) {
            return figures;
        }
        List<TicketRow> owing = tickets.tickets(g.id(), false).stream().filter(t -> OWES_INSTALMENT.contains(t.status())).toList();
        BigDecimal subscription = Money.scale(g.subscription());

        for (CycleDue cycle : due) {
            Lifecycles.CYCLE.require(CycleStatus.SCHEDULED, CycleStatus.COLLECTION_OPEN);
            repo.openCycleCollection(cycle.id(), today);
            int raised = 0;
            BigDecimal advanceUsed = Money.ZERO;
            BigDecimal dividendApplied = Money.ZERO;
            for (TicketRow t : owing) {
                String ref = "DM-" + cycle.id() + "-" + t.id();
                var demandId = repo.insertDemand(cycle.id(), t.id(), ref, cycle.dueDate(), today, subscription);
                if (demandId.isEmpty()) {
                    continue; // already raised (a rerun after a partial failure elsewhere, or a manual repair)
                }
                long id = demandId.get();
                raised++;
                PostingContext ctx = new PostingContext(g.id(), today, cycle.id(), "DEMAND", ref, actor);
                ledger.post(JournalTemplates.demand("DEMAND:" + id, t.id(), subscription), ctx);

                DemandFigures fig = new DemandFigures(subscription, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO);

                // 1. dividends declared earlier and meant for the next instalment
                BigDecimal adjustment = Money.ZERO;
                for (DividendRow dv : repo.declaredDividendsBefore(t.id(), cycle.cycleNo())) {
                    if (adjustment.add(dv.amount()).compareTo(subscription) > 0) {
                        break; // a dividend is applied whole or not at all; the rest waits for the next instalment
                    }
                    adjustment = adjustment.add(dv.amount());
                    repo.markDividendApplied(dv.id(), id);
                    ledger.post(JournalTemplates.dividendApplied("DIVAPPLY:" + dv.id(), t.id(), dv.amount()),
                            new PostingContext(g.id(), today, cycle.id(), "DIVIDEND", ref, actor));
                    audit.event("DividendApplied", "TICKET", groupCode + "/" + t.ticketNo(),
                            Json.obj("groupCode", groupCode, "ticketNo", t.ticketNo(), "cycleNo", cycle.cycleNo(), "demandRef", ref,
                                    "dividendCycleNo", dv.cycleNo(), "amount", dv.amount()));
                }
                if (adjustment.signum() > 0) {
                    fig = fig.withDividendAdjustment(adjustment);
                    dividendApplied = dividendApplied.add(adjustment);
                }

                // 2. advance money held for the ticket
                List<AdvanceRow> held = repo.openAdvance(t.id());
                if (!held.isEmpty() && fig.subscriptionOutstanding().signum() > 0) {
                    List<Use> uses = AdvancePlanner.consume(
                            held.stream().map(a -> new Slice(a.allocationId(), a.paymentId(), a.available())).toList(),
                            fig.subscriptionOutstanding());
                    for (Use u : uses) {
                        repo.useAdvance(u.allocationId(), u.amount());
                        repo.insertAllocation(u.paymentId(), id, AllocationBucket.CURRENT, u.amount(), true);
                    }
                    BigDecimal used = AdvancePlanner.total(uses);
                    if (used.signum() > 0) {
                        fig = fig.paySubscription(used);
                        advanceUsed = advanceUsed.add(used);
                    }
                }
                if (adjustment.signum() > 0 || fig.subscriptionPaid().signum() > 0) {
                    repo.saveFigures(id, fig);
                }
                audit.event("DemandRaised", "TICKET", groupCode + "/" + t.ticketNo(),
                        Json.obj("groupCode", groupCode, "ticketNo", t.ticketNo(), "customerId", t.customerId(), "cycleNo", cycle.cycleNo(),
                                "demandRef", ref, "dueDate", cycle.dueDate().toString(), "amount", subscription,
                                "dividendAdjustment", adjustment, "paidFromAdvance", fig.subscriptionPaid(),
                                "outstanding", fig.subscriptionOutstanding(), "raisedOn", today.toString()));
            }
            audit.event("CycleCollectionOpened", "GROUP", groupCode,
                    Json.obj("groupCode", groupCode, "cycleNo", cycle.cycleNo(), "dueDate", cycle.dueDate().toString(),
                            "demands", raised, "raisedOn", today.toString()));
            audit.log("CYCLE", groupCode + "#" + cycle.cycleNo(), "COLLECTION_OPENED", actor,
                    raised + " demand(s) of " + subscription.toPlainString() + " raised on " + today);
            figures.merge("cyclesOpened", BigDecimal.ONE, BigDecimal::add);
            figures.merge("demandsRaised", BigDecimal.valueOf(raised), BigDecimal::add);
            figures.merge("advanceUsed", advanceUsed, BigDecimal::add);
            figures.merge("dividendApplied", dividendApplied, BigDecimal::add);
        }
        return figures;
    }
}
