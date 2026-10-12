package com.bank.lending.businessdate.dto;

import com.bank.lending.businessdate.domain.BusinessDateHistory;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public record BusinessDateHistoryResponse(
        LocalDate businessDate,
        OffsetDateTime openedAt,
        OffsetDateTime eodStartedAt,
        OffsetDateTime closedAt,
        String closedBy,
        String note
) {
    public static BusinessDateHistoryResponse from(BusinessDateHistory h) {
        return new BusinessDateHistoryResponse(h.getBusinessDate(), h.getOpenedAt(), h.getEodStartedAt(),
                h.getClosedAt(), h.getClosedBy(), h.getNote());
    }
}
