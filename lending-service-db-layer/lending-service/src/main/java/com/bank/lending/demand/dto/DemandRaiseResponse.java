package com.bank.lending.demand.dto;

import java.time.LocalDate;
import java.util.List;

/** Outcome of raising demands for one account or for every account that can have them. */
public record DemandRaiseResponse(
        LocalDate businessDate,
        int accountsProcessed,
        int demandsCreated,
        int accountsFailed,
        List<String> failures
) {
}
