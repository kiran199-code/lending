package com.bank.lending.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Response shapes of the admin API. Plain records: the admin service never writes. */
public final class AdminDtos {

    private AdminDtos() {
    }

    public record PageResponse<T>(List<T> items, int page, int size, long total, int totalPages) {
        public static <T> PageResponse<T> of(List<T> items, int page, int size, long total) {
            int pages = size <= 0 ? 0 : (int) ((total + size - 1) / size);
            return new PageResponse<>(items, page, size, total, pages);
        }
    }

    // ---------------------------------------------------------------- accounts

    public record AccountSummary(Long accountId, String accountNumber, Long customerId, Long sanctionRefId,
                                 String productCode, String accountType, String accountStatus,
                                 BigDecimal sanctionedAmount, BigDecimal disbursedAmount, BigDecimal interestRate,
                                 Integer tenureMonths, BigDecimal emiAmount, LocalDate nextDueDate,
                                 int daysPastDue, OffsetDateTime createdAt, String currencyCode,
                                 String branchCode, String limitFacilityRef) {
    }

    public record AccountDetail(Long accountId, String accountNumber, Long customerId, Long sanctionRefId,
                                String productCode, String accountType, String accountStatus,
                                BigDecimal sanctionedAmount, BigDecimal disbursedAmount, Integer tenureMonths,
                                String rateType, BigDecimal interestRate, String benchmarkRateRef, BigDecimal spread,
                                String dayCountConvention, BigDecimal emiAmount, LocalDate emiStartDate,
                                Integer version, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                                Figures figures, String currencyCode, String branchCode, String limitFacilityRef) {
    }

    /** Numbers derived from the schedule, payments and accruals of one account. */
    public record Figures(int installmentsTotal, int installmentsPaid, LocalDate nextDueDate,
                          BigDecimal nextEmiAmount, int overdueInstallments, BigDecimal overdueAmount,
                          int daysPastDue, BigDecimal outstandingPrincipal, BigDecimal totalPaid,
                          BigDecimal totalAccrued, LocalDate lastAccrualDate) {
        public Figures withAccrual(BigDecimal accrued, LocalDate lastDate) {
            return new Figures(installmentsTotal, installmentsPaid, nextDueDate, nextEmiAmount, overdueInstallments,
                    overdueAmount, daysPastDue, outstandingPrincipal, totalPaid, accrued, lastDate);
        }
    }

    // ---------------------------------------------------------------- schedule

    public record ScheduleRow(Integer installmentNumber, LocalDate dueDate, BigDecimal principal, BigDecimal interest,
                              BigDecimal emi, BigDecimal outstandingBalance, String status, Integer scheduleVersion,
                              int daysPastDue) {
    }

    public record ScheduleVersion(int version, int installments, OffsetDateTime generatedAt) {
    }

    public record ScheduleTotals(BigDecimal totalPrincipal, BigDecimal totalInterest, BigDecimal totalEmi,
                                 BigDecimal paidEmi, BigDecimal pendingEmi, BigDecimal overdueEmi,
                                 int paid, int pending, int overdue) {
    }

    /** selectedVersion is null for the combined view (every instalment at its latest version). */
    public record ScheduleResponse(Long accountId, Integer selectedVersion, List<ScheduleVersion> versions,
                                   List<ScheduleRow> rows, ScheduleTotals totals) {
    }

    // ---------------------------------------------------------------- accruals and payments

    public record AccrualRow(LocalDate businessDate, Long accountId, String accountNumber,
                             BigDecimal accruedAmount, BigDecimal outstandingPrincipal) {
    }

    public record AccrualResponse(List<AccrualRow> rows, BigDecimal total, int days) {
    }

    public record PaymentRow(Long transactionId, LocalDate valueDate, Long accountId, String accountNumber,
                             Integer installmentNumber, String type, BigDecimal amount, BigDecimal principalPaid,
                             BigDecimal interestPaid, BigDecimal penalInterestPaid, String channel, String status,
                             String idempotencyKey) {
    }

    public record PaymentResponse(List<PaymentRow> rows, BigDecimal totalReceived, BigDecimal principalReceived,
                                  BigDecimal interestReceived, int failed) {
    }

    public record DailyAmount(LocalDate date, BigDecimal amount, long count) {
    }

    // ---------------------------------------------------------------- portfolio views

    public record UpcomingPaymentRow(Long accountId, String accountNumber, Long customerId, String productCode,
                                     String accountStatus, Integer installmentNumber, LocalDate dueDate,
                                     BigDecimal principal, BigDecimal interest, BigDecimal emi, int daysToDue) {
    }

    public record UpcomingResponse(PageResponse<UpcomingPaymentRow> page, BigDecimal totalAmount, LocalDate from,
                                   LocalDate to) {
    }

    public record OverdueRow(Long accountId, String accountNumber, Long customerId, String productCode,
                             String accountStatus, Integer installmentNumber, LocalDate dueDate,
                             BigDecimal principal, BigDecimal interest, BigDecimal emi, int daysPastDue) {
    }

    public record BucketSummary(String bucket, long accounts, BigDecimal amount) {
    }

    public record StatusCount(String status, long accounts, BigDecimal sanctioned, BigDecimal disbursed) {
    }

    public record Dashboard(LocalDate asOf, long totalAccounts, long activeAccounts, BigDecimal totalDisbursed,
                            BigDecimal outstandingPrincipal, long overdueAccounts, BigDecimal overdueAmount,
                            long dueNext7Count, BigDecimal dueNext7Amount, BigDecimal collectionsMtd,
                            long collectionsMtdCount, LocalDate lastAccrualDate, BigDecimal lastAccrualAmount,
                            BigDecimal accrualMtd, long outboxPending, long outboxFailed,
                            List<StatusCount> byStatus, List<DailyAmount> accrualSeries,
                            List<DailyAmount> collectionSeries) {
    }

    // ---------------------------------------------------------------- operations

    public record OutboxRow(Long outboxId, Long accountId, String accountNumber, String eventType,
                            String postingStatus, int retryCount, OffsetDateTime lastAttemptedAt,
                            OffsetDateTime createdAt) {
    }

    public record OutboxResponse(PageResponse<OutboxRow> page, Map<String, Long> counts) {
    }

    public record BatchJobRow(String jobName, String category, String type, String cron, boolean enabled,
                              Integer priority, Integer timeoutMinutes) {
    }

    public record BatchExecutionRow(Long executionId, String jobName, String status, String exitCode,
                                    LocalDateTime startTime, LocalDateTime endTime) {
    }

    public record BatchOverview(List<BatchJobRow> jobs, List<BatchExecutionRow> recentExecutions, String note) {
    }

    // ---------------------------------------------------------------- configuration

    /** One row of batch.batch_job_config (all editable settings plus the read-only descriptors). */
    public record BatchJobConfigRow(long configId, String jobName, String category, String type, String cron,
                                    boolean enabled, boolean restartable, Integer chunkSize, Integer concurrency,
                                    Integer throttleLimit, int retryLimit, int skipLimit, int priority,
                                    Integer timeoutMinutes, int version, String retryableExceptions,
                                    String skippableExceptions, String jobParams, OffsetDateTime updatedAt) {
    }

    /** Partial edit; a null field is left unchanged. Same shape as lending-batch-service's request. */
    public record BatchJobConfigUpdate(Boolean enabled, String cronExpression, Boolean restartable,
                                       Integer chunkSize, Integer concurrency, Integer throttleLimit,
                                       Integer retryLimit, Integer skipLimit, Integer priority,
                                       Integer timeoutMinutes, Integer expectedVersion) {
    }

    public record BatchConfigResponse(List<BatchJobConfigRow> jobs, boolean editable, String note) {
    }

    public record ApplyResult(BatchJobConfigRow job, boolean applied, String message) {
    }

    public record SettingRow(String name, String envVar, String value, String defaultValue, String sandboxValue,
                             String description, boolean secret, boolean restartRequired) {
    }

    public record SettingGroup(String title, List<SettingRow> settings) {
    }

    public record ServiceSettings(String service, String description, String changeVia, boolean liveValues,
                                  List<SettingGroup> groups) {
    }

    public record FixedRule(String area, String rule, String value) {
    }

    public record ConfigOverview(List<ServiceSettings> services, List<FixedRule> fixedRules, boolean batchEditable) {
    }

    /** One loan product with the number of accounts opened on it. Null min/max means "no limit". */
    public record ProductRow(Long productId, String productCode, String productName, String description,
                             String accountType, String rateType, String scheduleType, String dayCountConvention,
                             String benchmarkRateRef, BigDecimal defaultInterestRate, BigDecimal defaultSpread,
                             BigDecimal minInterestRate, BigDecimal maxInterestRate,
                             BigDecimal minSpread, BigDecimal maxSpread,
                             BigDecimal minSanctionedAmount, BigDecimal maxSanctionedAmount,
                             Integer minTenureMonths, Integer maxTenureMonths,
                             String repaymentFrequency, List<String> allowedRepaymentFrequencies,
                             int moratoriumMonths, Integer maxMoratoriumMonths,
                             BigDecimal balloonPercent, BigDecimal maxBalloonPercent,
                             BigDecimal stepUpPercent, BigDecimal maxStepUpPercent,
                             boolean penalInterestEnabled, BigDecimal penalInterestRate, int penalGraceDays,
                             BigDecimal penalCapPercent,
                             String status, long accountCount, int version) {
    }

    /** Product list plus whether editing is switched on (needs LENDING_SERVICE_URL). */
    public record ProductsResponse(List<ProductRow> products, boolean editable, String note) {
    }

    /** Create / replace body for a product; sent on to lending-service, which validates it. */
    public record ProductInput(String productCode, String productName, String description,
                               String accountType, String rateType, String scheduleType, String dayCountConvention,
                               String benchmarkRateRef, BigDecimal defaultInterestRate, BigDecimal defaultSpread,
                               BigDecimal minInterestRate, BigDecimal maxInterestRate,
                               BigDecimal minSpread, BigDecimal maxSpread,
                               BigDecimal minSanctionedAmount, BigDecimal maxSanctionedAmount,
                               Integer minTenureMonths, Integer maxTenureMonths,
                               String repaymentFrequency, List<String> allowedRepaymentFrequencies,
                               Integer moratoriumMonths, Integer maxMoratoriumMonths,
                               BigDecimal balloonPercent, BigDecimal maxBalloonPercent,
                               BigDecimal stepUpPercent, BigDecimal maxStepUpPercent,
                               Boolean penalInterestEnabled, BigDecimal penalInterestRate, Integer penalGraceDays,
                               BigDecimal penalCapPercent,
                               String status, Integer expectedVersion) {
    }
}
