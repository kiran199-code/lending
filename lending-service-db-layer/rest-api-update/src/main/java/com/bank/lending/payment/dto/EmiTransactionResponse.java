package com.bank.lending.payment.dto;

import com.bank.lending.payment.domain.EmiTransaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

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
        OffsetDateTime createdAt
) {
    public static EmiTransactionResponse from(EmiTransaction t) {
        return new EmiTransactionResponse(
                t.getTransactionId(), t.getValueDate(), t.getAccountId(), t.getInstallmentNumber(),
                t.getTransactionType(), t.getAmount(), t.getPrincipalPaid(), t.getInterestPaid(),
                t.getPaymentChannel(), t.getPaymentStatus(), t.getIdempotencyKey(), t.getCreatedAt()
        );
    }
}
