package com.bank.lending.payment.service;

import com.bank.lending.businessdate.service.BusinessDateService;
import com.bank.lending.account.domain.LoanAccount;
import com.bank.lending.account.repository.LoanAccountRepository;
import com.bank.lending.common.exception.InvalidAccountStateException;
import com.bank.lending.common.exception.ResourceNotFoundException;
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
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.bank.lending.payment.domain.EmiTransaction.PaymentStatus.SUCCESS;
import static com.bank.lending.payment.domain.EmiTransaction.TransactionType.*;
import static com.bank.lending.schedule.domain.AmortizationScheduleEntry.InstallmentStatus.PAID;
import static com.bank.lending.schedule.domain.AmortizationScheduleEntry.InstallmentStatus.PENDING;

/**
 * Posts money movements against a loan account: scheduled EMIs, ad-hoc part-payments,
 * and foreclosure payoffs. Each mutating method is idempotent on (account_id, idempotency_key),
 * matching the unique index backing EmiTransactionRepository#findByAccountIdAndIdempotencyKey.
 */
@Service
@RequiredArgsConstructor
public class EmiPaymentService {

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

    @Transactional
    public EmiTransaction postEmiPayment(Long accountId, LocalDate valueDate, BigDecimal amount,
                                          EmiTransaction.PaymentChannel channel, String idempotencyKey) {
        Optional<EmiTransaction> existing = emiTransactionRepository.findByAccountIdAndIdempotencyKey(accountId, idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        businessDateService.assertNotFuture(valueDate, "valueDate");

        LoanAccount account = getAccountOrThrow(accountId);
        if (account.getAccountStatus() != LoanAccount.AccountStatus.ACTIVE) {
            throw new InvalidAccountStateException("Account " + accountId + " is not ACTIVE (status=" + account.getAccountStatus() + ")");
        }

        int version = currentVersionOrThrow(accountId);
        AmortizationScheduleEntry installment = pendingInstallments(accountId, version).stream()
                .findFirst()
                .orElseThrow(() -> new InvalidAccountStateException("No pending installment for account " + accountId));

        BigDecimal due = installment.getPrincipalComponent().add(installment.getInterestComponent());
        if (amount.compareTo(due) != 0) {
            throw new InvalidAccountStateException("Amount " + amount + " does not match EMI due " + due
                    + " for installment " + installment.getInstallmentNumber() + " on account " + accountId
                    + "; use postPartPayment for other amounts");
        }

        installment.setInstallmentStatus(PAID);
        scheduleRepository.save(installment);

        EmiTransaction txn = EmiTransaction.builder()
                .valueDate(valueDate)
                .accountId(accountId)
                .installmentNumber(installment.getInstallmentNumber())
                .transactionType(EMI)
                .amount(amount)
                .principalPaid(installment.getPrincipalComponent())
                .interestPaid(installment.getInterestComponent())
                .paymentChannel(channel)
                .paymentStatus(SUCCESS)
                .idempotencyKey(idempotencyKey)
                .build();
        EmiTransaction saved = emiTransactionRepository.save(txn);

        ledgerOutboxService.recordEvent(accountId, LedgerOutboxEntry.EventType.EMI_POSTING, Map.of(
                "installmentNumber", installment.getInstallmentNumber(),
                "principalPaid", installment.getPrincipalComponent(),
                "interestPaid", installment.getInterestComponent(),
                "valueDate", valueDate.toString()
        ));

        if (pendingInstallments(accountId, version).isEmpty()) {
            account.setAccountStatus(LoanAccount.AccountStatus.CLOSED);
            loanAccountRepository.save(account);
        }

        return saved;
    }

    /**
     * Ad-hoc principal prepayment applied against the next pending installment's outstanding
     * balance. Reduces principal and regenerates the schedule (same remaining installment count,
     * recalculated EMI) rather than shortening tenure — adjust here if the product instead wants
     * a tenure reduction with EMI held constant.
     */
    @Transactional
    public EmiTransaction postPartPayment(Long accountId, LocalDate valueDate, BigDecimal amount,
                                           EmiTransaction.PaymentChannel channel, String idempotencyKey) {
        Optional<EmiTransaction> existing = emiTransactionRepository.findByAccountIdAndIdempotencyKey(accountId, idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        businessDateService.assertNotFuture(valueDate, "valueDate");

        LoanAccount account = getAccountOrThrow(accountId);
        if (account.getAccountStatus() != LoanAccount.AccountStatus.ACTIVE) {
            throw new InvalidAccountStateException("Account " + accountId + " is not ACTIVE (status=" + account.getAccountStatus() + ")");
        }

        int version = currentVersionOrThrow(accountId);
        List<AmortizationScheduleEntry> pending = pendingInstallments(accountId, version);
        if (pending.isEmpty()) {
            throw new InvalidAccountStateException("Account " + accountId + " has no pending installments to part-pay against");
        }

        AmortizationScheduleEntry nextPending = pending.get(0);
        BigDecimal currentOutstanding = nextPending.getOutstandingBalance().add(nextPending.getPrincipalComponent());
        BigDecimal newOutstanding = currentOutstanding.subtract(amount);
        if (newOutstanding.compareTo(BigDecimal.ZERO) < 0) {
            throw new InvalidAccountStateException(
                    "Part-payment of " + amount + " exceeds outstanding principal " + currentOutstanding + "; use foreclose instead");
        }

        EmiTransaction txn = EmiTransaction.builder()
                .valueDate(valueDate)
                .accountId(accountId)
                .installmentNumber(null)
                .transactionType(PART_PAYMENT)
                .amount(amount)
                .principalPaid(amount)
                .interestPaid(BigDecimal.ZERO)
                .paymentChannel(channel)
                .paymentStatus(SUCCESS)
                .idempotencyKey(idempotencyKey)
                .build();
        EmiTransaction saved = emiTransactionRepository.save(txn);

        if (newOutstanding.compareTo(BigDecimal.ZERO) == 0) {
            account.setAccountStatus(LoanAccount.AccountStatus.CLOSED);
            loanAccountRepository.save(account);
        } else {
            scheduleService.regenerateSchedule(account, newOutstanding, account.getInterestRate(),
                    pending.size(), nextPending.getDueDate(), nextPending.getInstallmentNumber());
        }

        ledgerOutboxService.recordEvent(accountId, LedgerOutboxEntry.EventType.EMI_POSTING, Map.of(
                "type", "PART_PAYMENT",
                "amount", amount,
                "valueDate", valueDate.toString(),
                "newOutstandingPrincipal", newOutstanding
        ));

        return saved;
    }

    /**
     * Full payoff: outstanding principal (as of the next pending installment) plus interest
     * accrued since the last paid installment's due date, up to the foreclosure date.
     */
    @Transactional
    public EmiTransaction foreclose(Long accountId, LocalDate foreclosureDate,
                                     EmiTransaction.PaymentChannel channel, String idempotencyKey) {
        Optional<EmiTransaction> existing = emiTransactionRepository.findByAccountIdAndIdempotencyKey(accountId, idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        businessDateService.assertNotFuture(foreclosureDate, "foreclosureDate");

        LoanAccount account = getAccountOrThrow(accountId);
        if (NOT_FORECLOSABLE.contains(account.getAccountStatus())) {
            throw new InvalidAccountStateException(
                    "Account " + accountId + " cannot be foreclosed from status " + account.getAccountStatus());
        }

        int version = currentVersionOrThrow(accountId);
        List<AmortizationScheduleEntry> allEntries = scheduleRepository
                .findByAccountIdAndScheduleVersionOrderByInstallmentNumber(accountId, version);

        List<AmortizationScheduleEntry> pending = allEntries.stream()
                .filter(e -> e.getInstallmentStatus() != PAID)
                .sorted(Comparator.comparing(AmortizationScheduleEntry::getInstallmentNumber))
                .toList();

        BigDecimal outstandingPrincipal = pending.isEmpty()
                ? BigDecimal.ZERO
                : pending.get(0).getOutstandingBalance().add(pending.get(0).getPrincipalComponent());

        LocalDate accrualFrom = allEntries.stream()
                .filter(e -> e.getInstallmentStatus() == PAID)
                .max(Comparator.comparing(AmortizationScheduleEntry::getInstallmentNumber))
                .map(e -> e.getDueDate().plusDays(1))
                .orElse(account.getEmiStartDate() != null ? account.getEmiStartDate() : foreclosureDate);

        BigDecimal accruedInterest = interestAccrualRepository
                .findByAccountIdAndBusinessDateBetweenOrderByBusinessDateAsc(accountId, accrualFrom, foreclosureDate)
                .stream()
                .map(a -> a.getAccruedAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal payoffAmount = outstandingPrincipal.add(accruedInterest);

        EmiTransaction txn = EmiTransaction.builder()
                .valueDate(foreclosureDate)
                .accountId(accountId)
                .installmentNumber(null)
                .transactionType(FORECLOSURE)
                .amount(payoffAmount)
                .principalPaid(outstandingPrincipal)
                .interestPaid(accruedInterest)
                .paymentChannel(channel)
                .paymentStatus(SUCCESS)
                .idempotencyKey(idempotencyKey)
                .build();
        EmiTransaction saved = emiTransactionRepository.save(txn);

        account.setAccountStatus(LoanAccount.AccountStatus.FORECLOSED);
        loanAccountRepository.save(account);

        ledgerOutboxService.recordEvent(accountId, LedgerOutboxEntry.EventType.FORECLOSURE, Map.of(
                "payoffAmount", payoffAmount,
                "principal", outstandingPrincipal,
                "interest", accruedInterest,
                "foreclosureDate", foreclosureDate.toString()
        ));

        return saved;
    }

    /** Read accessor for the REST layer — payment/foreclosure history for an account, most recent first. */
    @Transactional(readOnly = true)
    public List<EmiTransaction> getTransactionHistory(Long accountId) {
        return emiTransactionRepository.findByAccountIdOrderByValueDateDesc(accountId);
    }

    private LoanAccount getAccountOrThrow(Long accountId) {
        return loanAccountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan account " + accountId + " not found"));
    }

    private int currentVersionOrThrow(Long accountId) {
        return scheduleRepository.findCurrentVersion(accountId)
                .orElseThrow(() -> new InvalidAccountStateException("No amortization schedule found for account " + accountId));
    }

    private List<AmortizationScheduleEntry> pendingInstallments(Long accountId, int version) {
        return scheduleRepository.findByAccountIdAndScheduleVersionAndInstallmentStatus(accountId, version, PENDING)
                .stream()
                .sorted(Comparator.comparing(AmortizationScheduleEntry::getInstallmentNumber))
                .toList();
    }
}
