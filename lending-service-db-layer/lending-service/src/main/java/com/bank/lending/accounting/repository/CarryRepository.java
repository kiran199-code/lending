package com.bank.lending.accounting.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

/** Cumulative accrual and cumulative posted amount per (account, stream, ref), see RoundingCarry. */
@Repository
public class CarryRepository {

    public record State(BigDecimal cumulativeAccrued, BigDecimal cumulativePosted) {
    }

    private final JdbcTemplate jdbc;

    public CarryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Reads the state and locks the row for the rest of the transaction, creating a zero state if there is none. */
    public State lock(long accountId, String stream, long refId) {
        jdbc.update("INSERT INTO acct_accrual_carry(account_id, stream, ref_id) VALUES (?, ?, ?) "
                + "ON CONFLICT (account_id, stream, ref_id) DO NOTHING", accountId, stream, refId);
        List<State> found = jdbc.query("SELECT cum_accrued, cum_posted FROM acct_accrual_carry "
                        + "WHERE account_id = ? AND stream = ? AND ref_id = ? FOR UPDATE",
                (rs, i) -> new State(rs.getBigDecimal(1), rs.getBigDecimal(2)), accountId, stream, refId);
        return found.isEmpty() ? new State(BigDecimal.ZERO, BigDecimal.ZERO) : found.get(0);
    }

    public void save(long accountId, String stream, long refId, BigDecimal cumulativeAccrued, BigDecimal cumulativePosted) {
        jdbc.update("UPDATE acct_accrual_carry SET cum_accrued = ?, cum_posted = ?, updated_at = now() "
                + "WHERE account_id = ? AND stream = ? AND ref_id = ?", cumulativeAccrued, cumulativePosted,
                accountId, stream, refId);
    }

    /** Sets the state outright (cutover: starts the carry from what the sub-ledger already holds). */
    public void set(long accountId, String stream, long refId, BigDecimal cumulativeAccrued, BigDecimal cumulativePosted) {
        jdbc.update("INSERT INTO acct_accrual_carry(account_id, stream, ref_id, cum_accrued, cum_posted) "
                + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (account_id, stream, ref_id) "
                + "DO UPDATE SET cum_accrued = EXCLUDED.cum_accrued, cum_posted = EXCLUDED.cum_posted, updated_at = now()",
                accountId, stream, refId, cumulativeAccrued, cumulativePosted);
    }
}
