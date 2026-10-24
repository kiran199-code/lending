package com.bank.lending.chit.collection;

import com.bank.lending.chit.calc.DemandFigures;
import com.bank.lending.chit.calc.LateFeeCalculator;
import com.bank.lending.chit.collection.CollectionModels.DemandRow;
import com.bank.lending.chit.common.Audit;
import com.bank.lending.chit.common.Json;
import com.bank.lending.chit.common.Money;
import com.bank.lending.chit.domain.GroupStatus;
import com.bank.lending.chit.domain.rules.ChitRules;
import com.bank.lending.chit.domain.rules.LateFeeRule;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Charges late fees for one group, one day at a time, in one transaction under the group lock.
 *
 * <p>Every overdue open demand is walked from the day after its last charged day (or its first chargeable day) up to
 * the business date, so a skipped run is made good by the next one and a repeated run charges nothing: the
 * (demand, day) pair is unique in the database and the ledger posting key carries the same pair.
 * {@link LateFeeCalculator#accrualForDay} decides each day's amount from the subscription still unpaid; for a day that
 * is caught up late, today's unpaid amount is used (payments made in between are not replayed day by day), which is
 * the one approximation in this job. Postings carry the run's business date, never an earlier one.
 */
@Service
public class LateFeeWriter {

    private final GroupService groupService;
    private final CollectionRepository repo;
    private final EnrolmentRepository tickets;
    private final LedgerService ledger;
    private final Audit audit;

    public LateFeeWriter(GroupService groupService, CollectionRepository repo, EnrolmentRepository tickets, LedgerService ledger, Audit audit) {
        this.groupService = groupService;
        this.repo = repo;
        this.tickets = tickets;
        this.ledger = ledger;
        this.audit = audit;
    }

    @Transactional
    public Map<String, BigDecimal> accrue(String groupCode, LocalDate today, String actor) {
        Map<String, BigDecimal> figures = new LinkedHashMap<>();
        figures.put("lateFeesPosted", BigDecimal.ZERO);
        figures.put("lateFeeTotal", Money.ZERO);

        GroupRow g = groupService.lock(groupCode);
        if (g.status() != GroupStatus.ACTIVE && g.status() != GroupStatus.CLOSING) {
            return figures;
        }
        ChitRules rules = groupService.rulesOf(g);
        LateFeeRule rule = rules.lateFeeRule();
        if (rule == null || rule.kind() == LateFeeRule.Kind.NONE) {
            return figures;
        }
        int grace = rules.graceDays() == null ? 0 : rules.graceDays();
        Map<Long, TicketRow> ticketById = new HashMap<>();
        tickets.tickets(g.id(), true).forEach(t -> ticketById.put(t.id(), t));

        for (DemandRow d : repo.overdueOpenDemands(g.id(), today)) {
            DemandFigures f = d.figures();
            Optional<LocalDate> last = repo.lastLateFeeDate(d.id());
            LocalDate day = last.map(l -> l.plusDays(1)).orElse(d.dueDate().plusDays(grace + 1L));
            int posted = 0;
            BigDecimal total = Money.ZERO;
            for (; !day.isAfter(today); day = day.plusDays(1)) {
                BigDecimal fee = LateFeeCalculator.accrualForDay(rule, f.subscriptionOutstanding(), d.dueDate(), day, grace, f.lateFeeAccrued());
                if (fee.signum() <= 0) {
                    continue;
                }
                if (!repo.insertLateFee(d.id(), day, fee)) {
                    continue; // that day was already charged
                }
                f = f.accrueLateFee(fee);
                ledger.post(JournalTemplates.lateFee("LATEFEE:" + d.id() + ":" + day, d.ticketId(), fee),
                        new PostingContext(g.id(), today, d.cycleId(), "LATE_FEE", d.demandRef(), actor));
                posted++;
                total = total.add(fee);
            }
            if (posted > 0) {
                repo.saveFigures(d.id(), f);
                TicketRow t = ticketById.get(d.ticketId());
                audit.event("LateFeeAccrued", "TICKET", groupCode + "/" + t.ticketNo(),
                        Json.obj("groupCode", groupCode, "ticketNo", t.ticketNo(), "customerId", t.customerId(), "demandRef", d.demandRef(),
                                "days", posted, "amount", total, "totalAccrued", f.lateFeeAccrued(), "businessDate", today.toString()));
                figures.merge("lateFeesPosted", BigDecimal.valueOf(posted), BigDecimal::add);
                figures.merge("lateFeeTotal", total, BigDecimal::add);
            }
        }
        if (figures.get("lateFeesPosted").signum() > 0) {
            audit.log("GROUP", groupCode, "LATE_FEES_ACCRUED", actor,
                    figures.get("lateFeesPosted").toPlainString() + " daily fee(s) totalling " + figures.get("lateFeeTotal").toPlainString() + " for " + today);
        }
        return figures;
    }
}
