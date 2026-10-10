package com.bank.lending.account.dto;

import com.bank.lending.account.domain.LoanAccount;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Outcome of a bulk call. One failing item never stops the others: "
        + "look at each entry in results.")
public record BulkOperationResponse(
        int total,
        int succeeded,
        int failed,
        List<ItemResult> results
) {

    public enum Outcome {
        /** A new account was created. */
        CREATED,
        /** An account with this sanctionRefId already existed; it was returned unchanged. */
        ALREADY_EXISTS,
        /** The account was disbursed now and its repayment schedule generated. */
        DISBURSED,
        /** The account was already active; nothing was changed. */
        ALREADY_ACTIVE,
        /** The item was rejected. See message. */
        FAILED
    }

    public record ItemResult(
            @Schema(description = "Position of the item in the request list, starting at 0.") int index,
            Outcome outcome,
            Long accountId,
            String accountNumber,
            LoanAccount.AccountStatus accountStatus,
            @Schema(description = "Reason, when the outcome is FAILED.") String message
    ) {
    }

    public static BulkOperationResponse of(List<ItemResult> results) {
        int failed = (int) results.stream().filter(r -> r.outcome() == Outcome.FAILED).count();
        return new BulkOperationResponse(results.size(), results.size() - failed, failed, results);
    }
}
