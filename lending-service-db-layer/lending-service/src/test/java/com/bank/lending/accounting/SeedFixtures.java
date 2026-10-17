package com.bank.lending.accounting;

import com.bank.lending.accounting.engine.AccountingConfig;
import com.bank.lending.accounting.model.Defs.AccountDef;
import com.bank.lending.accounting.model.Defs.EventDef;
import com.bank.lending.accounting.model.Defs.LedgerDef;
import com.bank.lending.accounting.model.Defs.RouteDef;
import com.bank.lending.accounting.model.Defs.RuleDef;
import com.bank.lending.accounting.model.Defs.TemplateDef;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The V17 seed as an in-memory configuration, for tests of the engine. GENERATED from the same definition as
 * V17__accounting_seed.sql (see gen/generate.py in the accounting design notes): do not edit by hand.
 */
final class SeedFixtures {

    private SeedFixtures() {
    }

    static AccountingConfig config() {
        Map<String, EventDef> events = new LinkedHashMap<>();
        Map<String, List<TemplateDef>> templates = new HashMap<>();
        Map<Integer, LedgerDef> ledgers = new LinkedHashMap<>();
        Map<String, AccountDef> accounts = new HashMap<>();
        List<RouteDef> routes = new ArrayList<>();
        Map<String, List<Integer>> eventLedgers = new HashMap<>();
        LocalDate from = LocalDate.of(2000, 1, 1);

        ledgers.put(1, new LedgerDef(1, "MAIN", "INTERNAL", "PRIMARY", "INR", "IMMEDIATE", "ACTIVE"));
        ledgers.put(2, new LedgerDef(2, "MEMO", "INTERNAL", "MEMO", "INR", "IMMEDIATE", "ACTIVE"));
        ledgers.put(3, new LedgerDef(3, "EXT_SAMPLE", "EXTERNAL", "PRIMARY", "INR", "EOD_BATCH", "SUSPENDED"));
        accounts.put(AccountingConfig.accountKey(1, "1100"), new AccountDef(1, "1100", "ASSET", "DR", true, "ACTIVE", null));
        accounts.put(AccountingConfig.accountKey(1, "1110"), new AccountDef(1, "1110", "ASSET", "DR", true, "ACTIVE", null));
        accounts.put(AccountingConfig.accountKey(1, "1120"), new AccountDef(1, "1120", "ASSET", "DR", true, "ACTIVE", null));
        accounts.put(AccountingConfig.accountKey(1, "1130"), new AccountDef(1, "1130", "ASSET", "DR", true, "ACTIVE", null));
        accounts.put(AccountingConfig.accountKey(1, "1140"), new AccountDef(1, "1140", "ASSET", "DR", true, "ACTIVE", null));
        accounts.put(AccountingConfig.accountKey(1, "1150"), new AccountDef(1, "1150", "ASSET", "DR", true, "ACTIVE", null));
        accounts.put(AccountingConfig.accountKey(1, "1190"), new AccountDef(1, "1190", "ASSET", "CR", true, "ACTIVE", null));
        accounts.put(AccountingConfig.accountKey(1, "1200"), new AccountDef(1, "1200", "ASSET", "DR", true, "ACTIVE", null));
        accounts.put(AccountingConfig.accountKey(1, "1210"), new AccountDef(1, "1210", "LIABILITY", "CR", true, "ACTIVE", null));
        accounts.put(AccountingConfig.accountKey(1, "2100"), new AccountDef(1, "2100", "LIABILITY", "CR", true, "ACTIVE", null));
        accounts.put(AccountingConfig.accountKey(1, "2200"), new AccountDef(1, "2200", "LIABILITY", "CR", true, "ACTIVE", null));
        accounts.put(AccountingConfig.accountKey(1, "2210"), new AccountDef(1, "2210", "LIABILITY", "CR", true, "ACTIVE", null));
        accounts.put(AccountingConfig.accountKey(1, "2900"), new AccountDef(1, "2900", "LIABILITY", "CR", true, "ACTIVE", null));
        accounts.put(AccountingConfig.accountKey(1, "2950"), new AccountDef(1, "2950", "LIABILITY", "CR", true, "ACTIVE", null));
        accounts.put(AccountingConfig.accountKey(1, "4100"), new AccountDef(1, "4100", "INCOME", "CR", true, "ACTIVE", null));
        accounts.put(AccountingConfig.accountKey(1, "4110"), new AccountDef(1, "4110", "INCOME", "CR", true, "ACTIVE", null));
        accounts.put(AccountingConfig.accountKey(1, "4120"), new AccountDef(1, "4120", "INCOME", "CR", true, "ACTIVE", null));
        accounts.put(AccountingConfig.accountKey(1, "4130"), new AccountDef(1, "4130", "INCOME", "CR", true, "ACTIVE", null));
        accounts.put(AccountingConfig.accountKey(1, "5100"), new AccountDef(1, "5100", "EXPENSE", "DR", true, "ACTIVE", null));
        accounts.put(AccountingConfig.accountKey(1, "5110"), new AccountDef(1, "5110", "EXPENSE", "DR", true, "ACTIVE", null));
        accounts.put(AccountingConfig.accountKey(1, "5120"), new AccountDef(1, "5120", "EXPENSE", "DR", true, "ACTIVE", null));
        accounts.put(AccountingConfig.accountKey(1, "6100"), new AccountDef(1, "6100", "INCOME", "CR", true, "ACTIVE", null));
        accounts.put(AccountingConfig.accountKey(1, "6110"), new AccountDef(1, "6110", "INCOME", "CR", true, "ACTIVE", null));
        accounts.put(AccountingConfig.accountKey(1, "6900"), new AccountDef(1, "6900", "EXPENSE", "DR", true, "ACTIVE", null));
        routes.add(new RouteDef(1, 1, "LOAN_PRINCIPAL", null, null, null, "1100", 0, from, null));
        routes.add(new RouteDef(2, 1, "PRINCIPAL_DUE", null, null, null, "1110", 0, from, null));
        routes.add(new RouteDef(3, 1, "INT_ACCRUED_RECEIVABLE", null, null, null, "1120", 0, from, null));
        routes.add(new RouteDef(4, 1, "INT_DUE_RECEIVABLE", null, null, null, "1130", 0, from, null));
        routes.add(new RouteDef(5, 1, "PENAL_RECEIVABLE", null, null, null, "1140", 0, from, null));
        routes.add(new RouteDef(6, 1, "FEE_RECEIVABLE", null, null, null, "1150", 0, from, null));
        routes.add(new RouteDef(7, 1, "PROVISION_RESERVE", null, null, null, "1190", 0, from, null));
        routes.add(new RouteDef(8, 1, "CUSTOMER_SETTLEMENT", null, null, null, "1200", 0, from, null));
        routes.add(new RouteDef(9, 1, "DISBURSAL_CLEARING", null, null, null, "1210", 0, from, null));
        routes.add(new RouteDef(10, 1, "ADVANCE_LIABILITY", null, null, null, "2100", 0, from, null));
        routes.add(new RouteDef(11, 1, "INT_SUSPENSE", null, null, null, "2200", 0, from, null));
        routes.add(new RouteDef(12, 1, "PENAL_SUSPENSE", null, null, null, "2210", 0, from, null));
        routes.add(new RouteDef(13, 1, "TECH_SUSPENSE", null, null, null, "2900", 0, from, null));
        routes.add(new RouteDef(14, 1, "OPENING_CONTRA", null, null, null, "2950", 0, from, null));
        routes.add(new RouteDef(15, 1, "INT_INCOME", null, null, null, "4100", 0, from, null));
        routes.add(new RouteDef(16, 1, "PENAL_INCOME", null, null, null, "4110", 0, from, null));
        routes.add(new RouteDef(17, 1, "FEE_INCOME", null, null, null, "4120", 0, from, null));
        routes.add(new RouteDef(18, 1, "RECOVERY_INCOME", null, null, null, "4130", 0, from, null));
        routes.add(new RouteDef(19, 1, "PROVISION_EXPENSE", null, null, null, "5100", 0, from, null));
        routes.add(new RouteDef(20, 1, "WRITEOFF_EXPENSE", null, null, null, "5110", 0, from, null));
        routes.add(new RouteDef(21, 1, "WAIVER_EXPENSE", null, null, null, "5120", 0, from, null));
        routes.add(new RouteDef(22, 1, "FX_REALISED", null, null, null, "6100", 0, from, null));
        routes.add(new RouteDef(23, 1, "FX_UNREALISED", null, null, null, "6110", 0, from, null));
        routes.add(new RouteDef(24, 1, "ROUNDING", null, null, null, "6900", 0, from, null));
        accounts.put(AccountingConfig.accountKey(2, "M100"), new AccountDef(2, "M100", "MEMO", "DR", true, "ACTIVE", null));
        accounts.put(AccountingConfig.accountKey(2, "M900"), new AccountDef(2, "M900", "MEMO", "CR", true, "ACTIVE", null));
        routes.add(new RouteDef(25, 2, "WO_MEMO", null, null, null, "M100", 0, from, null));
        routes.add(new RouteDef(26, 2, "WO_MEMO_CONTRA", null, null, null, "M900", 0, from, null));
        events.put("DISBURSE", new EventDef("DISBURSE", "Loan disbursement", "CORE", "BLOCKING", "LIVE", false, "FAIL", "Disbursement, account {account}", "ACTIVE", "SPOT_MID", Set.of("DISBURSED_AMT", "FEE_DEDUCTED_AMT", "NET_PAYOUT_AMT")));
        eventLedgers.put("DISBURSE", List.of(1));
        {
            List<RuleDef> rules = new ArrayList<>();
            rules.add(new RuleDef(1, 1, "DR", "LOAN_PRINCIPAL", "DISBURSED_AMT", null, null, "TXN", null));
            rules.add(new RuleDef(2, 2, "CR", "DISBURSAL_CLEARING", "NET_PAYOUT_AMT", null, null, "TXN", null));
            rules.add(new RuleDef(3, 3, "CR", "FEE_INCOME", "FEE_DEDUCTED_AMT", null, null, "TXN", null));
            templates.put("DISBURSE", List.of(new TemplateDef(1, "DISBURSE", null, 1, from, null, rules)));
        }
        events.put("INT_ACCRUE", new EventDef("INT_ACCRUE", "Daily interest accrual", "CORE", "NON_BLOCKING", "LIVE", true, "FAIL", "Interest accrual, account {account}, {valueDate}", "ACTIVE", "SPOT_MID", Set.of("INT_ACCRUED_AMT")));
        eventLedgers.put("INT_ACCRUE", List.of(1));
        {
            List<RuleDef> rules = new ArrayList<>();
            rules.add(new RuleDef(4, 1, "DR", "INT_ACCRUED_RECEIVABLE", "INT_ACCRUED_AMT", null, null, "TXN", null));
            rules.add(new RuleDef(5, 2, "CR", "INT_INCOME", "INT_ACCRUED_AMT", "npa_stage NOT IN ('NPA_SUBSTANDARD','NPA_DOUBTFUL','NPA_LOSS')", null, "TXN", null));
            rules.add(new RuleDef(6, 3, "CR", "INT_SUSPENSE", "INT_ACCRUED_AMT", "npa_stage IN ('NPA_SUBSTANDARD','NPA_DOUBTFUL','NPA_LOSS')", null, "TXN", null));
            templates.put("INT_ACCRUE", List.of(new TemplateDef(2, "INT_ACCRUE", null, 1, from, null, rules)));
        }
        events.put("INT_TRUEUP", new EventDef("INT_TRUEUP", "Accrued interest true-up", "CORE", "BLOCKING", "LIVE", false, "FAIL", "Interest true-up, account {account}, {valueDate}", "ACTIVE", "SPOT_MID", Set.of("INT_TRUEUP_AMT", "INT_TRUEDOWN_AMT")));
        eventLedgers.put("INT_TRUEUP", List.of(1));
        {
            List<RuleDef> rules = new ArrayList<>();
            rules.add(new RuleDef(7, 1, "DR", "INT_ACCRUED_RECEIVABLE", "INT_TRUEUP_AMT", null, null, "TXN", null));
            rules.add(new RuleDef(8, 2, "CR", "INT_INCOME", "INT_TRUEUP_AMT", null, null, "TXN", null));
            rules.add(new RuleDef(9, 3, "DR", "INT_INCOME", "INT_TRUEDOWN_AMT", null, null, "TXN", null));
            rules.add(new RuleDef(10, 4, "CR", "INT_ACCRUED_RECEIVABLE", "INT_TRUEDOWN_AMT", null, null, "TXN", null));
            templates.put("INT_TRUEUP", List.of(new TemplateDef(3, "INT_TRUEUP", null, 1, from, null, rules)));
        }
        events.put("DEMAND_RAISE", new EventDef("DEMAND_RAISE", "Demand raised on due date", "CORE", "BLOCKING", "LIVE", true, "FAIL", "Demand raised, account {account}, instalment {qualifier}", "ACTIVE", "SPOT_MID", Set.of("PRINCIPAL_DUE_AMT", "INTEREST_DUE_AMT")));
        eventLedgers.put("DEMAND_RAISE", List.of(1));
        {
            List<RuleDef> rules = new ArrayList<>();
            rules.add(new RuleDef(11, 1, "DR", "PRINCIPAL_DUE", "PRINCIPAL_DUE_AMT", null, null, "TXN", null));
            rules.add(new RuleDef(12, 2, "DR", "INT_DUE_RECEIVABLE", "INTEREST_DUE_AMT", null, null, "TXN", null));
            rules.add(new RuleDef(13, 3, "CR", "LOAN_PRINCIPAL", "PRINCIPAL_DUE_AMT", null, null, "TXN", null));
            rules.add(new RuleDef(14, 4, "CR", "INT_ACCRUED_RECEIVABLE", "INTEREST_DUE_AMT", null, null, "TXN", null));
            templates.put("DEMAND_RAISE", List.of(new TemplateDef(4, "DEMAND_RAISE", null, 1, from, null, rules)));
        }
        events.put("ADVANCE_ADJUST", new EventDef("ADVANCE_ADJUST", "Advance applied to a demand", "CORE", "BLOCKING", "LIVE", false, "FAIL", "Advance applied, account {account}, {valueDate}", "ACTIVE", "SPOT_MID", Set.of("ADVANCE_APPLIED_AMT", "PENAL_PAID", "INTEREST_PAID", "PRINCIPAL_PAID")));
        eventLedgers.put("ADVANCE_ADJUST", List.of(1));
        {
            List<RuleDef> rules = new ArrayList<>();
            rules.add(new RuleDef(15, 1, "DR", "ADVANCE_LIABILITY", "ADVANCE_APPLIED_AMT", null, null, "TXN", null));
            rules.add(new RuleDef(16, 2, "CR", "PENAL_RECEIVABLE", "PENAL_PAID", null, null, "TXN", null));
            rules.add(new RuleDef(17, 3, "CR", "INT_DUE_RECEIVABLE", "INTEREST_PAID", null, null, "TXN", null));
            rules.add(new RuleDef(18, 4, "CR", "PRINCIPAL_DUE", "PRINCIPAL_PAID", null, null, "TXN", null));
            templates.put("ADVANCE_ADJUST", List.of(new TemplateDef(5, "ADVANCE_ADJUST", null, 1, from, null, rules)));
        }
        events.put("PENAL_ACCRUE", new EventDef("PENAL_ACCRUE", "Penal interest accrual", "CORE", "NON_BLOCKING", "LIVE", true, "FAIL", "Penal interest, account {account}, {valueDate}", "ACTIVE", "SPOT_MID", Set.of("PENAL_ACCRUED_AMT")));
        eventLedgers.put("PENAL_ACCRUE", List.of(1));
        {
            List<RuleDef> rules = new ArrayList<>();
            rules.add(new RuleDef(19, 1, "DR", "PENAL_RECEIVABLE", "PENAL_ACCRUED_AMT", null, null, "TXN", null));
            rules.add(new RuleDef(20, 2, "CR", "PENAL_INCOME", "PENAL_ACCRUED_AMT", "npa_stage NOT IN ('NPA_SUBSTANDARD','NPA_DOUBTFUL','NPA_LOSS')", null, "TXN", null));
            rules.add(new RuleDef(21, 3, "CR", "PENAL_SUSPENSE", "PENAL_ACCRUED_AMT", "npa_stage IN ('NPA_SUBSTANDARD','NPA_DOUBTFUL','NPA_LOSS')", null, "TXN", null));
            templates.put("PENAL_ACCRUE", List.of(new TemplateDef(6, "PENAL_ACCRUE", null, 1, from, null, rules)));
        }
        events.put("EMI_PAYMENT", new EventDef("EMI_PAYMENT", "Instalment payment", "CORE", "BLOCKING", "LIVE", false, "FAIL", "Payment, account {account}, {valueDate}", "ACTIVE", "SPOT_MID", Set.of("PAYMENT_AMT", "PENAL_PAID", "INTEREST_PAID", "PRINCIPAL_PAID", "ADVANCE_CREATED")));
        eventLedgers.put("EMI_PAYMENT", List.of(1));
        {
            List<RuleDef> rules = new ArrayList<>();
            rules.add(new RuleDef(22, 1, "DR", "CUSTOMER_SETTLEMENT", "PAYMENT_AMT", null, null, "TXN", null));
            rules.add(new RuleDef(23, 2, "CR", "PENAL_RECEIVABLE", "PENAL_PAID", null, null, "TXN", null));
            rules.add(new RuleDef(24, 3, "CR", "INT_DUE_RECEIVABLE", "INTEREST_PAID", null, null, "TXN", null));
            rules.add(new RuleDef(25, 4, "CR", "PRINCIPAL_DUE", "PRINCIPAL_PAID", null, null, "TXN", null));
            rules.add(new RuleDef(26, 5, "CR", "ADVANCE_LIABILITY", "ADVANCE_CREATED", null, null, "TXN", null));
            templates.put("EMI_PAYMENT", List.of(new TemplateDef(7, "EMI_PAYMENT", null, 1, from, null, rules)));
        }
        events.put("PART_PAYMENT", new EventDef("PART_PAYMENT", "Part-payment", "CORE", "BLOCKING", "LIVE", false, "FAIL", "Part-payment, account {account}, {valueDate}", "ACTIVE", "SPOT_MID", Set.of("PAYMENT_AMT", "PENAL_PAID", "INTEREST_PAID", "PRINCIPAL_PAID", "PREPAID_PRINCIPAL_AMT")));
        eventLedgers.put("PART_PAYMENT", List.of(1));
        {
            List<RuleDef> rules = new ArrayList<>();
            rules.add(new RuleDef(27, 1, "DR", "CUSTOMER_SETTLEMENT", "PAYMENT_AMT", null, null, "TXN", null));
            rules.add(new RuleDef(28, 2, "CR", "PENAL_RECEIVABLE", "PENAL_PAID", null, null, "TXN", null));
            rules.add(new RuleDef(29, 3, "CR", "INT_DUE_RECEIVABLE", "INTEREST_PAID", null, null, "TXN", null));
            rules.add(new RuleDef(30, 4, "CR", "PRINCIPAL_DUE", "PRINCIPAL_PAID", null, null, "TXN", null));
            rules.add(new RuleDef(31, 5, "CR", "LOAN_PRINCIPAL", "PREPAID_PRINCIPAL_AMT", null, null, "TXN", null));
            templates.put("PART_PAYMENT", List.of(new TemplateDef(8, "PART_PAYMENT", null, 1, from, null, rules)));
        }
        events.put("FORECLOSURE", new EventDef("FORECLOSURE", "Foreclosure", "CORE", "BLOCKING", "LIVE", false, "FAIL", "Foreclosure, account {account}, {valueDate}", "ACTIVE", "SPOT_MID", Set.of("PAYMENT_AMT", "ADVANCE_APPLIED_AMT", "PENAL_PAID", "INTEREST_DUE_PAID", "PRINCIPAL_DUE_PAID", "PRINCIPAL_NOT_DUE_PAID", "ACCRUED_INT_PAID", "FORECLOSURE_FEE_AMT")));
        eventLedgers.put("FORECLOSURE", List.of(1));
        {
            List<RuleDef> rules = new ArrayList<>();
            rules.add(new RuleDef(32, 1, "DR", "CUSTOMER_SETTLEMENT", "PAYMENT_AMT", null, null, "TXN", null));
            rules.add(new RuleDef(33, 2, "DR", "ADVANCE_LIABILITY", "ADVANCE_APPLIED_AMT", null, null, "TXN", null));
            rules.add(new RuleDef(34, 3, "CR", "PENAL_RECEIVABLE", "PENAL_PAID", null, null, "TXN", null));
            rules.add(new RuleDef(35, 4, "CR", "INT_DUE_RECEIVABLE", "INTEREST_DUE_PAID", null, null, "TXN", null));
            rules.add(new RuleDef(36, 5, "CR", "PRINCIPAL_DUE", "PRINCIPAL_DUE_PAID", null, null, "TXN", null));
            rules.add(new RuleDef(37, 6, "CR", "LOAN_PRINCIPAL", "PRINCIPAL_NOT_DUE_PAID", null, null, "TXN", null));
            rules.add(new RuleDef(38, 7, "CR", "INT_ACCRUED_RECEIVABLE", "ACCRUED_INT_PAID", null, null, "TXN", null));
            rules.add(new RuleDef(39, 8, "CR", "FEE_INCOME", "FORECLOSURE_FEE_AMT", null, null, "TXN", null));
            templates.put("FORECLOSURE", List.of(new TemplateDef(9, "FORECLOSURE", null, 1, from, null, rules)));
        }
        events.put("FEE_LEVY", new EventDef("FEE_LEVY", "Fee levied", "FEE", "BLOCKING", "LIVE", false, "FAIL", "Fee {qualifier}, account {account}", "ACTIVE", "SPOT_MID", Set.of("FEE_AMT")));
        eventLedgers.put("FEE_LEVY", List.of(1));
        {
            List<RuleDef> rules = new ArrayList<>();
            rules.add(new RuleDef(40, 1, "DR", "FEE_RECEIVABLE", "FEE_AMT", null, null, "TXN", null));
            rules.add(new RuleDef(41, 2, "CR", "FEE_INCOME", "FEE_AMT", null, null, "TXN", null));
            templates.put("FEE_LEVY", List.of(new TemplateDef(10, "FEE_LEVY", null, 1, from, null, rules)));
        }
        events.put("FEE_REVERSAL", new EventDef("FEE_REVERSAL", "Fee reversed", "FEE", "BLOCKING", "LIVE", false, "FAIL", "Fee reversal {qualifier}, account {account}", "ACTIVE", "SPOT_MID", Set.of("FEE_REVERSED_AMT")));
        eventLedgers.put("FEE_REVERSAL", List.of(1));
        {
            List<RuleDef> rules = new ArrayList<>();
            rules.add(new RuleDef(42, 1, "DR", "FEE_INCOME", "FEE_REVERSED_AMT", null, null, "TXN", null));
            rules.add(new RuleDef(43, 2, "CR", "FEE_RECEIVABLE", "FEE_REVERSED_AMT", null, null, "TXN", null));
            templates.put("FEE_REVERSAL", List.of(new TemplateDef(11, "FEE_REVERSAL", null, 1, from, null, rules)));
        }
        events.put("OPENING_BALANCE", new EventDef("OPENING_BALANCE", "Cutover opening balance", "SYSTEM", "BLOCKING", "LIVE", false, "FAIL", "Opening balance, account {account}", "ACTIVE", "SPOT_MID", Set.of("OPENING_PRINCIPAL", "OPENING_PRINCIPAL_DUE", "OPENING_INT_ACCRUED", "OPENING_INT_DUE", "OPENING_PENAL", "OPENING_ADVANCE", "OPENING_NET", "OPENING_NET_NEG")));
        eventLedgers.put("OPENING_BALANCE", List.of(1));
        {
            List<RuleDef> rules = new ArrayList<>();
            rules.add(new RuleDef(44, 1, "DR", "LOAN_PRINCIPAL", "OPENING_PRINCIPAL", null, null, "TXN", null));
            rules.add(new RuleDef(45, 2, "DR", "PRINCIPAL_DUE", "OPENING_PRINCIPAL_DUE", null, null, "TXN", null));
            rules.add(new RuleDef(46, 3, "DR", "INT_ACCRUED_RECEIVABLE", "OPENING_INT_ACCRUED", null, null, "TXN", null));
            rules.add(new RuleDef(47, 4, "DR", "INT_DUE_RECEIVABLE", "OPENING_INT_DUE", null, null, "TXN", null));
            rules.add(new RuleDef(48, 5, "DR", "PENAL_RECEIVABLE", "OPENING_PENAL", null, null, "TXN", null));
            rules.add(new RuleDef(49, 6, "CR", "ADVANCE_LIABILITY", "OPENING_ADVANCE", null, null, "TXN", null));
            rules.add(new RuleDef(50, 7, "CR", "OPENING_CONTRA", "OPENING_NET", null, null, "TXN", null));
            rules.add(new RuleDef(51, 8, "DR", "OPENING_CONTRA", "OPENING_NET_NEG", null, null, "TXN", null));
            templates.put("OPENING_BALANCE", List.of(new TemplateDef(12, "OPENING_BALANCE", null, 1, from, null, rules)));
        }
        return new AccountingConfig(events, templates, ledgers, accounts, routes, eventLedgers);
    }
}
