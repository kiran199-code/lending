package com.bank.lending.demand.service;

import com.bank.lending.account.domain.LoanAccount;
import com.bank.lending.account.repository.LoanAccountRepository;
import com.bank.lending.businessdate.service.BusinessDateService;
import com.bank.lending.demand.dto.DemandRaiseResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static com.bank.lending.account.domain.LoanAccount.AccountStatus.*;

/**
 * The daily demand raise for every account that can owe something. Each account is raised in its own transaction, so
 * one failing account does not stop the rest; the response counts what was created and lists what failed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemandBatchService {

    private static final Set<LoanAccount.AccountStatus> TRACKED = EnumSet.of(
            ACTIVE, NPA_SMA0, NPA_SMA1, NPA_SMA2, NPA_SUBSTANDARD, NPA_DOUBTFUL, NPA_LOSS);
    private static final int PAGE_SIZE = 200;
    private static final int MAX_FAILURES_LISTED = 20;

    private final LoanAccountRepository loanAccountRepository;
    private final DemandService demandService;
    private final BusinessDateService businessDateService;

    // Deliberately not @Transactional: each account commits on its own inside DemandService.raiseForAccount.
    public DemandRaiseResponse raiseAll(LocalDate businessDate) {
        businessDateService.assertNotFuture(businessDate, "businessDate");
        int processed = 0;
        int created = 0;
        List<String> failures = new ArrayList<>();
        int failed = 0;
        int pageNo = 0;
        Page<LoanAccount> page;
        do {
            page = loanAccountRepository.findByAccountStatusIn(TRACKED, PageRequest.of(pageNo, PAGE_SIZE, Sort.by("accountId")));
            for (LoanAccount account : page.getContent()) {
                try {
                    created += demandService.raiseForAccount(account.getAccountId(), businessDate);
                    processed++;
                } catch (RuntimeException e) {
                    failed++;
                    log.warn("Demand raise failed for account {}: {}", account.getAccountId(), e.toString());
                    if (failures.size() < MAX_FAILURES_LISTED) {
                        failures.add(account.getAccountId() + ": " + e.getMessage());
                    }
                }
            }
            pageNo++;
        } while (page.hasNext());
        log.info("Demand raise for {}: {} accounts, {} demands created, {} failed", businessDate, processed, created, failed);
        return new DemandRaiseResponse(businessDate, processed, created, failed, failures);
    }

    public DemandRaiseResponse raiseOne(Long accountId, LocalDate businessDate) {
        businessDateService.assertNotFuture(businessDate, "businessDate");
        int created = demandService.raiseForAccount(accountId, businessDate);
        return new DemandRaiseResponse(businessDate, 1, created, 0, List.of());
    }
}
