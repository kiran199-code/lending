package com.bank.lending.account.repository;

import com.bank.lending.account.domain.LoanAccount;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;

public interface LoanAccountRepository extends JpaRepository<LoanAccount, Long> {

    Optional<LoanAccount> findByAccountNumber(String accountNumber);

    // Enforces idempotency: a second creation attempt for the same sanction
    // should look this up first rather than relying solely on the DB unique
    // constraint throwing at insert time.
    Optional<LoanAccount> findBySanctionRefId(Long sanctionRefId);

    Page<LoanAccount> findByCustomerId(Long customerId, Pageable pageable);

    // Used by the daily accrual batch job — paged reader over ACTIVE accounts.
    @Query("SELECT a FROM LoanAccount a WHERE a.accountStatus = 'ACTIVE'")
    Page<LoanAccount> findAllActiveForAccrual(Pageable pageable);

    @Query("SELECT a FROM LoanAccount a WHERE a.accountStatus = 'ACTIVE' AND a.rateType = 'FLOATING' " +
           "AND a.benchmarkRateRef = :benchmarkRef")
    Page<LoanAccount> findActiveFloatingByBenchmark(@Param("benchmarkRef") String benchmarkRef, Pageable pageable);

    // Used by the delinquency classification batch — covers ACTIVE plus every
    // NPA/SMA bucket so accounts already delinquent can also be re-evaluated
    // (and upgraded back to ACTIVE if they cure).
    Page<LoanAccount> findByAccountStatusIn(Collection<LoanAccount.AccountStatus> statuses, Pageable pageable);
}
