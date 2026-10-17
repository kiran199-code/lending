package com.bank.lending.demand.service;

import com.bank.lending.account.domain.LoanAccount;
import com.bank.lending.account.repository.LoanAccountRepository;
import com.bank.lending.accounting.service.AccountingPoster;
import com.bank.lending.common.exception.ResourceNotFoundException;
import com.bank.lending.demand.domain.LoanDemand;
import com.bank.lending.demand.domain.PaymentAllocation;
import com.bank.lending.demand.repository.LoanDemandRepository;
import com.bank.lending.demand.repository.PaymentAllocationRepository;
import com.bank.lending.demand.util.PaymentWaterfall;
import com.bank.lending.schedule.domain.AmortizationScheduleEntry;
import com.bank.lending.schedule.repository.AmortizationScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.bank.lending.demand.domain.LoanDemand.DemandStatus.CLEARED;
import static com.bank.lending.demand.domain.LoanDemand.DemandStatus.OPEN;
import static com.bank.lending.demand.domain.LoanDemand.DemandStatus.PART_PAID;
import static com.bank.lending.schedule.domain.AmortizationScheduleEntry.InstallmentStatus.PAID;

/**
 * Raises demands (what falls due on each instalment's due date) and applies money to them. A demand is raised once
 * per instalment, so every method that raises is safe to repeat; applying money keeps the demand's paid amounts, its
 * status and the schedule row's PAID flag in step, and writes one allocation line per bucket touched.
 */
@Service
@RequiredArgsConstructor
public class DemandService {

    private final LoanDemandRepository demandRepository;
    private final PaymentAllocationRepository allocationRepository;
    private final AmortizationScheduleRepository scheduleRepository;
    private final LoanAccountRepository loanAccountRepository;
    private final AccountingPoster accountingPoster;

    /**
     * Raises a demand for every instalment of the current schedule that is due on or before {@code upTo} and has none
     * yet, with its real due date (so a run that was missed leaves no gap). Instalments already marked PAID, from
     * before demands existed, get a demand that is already cleared. Any advance the account holds is then applied to
     * the open demands.
     *
     * @return how many demands were created
     */
    @Transactional
    public int raiseDue(LoanAccount account, LocalDate upTo) {
        Long accountId = account.getAccountId();
        int version = scheduleRepository.findCurrentVersion(accountId).orElse(0);
        if (version == 0) {
            return 0;
        }
        Set<Integer> demanded = new HashSet<>(demandRepository.findDemandedInstallmentNumbers(accountId));
        List<LoanDemand> created = new ArrayList<>();
        for (AmortizationScheduleEntry e : scheduleRepository
                .findByAccountIdAndScheduleVersionOrderByInstallmentNumber(accountId, version)) {
            if (e.getDueDate().isAfter(upTo) || demanded.contains(e.getInstallmentNumber())) {
                continue;
            }
            boolean cleared = e.getInstallmentStatus() == PAID
                    || e.getPrincipalComponent().add(e.getInterestComponent()).signum() == 0;
            if (cleared && e.getInstallmentStatus() != PAID) {
                e.setInstallmentStatus(PAID); // nothing to pay on this instalment
                scheduleRepository.save(e);
            }
            created.add(LoanDemand.builder()
                    .accountId(accountId)
                    .scheduleVersion(version)
                    .installmentNumber(e.getInstallmentNumber())
                    .dueDate(e.getDueDate())
                    .principalDue(e.getPrincipalComponent())
                    .interestDue(e.getInterestComponent())
                    .principalPaid(cleared ? e.getPrincipalComponent() : BigDecimal.ZERO)
                    .interestPaid(cleared ? e.getInterestComponent() : BigDecimal.ZERO)
                    .status(cleared ? CLEARED : OPEN)
                    .clearedDate(cleared ? e.getDueDate() : null)
                    .raisedOn(upTo)
                    .build());
        }
        if (!created.isEmpty()) {
            demandRepository.saveAll(created);
            accountingPoster.demandsRaised(account, created);
        }
        if (account.getAdvanceBalance().signum() > 0) {
            applyAdvance(account, upTo);
        }
        return created.size();
    }

    /** Raises the demands due on or before {@code upTo} for one account, in its own transaction. @return how many were created */
    @Transactional
    public int raiseForAccount(Long accountId, LocalDate upTo) {
        LoanAccount account = loanAccountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan account " + accountId + " not found"));
        return raiseDue(account, upTo);
    }

    /** Instalment numbers of the account that already have a demand, cleared or not. */
    @Transactional(readOnly = true)
    public List<Integer> demandedInstallments(Long accountId) {
        return demandRepository.findDemandedInstallmentNumbers(accountId);
    }

    /** Due date of the most recent demand of the account, cleared or not. */
    @Transactional(readOnly = true)
    public java.util.Optional<LocalDate> lastDemandDueDate(Long accountId) {
        return demandRepository.findTopByAccountIdOrderByDueDateDesc(accountId).map(LoanDemand::getDueDate);
    }

    /** Demands that still have something to pay, oldest first. */
    @Transactional(readOnly = true)
    public List<LoanDemand> openDemands(Long accountId) {
        return demandRepository.findByAccountIdAndStatusNotOrderByInstallmentNumberAsc(accountId, CLEARED);
    }

    /**
     * Instalments of the current schedule that have no demand yet and are due after {@code after}, oldest first.
     * Instalments already marked PAID are left out.
     */
    @Transactional(readOnly = true)
    public List<AmortizationScheduleEntry> undemandedEntries(Long accountId, LocalDate after) {
        int version = scheduleRepository.findCurrentVersion(accountId).orElse(0);
        if (version == 0) {
            return List.of();
        }
        Set<Integer> demanded = new HashSet<>(demandRepository.findDemandedInstallmentNumbers(accountId));
        return scheduleRepository.findByAccountIdAndScheduleVersionOrderByInstallmentNumber(accountId, version).stream()
                .filter(e -> e.getInstallmentStatus() != PAID)
                .filter(e -> !demanded.contains(e.getInstallmentNumber()))
                .filter(e -> e.getDueDate().isAfter(after))
                .sorted(Comparator.comparing(AmortizationScheduleEntry::getInstallmentNumber))
                .toList();
    }

    public static List<PaymentWaterfall.Due> toDues(List<LoanDemand> demands) {
        return demands.stream()
                .map(d -> new PaymentWaterfall.Due(d.getDemandId(), d.getInstallmentNumber(), d.getDueDate(),
                        d.penalOutstanding(), d.interestOutstanding(), d.principalOutstanding()))
                .toList();
    }

    /**
     * Applies waterfall lines to the demands they name: raises the paid amounts, updates each demand's status, marks
     * the instalment PAID on the schedule when a demand is cleared, and writes one allocation line per waterfall line.
     * Lines with no demand (principal paid outside a demand) are only recorded.
     */
    @Transactional
    public void applyLines(LoanAccount account, List<LoanDemand> demands, List<PaymentWaterfall.Line> lines,
                           LocalDate valueDate, Long transactionId, PaymentAllocation.Source source) {
        Map<Long, LoanDemand> byId = demands.stream()
                .collect(Collectors.toMap(LoanDemand::getDemandId, Function.identity()));
        Set<LoanDemand> touched = new HashSet<>();
        for (PaymentWaterfall.Line line : lines) {
            LoanDemand demand = line.demandId() == null ? null : byId.get(line.demandId());
            if (demand != null) {
                switch (line.component()) {
                    case INTEREST -> demand.setInterestPaid(demand.getInterestPaid().add(line.amount()));
                    case PRINCIPAL -> demand.setPrincipalPaid(demand.getPrincipalPaid().add(line.amount()));
                    case PENALTY -> demand.setPenalPaid(demand.getPenalPaid().add(line.amount()));
                }
                touched.add(demand);
            }
            record(account, line.demandId(), line.installmentNumber(), transactionId, valueDate,
                    PaymentAllocation.Bucket.valueOf(line.component().name()), source, line.amount());
        }
        for (LoanDemand d : touched) {
            refreshStatus(d, valueDate);
        }
        demandRepository.saveAll(touched);
    }

    /** Writes one allocation line that is not tied to a demand (principal or interest paid outside a demand, or an advance). */
    @Transactional
    public void record(LoanAccount account, Long demandId, Integer installmentNumber, Long transactionId,
                       LocalDate valueDate, PaymentAllocation.Bucket bucket, PaymentAllocation.Source source,
                       BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            return;
        }
        allocationRepository.save(PaymentAllocation.builder()
                .accountId(account.getAccountId())
                .demandId(demandId)
                .installmentNumber(installmentNumber)
                .transactionId(transactionId)
                .valueDate(valueDate)
                .bucket(bucket)
                .source(source)
                .amount(amount)
                .build());
    }

    /** Puts money that matches no demand on the account as an advance, to be applied when the next demand is raised. */
    @Transactional
    public void holdAdvance(LoanAccount account, BigDecimal amount, LocalDate valueDate, Long transactionId) {
        if (amount.signum() <= 0) {
            return;
        }
        account.setAdvanceBalance(account.getAdvanceBalance().add(amount));
        loanAccountRepository.save(account);
        record(account, null, null, transactionId, valueDate, PaymentAllocation.Bucket.ADVANCE,
                PaymentAllocation.Source.PAYMENT, amount);
    }

    private void applyAdvance(LoanAccount account, LocalDate date) {
        List<LoanDemand> open = openDemands(account.getAccountId());
        if (open.isEmpty() || account.getAdvanceBalance().signum() <= 0) {
            return;
        }
        PaymentWaterfall.Result result = PaymentWaterfall.apply(toDues(open), account.getAdvanceBalance());
        applyLines(account, open, result.lines(), date, null, PaymentAllocation.Source.ADVANCE);
        account.setAdvanceBalance(result.surplus());
        loanAccountRepository.save(account);
        BigDecimal penal = applied(result, PaymentWaterfall.Component.PENALTY);
        BigDecimal interest = applied(result, PaymentWaterfall.Component.INTEREST);
        BigDecimal principal = applied(result, PaymentWaterfall.Component.PRINCIPAL);
        if (penal.add(interest).add(principal).signum() > 0) {
            accountingPoster.advanceApplied(account, date, allocationRepository.countByAccountId(account.getAccountId()),
                    penal, interest, principal);
        }
    }

    private static BigDecimal applied(PaymentWaterfall.Result result, PaymentWaterfall.Component component) {
        return result.lines().stream().filter(l -> l.component() == component)
                .map(PaymentWaterfall.Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * A demand's principal and interest decide when its instalment is PAID; the demand itself is CLEARED only when its
     * penal interest has been paid as well, so penal interest left unpaid on a paid-up instalment stays payable.
     */
    private void refreshStatus(LoanDemand d, LocalDate valueDate) {
        boolean principalAndInterestPaid = d.outstanding().signum() == 0;
        if (principalAndInterestPaid && d.getClearedDate() == null) {
            d.setClearedDate(valueDate);
            scheduleRepository.findByAccountIdAndScheduleVersionAndInstallmentNumber(
                    d.getAccountId(), d.getScheduleVersion(), d.getInstallmentNumber()).ifPresent(e -> {
                e.setInstallmentStatus(PAID);
                scheduleRepository.save(e);
            });
        }
        if (principalAndInterestPaid && d.penalOutstanding().signum() == 0) {
            d.setStatus(CLEARED);
        } else if (d.getPrincipalPaid().add(d.getInterestPaid()).add(d.getPenalPaid()).signum() > 0) {
            d.setStatus(PART_PAID);
        } else {
            d.setStatus(OPEN);
        }
    }
}
