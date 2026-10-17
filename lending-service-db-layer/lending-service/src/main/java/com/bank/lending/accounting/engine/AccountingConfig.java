package com.bank.lending.accounting.engine;

import com.bank.lending.accounting.model.Defs.AccountDef;
import com.bank.lending.accounting.model.Defs.EventDef;
import com.bank.lending.accounting.model.Defs.LedgerDef;
import com.bank.lending.accounting.model.Defs.RouteDef;
import com.bank.lending.accounting.model.Defs.TemplateDef;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A consistent, immutable snapshot of the accounting configuration, loaded once and cached by the repository. */
public record AccountingConfig(
        Map<String, EventDef> events,
        Map<String, List<TemplateDef>> templatesByEvent,
        Map<Integer, LedgerDef> ledgers,
        Map<String, AccountDef> accounts,       // key: ledgerId + "/" + code
        List<RouteDef> routes,
        Map<String, List<Integer>> eventLedgers // event code -> ledger ids
) {
    public static String accountKey(int ledgerId, String code) {
        return ledgerId + "/" + code;
    }

    public Optional<AccountDef> account(int ledgerId, String code) {
        return Optional.ofNullable(accounts.get(accountKey(ledgerId, code)));
    }
}
