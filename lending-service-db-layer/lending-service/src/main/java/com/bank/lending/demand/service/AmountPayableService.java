package com.bank.lending.demand.service;

import com.bank.lending.account.domain.LoanAccount;
import com.bank.lending.account.repository.LoanAccountRepository;
import com.bank.lending.businessdate.service.BusinessDateService;
import com.bank.lending.common.exception.ResourceNotFoundException;
import com.bank.lending.demand.domain.LoanDemand;
import com.bank.lending.demand.dto.AllocationLine;
import com.bank.lending.demand.dto.AmountPayableResponse;
import com.bank.lending.demand.dto.PaymentPreviewRequest;
import com.bank.lending.demand.dto.PaymentPreviewResponse;
import com.bank.lending.demand.util.PaymentWaterfall;
import com.bank.lending.payment.domain.EmiTransaction;
import com.bank.lending.schedule.domain.AmortizationScheduleEntry;
import com.bank.lending.schedule.repository.AmortizationScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.bank.lending.schedule.domain.AmortizationScheduleEntry.InstallmentStatus.PAID;

/** Read side of demands: what is payable on a date, and how a payment would be applied. Changes nothing. */
@Service
@RequiredArgsConstructor
public class AmountPayableService {

    private final LoanAccountRepository loanAccountRepository;
    private final AmortizationScheduleRepository scheduleRepository;
    private final DemandService demandService;
    private final PaymentPlanner planner;
    private final BusinessDateService businessDateService;
    private final PenalInterestService penalService;

    @Transactional(readOnly = true)
    public AmountPayableResponse amountPayable(Long accountId, LocalDate asOf) {
        LocalDate date = asOf != null ? asOf : businessDateService.currentDate();
        LoanAccount account = account(accountId);
        List<AmountPayableResponse.DemandLine> lines = demandLines(account, date);

        BigDecimal overdue = BigDecimal.ZERO;
        BigDecimal dueToday = BigDecimal.ZERO;
        for (AmountPayableResponse.DemandLine l : lines) {
            if (l.dueDate().isBefore(date)) {
                overdue = overdue.add(l.outstanding());
            } else {
                dueToday = dueToday.add(l.outstanding());
            }
        }
        BigDecimal penal = lines.stream().map(AmountPayableResponse.DemandLine::penalOutstanding)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal total = overdue.add(dueToday);
        BigDecimal payable = total.subtract(account.getAdvanceBalance()).max(BigDecimal.ZERO);
        // Days past due run from the oldest instalment that still has principal or interest unpaid, not from one that
        // only owes penal interest.
        long dpd = lines.stream()
                .filter(l -> l.principalOutstanding().add(l.interestOutstanding()).signum() > 0)
                .findFirst().map(l -> Math.max(0, ChronoUnit.DAYS.between(l.dueDate(), date))).orElse(0L);

        List<AmortizationScheduleEntry> ahead = demandService.undemandedEntries(accountId, date);
        AmountPayableResponse.NextInstallment next = ahead.isEmpty() ? null : toNext(ahead.get(0));

        return new AmountPayableResponse(accountId, date, account.getAccountStatus().name(), dpd, lines,
                overdue, dueToday, penal, account.getAdvanceBalance(), payable, next);
    }

    @Transactional(readOnly = true)
    public PaymentPreviewResponse preview(Long accountId, PaymentPreviewRequest request) {
        businessDateService.assertNotFuture(request.valueDate(), "valueDate");
        LoanAccount account = account(accountId);
        EmiTransaction.TransactionType type = "PART_PAYMENT".equalsIgnoreCase(request.paymentType())
                ? EmiTransaction.TransactionType.PART_PAYMENT : EmiTransaction.TransactionType.EMI;

        PaymentPlanner.Plan plan = planner.plan(account, request.valueDate(), request.amount(), type,
                duesAsOf(account, request.valueDate()));
        PaymentWaterfall.Result w = plan.waterfall();
        List<AllocationLine> lines = new ArrayList<>(w.lines().stream().map(AllocationLine::from).toList());
        BigDecimal toPrincipal = w.applied(PaymentWaterfall.Component.PRINCIPAL);
        if (plan.surplus().signum() > 0) {
            lines.add(new AllocationLine(null,
                    plan.treatment() == PaymentPlanner.SurplusTreatment.PRINCIPAL ? "PRINCIPAL" : "ADVANCE",
                    plan.surplus()));
            if (plan.treatment() == PaymentPlanner.SurplusTreatment.PRINCIPAL) {
                toPrincipal = toPrincipal.add(plan.surplus());
            }
        }
        return new PaymentPreviewResponse(accountId, request.valueDate(), request.amount(), type.name(), lines,
                w.applied(PaymentWaterfall.Component.PENALTY), w.applied(PaymentWaterfall.Component.INTEREST),
                toPrincipal, plan.surplus(), plan.treatment().name(), plan.allowed(), plan.message());
    }

    /** Open demands plus instalments that are due but not yet raised, oldest first, as waterfall input. */
    private List<PaymentWaterfall.Due> duesAsOf(LoanAccount account, LocalDate date) {
        List<PaymentWaterfall.Due> dues = new ArrayList<>();
        for (AmountPayableResponse.DemandLine l : demandLines(account, date)) {
            dues.add(new PaymentWaterfall.Due(null, l.installmentNumber(), l.dueDate(), l.penalOutstanding(),
                    l.interestOutstanding(), l.principalOutstanding()));
        }
        return dues;
    }

    private List<AmountPayableResponse.DemandLine> demandLines(LoanAccount account, LocalDate date) {
        Long accountId = account.getAccountId();
        List<AmountPayableResponse.DemandLine> lines = new ArrayList<>();
        for (LoanDemand d : demandService.openDemands(accountId)) {
            if (!d.getDueDate().isAfter(date)) {
                // Penal interest is owed for the days before the date: a payment on the date stops that day's charge.
                BigDecimal penal = penalService.penalOutstanding(account, d, date.minusDays(1));
                lines.add(new AmountPayableResponse.DemandLine(d.getInstallmentNumber(), d.getDueDate(),
                        d.getPrincipalDue(), d.getInterestDue(), d.getPrincipalPaid(), d.getInterestPaid(),
                        d.principalOutstanding(), d.interestOutstanding(), penal,
                        d.outstanding().add(penal), d.getStatus().name(), true));
            }
        }
        // Instalments that fell due but whose demand has not been raised yet still count.
        int version = scheduleRepository.findCurrentVersion(accountId).orElse(0);
        if (version > 0) {
            Set<Integer> allDemanded = new HashSet<>(demandService.demandedInstallments(accountId));
            for (AmortizationScheduleEntry e : scheduleRepository
                    .findByAccountIdAndScheduleVersionOrderByInstallmentNumber(accountId, version)) {
                if (e.getDueDate().isAfter(date) || e.getInstallmentStatus() == PAID
                        || allDemanded.contains(e.getInstallmentNumber())) {
                    continue;
                }
                lines.add(new AmountPayableResponse.DemandLine(e.getInstallmentNumber(), e.getDueDate(),
                        e.getPrincipalComponent(), e.getInterestComponent(), BigDecimal.ZERO, BigDecimal.ZERO,
                        e.getPrincipalComponent(), e.getInterestComponent(), BigDecimal.ZERO,
                        e.getPrincipalComponent().add(e.getInterestComponent()), "OPEN", false));
            }
        }
        lines.sort(Comparator.comparing(AmountPayableResponse.DemandLine::installmentNumber));
        return lines;
    }

    private static AmountPayableResponse.NextInstallment toNext(AmortizationScheduleEntry e) {
        return new AmountPayableResponse.NextInstallment(e.getInstallmentNumber(), e.getDueDate(),
                e.getPrincipalComponent(), e.getInterestComponent(),
                e.getPrincipalComponent().add(e.getInterestComponent()));
    }

    private LoanAccount account(Long accountId) {
        return loanAccountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan account " + accountId + " not found"));
    }
}
