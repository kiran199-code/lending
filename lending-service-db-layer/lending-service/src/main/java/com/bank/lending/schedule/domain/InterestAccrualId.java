package com.bank.lending.schedule.domain;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Composite PK matching (accrual_id, business_date) in the partitioned
 * interest_accrual table. Postgres requires the partition key column
 * (business_date) to be part of any primary key on a partitioned table.
 */
public class InterestAccrualId implements Serializable {

    private Long accrualId;
    private LocalDate businessDate;

    public InterestAccrualId() {}

    public InterestAccrualId(Long accrualId, LocalDate businessDate) {
        this.accrualId = accrualId;
        this.businessDate = businessDate;
    }

    public Long getAccrualId() { return accrualId; }
    public LocalDate getBusinessDate() { return businessDate; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof InterestAccrualId that)) return false;
        return Objects.equals(accrualId, that.accrualId)
                && Objects.equals(businessDate, that.businessDate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(accrualId, businessDate);
    }
}
