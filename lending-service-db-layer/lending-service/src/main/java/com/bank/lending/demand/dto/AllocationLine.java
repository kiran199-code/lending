package com.bank.lending.demand.dto;

import com.bank.lending.demand.domain.PaymentAllocation;
import com.bank.lending.demand.util.PaymentWaterfall;

import java.math.BigDecimal;

/** One line of how a payment is (or would be) applied. component is PENALTY, INTEREST, PRINCIPAL or ADVANCE. */
public record AllocationLine(Integer installmentNumber, String component, BigDecimal amount) {

    public static AllocationLine from(PaymentAllocation a) {
        return new AllocationLine(a.getInstallmentNumber(), a.getBucket().name(), a.getAmount());
    }

    public static AllocationLine from(PaymentWaterfall.Line l) {
        return new AllocationLine(l.installmentNumber(), l.component().name(), l.amount());
    }
}
