package com.bank.lending.payment.domain;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Composite PK matching (transaction_id, value_date) in the partitioned
 * emi_transaction table.
 */
public class EmiTransactionId implements Serializable {

    private Long transactionId;
    private LocalDate valueDate;

    public EmiTransactionId() {}

    public EmiTransactionId(Long transactionId, LocalDate valueDate) {
        this.transactionId = transactionId;
        this.valueDate = valueDate;
    }

    public Long getTransactionId() { return transactionId; }
    public LocalDate getValueDate() { return valueDate; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EmiTransactionId that)) return false;
        return Objects.equals(transactionId, that.transactionId)
                && Objects.equals(valueDate, that.valueDate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(transactionId, valueDate);
    }
}
