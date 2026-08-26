package com.bank.lending.schedule.repository;

import com.bank.lending.schedule.domain.InterestAccrual;
import com.bank.lending.schedule.domain.InterestAccrualId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface InterestAccrualRepository extends JpaRepository<InterestAccrual, InterestAccrualId> {

    Optional<InterestAccrual> findByAccountIdAndBusinessDate(Long accountId, LocalDate businessDate);

    // Used by foreclosure payoff calculation to sum accrued-but-unposted interest
    // between the last paid installment's due date and the foreclosure date.
    java.util.List<InterestAccrual> findByAccountIdAndBusinessDateBetweenOrderByBusinessDateAsc(
            Long accountId, LocalDate from, LocalDate to);

    // Idempotent insert for the daily accrual batch — mirrors the
    // UNIQUE(account_id, business_date) constraint in V5. Native query used
    // because Spring Data JPA has no portable "upsert"/ON CONFLICT abstraction.
    @Modifying
    @Query(value = """
            INSERT INTO interest_accrual (account_id, business_date, accrued_amount, outstanding_principal)
            VALUES (:accountId, :businessDate, :accruedAmount, :outstandingPrincipal)
            ON CONFLICT (account_id, business_date) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("accountId") Long accountId,
                        @Param("businessDate") LocalDate businessDate,
                        @Param("accruedAmount") java.math.BigDecimal accruedAmount,
                        @Param("outstandingPrincipal") java.math.BigDecimal outstandingPrincipal);
}
