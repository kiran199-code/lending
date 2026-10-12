package com.bank.lending.statement;

import com.bank.lending.businessdate.service.BusinessDateService;
import com.bank.lending.account.domain.LoanAccount;
import com.bank.lending.account.service.LoanAccountService;
import com.bank.lending.payment.domain.EmiTransaction;
import com.bank.lending.payment.service.EmiPaymentService;
import com.bank.lending.schedule.domain.AmortizationScheduleEntry;
import com.bank.lending.schedule.repository.AmortizationScheduleRepository;
import com.bank.lending.schedule.service.AmortizationScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Assembles the customer-facing loan statement: account details, the full repayment schedule and
 * the payments received. Read-only; each lookup runs in its own short transaction.
 */
@Service
@RequiredArgsConstructor
public class LoanStatementService {

    private final LoanAccountService loanAccountService;
    private final AmortizationScheduleService scheduleService;
    private final AmortizationScheduleRepository scheduleRepository;
    private final EmiPaymentService emiPaymentService;
    private final LoanStatementPdfRenderer pdfRenderer;
    private final BusinessDateService businessDateService;

    public GeneratedStatement pdfByAccountId(Long accountId) {
        return pdf(loanAccountService.getById(accountId));
    }

    public GeneratedStatement pdfByAccountNumber(String accountNumber) {
        return pdf(loanAccountService.getByAccountNumber(accountNumber));
    }

    private GeneratedStatement pdf(LoanAccount account) {
        LoanStatementData data = assemble(account);
        String safeNumber = account.getAccountNumber().replaceAll("[^A-Za-z0-9_-]", "");
        return new GeneratedStatement("loan-statement-" + safeNumber + ".pdf", pdfRenderer.render(data));
    }

    LoanStatementData assemble(LoanAccount account) {
        Long accountId = account.getAccountId();

        List<AmortizationScheduleEntry> current = scheduleService.getCurrentSchedule(accountId);
        Set<Integer> currentNumbers = current.stream()
                .map(AmortizationScheduleEntry::getInstallmentNumber)
                .collect(Collectors.toSet());

        // A part-payment or rate reset regenerates the schedule under a new version that only covers
        // the installments still to come. Installments paid before that live in older versions, so
        // bring those across (paid ones only) to give the customer the complete picture.
        Map<Integer, AmortizationScheduleEntry> paidEarlier = new TreeMap<>();
        for (AmortizationScheduleEntry e : scheduleRepository.findByAccountIdOrderByScheduleVersionAscInstallmentNumberAsc(accountId)) {
            if (e.getInstallmentStatus() == AmortizationScheduleEntry.InstallmentStatus.PAID
                    && !currentNumbers.contains(e.getInstallmentNumber())) {
                paidEarlier.put(e.getInstallmentNumber(), e);   // newer version wins if a number repeats
            }
        }
        List<AmortizationScheduleEntry> schedule = new ArrayList<>(paidEarlier.values());
        schedule.addAll(current);
        schedule.sort(Comparator.comparing(AmortizationScheduleEntry::getInstallmentNumber));

        // Failed attempts are internal retries, not something the customer was charged for.
        List<EmiTransaction> payments = emiPaymentService.getTransactionHistory(accountId).stream()
                .filter(p -> p.getPaymentStatus() != EmiTransaction.PaymentStatus.FAILED)
                .sorted(Comparator.comparing(EmiTransaction::getValueDate)
                        .thenComparing(EmiTransaction::getTransactionId))
                .toList();

        return new LoanStatementData(account, schedule, payments, businessDateService.currentDate());
    }
}
