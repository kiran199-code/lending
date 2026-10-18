package com.bank.lending.db;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayMigrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void allMigrationsApplySuccessfully() {
        Integer pendingCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = false", Integer.class);
        assertThat(pendingCount).isZero();
    }

    @Test
    void coreTablesExist() {
        List<String> tables = jdbcTemplate.queryForList(
                """
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                """, String.class);

        assertThat(tables).contains(
                "loan_account", "mortgage", "mortgage_valuation_history",
                "amortization_schedule", "interest_accrual", "emi_transaction",
                "processed_request", "ledger_outbox", "limit_outbox", "loan_product", "benchmark_rate",
                "business_date_state", "business_date_history", "non_working_day",
                "loan_demand", "payment_allocation", "penal_interest"
        );
    }

    @Test
    void accountingTablesExistAndTheSeedIsLoaded() {
        List<String> tables = jdbcTemplate.queryForList(
                """
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND (table_name LIKE 'acct\\_%' OR table_name IN ('gl_ledger', 'gl_account'))
                """, String.class);
        assertThat(tables).contains(
                "acct_event_def", "acct_amount_tag", "acct_role", "gl_ledger", "gl_account", "acct_route",
                "acct_event_ledger", "acct_entry_template", "acct_entry_rule", "acct_journal", "acct_journal_line",
                "acct_idempotency", "acct_journal_reversal", "acct_accrual_carry", "acct_posting_exception",
                "acct_balance_daily", "acct_reconciliation", "acct_period", "acct_config_audit");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM acct_entry_template WHERE status = 'APPROVED'",
                Integer.class)).isEqualTo(12);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM gl_account", Integer.class)).isEqualTo(26);
    }

    @Test
    void currencyAndBranchAreReferenceDataWithOneSeededRowEach() {
        assertThat(jdbcTemplate.queryForList("SELECT currency_code FROM currency", String.class)).containsExactly("INR");
        assertThat(jdbcTemplate.queryForList("SELECT branch_code FROM branch", String.class)).containsExactly("ONLINE");
        List<String> columns = jdbcTemplate.queryForList(
                """
                SELECT table_name || '.' || column_name FROM information_schema.columns
                WHERE table_schema = 'public' AND column_name IN ('currency_code', 'branch_code')
                  AND table_name IN ('loan_account', 'acct_journal', 'acct_journal_line', 'acct_route', 'acct_balance_daily')
                """, String.class);
        assertThat(columns).contains("loan_account.currency_code", "loan_account.branch_code", "acct_journal.branch_code",
                "acct_journal_line.branch_code", "acct_route.branch_code", "acct_balance_daily.branch_code");
    }

    @Test
    void journalsCannotBeUpdatedOrDeleted() {
        Integer blocked = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_trigger WHERE tgname IN ('trg_acct_journal_immutable', 'trg_acct_journal_line_immutable')",
                Integer.class);
        assertThat(blocked).isPositive();
    }

    @Test
    void scheduleParameterColumnsExistOnProductAndAccount() {
        List<String> columns = jdbcTemplate.queryForList(
                """
                SELECT table_name || '.' || column_name FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name IN ('loan_product', 'loan_account')
                  AND column_name IN ('repayment_frequency', 'moratorium_months', 'balloon_percent', 'step_up_percent')
                """, String.class);
        assertThat(columns).contains(
                "loan_product.repayment_frequency", "loan_product.moratorium_months",
                "loan_product.balloon_percent", "loan_product.step_up_percent",
                "loan_account.repayment_frequency", "loan_account.moratorium_months",
                "loan_account.balloon_percent", "loan_account.step_up_percent");
    }

    @Test
    void interestAccrualPartitionsExistForBootstrapMonths() {
        List<String> partitions = jdbcTemplate.queryForList(
                """
                SELECT inhrelid::regclass::text FROM pg_inherits
                WHERE inhparent = 'interest_accrual'::regclass
                """, String.class);

        assertThat(partitions).contains(
                "interest_accrual_2026_07", "interest_accrual_2026_08", "interest_accrual_2026_09");
    }

    @Test
    void circularForeignKeyBetweenLoanAccountAndMortgageIsEstablished() {
        Integer fkCount = jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM information_schema.table_constraints
                WHERE constraint_name = 'fk_loan_account_mortgage' AND constraint_type = 'FOREIGN KEY'
                """, Integer.class);
        assertThat(fkCount).isEqualTo(1);
    }

    @Test
    void partitionManagementFunctionCanCreateNewPartition() {
        jdbcTemplate.execute("SELECT create_monthly_partition('interest_accrual', 2026, 10)");

        Integer exists = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_class WHERE relname = 'interest_accrual_2026_10'", Integer.class);
        assertThat(exists).isEqualTo(1);
    }
}
