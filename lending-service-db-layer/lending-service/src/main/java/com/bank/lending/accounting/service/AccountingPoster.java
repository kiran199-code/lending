package com.bank.lending.accounting.service;

import com.bank.lending.account.domain.LoanAccount;
import com.bank.lending.accounting.AccountingSettings;
import com.bank.lending.accounting.engine.AccountingConfig;
import com.bank.lending.accounting.model.Defs.CarryInput;
import com.bank.lending.accounting.model.Defs.LedgerDef;
import com.bank.lending.accounting.model.Defs.PostingRequest;
import com.bank.lending.accounting.repository.AccountingConfigRepository;
import com.bank.lending.accounting.repository.JournalRepository;
import com.bank.lending.accounting.repository.SubledgerRepository;
import com.bank.lending.businessdate.service.BusinessDateService;
import com.bank.lending.demand.domain.LoanDemand;
import com.bank.lending.demand.domain.PenalInterest;
import com.bank.lending.payment.domain.EmiTransaction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What the loan modules call to journal a business event. Each method turns the domain objects it is given into the
 * named amount tags of one accounting event and hands them to {@link AccountingEventService}; which accounts are
 * debited and credited is decided by configuration, not here.
 *
 * <p>Every method must run inside the caller's transaction, so the journal and the business change commit or roll
 * back together. Amounts are non-negative tags; the one place that needs sign judgement is the interest true-up,
 * which is explained on {@link #demandsRaised}.
 */
@Service
public class AccountingPoster {

    private static final String SYSTEM = "SYSTEM";
    private static final String INT_RECEIVABLE = "INT_ACCRUED_RECEIVABLE";

    private final AccountingEventService events;
    private final AccountingSettings settings;
    private final AccountingConfigRepository configRepository;
    private final JournalRepository journals;
    private final SubledgerRepository subledger;
    private final BusinessDateService businessDates;

    public AccountingPoster(AccountingEventService events, AccountingSettings settings,
                            AccountingConfigRepository configRepository, JournalRepository journals,
                            SubledgerRepository subledger, BusinessDateService businessDates) {
        this.events = events;
        this.settings = settings;
        this.configRepository = configRepository;
        this.journals = journals;
        this.subledger = subledger;
        this.businessDates = businessDates;
    }

    // ----------------------------------------------------------------------------------------------- disbursement

    /** Dr principal, Cr disbursal clearing (and fee income if a fee was deducted at source). */
    @Transactional(propagation = Propagation.MANDATORY)
    public PostResult disbursed(LoanAccount account, BigDecimal amount, BigDecimal feeDeducted) {
        if (!settings.enabled()) {
            return PostResult.skipped("accounting is disabled");
        }
        BigDecimal fee = feeDeducted == null ? BigDecimal.ZERO : feeDeducted;
        Map<String, BigDecimal> tags = new LinkedHashMap<>();
        tags.put("DISBURSED_AMT", amount);
        tags.put("FEE_DEDUCTED_AMT", fee);
        tags.put("NET_PAYOUT_AMT", amount.subtract(fee));
        LocalDate today = businessDates.currentDate();
        return events.postSafely(request("DISBURSE", account, "ACCOUNT", account.getAccountId(), null, today, today,
                tags, null));
    }

    // ----------------------------------------------------------------------------------------------- accrual

    /**
     * One day's interest. {@code rawDaily} is the 4-decimal figure stored in the accrual table; the engine posts the
     * change in the rounded running total so the journal never drifts from the sub-ledger.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PostResult interestAccrued(LoanAccount account, LocalDate date, BigDecimal rawDaily) {
        if (!settings.enabled()) {
            return PostResult.skipped("accounting is disabled");
        }
        PostingRequest req = new PostingRequest("INT_ACCRUE", account.getAccountId(), account.getProductCode(),
                "INT_ACCRUAL", account.getAccountId(), date, date.toString(), date, date, account.getCurrencyCode(),
                new LinkedHashMap<>(), context(account),
                List.of(new CarryInput("INT_ACCRUED_AMT", "INT", 0L, rawDaily)), SYSTEM, account.getBranchCode());
        return events.postSafely(req);
    }

    // ----------------------------------------------------------------------------------------------- demands

    /**
     * Journals the demands just raised, oldest first. For each demand whose interest is not already cleared:
     *
     * <ol>
     *   <li>The accrued-interest receivable is trued to what the accrual table says is still accrued after the demand's
     *       due date. Interest on the demand that was never accrued (the daily job missed days) is recognised now as
     *       an INT_TRUEUP; interest accrued beyond what the schedule demands is taken back as a true-down. The effect
     *       is that the receivable never goes negative and always matches the sub-ledger to the paisa.</li>
     *   <li>Principal and interest move from not-due to due (DEMAND_RAISE).</li>
     * </ol>
     *
     * <p>Demands created already cleared (instalments marked paid before demands existed) are skipped: their money
     * moved before the general ledger was switched on and is covered by the opening balance.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void demandsRaised(LoanAccount account, List<LoanDemand> created) {
        if (!settings.enabled() || created.isEmpty()) {
            return;
        }
        LocalDate today = businessDates.currentDate();
        Integer ledgerId = primaryLedgerId();
        for (LoanDemand d : created) {
            if (d.getStatus() == LoanDemand.DemandStatus.CLEARED) {
                continue;
            }
            LocalDate valueDate = d.getDueDate().isAfter(today) ? today : d.getDueDate();
            BigDecimal interest = d.getInterestDue();
            if (ledgerId != null) {
                BigDecimal balance = journals.netDebit(account.getAccountId(), ledgerId, INT_RECEIVABLE);
                BigDecimal target = subledger.accruedAfter(account.getAccountId(), d.getDueDate());
                BigDecimal trueUp = target.add(interest).subtract(balance);
                if (trueUp.signum() != 0) {
                    Map<String, BigDecimal> t = new LinkedHashMap<>();
                    if (trueUp.signum() > 0) {
                        t.put("INT_TRUEUP_AMT", trueUp);
                    } else {
                        t.put("INT_TRUEDOWN_AMT", trueUp.negate());
                    }
                    events.postSafely(request("INT_TRUEUP", account, "DEMAND", d.getDemandId(), null, today, valueDate,
                            t, null));
                }
            }
            Map<String, BigDecimal> tags = new LinkedHashMap<>();
            tags.put("PRINCIPAL_DUE_AMT", d.getPrincipalDue());
            tags.put("INTEREST_DUE_AMT", interest);
            events.postSafely(request("DEMAND_RAISE", account, "DEMAND", d.getDemandId(),
                    String.valueOf(d.getInstallmentNumber()), today, valueDate, tags, null));
        }
    }

    /**
     * An advance applied to open demands. {@code sequence} must be different for every application on the same
     * account and date (the allocation row count serves), so two applications on a day are two journals.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PostResult advanceApplied(LoanAccount account, LocalDate date, long sequence, BigDecimal penal,
                                     BigDecimal interest, BigDecimal principal) {
        if (!settings.enabled()) {
            return PostResult.skipped("accounting is disabled");
        }
        Map<String, BigDecimal> tags = new LinkedHashMap<>();
        tags.put("ADVANCE_APPLIED_AMT", penal.add(interest).add(principal));
        tags.put("PENAL_PAID", penal);
        tags.put("INTEREST_PAID", interest);
        tags.put("PRINCIPAL_PAID", principal);
        return events.postSafely(request("ADVANCE_ADJUST", account, "ADVANCE_APPLY", account.getAccountId(),
                date + "#" + sequence, businessDates.currentDate(), date, tags, null));
    }

    // ----------------------------------------------------------------------------------------------- penal interest

    /**
     * Penal interest charged by one accrual run. Each demand is its own rounding stream, so the receivable of a demand
     * is always {@code round(penal accrued, 2) - penal paid} and settles to zero when it is paid.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PostResult penalAccrued(LoanAccount account, List<PenalInterest> written) {
        if (!settings.enabled() || written.isEmpty()) {
            return PostResult.skipped("nothing charged");
        }
        Map<Long, BigDecimal> perDemand = new TreeMap<>();
        long lastId = 0L;
        LocalDate lastDate = null;
        for (PenalInterest p : written) {
            perDemand.merge(p.getDemandId(), p.getAmount(), BigDecimal::add);
            if (p.getPenalId() != null) {
                lastId = Math.max(lastId, p.getPenalId());
            }
            if (lastDate == null || p.getBusinessDate().isAfter(lastDate)) {
                lastDate = p.getBusinessDate();
            }
        }
        List<CarryInput> carries = new ArrayList<>();
        perDemand.forEach((demandId, raw) -> carries.add(new CarryInput("PENAL_ACCRUED_AMT", "PENAL", demandId, raw)));
        LocalDate today = businessDates.currentDate();
        LocalDate valueDate = lastDate.isAfter(today) ? today : lastDate;
        PostingRequest req = new PostingRequest("PENAL_ACCRUE", account.getAccountId(), account.getProductCode(),
                "PENAL_RUN", account.getAccountId(), valueDate, valueDate + "#" + lastId, today, valueDate,
                account.getCurrencyCode(), new LinkedHashMap<>(), context(account), carries, SYSTEM, account.getBranchCode());
        return events.postSafely(req);
    }

    // ----------------------------------------------------------------------------------------------- payments

    /** An instalment payment: cash in, penal, interest and principal settled, any surplus held as advance. */
    @Transactional(propagation = Propagation.MANDATORY)
    public PostResult emiPaid(LoanAccount account, EmiTransaction txn, BigDecimal penal, BigDecimal interest,
                              BigDecimal principal, BigDecimal advanceCreated) {
        if (!settings.enabled()) {
            return PostResult.skipped("accounting is disabled");
        }
        Map<String, BigDecimal> tags = new LinkedHashMap<>();
        tags.put("PAYMENT_AMT", txn.getAmount());
        tags.put("PENAL_PAID", penal);
        tags.put("INTEREST_PAID", interest);
        tags.put("PRINCIPAL_PAID", principal);
        tags.put("ADVANCE_CREATED", advanceCreated);
        return events.postSafely(request("EMI_PAYMENT", account, "EMI_TXN", txn.getTransactionId(), null,
                businessDates.currentDate(), txn.getValueDate(), tags, null));
    }

    /** A part-payment: what is due is settled first, the rest reduces principal that is not yet due. */
    @Transactional(propagation = Propagation.MANDATORY)
    public PostResult partPaid(LoanAccount account, EmiTransaction txn, BigDecimal penal, BigDecimal interest,
                               BigDecimal principalDue, BigDecimal prepaidPrincipal) {
        if (!settings.enabled()) {
            return PostResult.skipped("accounting is disabled");
        }
        Map<String, BigDecimal> tags = new LinkedHashMap<>();
        tags.put("PAYMENT_AMT", txn.getAmount());
        tags.put("PENAL_PAID", penal);
        tags.put("INTEREST_PAID", interest);
        tags.put("PRINCIPAL_PAID", principalDue);
        tags.put("PREPAID_PRINCIPAL_AMT", prepaidPrincipal);
        return events.postSafely(request("PART_PAYMENT", account, "EMI_TXN", txn.getTransactionId(), null,
                businessDates.currentDate(), txn.getValueDate(), tags, null));
    }

    /**
     * Foreclosure. The accrued interest paid is first trued against the receivable (so the receivable closes to zero
     * even if the accrual table and the journal differ by a paisa, or the daily job missed days), then the payoff is
     * journalled in one entry.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PostResult foreclosed(LoanAccount account, EmiTransaction txn, BigDecimal payoff, BigDecimal advanceUsed,
                                 BigDecimal penal, BigDecimal interestDue, BigDecimal principalDue,
                                 BigDecimal principalNotDue, BigDecimal accruedInterest) {
        if (!settings.enabled()) {
            return PostResult.skipped("accounting is disabled");
        }
        LocalDate today = businessDates.currentDate();
        Integer ledgerId = primaryLedgerId();
        if (ledgerId != null) {
            BigDecimal balance = journals.netDebit(account.getAccountId(), ledgerId, INT_RECEIVABLE);
            BigDecimal trueUp = accruedInterest.subtract(balance);
            if (trueUp.signum() != 0) {
                Map<String, BigDecimal> t = new LinkedHashMap<>();
                t.put(trueUp.signum() > 0 ? "INT_TRUEUP_AMT" : "INT_TRUEDOWN_AMT", trueUp.abs());
                events.postSafely(request("INT_TRUEUP", account, "FORECLOSURE", txn.getTransactionId(), null, today,
                        txn.getValueDate(), t, null));
            }
        }
        Map<String, BigDecimal> tags = new LinkedHashMap<>();
        tags.put("PAYMENT_AMT", payoff);
        tags.put("ADVANCE_APPLIED_AMT", advanceUsed);
        tags.put("PENAL_PAID", penal);
        tags.put("INTEREST_DUE_PAID", interestDue);
        tags.put("PRINCIPAL_DUE_PAID", principalDue);
        tags.put("PRINCIPAL_NOT_DUE_PAID", principalNotDue);
        tags.put("ACCRUED_INT_PAID", accruedInterest);
        return events.postSafely(request("FORECLOSURE", account, "EMI_TXN", txn.getTransactionId(), null, today,
                txn.getValueDate(), tags, null));
    }

    // ----------------------------------------------------------------------------------------------- helpers

    private PostingRequest request(String event, LoanAccount account, String sourceType, Long sourceId,
                                   String qualifier, LocalDate businessDate, LocalDate valueDate,
                                   Map<String, BigDecimal> amounts, List<CarryInput> carries) {
        Map<String, BigDecimal> clean = new LinkedHashMap<>();
        amounts.forEach((k, v) -> clean.put(k, v == null ? BigDecimal.ZERO : v.setScale(2, RoundingMode.HALF_UP)));
        return new PostingRequest(event, account.getAccountId(), account.getProductCode(), sourceType, sourceId,
                valueDate, qualifier, businessDate, valueDate, account.getCurrencyCode(), clean, context(account),
                carries, SYSTEM, account.getBranchCode());
    }

    private static Map<String, Object> context(LoanAccount account) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("npa_stage", account.getAccountStatus() == null ? null : account.getAccountStatus().name());
        ctx.put("product_code", account.getProductCode());
        ctx.put("branch_code", account.getBranchCode());
        ctx.put("currency_code", account.getCurrencyCode());
        return ctx;
    }

    /** The ledger the loan sub-ledger reconciles to: the active internal primary ledger. */
    private Integer primaryLedgerId() {
        AccountingConfig cfg = configRepository.current();
        return cfg.ledgers().values().stream()
                .filter(l -> "INTERNAL".equals(l.ledgerType()) && "PRIMARY".equals(l.purpose()) && "ACTIVE".equals(l.status()))
                .map(LedgerDef::ledgerId).sorted().findFirst().orElse(null);
    }
}
