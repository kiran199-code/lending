package com.bank.lending.schedule.service;

import com.bank.lending.account.domain.LoanAccount;
import com.bank.lending.account.repository.LoanAccountRepository;
import com.bank.lending.schedule.domain.AmortizationScheduleEntry;
import com.bank.lending.schedule.repository.AmortizationScheduleRepository;
import com.bank.lending.schedule.util.ScheduleCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.bank.lending.schedule.domain.AmortizationScheduleEntry.InstallmentStatus.PAID;
import static com.bank.lending.schedule.domain.AmortizationScheduleEntry.InstallmentStatus.PENDING;

/**
 * Generates and regenerates repayment schedules (see {@link ScheduleCalculator} for the schedule types). Each call to
 * regenerateSchedule bumps schedule_version and writes a fresh set of rows for the
 * remaining tenure; prior versions are left untouched for audit (see AmortizationScheduleEntry).
 */
@Service
@RequiredArgsConstructor
public class AmortizationScheduleService {

    private final AmortizationScheduleRepository scheduleRepository;
    private final LoanAccountRepository loanAccountRepository;

    @Transactional
    public BigDecimal generateInitialSchedule(LoanAccount account, BigDecimal principal, LocalDate firstDueDate) {
        int periodMonths = account.getRepaymentFrequency().months();
        return generateSchedule(account, principal, account.getInterestRate(),
                account.getTenureMonths() / periodMonths, firstDueDate, 1, 1,
                account.getMoratoriumMonths() / periodMonths, 0);
    }

    /**
     * Writes a new schedule version for the instalments still to come (after a part-payment or a
     * floating rate reset). {@code remainingInstallments} counts instalments, not months. A
     * moratorium that is still running carries on, and a step-up schedule stays in the same step-up year.
     */
    @Transactional
    public BigDecimal regenerateSchedule(LoanAccount account, BigDecimal outstandingPrincipal, BigDecimal annualRate,
                                          int remainingInstallments, LocalDate nextDueDate, int startInstallmentNumber) {
        int currentVersion = scheduleRepository.findCurrentVersion(account.getAccountId()).orElse(0);
        int periodMonths = account.getRepaymentFrequency().months();

        int remainingMoratorium = 0;
        if (account.getScheduleType() != LoanAccount.ScheduleType.INTEREST_ONLY && currentVersion > 0) {
            List<AmortizationScheduleEntry> unpaid = scheduleRepository
                    .findByAccountIdAndScheduleVersionOrderByInstallmentNumber(account.getAccountId(), currentVersion)
                    .stream().filter(e -> e.getInstallmentStatus() != PAID).toList();
            for (int i = 0; i < unpaid.size() - 1 && unpaid.get(i).getPrincipalComponent().signum() == 0; i++) {
                remainingMoratorium++;
            }
        }
        int totalMoratorium = account.getMoratoriumMonths() / periodMonths;
        int elapsedAmortizing = remainingMoratorium > 0 ? 0 : Math.max(0, startInstallmentNumber - 1 - totalMoratorium);

        return generateSchedule(account, outstandingPrincipal, annualRate, remainingInstallments, nextDueDate,
                currentVersion + 1, startInstallmentNumber, remainingMoratorium, elapsedAmortizing);
    }

    @Transactional(readOnly = true)
    public List<AmortizationScheduleEntry> getCurrentSchedule(Long accountId) {
        int version = scheduleRepository.findCurrentVersion(accountId).orElse(0);
        if (version == 0) {
            return List.of();
        }
        return scheduleRepository.findByAccountIdAndScheduleVersionOrderByInstallmentNumber(accountId, version);
    }

    private BigDecimal generateSchedule(LoanAccount account, BigDecimal principal, BigDecimal annualRatePercent,
                                         int installments, LocalDate firstDueDate, int version, int startInstallmentNumber,
                                         int moratoriumInstallments, int elapsedAmortizing) {
        ScheduleCalculator.Result result = ScheduleCalculator.generate(new ScheduleCalculator.Request(
                account.getScheduleType(), account.getDayCountConvention(), account.getRepaymentFrequency(),
                principal, annualRatePercent, installments, firstDueDate,
                moratoriumInstallments, balloonAmount(account), account.getStepUpPercent(), elapsedAmortizing));

        List<AmortizationScheduleEntry> entries = new ArrayList<>(installments);
        for (ScheduleCalculator.Installment line : result.installments()) {
            entries.add(AmortizationScheduleEntry.builder()
                    .accountId(account.getAccountId())
                    .installmentNumber(startInstallmentNumber + line.number() - 1)
                    .dueDate(line.dueDate())
                    .principalComponent(line.principal())
                    .interestComponent(line.interest())
                    .outstandingBalance(line.closingBalance())
                    .installmentStatus(PENDING)
                    .scheduleVersion(version)
                    .build());
        }
        scheduleRepository.saveAll(entries);

        account.setEmiAmount(result.regularInstallment());
        loanAccountRepository.save(account);

        return result.regularInstallment();
    }

    /** BALLOON accounts: the balloon share of the amount lent (zero for every other schedule type). */
    private static BigDecimal balloonAmount(LoanAccount account) {
        if (account.getScheduleType() != LoanAccount.ScheduleType.BALLOON) {
            return BigDecimal.ZERO;
        }
        BigDecimal lent = account.getDisbursedAmount() != null ? account.getDisbursedAmount() : account.getSanctionedAmount();
        return lent.multiply(account.getBalloonPercent()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }
}
