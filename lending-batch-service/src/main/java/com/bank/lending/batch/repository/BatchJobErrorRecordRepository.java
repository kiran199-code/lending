package com.bank.lending.batch.repository;

import com.bank.lending.batch.domain.BatchJobErrorRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BatchJobErrorRecordRepository extends JpaRepository<BatchJobErrorRecord, Long> {

    Page<BatchJobErrorRecord> findByJobNameOrderByCreatedAtDesc(String jobName, Pageable pageable);

    Page<BatchJobErrorRecord> findByJobExecutionId(Long jobExecutionId, Pageable pageable);

    Page<BatchJobErrorRecord> findByJobNameAndReplayStatusOrderByCreatedAtDesc(
            String jobName, BatchJobErrorRecord.ReplayStatus replayStatus, Pageable pageable);
}
