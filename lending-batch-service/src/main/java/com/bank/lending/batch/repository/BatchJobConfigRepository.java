package com.bank.lending.batch.repository;

import com.bank.lending.batch.domain.BatchJobConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BatchJobConfigRepository extends JpaRepository<BatchJobConfig, Long> {

    Optional<BatchJobConfig> findByJobName(String jobName);

    List<BatchJobConfig> findByEnabledTrue();

    List<BatchJobConfig> findByJobCategory(BatchJobConfig.JobCategory jobCategory);
}
