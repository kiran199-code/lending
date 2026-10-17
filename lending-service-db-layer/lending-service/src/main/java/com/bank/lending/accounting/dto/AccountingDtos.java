package com.bank.lending.accounting.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Request and response shapes of the accounting API. */
public final class AccountingDtos {

    private AccountingDtos() {
    }

    public record JournalSummary(long journalId, String journalNo, String eventCode, Long accountId, String productCode,
                                 String sourceType, Long sourceId, LocalDate businessDate, LocalDate valueDate,
                                 String txnCurrency, String postingMode, boolean backdated, boolean priorPeriod,
                                 Long reversalOfJournalId, String narrative, Long templateId, Integer templateVersion,
                                 OffsetDateTime createdAt, String branchCode) {
    }

    public record JournalLineView(long lineId, int lineSeq, int ledgerId, String ledgerCode, String glAccountCode,
                                  String roleCode, String side, BigDecimal amountTxn, String currencyTxn,
                                  BigDecimal fxRate, BigDecimal amountBase, String currencyBase, Long ruleId,
                                  String narrative) {
    }

    public record JournalView(JournalSummary header, List<JournalLineView> lines, Long reversedByJournalId) {
    }

    public record TrialBalanceRow(int ledgerId, String ledgerCode, String glAccountCode, String accountName,
                                  String currency, BigDecimal debits, BigDecimal credits, BigDecimal netDebit,
                                  String branchCode) {
    }

    public record TrialBalance(LocalDate asOf, List<TrialBalanceRow> rows, BigDecimal totalDebits,
                               BigDecimal totalCredits, boolean balanced) {
    }

    public record ExceptionRow(long exceptionId, String eventCode, Long accountId, String idempotencyKey,
                               String reason, String errorMessage, boolean eodGate, int attempts, String status,
                               OffsetDateTime createdAt, OffsetDateTime lastAttemptAt, String resolvedBy,
                               String resolvedNote) {
    }

    public record ReconciliationRow(String checkCode, String status, BigDecimal expected, BigDecimal actual,
                                    BigDecimal difference, String detail, LocalDate businessDate,
                                    OffsetDateTime runAt) {
    }

    public record ReconciliationResult(LocalDate businessDate, boolean allPassed, List<ReconciliationRow> checks) {
    }

    public record EventView(String eventCode, String name, String category, String blockingMode, String postingMode,
                            boolean eodGate, String status, List<String> tags, List<Integer> ledgerIds) {
    }

    public record RuleView(long ruleId, int lineSeq, String side, String roleCode, String amountSource,
                           String condition, String ledgerScope, String currencyBasis, String narrative) {
    }

    public record TemplateView(long templateId, String eventCode, String productCode, int version, String status,
                               LocalDate effectiveFrom, LocalDate effectiveTo, String createdBy, String approvedBy,
                               List<RuleView> rules) {
    }

    public record LedgerView(int ledgerId, String ledgerCode, String name, String ledgerType, String purpose,
                             String baseCurrency, String deliveryMode, String status) {
    }

    public record GlAccountView(int ledgerId, String glAccountCode, String name, String nature, String normalBalance,
                                String status, boolean postingAllowed) {
    }

    public record RouteView(long routeId, int ledgerId, String roleCode, String productCode, String npaStage,
                            String currency, String glAccountCode, int priority, LocalDate effectiveFrom,
                            LocalDate effectiveTo, String status, String branchCode) {
    }

    public record RuleInput(Integer lineSeq, String side, String roleCode, String amountSource, String condition,
                            String ledgerScope, String currencyBasis, String narrative) {
    }

    public record TemplateDraftRequest(String eventCode, String productCode, LocalDate effectiveFrom,
                                       LocalDate effectiveTo, String description, List<RuleInput> rules) {
    }

    public record RouteDraftRequest(Integer ledgerId, String roleCode, String productCode, String npaStage,
                                    String currency, String glAccountCode, Integer priority, LocalDate effectiveFrom,
                                    LocalDate effectiveTo, String branchCode) {
    }

    public record SimulationRequest(String eventCode, Long templateId, String productCode, String npaStage,
                                    String currency, LocalDate businessDate, Map<String, BigDecimal> amounts,
                                    Map<String, Object> context, String branchCode) {
    }

    public record SimulatedLine(int lineSeq, String ledgerCode, String glAccountCode, String roleCode, String side,
                                BigDecimal amountTxn, String currencyTxn, BigDecimal fxRate, BigDecimal amountBase,
                                String currencyBase, Long ruleId) {
    }

    public record SimulationResult(String eventCode, Long templateId, Integer templateVersion,
                                   List<SimulatedLine> lines, boolean balanced, String message) {
    }

    public record ValidationResult(boolean valid, List<String> problems) {
    }

    public record PostResultView(String status, Long journalId, String journalNo, String note) {
    }

    public record OpeningBalanceResult(LocalDate businessDate, int accountsPosted, int accountsSkipped,
                                       int accountsFailed) {
    }

    public record GateStatus(long openGateExceptions, long openExceptions) {
    }
}
