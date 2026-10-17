package com.bank.lending.accounting;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Deployment-level switches of the accounting module.
 *
 * <ul>
 *   <li>{@code accounting.enabled} (default true): master switch. When false the hooks post nothing, so the module
 *       can be switched off without a code change; per-event control is {@code acct_event_def.posting_mode}.</li>
 *   <li>{@code accounting.base-currency} (default INR): the currency every loan account is booked in until accounts
 *       carry their own currency (phase E).</li>
 *   <li>{@code accounting.recon-interest-tolerance} (default 0.01): the most that the accrued-interest receivable of
 *       one account may differ from the sub-ledger in reconciliation. Interest is accrued daily at four decimals and
 *       journalled at two, so a paisa of difference between demands is rounding, not an error; every other bucket
 *       must match exactly.</li>
 *   <li>{@code accounting.config-cache-seconds} (default 30): how long the configuration snapshot is cached.</li>
 * </ul>
 */
@Component
public class AccountingSettings {

    private final boolean enabled;
    private final String baseCurrency;
    private final BigDecimal interestTolerance;
    private final long configCacheSeconds;

    public AccountingSettings(@Value("${accounting.enabled:true}") boolean enabled,
                              @Value("${accounting.base-currency:INR}") String baseCurrency,
                              @Value("${accounting.recon-interest-tolerance:0.01}") BigDecimal interestTolerance,
                              @Value("${accounting.config-cache-seconds:30}") long configCacheSeconds) {
        this.enabled = enabled;
        this.baseCurrency = baseCurrency;
        this.interestTolerance = interestTolerance;
        this.configCacheSeconds = configCacheSeconds;
    }

    public boolean enabled() {
        return enabled;
    }

    public String baseCurrency() {
        return baseCurrency;
    }

    public BigDecimal interestTolerance() {
        return interestTolerance;
    }

    public long configCacheSeconds() {
        return configCacheSeconds;
    }
}
