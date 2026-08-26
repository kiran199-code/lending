package com.bank.lending.batch.core.launch;

import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/** Thin read-only wrapper over JobExplorer backing the admin "last N executions" / "today's status" views. */
@Service
@RequiredArgsConstructor
public class BatchExecutionQueryService {

    private final JobExplorer jobExplorer;

    public List<JobExecution> recentExecutions(String jobName, int limit) {
        List<JobInstance> instances = jobExplorer.getJobInstances(jobName, 0, limit);
        return instances.stream()
                .flatMap(instance -> jobExplorer.getJobExecutions(instance).stream())
                .sorted(Comparator.comparing(JobExecution::getCreateTime, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit)
                .toList();
    }

    public boolean isRunning(String jobName) {
        return !jobExplorer.findRunningJobExecutions(jobName).isEmpty();
    }

    public JobExecution latestExecution(String jobName) {
        List<JobExecution> recent = recentExecutions(jobName, 1);
        return recent.isEmpty() ? null : recent.get(0);
    }
}
