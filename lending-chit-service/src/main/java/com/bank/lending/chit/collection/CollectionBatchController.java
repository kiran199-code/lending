package com.bank.lending.chit.collection;

import com.bank.lending.chit.collection.CollectionDtos.RunSummary;
import com.bank.lending.chit.common.Actor;
import com.bank.lending.chit.platform.BusinessDateSource;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * The three daily jobs, called by lending-batch-service (or by an operator through the admin console). Each is safe
 * to repeat: a second call for the same day changes nothing. {@code businessDate} is optional; when given it must equal
 * lending-service's current business date, otherwise the call is refused (409 BUSINESS_DATE_MISMATCH) so a stale or
 * misconfigured batch can never book a day that is not today.
 */
@RestController
@RequestMapping("/api/v1/chit/batch")
@Tag(name = "Batch", description = "Daily demand run, late-fee run and default check")
public class CollectionBatchController {

    private final CollectionBatchService batch;
    private final BusinessDateSource dates;

    public CollectionBatchController(CollectionBatchService batch, BusinessDateSource dates) {
        this.batch = batch;
        this.dates = dates;
    }

    @PostMapping("/demand-run")
    @Operation(summary = "Open every cycle that has fallen due and raise the instalments, in every collecting group (chit.batch.run)")
    public RunSummary demandRun(@RequestParam(value = "businessDate", required = false) String businessDate,
                                @RequestHeader(value = "X-Actor", required = false) String actor) {
        return batch.raiseDemands(day(businessDate), Actor.orService(actor));
    }

    @PostMapping("/late-fee-run")
    @Operation(summary = "Charge the late fee for every overdue instalment up to the business date (chit.batch.run)")
    public RunSummary lateFeeRun(@RequestParam(value = "businessDate", required = false) String businessDate,
                                 @RequestHeader(value = "X-Actor", required = false) String actor) {
        return batch.accrueLateFees(day(businessDate), Actor.orService(actor));
    }

    @PostMapping("/default-run")
    @Operation(summary = "Flag tickets that have breached the group's default threshold (chit.batch.run)")
    public RunSummary defaultRun(@RequestParam(value = "businessDate", required = false) String businessDate,
                                 @RequestHeader(value = "X-Actor", required = false) String actor) {
        return batch.checkDefaults(day(businessDate), Actor.orService(actor));
    }

    private LocalDate day(String requested) {
        return BusinessDateGuard.resolve(requested, dates.today());
    }
}
