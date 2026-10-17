package com.bank.lending.payment.dto;

import com.bank.lending.demand.dto.AllocationLine;
import com.bank.lending.payment.domain.EmiTransaction;
import com.bank.lending.payment.service.PaymentOutcome;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * A payment record. {@code allocations} (where each part of the payment went) and {@code advanceBalance} (money the
 * account holds that matches no demand yet) are filled when a payment is posted and are null in the history list.
 */
public record EmiTransactionResponse(
        Long transactionId,
        LocalDate valueDate,
        Long accountId,
        Integer installmentNumber,
        EmiTransaction.TransactionType transactionType,
        BigDecimal amount,
        BigDecimal principalPaid,
        BigDecimal interestPaid,
        EmiTransaction.PaymentChannel paymentChannel,
        EmiTransaction.PaymentStatus paymentStatus,
        String idempotencyKey,
        OffsetDateTime createdAt,
        List<AllocationLine> allocations,
        BigDecimal advanceBalance
) {
    public static EmiTransactionResponse from(EmiTransaction t) {
        return from(t, null, null);
    }

    public static EmiTransactionResponse from(PaymentOutcome outcome) {
        return from(outcome.transaction(),
                outcome.allocations().stream().map(AllocationLine::from).toList(),
                outcome.advanceBalance());
    }

    private static EmiTransactionResponse from(EmiTransaction t, List<AllocationLine> allocations, BigDecimal advance) {
        return new EmiTransactionResponse(
                t.getTransactionId(), t.getValueDate(), t.getAccountId(), t.getInstallmentNumber(),
                t.getTransactionType(), t.getAmount(), t.getPrincipalPaid(), t.getInterestPaid(),
                t.getPaymentChannel(), t.getPaymentStatus(), t.getIdempotencyKey(), t.getCreatedAt(),
                allocations, advance
        );
    }
}
