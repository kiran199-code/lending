package com.bank.lending.payment.service;

import com.bank.lending.demand.domain.PaymentAllocation;
import com.bank.lending.payment.domain.EmiTransaction;

import java.math.BigDecimal;
import java.util.List;

/** A posted payment, where each part of it went, and the advance the account holds afterwards. */
public record PaymentOutcome(EmiTransaction transaction, List<PaymentAllocation> allocations, BigDecimal advanceBalance) {
}
