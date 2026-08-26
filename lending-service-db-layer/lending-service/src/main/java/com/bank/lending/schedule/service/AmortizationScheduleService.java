package com.bank.lending.schedule.service;

import com.bank.lending.account.domain.LoanAccount;
import com.bank.lending.account.repository.LoanAccountRepository;
import com.bank.lending.common.util.FinanceMath;
import com.bank.lending.schedule.domain.AmortizationScheduleEntry;
import com.bank.lending.schedule.repository.AmortizationScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.bank.lending.schedule.domain.AmortizationScheduleEntry.InstallmentStatus.PENDING;

/**
 * Generates and regenerates reducing-balance amortization schedules. Each call to
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
        return generateSchedule(account, principal, account.getInterestRate(), account.getTenureMonths(), firstDueDate, 1, 1);
    }

    @Transactional
    public BigDecimal regenerateSchedule(LoanAccount account, BigDecimal outstandingPrincipal, BigDecimal annualRate,
                                          int remainingInstallments, LocalDate nextDueDate, int startInstallmentNumber) {
        int newVersion = scheduleRepository.findCurrentVersion(account.getAccountId()).orElse(0) + 1;
        return generateSchedule(account, outstandingPrincipal, annualRate, remainingInstallments, nextDueDate, newVersion, startInstallmentNumber);
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
                                         int tenureMonths, LocalDate firstDueDate, int version, int startInstallmentNumber) {
        BigDecimal monthlyRate = FinanceMath.monthlyRate(annualRatePercent);
        BigDecimal emi = FinanceMath.calculateEmi(principal, monthlyRate, tenureMonths);

        BigDecimal outstanding = principal;
        List<AmortizationScheduleEntry> entries = new ArrayList<>(tenureMonths);
        for (int i = 0; i < tenureMonths; i++) {
            boolean isLast = i == tenureMonths - 1;
            BigDecimal interest = outstanding.multiply(monthlyRate).setScale(2, RoundingMode.HALF_UP);
            BigDecimal principalComponent = isLast ? outstanding : emi.subtract(interest);
            if (principalComponent.compareTo(outstanding) > 0) {
                principalComponent = outstanding;
            }
            outstanding = outstanding.subtract(principalComponent).setScale(2, RoundingMode.HALF_UP);

            entries.add(AmortizationScheduleEntry.builder()
                    .accountId(account.getAccountId())
                    .installmentNumber(startInstallmentNumber + i)
                    .dueDate(firstDueDate.plusMonths(i))
                    .principalComponent(principalComponent)
                    .interestComponent(interest)
                    .outstandingBalance(outstanding)
                    .installmentStatus(PENDING)
                    .scheduleVersion(version)
                    .build());
        }
        scheduleRepository.saveAll(entries);

        account.setEmiAmount(emi);
        loanAccountRepository.save(account);

        return emi;
    }
}
