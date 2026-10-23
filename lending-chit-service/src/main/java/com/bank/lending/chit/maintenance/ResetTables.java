package com.bank.lending.chit.maintenance;

import java.util.List;

/**
 * Every table the clear-data call empties, in the order they are reported. Kept in its own class so a unit test can
 * compare it with the CREATE TABLE statements in the migrations: a new table that is not listed here would survive a
 * reset and leave orphaned rows, and the test fails until it is added. Only the Flyway history table is left out.
 */
public final class ResetTables {

    public static final List<String> TABLES = List.of(
            "chit_group", "chit_group_rules_version", "chit_ticket", "chit_subscriber_link", "chit_cycle", "chit_fee_receipt",
            "chit_demand", "chit_payment", "chit_payment_allocation", "chit_late_fee",
            "chit_auction", "chit_bid", "chit_draw_record",
            "chit_prize_payout", "chit_dividend", "chit_security", "chit_default_case", "chit_ticket_transfer",
            "chit_closure_report",
            "ledger_account", "ledger_journal", "ledger_line",
            "idempotency_key", "chit_event", "audit_log");

    private ResetTables() {
    }
}
