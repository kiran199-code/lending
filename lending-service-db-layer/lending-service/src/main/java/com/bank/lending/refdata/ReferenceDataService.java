package com.bank.lending.refdata;

import com.bank.lending.accounting.model.ConfigConflictException;
import com.bank.lending.common.exception.ResourceNotFoundException;
import com.bank.lending.refdata.ReferenceDtos.BranchRequest;
import com.bank.lending.refdata.ReferenceDtos.BranchView;
import com.bank.lending.refdata.ReferenceDtos.CurrencyRequest;
import com.bank.lending.refdata.ReferenceDtos.CurrencyView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Currencies and branches, and the rule that every loan account carries one of each.
 *
 * <p>Fresh install: currency INR (the base currency) and branch ONLINE, both active. More are added with an insert
 * (the reference API) and need no release. An account takes its currency and branch when it is created and keeps
 * them; every accounting entry for the account is then posted in that currency and to that branch.
 *
 * <p>Until foreign-currency accounting exists (FX rates and revaluation), only the base currency can be active:
 * activating another currency is refused unless {@code accounting.multi-currency-enabled=true}.
 */
@Service
public class ReferenceDataService {

    /** Currency of a new account when none is given: the currency the books are kept in. */
    public static final String DEFAULT_CURRENCY = "INR";
    /** Branch of a new account when none is given: the single branch of a fresh install. */
    public static final String DEFAULT_BRANCH = "ONLINE";

    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final Pattern BRANCH = Pattern.compile("[A-Z0-9_]{1,10}");

    private final ReferenceDataRepository repository;
    private final String baseCurrency;
    private final String defaultBranch;
    private final boolean multiCurrency;

    public ReferenceDataService(ReferenceDataRepository repository,
                                @Value("${accounting.base-currency:" + DEFAULT_CURRENCY + "}") String baseCurrency,
                                @Value("${lending.default-branch:" + DEFAULT_BRANCH + "}") String defaultBranch,
                                @Value("${accounting.multi-currency-enabled:false}") boolean multiCurrency) {
        this.repository = repository;
        this.baseCurrency = baseCurrency.trim().toUpperCase(Locale.ROOT);
        this.defaultBranch = defaultBranch.trim().toUpperCase(Locale.ROOT);
        this.multiCurrency = multiCurrency;
    }

    // ------------------------------------------------------------------------------------------------ use on an account

    /** The currency code for a new account: the one asked for, or the base currency; it must exist and be active. */
    @Transactional(readOnly = true)
    public String resolveCurrency(String requested) {
        String code = requested == null || requested.isBlank() ? baseCurrency : requested.trim().toUpperCase(Locale.ROOT);
        CurrencyView c = repository.currency(code, baseCurrency)
                .orElseThrow(() -> new IllegalArgumentException("Unknown currency " + code + " (see /api/v1/reference/currencies)"));
        if (!c.active()) {
            throw new IllegalArgumentException("Currency " + code + " is not active, so no new account can be opened in it");
        }
        return code;
    }

    /** The branch code for a new account: the one asked for, or the default branch; it must exist and be active. */
    @Transactional(readOnly = true)
    public String resolveBranch(String requested) {
        String code = requested == null || requested.isBlank() ? defaultBranch : requested.trim().toUpperCase(Locale.ROOT);
        BranchView b = repository.branch(code, defaultBranch)
                .orElseThrow(() -> new IllegalArgumentException("Unknown branch " + code + " (see /api/v1/reference/branches)"));
        if (!b.active()) {
            throw new IllegalArgumentException("Branch " + code + " is not active, so no new account can be opened in it");
        }
        return code;
    }

    // ------------------------------------------------------------------------------------------------ currencies

    @Transactional(readOnly = true)
    public List<CurrencyView> currencies() {
        return repository.currencies(baseCurrency);
    }

    @Transactional
    public CurrencyView addCurrency(CurrencyRequest r) {
        if (r == null || r.currencyCode() == null || r.currencyName() == null || r.currencyName().isBlank()) {
            throw new IllegalArgumentException("currencyCode and currencyName are required");
        }
        String code = r.currencyCode().trim().toUpperCase(Locale.ROOT);
        if (!CURRENCY.matcher(code).matches()) {
            throw new IllegalArgumentException("currencyCode must be three letters, e.g. USD");
        }
        int minor = r.minorUnits() == null ? 2 : r.minorUnits();
        if (minor < 0 || minor > 4) {
            throw new IllegalArgumentException("minorUnits must be between 0 and 4");
        }
        if (repository.currency(code, baseCurrency).isPresent()) {
            throw new ConfigConflictException("Currency " + code + " already exists");
        }
        boolean active = Boolean.TRUE.equals(r.active());
        requireMayActivate(code, active);
        repository.insertCurrency(code, r.currencyName().trim(), minor, active);
        return repository.currency(code, baseCurrency).orElseThrow();
    }

    @Transactional
    public CurrencyView setCurrencyActive(String code, boolean active) {
        String c = code.trim().toUpperCase(Locale.ROOT);
        CurrencyView current = repository.currency(c, baseCurrency)
                .orElseThrow(() -> new ResourceNotFoundException("Currency " + c + " not found"));
        if (!active && current.base()) {
            throw new ConfigConflictException("The base currency " + c + " cannot be deactivated");
        }
        requireMayActivate(c, active);
        repository.updateCurrency(c, null, null, active);
        return repository.currency(c, baseCurrency).orElseThrow();
    }

    private void requireMayActivate(String code, boolean active) {
        if (active && !code.equals(baseCurrency) && !multiCurrency) {
            throw new ConfigConflictException("Currency " + code + " cannot be activated: accounts are kept in the base currency "
                    + baseCurrency + " until foreign-currency accounting is enabled (accounting.multi-currency-enabled)");
        }
    }

    // ------------------------------------------------------------------------------------------------ branches

    @Transactional(readOnly = true)
    public List<BranchView> branches() {
        return repository.branches(defaultBranch);
    }

    @Transactional
    public BranchView addBranch(BranchRequest r) {
        if (r == null || r.branchCode() == null || r.branchName() == null || r.branchName().isBlank()) {
            throw new IllegalArgumentException("branchCode and branchName are required");
        }
        String code = r.branchCode().trim().toUpperCase(Locale.ROOT);
        if (!BRANCH.matcher(code).matches()) {
            throw new IllegalArgumentException("branchCode must be 1 to 10 letters, digits or underscores");
        }
        if (repository.branch(code, defaultBranch).isPresent()) {
            throw new ConfigConflictException("Branch " + code + " already exists");
        }
        repository.insertBranch(code, r.branchName().trim(), r.active() == null || r.active());
        return repository.branch(code, defaultBranch).orElseThrow();
    }

    @Transactional
    public BranchView updateBranch(String code, String name, Boolean active) {
        String c = code.trim().toUpperCase(Locale.ROOT);
        BranchView current = repository.branch(c, defaultBranch)
                .orElseThrow(() -> new ResourceNotFoundException("Branch " + c + " not found"));
        boolean nowActive = active == null ? current.active() : active;
        if (!nowActive && current.defaultBranch()) {
            throw new ConfigConflictException("The default branch " + c + " cannot be deactivated");
        }
        repository.updateBranch(c, name == null || name.isBlank() ? null : name.trim(), nowActive);
        return repository.branch(c, defaultBranch).orElseThrow();
    }
}
