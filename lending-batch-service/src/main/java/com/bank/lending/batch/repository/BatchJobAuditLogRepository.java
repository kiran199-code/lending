package com.bank.lending.batch.repository;

import com.bank.lending.batch.domain.BatchJobAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BatchJobAuditLogRepository extends JpaRepository<BatchJobAuditLog, Long> {

    Page<BatchJobAuditLog> findByJobNameOrderByStartTimeDesc(String jobName, Pageable pageable);

    Page<BatchJobAuditLog> findByJobExecutionId(Long jobExecutionId, Pageable pageable);
}
