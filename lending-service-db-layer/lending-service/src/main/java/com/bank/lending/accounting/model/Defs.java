package com.bank.lending.accounting.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable configuration and request types of the accounting engine. */
public final class Defs {

    private Defs() {
    }

    public static final String DR = "DR";
    public static final String CR = "CR";

    public record EventDef(String eventCode, String name, String category, String blockingMode, String postingMode,
                           boolean eodGate, String onMissingTemplate, String narrativePattern, String status,
                           String fxRateType, Set<String> tags) {
        public boolean blocking() {
            return !"NON_BLOCKING".equals(blockingMode);
        }

        public boolean active() {
            return "ACTIVE".equals(status);
        }
    }

    public record RuleDef(long ruleId, int lineSeq, String side, String roleCode, String amountSource, String condition,
                          String ledgerScope, String currencyBasis, String narrative) {
    }

    public record TemplateDef(long templateId, String eventCode, String productCode, int version,
                              LocalDate effectiveFrom, LocalDate effectiveTo, List<RuleDef> rules) {
        public boolean effectiveOn(LocalDate d) {
            return !d.isBefore(effectiveFrom) && (effectiveTo == null || !d.isAfter(effectiveTo));
        }
    }

    public record LedgerDef(int ledgerId, String ledgerCode, String ledgerType, String purpose, String baseCurrency,
                            String deliveryMode, String status) {
        public boolean memo() {
            return "MEMO".equals(purpose);
        }
    }

    public record AccountDef(int ledgerId, String code, String nature, String normalBalance, boolean postingAllowed,
                             String status, String currencyRestriction) {
        public boolean acceptsCurrency(String ccy) {
            if (currencyRestriction == null || currencyRestriction.isBlank()) {
                return true;
            }
            for (String c : currencyRestriction.split(",")) {
                if (c.trim().equalsIgnoreCase(ccy)) {
                    return true;
                }
            }
            return false;
        }
    }

    public record RouteDef(long routeId, int ledgerId, String roleCode, String productCode, String npaStage,
                           String currency, String glAccountCode, int priority, LocalDate effectiveFrom,
                           LocalDate effectiveTo, String branchCode) {
        /** A route that applies to every branch. */
        public RouteDef(long routeId, int ledgerId, String roleCode, String productCode, String npaStage,
                        String currency, String glAccountCode, int priority, LocalDate effectiveFrom,
                        LocalDate effectiveTo) {
            this(routeId, ledgerId, roleCode, productCode, npaStage, currency, glAccountCode, priority, effectiveFrom,
                    effectiveTo, null);
        }

        public boolean effectiveOn(LocalDate d) {
            return (effectiveFrom == null || !d.isBefore(effectiveFrom)) && (effectiveTo == null || !d.isAfter(effectiveTo));
        }
    }

    /** One amount that is accrued at 4 dp and posted as the change in its rounded cumulative (see RoundingCarry). */
    public record CarryInput(String tag, String stream, long refId, BigDecimal raw) {
    }

    public record PostingRequest(String eventCode, Long accountId, String productCode, String sourceType, Long sourceId,
                                 LocalDate sourceDate, String qualifier, LocalDate businessDate, LocalDate valueDate,
                                 String currency, Map<String, BigDecimal> amounts, Map<String, Object> context,
                                 List<CarryInput> carries, String createdBy, String branchCode) {
        /** Branch every request is posted to when none is given: the single branch of a fresh install. */
        public static final String DEFAULT_BRANCH = "ONLINE";

        /** Request for the default branch (tests and the simulator). Production postings pass the account's branch. */
        public PostingRequest(String eventCode, Long accountId, String productCode, String sourceType, Long sourceId,
                              LocalDate sourceDate, String qualifier, LocalDate businessDate, LocalDate valueDate,
                              String currency, Map<String, BigDecimal> amounts, Map<String, Object> context,
                              List<CarryInput> carries, String createdBy) {
            this(eventCode, accountId, productCode, sourceType, sourceId, sourceDate, qualifier, businessDate, valueDate,
                    currency, amounts, context, carries, createdBy, DEFAULT_BRANCH);
        }

        public String idempotencyKey() {
            return eventCode + "|" + sourceType + "|" + sourceId + "|" + (qualifier == null ? "" : qualifier);
        }
    }

    public record DraftLine(int lineSeq, int ledgerId, String ledgerCode, String glAccountCode, String roleCode,
                            String side, BigDecimal amountTxn, String currencyTxn, BigDecimal fxRate, String fxRateType,
                            BigDecimal amountBase, String currencyBase, long ruleId, String narrative,
                            boolean rateStale) {
    }

    public record DraftJournal(String eventCode, long templateId, int templateVersion, String txnCurrency,
                               String baseCurrency, List<DraftLine> lines, String narrative) {
    }
}
