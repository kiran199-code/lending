package com.bank.lending.payment.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "emi_transaction")
@IdClass(EmiTransactionId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmiTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "transaction_id")
    private Long transactionId;

    @Id
    @Column(name = "value_date", nullable = false)
    private LocalDate valueDate;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "installment_number")
    private Integer installmentNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 20)
    private TransactionType transactionType;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "principal_paid", precision = 15, scale = 2)
    private BigDecimal principalPaid;

    @Column(name = "interest_paid", precision = 15, scale = 2)
    private BigDecimal interestPaid;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_channel", nullable = false, length = 20)
    private PaymentChannel paymentChannel;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 15)
    private PaymentStatus paymentStatus;

    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public enum TransactionType { EMI, PART_PAYMENT, FORECLOSURE, CHARGE }

    public enum PaymentChannel { NACH, MANUAL, ONLINE }

    public enum PaymentStatus { SUCCESS, FAILED, PENDING }
}
