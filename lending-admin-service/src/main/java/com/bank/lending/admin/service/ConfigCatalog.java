package com.bank.lending.admin.service;

import com.bank.lending.admin.dto.AdminDtos.FixedRule;
import com.bank.lending.admin.dto.AdminDtos.ServiceSettings;
import com.bank.lending.admin.dto.AdminDtos.SettingGroup;
import com.bank.lending.admin.dto.AdminDtos.SettingRow;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Reference list of every setting the three services read, with defaults and the values the Render
 * blueprint sets. Only this service's own settings show a live (effective) value: the other services
 * do not expose theirs, so for them the console shows the documented default and the sandbox value
 * from render.yaml, and says where to change it.
 */
@Component
public class ConfigCatalog {

    private static final String RENDER = "Render dashboard > the service > Environment (saving redeploys that service)";
    private static final String NO = "-";

    private final Environment env;

    public ConfigCatalog(Environment env) {
        this.env = env;
    }

    public List<ServiceSettings> services() {
        return List.of(admin(), lending(), batch());
    }

    // ---------------------------------------------------------------- this service

    private ServiceSettings admin() {
        List<SettingGroup> g = new ArrayList<>();
        g.add(new SettingGroup("Database (read-only)", List.of(
                live("Database URL", "DB_URL / DB_HOST, DB_PORT, DB_NAME", "spring.datasource.url", "jdbc:postgresql://localhost:5432/lending", "from lending-db", "Lending PostgreSQL. DB_URL wins if set; otherwise built from host, port and name.", false, true),
                live("Database user", "DB_USER", "spring.datasource.username", "lending_app", "from lending-db", "Sessions are forced read-only whatever the role can do.", false, true),
                live("Database password", "DB_PASSWORD", "spring.datasource.password", "changeme", "from lending-db", "Never shown.", true, true),
                live("Pool size", "DB_POOL_SIZE", "spring.datasource.hikari.maximum-pool-size", "5", "3", "Maximum database connections.", false, true),
                live("Pool minimum idle", "DB_POOL_MIN_IDLE", "spring.datasource.hikari.minimum-idle", "1", "1", "Connections kept warm.", false, true))));
        g.add(new SettingGroup("Sign-in and behaviour", List.of(
                live("Admin user id", "ADMIN_USERNAME", "admin.username", "admin", "admin", "Login for this console.", false, true),
                live("Admin password", "ADMIN_PASSWORD", "admin.password", "changeme", "generated", "Change in every real environment. Never shown.", true, true),
                live("Business timezone", "ADMIN_TIMEZONE", "admin.timezone", "Asia/Kolkata", NO, "Defines 'today' for due, overdue and month-to-date figures.", false, true),
                live("HTTP port", "SERVER_PORT", "server.port", "8082", "10000", "Port the console listens on.", false, true))));
        g.add(new SettingGroup("Links to other services", List.of(
                live("Lending service URL", "LENDING_SERVICE_URL", "lending.service.base-url", "(empty)", "https://lending-service-5tqm.onrender.com", "Enables the statement PDF download and editing of loan products.", false, true),
                live("Batch service URL", "BATCH_SERVICE_URL", "batch.service.base-url", "(empty)", "set by you", "Enables editing batch job settings.", false, true),
                live("Batch service user", "BATCH_ADMIN_USERNAME", "batch.service.username", "batch-admin", "batch-admin", "Login used to call the batch service.", false, true),
                live("Batch service password", "BATCH_ADMIN_PASSWORD", "batch.service.password", "(empty)", "copied from lending-batch-service", "Must equal the batch service's own BATCH_ADMIN_PASSWORD. Never shown.", true, true))));
        g.add(new SettingGroup("Runtime", List.of(
                live("JVM options", "JAVA_TOOL_OPTIONS", "JAVA_TOOL_OPTIONS", "(none)", "-Xmx256m -Xss512k -XX:+UseSerialGC -XX:TieredStopAtLevel=1", "Heap and GC; sized for Render's 512 MB free instances.", false, true))));
        return new ServiceSettings("lending-admin-service", "This console. Values below are the effective ones this instance is running with.",
                RENDER + ", or docker-compose.yml locally", true, g);
    }

    // ---------------------------------------------------------------- lending-service

    private ServiceSettings lending() {
        List<SettingGroup> g = new ArrayList<>();
        g.add(new SettingGroup("Database", List.of(
                ref("Database URL", "DB_URL / DB_HOST, DB_PORT, DB_NAME", "jdbc:postgresql://localhost:5432/lending", "from lending-db", "Lending PostgreSQL. DB_URL wins if set.", false, true),
                ref("Database user", "DB_USER", "lending_app", "from lending-db", "Owner of the public schema; runs Flyway migrations.", false, true),
                ref("Database password", "DB_PASSWORD", "changeme", "from lending-db", "Secret.", true, true),
                ref("Pool size", "DB_POOL_SIZE", "20", "5", "Maximum connections.", false, true),
                ref("Pool minimum idle", "DB_POOL_MIN_IDLE", "5", "1", "Connections kept warm.", false, true))));
        g.add(new SettingGroup("Statement PDF", List.of(
                ref("Institution name", "LENDING_STATEMENT_INSTITUTION_NAME", "Lending Bank", NO, "Shown in the statement header (lending.statement.institution-name).", false, true),
                ref("Currency", "LENDING_STATEMENT_CURRENCY", "INR", NO, "Currency code printed on statements (lending.statement.currency).", false, true),
                ref("Contact line", "LENDING_STATEMENT_CONTACT_LINE", "For any query, contact your branch or customer care and quote your account number.", NO, "Footer text (lending.statement.contact-line).", false, true),
                ref("Statement timezone", "LENDING_STATEMENT_TIMEZONE", "Asia/Kolkata", NO, "Statement date and 'today' for overdue (lending.statement.timezone).", false, true))));
        g.add(new SettingGroup("Server and runtime", List.of(
                ref("HTTP port", "SERVER_PORT", "8080", "10000", "Listening port.", false, true),
                ref("Swagger UI path", "springdoc.swagger-ui.path", "/swagger-ui.html", NO, "Interactive API docs; OpenAPI JSON at /v3/api-docs.", false, true),
                ref("Hibernate schema check", "spring.jpa.hibernate.ddl-auto", "validate", NO, "Fixed: Flyway owns the schema; the app only validates it.", false, true),
                ref("JDBC batch size", "spring.jpa.properties.hibernate.jdbc.batch_size", "50", NO, "Statements per JDBC batch.", false, true),
                ref("Flyway", "spring.flyway.*", "enabled, validate-on-migrate", NO, "Migrations V1-V9 run at startup.", false, true),
                ref("SQL logging", "logging.level.org.hibernate.SQL", "debug", NO, "Logs every SQL statement; lower it for production.", false, true),
                ref("JVM options", "JAVA_TOOL_OPTIONS", "(none)", "-Xmx256m -Xss512k -XX:+UseSerialGC -XX:TieredStopAtLevel=1", "Heap and GC.", false, true))));
        return new ServiceSettings("lending-service", "Account-servicing API. It does not expose its effective settings, so defaults and the render.yaml values are shown.",
                RENDER + ", or docker-compose.yml locally", false, g);
    }

    // ---------------------------------------------------------------- lending-batch-service

    private ServiceSettings batch() {
        List<SettingGroup> g = new ArrayList<>();
        g.add(new SettingGroup("Database", List.of(
                ref("Database host / port / name", "DB_HOST, DB_PORT, DB_NAME", "(required) / 5432 / (required)", "from lending-db", "Prod profile requires host and name.", false, true),
                ref("Database user", "DB_USERNAME", "(required)", "from lending-db", "Local profile uses lending_batch.", false, true),
                ref("Database password", "DB_PASSWORD", "(required)", "from lending-db", "Secret.", true, true),
                ref("Pool size", "DB_POOL_SIZE", "40", "5", "Maximum connections (prod profile).", false, true),
                ref("Pool minimum idle", "DB_POOL_MIN_IDLE", "10", "1", "Connections kept warm.", false, true))));
        g.add(new SettingGroup("Operations API and messaging", List.of(
                ref("Batch admin user", "BATCH_ADMIN_USERNAME", "batch-admin", "batch-admin", "HTTP Basic login for /api/v1/batch-admin.", false, true),
                ref("Batch admin password", "BATCH_ADMIN_PASSWORD", "changeme", "generated", "Secret. The admin console needs the same value to edit jobs.", true, true),
                ref("Kafka bootstrap servers", "KAFKA_BOOTSTRAP_SERVERS", "localhost:9092", "localhost:9092 (no broker)", "Where job-completion events are published.", false, true),
                ref("Kafka security protocol", "KAFKA_SECURITY_PROTOCOL", "PLAINTEXT", NO, "Kafka client security.", false, true),
                ref("Kafka send timeout", "BATCH_KAFKA_MAX_BLOCK_MS", "60000", "1000", "How long a send waits before failing; short on Render so a missing broker fails fast.", false, true))));
        g.add(new SettingGroup("Scheduler (Quartz) and framework", List.of(
                ref("Spring profile", "SPRING_PROFILES_ACTIVE", "local", "prod", "local = developer defaults; prod = everything from the environment.", false, true),
                ref("HTTP port", "SERVER_PORT", "8081", "10000", "Listening port.", false, true),
                ref("Scheduler threads", "spring.quartz.properties.org.quartz.threadPool.threadCount", "10", NO, "Jobs that can fire at the same instant.", false, true),
                ref("Clustered scheduler", "org.quartz.jobStore.isClustered", "true", NO, "Several instances can share one schedule safely.", false, true),
                ref("Misfire threshold", "org.quartz.jobStore.misfireThreshold", "60000 ms", NO, "How late a trigger may fire before it counts as misfired.", false, true),
                ref("Run jobs at startup", "spring.batch.job.enabled", "false", NO, "Fixed: jobs run only from cron or the admin API.", false, true),
                ref("JVM options", "JAVA_TOOL_OPTIONS", "(none)", "-Xmx256m -Xss512k -XX:+UseSerialGC -XX:TieredStopAtLevel=1", "Heap and GC.", false, true))));
        return new ServiceSettings("lending-batch-service", "Batch platform. Its infrastructure settings are listed here; each job's own settings (schedule, chunk size, retries) are live and editable on the Batch jobs tab.",
                RENDER + ", or docker-compose.yml locally; job settings: Batch jobs tab", false, g);
    }

    /** Behaviour that is coded into the services and cannot be changed by configuration. */
    public List<FixedRule> fixedRules() {
        return List.of(
                new FixedRule("Interest", "Daily accrual formula", "outstanding principal x annual rate / year basis, rounded to 4 decimals"),
                new FixedRule("Interest", "Day-count conventions", "ACT_365 = actual days / 365; ACT_ACT = actual days / 365 or 366 by year; ACT_360 = actual days / 360; THIRTY_360 (30/360 US) and THIRTY_E_360 (30E/360) = 30-day months / 360"),
                new FixedRule("Interest", "Accrual scope", "ACTIVE accounts only; one row per account and business date (repeat runs change nothing)"),
                new FixedRule("Product", "Product master", "table loan_product (API /api/v1/loan-products, view under Products); an account copies the product's terms when created and keeps its own copy afterwards"),
                new FixedRule("Product", "Defaults and limits", "omitted rate type, rate, benchmark, spread, day count and schedule type are taken from the product; account type and rate type must equal the product's; rate, spread, amount and tenure must be inside the product's bands (no band = no limit)"),
                new FixedRule("Product", "Rate reset", "a floating reset (benchmark + spread) is floored and capped at the product's rate band"),
                new FixedRule("Schedule", "Schedule types", "EMI = level instalments; EQUAL_PRINCIPAL = same principal each time; INTEREST_ONLY = interest each time, all principal with the last; BALLOON = level instalments leaving balloon % for the last; STEP_UP_EMI = instalment rises by step-up % each year"),
                new FixedRule("Schedule", "Repayment frequency", "MONTHLY, QUARTERLY, HALF_YEARLY or YEARLY; tenure must be a whole number of periods; due dates are the first due date plus whole periods"),
                new FixedRule("Schedule", "Moratorium", "the first N months pay interest only (not for INTEREST_ONLY); N must be a whole number of periods and leave at least one repayment instalment"),
                new FixedRule("Schedule", "Interest per instalment", "outstanding x annual rate x year fraction between the due dates under the day count; the last instalment clears whatever balance is left"),
                new FixedRule("Schedule", "Instalment sizing", "reducing balance at the average rate per period under the day count; straight-line when the rate is 0; rounded to 2 decimals HALF_UP"),
                new FixedRule("Schedule", "Reschedule", "after a part-payment or a floating rate reset the remaining instalments are rebuilt with the same type, a running moratorium carries on, and a step-up schedule stays in its current year"),
                new FixedRule("Delinquency", "Classification bands (days past due)", "0 ACTIVE; 1-30 SMA-0; 31-60 SMA-1; 61-90 SMA-2; 91-365 Substandard; 366-1095 Doubtful; over 1095 Loss"),
                new FixedRule("Mortgage", "LTV", "sanctioned amount / valuation x 100 (against the sanctioned amount, not outstanding principal)"),
                new FixedRule("Idempotency", "Create account / EMI posting / accrual", "sanctionRefId; (accountId, Idempotency-Key); (accountId, businessDate)"),
                new FixedRule("Bulk", "Items per bulk call", "200 maximum"),
                new FixedRule("Storage", "Monthly partitions", "interest_accrual and emi_transaction: current month + 6 ahead, created at startup and at 02:30 IST on the 1st and 15th"),
                new FixedRule("Admin console", "Overdue rule", "unpaid instalment with a due date before today in the business timezone; DPD buckets 1-30, 31-60, 61-90, 90+"),
                new FixedRule("Admin console", "Excluded from portfolio figures", "PENDING_DISBURSEMENT, CLOSED, FORECLOSED, WRITTEN_OFF"),
                new FixedRule("Batch", "Job set", "36 job slots; per-job settings live in batch.batch_job_config (editable on the Batch jobs tab)"));
    }

    // ---------------------------------------------------------------- helpers

    /** A setting of this service: the effective value comes from the Spring environment. */
    private SettingRow live(String name, String envVar, String property, String def, String sandbox,
                            String desc, boolean secret, boolean restart) {
        String v = env.getProperty(property);
        String shown;
        if (secret) {
            shown = (v == null || v.isEmpty()) ? "(not set)" : "******** (set)";
        } else {
            shown = (v == null || v.isEmpty()) ? "(empty)" : v;
        }
        return new SettingRow(name, envVar, shown, def, sandbox, desc, secret, restart);
    }

    /** A setting of another service: no live value, only documented default and sandbox value. */
    private SettingRow ref(String name, String envVar, String def, String sandbox, String desc,
                           boolean secret, boolean restart) {
        return new SettingRow(name, envVar, null, secret ? "(secret)" : def, sandbox, desc, secret, restart);
    }
}
