package com.bank.lending.businessdate.repository;

import com.bank.lending.businessdate.domain.BusinessDateState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface BusinessDateStateRepository extends JpaRepository<BusinessDateState, Short> {

    /** Row lock for state transitions, so two EOD runners (or two instances) cannot both advance the date. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM BusinessDateState s WHERE s.id = 1")
    Optional<BusinessDateState> findForUpdate();
}
