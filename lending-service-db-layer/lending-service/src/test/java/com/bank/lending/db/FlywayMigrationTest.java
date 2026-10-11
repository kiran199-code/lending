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
                "processed_request", "ledger_outbox", "loan_product"
        );
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
