package com.bank.lending.account.service;

import com.bank.lending.account.domain.LoanAccount;
import com.bank.lending.account.dto.BulkDisburseRequest;
import com.bank.lending.account.dto.BulkOperationResponse;
import com.bank.lending.account.dto.BulkOperationResponse.ItemResult;
import com.bank.lending.account.dto.BulkOperationResponse.Outcome;
import com.bank.lending.account.dto.CreateLoanAccountRequest;
import com.bank.lending.account.repository.LoanAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the single-account operations in a loop and reports a result per item.
 *
 * <p>Deliberately NOT {@code @Transactional}: every call into {@link LoanAccountService} commits
 * or rolls back on its own, so one rejected item cannot undo the items that already succeeded.
 * Both operations are idempotent in the underlying service (same sanctionRefId returns the
 * existing account; disbursing an ACTIVE account returns it unchanged), so re-sending a whole
 * batch after a timeout is safe.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoanAccountBulkService {

    private final LoanAccountService loanAccountService;
    private final LoanAccountRepository loanAccountRepository;

    public BulkOperationResponse createAccounts(List<CreateLoanAccountRequest> requests) {
        List<ItemResult> results = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            CreateLoanAccountRequest request = requests.get(i);
            try {
                boolean existed = loanAccountRepository.findBySanctionRefId(request.sanctionRefId()).isPresent();
                LoanAccount account = loanAccountService.createAccount(request);
                results.add(success(i, existed ? Outcome.ALREADY_EXISTS : Outcome.CREATED, account));
            } catch (Exception e) {
                results.add(failure(i, null, e));
            }
        }
        return BulkOperationResponse.of(results);
    }

    public BulkOperationResponse disburseAccounts(BulkDisburseRequest request) {
        List<BulkDisburseRequest.Item> items = request.items();
        List<ItemResult> results = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            BulkDisburseRequest.Item item = items.get(i);
            try {
                LoanAccount before = loanAccountService.getById(item.accountId());

                BigDecimal amount = item.disbursedAmount() != null ? item.disbursedAmount() : before.getSanctionedAmount();
                if (amount.compareTo(before.getSanctionedAmount()) > 0) {
                    results.add(failed(i, item.accountId(), "Disbursed amount " + amount
                            + " exceeds the sanctioned amount " + before.getSanctionedAmount()));
                    continue;
                }
                LocalDate firstEmiDueDate = item.firstEmiDueDate() != null
                        ? item.firstEmiDueDate() : request.defaultFirstEmiDueDate();
                if (firstEmiDueDate == null) {
                    results.add(failed(i, item.accountId(),
                            "No firstEmiDueDate: set it on the item or set defaultFirstEmiDueDate"));
                    continue;
                }

                boolean wasActive = before.getAccountStatus() == LoanAccount.AccountStatus.ACTIVE;
                LoanAccount after = loanAccountService.disburse(item.accountId(), amount, firstEmiDueDate);
                results.add(success(i, wasActive ? Outcome.ALREADY_ACTIVE : Outcome.DISBURSED, after));
            } catch (Exception e) {
                results.add(failure(i, item.accountId(), e));
            }
        }
        return BulkOperationResponse.of(results);
    }

    private static ItemResult success(int index, Outcome outcome, LoanAccount account) {
        return new ItemResult(index, outcome, account.getAccountId(), account.getAccountNumber(),
                account.getAccountStatus(), null);
    }

    private static ItemResult failed(int index, Long accountId, String message) {
        return new ItemResult(index, Outcome.FAILED, accountId, null, null, message);
    }

    private ItemResult failure(int index, Long accountId, Exception e) {
        log.warn("Bulk item {} failed: {}", index, e.toString());
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
        String message = cause.getMessage() != null ? cause.getMessage() : e.getClass().getSimpleName();
        return failed(index, accountId, message.length() > 300 ? message.substring(0, 300) : message);
    }
}
