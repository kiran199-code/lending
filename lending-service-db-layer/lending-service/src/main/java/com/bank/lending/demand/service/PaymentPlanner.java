package com.bank.lending.demand.service;

import com.bank.lending.account.domain.LoanAccount;
import com.bank.lending.demand.util.PaymentWaterfall;
import com.bank.lending.payment.domain.EmiTransaction;
import com.bank.lending.schedule.domain.AmortizationScheduleEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Works out, without changing anything, how a payment would be applied and whether it is allowed. The real posting
 * and the preview shown before a payment is confirmed both call it, so what the screen shows is what is posted.
 *
 * <p>A payment first goes through the waterfall over what is due. What is left (the surplus) is then either held as
 * an advance for the next instalment (EMI payment) or paid against principal (part-payment).
 */
@Service
@RequiredArgsConstructor
public class PaymentPlanner {

    public enum SurplusTreatment { ADVANCE, PRINCIPAL }

    public record Plan(PaymentWaterfall.Result waterfall, SurplusTreatment treatment,
                       List<AmortizationScheduleEntry> undemanded, boolean allowed, String message) {
        public BigDecimal surplus() {
            return waterfall.surplus();
        }

        /** The first instalment not yet due: principal still to be repaid as of that instalment. */
        public BigDecimal principalBase() {
            if (undemanded.isEmpty()) {
                return BigDecimal.ZERO;
            }
            AmortizationScheduleEntry next = undemanded.get(0);
            return next.getOutstandingBalance().add(next.getPrincipalComponent());
        }
    }

    private final DemandService demandService;

    /**
     * @param dues what is due as of {@code valueDate}, oldest first (open demands, plus instalments due but not yet raised when previewing)
     * @param type EMI or PART_PAYMENT
     */
    public Plan plan(LoanAccount account, LocalDate valueDate, BigDecimal amount,
                     EmiTransaction.TransactionType type, List<PaymentWaterfall.Due> dues) {
        PaymentWaterfall.Result result = PaymentWaterfall.apply(dues, amount);
        List<AmortizationScheduleEntry> undemanded = demandService.undemandedEntries(account.getAccountId(), valueDate);
        BigDecimal surplus = result.surplus();

        if (type == EmiTransaction.TransactionType.PART_PAYMENT) {
            Plan p = new Plan(result, SurplusTreatment.PRINCIPAL, undemanded, true, null);
            if (surplus.signum() > 0) {
                if (undemanded.isEmpty()) {
                    return new Plan(result, SurplusTreatment.PRINCIPAL, undemanded, false,
                            "Amount " + amount + " is more than what is due (" + result.totalApplied()
                                    + ") and no later instalment is left to prepay; use foreclosure instead");
                }
                if (surplus.compareTo(p.principalBase()) > 0) {
                    return new Plan(result, SurplusTreatment.PRINCIPAL, undemanded, false,
                            "Part-payment of " + surplus + " (after " + result.totalApplied()
                                    + " due) exceeds outstanding principal " + p.principalBase()
                                    + "; use foreclosure instead");
                }
            }
            return p;
        }

        BigDecimal room = undemanded.stream()
                .map(e -> e.getPrincipalComponent().add(e.getInterestComponent()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .subtract(account.getAdvanceBalance());
        if (surplus.compareTo(room) > 0) {
            return new Plan(result, SurplusTreatment.ADVANCE, undemanded, false,
                    "Amount " + amount + " is more than the " + result.totalApplied().add(room.max(BigDecimal.ZERO))
                            + " still payable on this account; use foreclosure to pay it off");
        }
        return new Plan(result, SurplusTreatment.ADVANCE, undemanded, true, null);
    }
}
