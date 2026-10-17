package com.bank.lending.payment.service;

import com.bank.lending.account.domain.LoanAccount;
import com.bank.lending.account.repository.LoanAccountRepository;
import com.bank.lending.account.service.LoanAccountService;
import com.bank.lending.businessdate.service.BusinessDateService;
import com.bank.lending.common.exception.InvalidAccountStateException;
import com.bank.lending.common.exception.ResourceNotFoundException;
import com.bank.lending.demand.domain.LoanDemand;
import com.bank.lending.demand.domain.PaymentAllocation;
import com.bank.lending.demand.repository.PaymentAllocationRepository;
import com.bank.lending.demand.service.DemandService;
import com.bank.lending.demand.service.PaymentPlanner;
import com.bank.lending.demand.util.PaymentWaterfall;
import com.bank.lending.ledger.domain.LedgerOutboxEntry;
import com.bank.lending.ledger.service.LedgerOutboxService;
import com.bank.lending.payment.domain.EmiTransaction;
import com.bank.lending.payment.repository.EmiTransactionRepository;
import com.bank.lending.schedule.domain.AmortizationScheduleEntry;
import com.bank.lending.schedule.repository.AmortizationScheduleRepository;
import com.bank.lending.schedule.repository.InterestAccrualRepository;
import com.bank.lending.schedule.service.AmortizationScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static com.bank.lending.demand.util.PaymentWaterfall.Component.INTEREST;
import static com.bank.lending.demand.util.PaymentWaterfall.Component.PRINCIPAL;
import static com.bank.lending.payment.domain.EmiTransaction.PaymentStatus.SUCCESS;
import static com.bank.lending.payment.domain.EmiTransaction.TransactionType.*;
import static com.bank.lending.schedule.domain.AmortizationScheduleEntry.InstallmentStatus.PAID;

/**
 * Posts money movements against a loan account: scheduled EMIs, ad-hoc part-payments,
 * and foreclosure payoffs. Each mutating method is idempotent on (account_id, idempotency_key),
 * matching the unique index backing EmiTransactionRepository#findByAccountIdAndIdempotencyKey.
 *
 * <p>Money is applied to what is owed, not to a fixed instalment: any amount is accepted and goes through
 * {@link PaymentWaterfall} over the account's open demands, oldest first, interest before principal. Demands that
 * fell due but were not yet raised are raised first, so a payment never depends on the daily job having run. A
 * payment is accepted on an ACTIVE or an NPA account, which is how arrears get paid.
 */
@Service
@RequiredArgsConstructor
public class EmiPaymentService {

    private static final Set<LoanAccount.AccountStatus> PAYABLE = EnumSet.of(
            LoanAccount.AccountStatus.ACTIVE, LoanAccount.AccountStatus.NPA_SMA0, LoanAccount.AccountStatus.NPA_SMA1,
            LoanAccount.AccountStatus.NPA_SMA2, LoanAccount.AccountStatus.NPA_SUBSTANDARD,
            LoanAccount.AccountStatus.NPA_DOUBTFUL, LoanAccount.AccountStatus.NPA_LOSS);

    private static final Set<LoanAccount.AccountStatus> NOT_FORECLOSABLE = EnumSet.of(
            LoanAccount.AccountStatus.PENDING_DISBURSEMENT, LoanAccount.AccountStatus.CLOSED,
            LoanAccount.AccountStatus.FORECLOSED, LoanAccount.AccountStatus.WRITTEN_OFF);

    private final LoanAccountRepository loanAccountRepository;
    private final AmortizationScheduleRepository scheduleRepository;
    private final AmortizationScheduleService scheduleService;
    private final EmiTransactionRepository emiTransactionRepository;
    private final InterestAccrualRepository interestAccrualRepository;
    private final LedgerOutboxService ledgerOutboxService;
    private final BusinessDateService businessDateService;
    private final DemandService demandService;
    private final PaymentPlanner planner;
    private final PaymentAllocationRepository allocationRepository;
    private final LoanAccountService loanAccountService;

    /**
     * Applies any positive amount to what is due, oldest first. Money left after everything due is paid is held as an
     * advance and applied when the next instalment falls due.
     */
    @Transactional
    public PaymentOutcome postEmiPayment(Long accountId, LocalDate valueDate, BigDecimal amount,
                                          EmiTransaction.PaymentChannel channel, String idempotencyKey) {
        Optional<EmiTransaction> existing = emiTransactionRepository.findByAccountIdAndIdempotencyKey(accountId, idempotencyKey);
        if (existing.isPresent()) {
            return outcomeOf(existing.get());
        }
        businessDateService.assertNotFuture(valueDate, "valueDate");

        LoanAccount account = getAccountOrThrow(accountId);
        requirePayable(account);

        demandService.raiseDue(account, valueDate);
        List<LoanDemand> open = demandService.openDemands(accountId);
        PaymentPlanner.Plan plan = planner.plan(account, valueDate, amount, EMI, DemandService.toDues(open));
        if (!plan.allowed()) {
            throw new InvalidAccountStateException(plan.message());
        }
        PaymentWaterfall.Result w = plan.waterfall();

        EmiTransaction txn = EmiTransaction.builder()
                .valueDate(valueDate)
                .accountId(accountId)
                .installmentNumber(firstInstallment(w))
                .transactionType(EMI)
                .amount(amount)
                .principalPaid(w.applied(PRINCIPAL))
                .interestPaid(w.applied(INTEREST))
                .paymentChannel(channel)
                .paymentStatus(SUCCESS)
                .idempotencyKey(idempotencyKey)
                .build();
        EmiTransaction saved = saveNew(txn);

        demandService.applyLines(account, open, w.lines(), valueDate, saved.getTransactionId(), PaymentAllocation.Source.PAYMENT);
        demandService.holdAdvance(account, plan.surplus(), valueDate, saved.getTransactionId());

        Integer instalment = firstInstallment(w);
        ledgerOutboxService.recordEvent(accountId, LedgerOutboxEntry.EventType.EMI_POSTING, Map.of(
                "installmentNumber", instalment == null ? 0 : instalment,
                "principalPaid", w.applied(PRINCIPAL),
                "interestPaid", w.applied(INTEREST),
                "valueDate", valueDate.toString()
        ));

        finish(account);
        return outcome(saved, account);
    }

    /**
     * Ad-hoc principal prepayment. The amount first pays what is due (the waterfall); only the rest reduces
     * principal, and only then is the schedule regenerated: same remaining instalment count, recalculated EMI,
     * for the instalments not yet due. Adjust here if the product instead wants a tenure reduction with EMI held
     * constant.
     */
    @Transactional
    public PaymentOutcome postPartPayment(Long accountId, LocalDate valueDate, BigDecimal amount,
                                           EmiTransaction.PaymentChannel channel, String idempotencyKey) {
        Optional<EmiTransaction> existing = emiTransactionRepository.findByAccountIdAndIdempotencyKey(accountId, idempotencyKey);
        if (existing.isPresent()) {
            return outcomeOf(existing.get());
        }
        businessDateService.assertNotFuture(valueDate, "valueDate");

        LoanAccount account = getAccountOrThrow(accountId);
        requirePayable(account);

        demandService.raiseDue(account, valueDate);
        List<LoanDemand> open = demandService.openDemands(accountId);
        PaymentPlanner.Plan plan = planner.plan(account, valueDate, amount, PART_PAYMENT, DemandService.toDues(open));
        if (!plan.allowed()) {
            throw new InvalidAccountStateException(plan.message());
        }
        PaymentWaterfall.Result w = plan.waterfall();
        BigDecimal toPrincipal = plan.surplus();

        EmiTransaction txn = EmiTransaction.builder()
                .valueDate(valueDate)
                .accountId(accountId)
                .installmentNumber(null)
                .transactionType(PART_PAYMENT)
                .amount(amount)
                .principalPaid(w.applied(PRINCIPAL).add(toPrincipal))
                .interestPaid(w.applied(INTEREST))
                .paymentChannel(channel)
                .paymentStatus(SUCCESS)
                .idempotencyKey(idempotencyKey)
                .build();
        EmiTransaction saved = saveNew(txn);

        demandService.applyLines(account, open, w.lines(), valueDate, saved.getTransactionId(), PaymentAllocation.Source.PAYMENT);

        BigDecimal newOutstanding = plan.principalBase();
        if (toPrincipal.signum() > 0) {
            demandService.record(account, null, null, saved.getTransactionId(), valueDate,
                    PaymentAllocation.Bucket.PRINCIPAL, PaymentAllocation.Source.PAYMENT, toPrincipal);
            AmortizationScheduleEntry next = plan.undemanded().get(0);
            newOutstanding = plan.principalBase().subtract(toPrincipal);
            if (newOutstanding.signum() == 0) {
                account.setAccountStatus(LoanAccount.AccountStatus.CLOSED);
                loanAccountRepository.save(account);
            } else {
                scheduleService.regenerateSchedule(account, newOutstanding, account.getInterestRate(),
                        plan.undemanded().size(), next.getDueDate(), next.getInstallmentNumber());
            }
        }

        ledgerOutboxService.recordEvent(accountId, LedgerOutboxEntry.EventType.EMI_POSTING, Map.of(
                "type", "PART_PAYMENT",
                "amount", amount,
                "valueDate", valueDate.toString(),
                "newOutstandingPrincipal", newOutstanding
        ));

        finish(account);
        return outcome(saved, account);
    }

    /**
     * Full payoff: everything still owed on raised demands, the principal of instalments not yet due, and the
     * interest accrued since the last demand's due date up to the foreclosure date; less any advance the account
     * holds. Open demands are cleared and the account becomes FORECLOSED.
     */
    @Transactional
    public PaymentOutcome foreclose(Long accountId, LocalDate foreclosureDate,
                                     EmiTransaction.PaymentChannel channel, String idempotencyKey) {
        Optional<EmiTransaction> existing = emiTransactionRepository.findByAccountIdAndIdempotencyKey(accountId, idempotencyKey);
        if (existing.isPresent()) {
            return outcomeOf(existing.get());
        }
        businessDateService.assertNotFuture(foreclosureDate, "foreclosureDate");

        LoanAccount account = getAccountOrThrow(accountId);
        if (NOT_FORECLOSABLE.contains(account.getAccountStatus())) {
            throw new InvalidAccountStateException(
                    "Account " + accountId + " cannot be foreclosed from status " + account.getAccountStatus());
        }
        currentVersionOrThrow(accountId);

        demandService.raiseDue(account, foreclosureDate);
        List<LoanDemand> open = demandService.openDemands(accountId);
        BigDecimal openPrincipal = open.stream().map(LoanDemand::principalOutstanding).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal openInterest = open.stream().map(LoanDemand::interestOutstanding).reduce(BigDecimal.ZERO, BigDecimal::add);

        List<AmortizationScheduleEntry> ahead = demandService.undemandedEntries(accountId, foreclosureDate);
        BigDecimal undemandedPrincipal = ahead.isEmpty()
                ? BigDecimal.ZERO
                : ahead.get(0).getOutstandingBalance().add(ahead.get(0).getPrincipalComponent());

        LocalDate accrualFrom = demandService.lastDemandDueDate(accountId)
                .map(d -> d.plusDays(1))
                .orElse(account.getEmiStartDate() != null ? account.getEmiStartDate() : foreclosureDate);
        BigDecimal accruedInterest = interestAccrualRepository
                .findByAccountIdAndBusinessDateBetweenOrderByBusinessDateAsc(accountId, accrualFrom, foreclosureDate)
                .stream()
                .map(a -> a.getAccruedAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal principalPaid = openPrincipal.add(undemandedPrincipal);
        BigDecimal interestPaid = openInterest.add(accruedInterest);
        BigDecimal owed = principalPaid.add(interestPaid);
        BigDecimal advanceUsed = account.getAdvanceBalance().min(owed);
        BigDecimal payoffAmount = owed.subtract(advanceUsed);
        if (payoffAmount.signum() <= 0) {
            throw new InvalidAccountStateException("Nothing is left to pay on account " + accountId
                    + " as of " + foreclosureDate + " after its advance of " + account.getAdvanceBalance()
                    + " is applied; contact operations to close it");
        }

        EmiTransaction txn = EmiTransaction.builder()
                .valueDate(foreclosureDate)
                .accountId(accountId)
                .installmentNumber(null)
                .transactionType(FORECLOSURE)
                .amount(payoffAmount)
                .principalPaid(principalPaid)
                .interestPaid(interestPaid)
                .paymentChannel(channel)
                .paymentStatus(SUCCESS)
                .idempotencyKey(idempotencyKey)
                .build();
        EmiTransaction saved = saveNew(txn);
        Long txnId = saved.getTransactionId();

        if (!open.isEmpty() && openPrincipal.add(openInterest).signum() > 0) {
            PaymentWaterfall.Result w = PaymentWaterfall.apply(DemandService.toDues(open), openPrincipal.add(openInterest));
            demandService.applyLines(account, open, w.lines(), foreclosureDate, txnId, PaymentAllocation.Source.PAYMENT);
        }
        demandService.record(account, null, null, txnId, foreclosureDate,
                PaymentAllocation.Bucket.PRINCIPAL, PaymentAllocation.Source.PAYMENT, undemandedPrincipal);
        demandService.record(account, null, null, txnId, foreclosureDate,
                PaymentAllocation.Bucket.INTEREST, PaymentAllocation.Source.PAYMENT, accruedInterest);
        if (advanceUsed.signum() > 0) {
            demandService.record(account, null, null, txnId, foreclosureDate,
                    PaymentAllocation.Bucket.ADVANCE, PaymentAllocation.Source.ADVANCE, advanceUsed);
            account.setAdvanceBalance(account.getAdvanceBalance().subtract(advanceUsed));
        }

        account.setAccountStatus(LoanAccount.AccountStatus.FORECLOSED);
        loanAccountRepository.save(account);

        ledgerOutboxService.recordEvent(accountId, LedgerOutboxEntry.EventType.FORECLOSURE, Map.of(
                "payoffAmount", payoffAmount,
                "principal", principalPaid,
                "interest", interestPaid,
                "foreclosureDate", foreclosureDate.toString()
        ));

        return outcome(saved, account);
    }

    /** Payment/foreclosure history for an account, most recent first. */
    @Transactional(readOnly = true)
    public List<EmiTransaction> getTransactionHistory(Long accountId) {
        return emiTransactionRepository.findByAccountIdOrderByValueDateDesc(accountId);
    }

    /** Gives a new payment record its transaction id from the table's sequence, then saves it. */
    private EmiTransaction saveNew(EmiTransaction txn) {
        txn.setTransactionId(emiTransactionRepository.nextTransactionId());
        return emiTransactionRepository.save(txn);
    }

    /**
     * Saves the account, closes it when every instalment is paid, and otherwise re-derives its status and days past
     * due from the schedule as of the current business date (so clearing arrears lowers or removes the delinquency
     * status straight away, not at the next classification run).
     */
    private void finish(LoanAccount account) {
        loanAccountRepository.save(account);
        closeIfFullyPaid(account);
        if (account.getAccountStatus() != LoanAccount.AccountStatus.CLOSED) {
            loanAccountService.classifyOne(account.getAccountId(), businessDateService.currentDate());
        }
    }

    private void closeIfFullyPaid(LoanAccount account) {
        Long accountId = account.getAccountId();
        int version = scheduleRepository.findCurrentVersion(accountId).orElse(0);
        if (version == 0 || !PAYABLE.contains(account.getAccountStatus())) {
            return;
        }
        List<AmortizationScheduleEntry> entries = scheduleRepository
                .findByAccountIdAndScheduleVersionOrderByInstallmentNumber(accountId, version);
        if (!entries.isEmpty() && entries.stream().allMatch(e -> e.getInstallmentStatus() == PAID)
                && demandService.openDemands(accountId).isEmpty()) {
            account.setAccountStatus(LoanAccount.AccountStatus.CLOSED);
            loanAccountRepository.save(account);
        }
    }

    private PaymentOutcome outcomeOf(EmiTransaction txn) {
        return outcome(txn, getAccountOrThrow(txn.getAccountId()));
    }

    private PaymentOutcome outcome(EmiTransaction txn, LoanAccount account) {
        List<PaymentAllocation> lines = allocationRepository
                .findByTransactionIdAndValueDateOrderByAllocationIdAsc(txn.getTransactionId(), txn.getValueDate());
        return new PaymentOutcome(txn, lines, account.getAdvanceBalance());
    }

    private static Integer firstInstallment(PaymentWaterfall.Result w) {
        return w.lines().stream().map(PaymentWaterfall.Line::installmentNumber)
                .filter(Objects::nonNull).min(Integer::compare).orElse(null);
    }

    private void requirePayable(LoanAccount account) {
        if (!PAYABLE.contains(account.getAccountStatus())) {
            throw new InvalidAccountStateException("Account " + account.getAccountId()
                    + " cannot take a payment in status " + account.getAccountStatus());
        }
    }

    private LoanAccount getAccountOrThrow(Long accountId) {
        return loanAccountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan account " + accountId + " not found"));
    }

    private int currentVersionOrThrow(Long accountId) {
        return scheduleRepository.findCurrentVersion(accountId)
                .orElseThrow(() -> new InvalidAccountStateException("No amortization schedule found for account " + accountId));
    }
}
