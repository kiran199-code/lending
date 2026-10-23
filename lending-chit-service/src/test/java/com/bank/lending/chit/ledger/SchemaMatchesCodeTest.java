package com.bank.lending.chit.ledger;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Guards against drift between the Java chart of accounts and the CHECK constraint in the V2 migration. */
class SchemaMatchesCodeTest {

    private static String migration(String name) throws IOException {
        try (InputStream in = SchemaMatchesCodeTest.class.getResourceAsStream("/db/migration/" + name)) {
            assertNotNull(in, name + " not found on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void accountTypesMatchTheLedgerAccountCheck() throws IOException {
        String sql = migration("V2__chit_ledger_support.sql");
        Matcher m = Pattern.compile("account_type VARCHAR\\(\\d+\\) NOT NULL CHECK \\(account_type IN \\(([^)]*)\\)").matcher(sql);
        assertEquals(true, m.find(), "account_type check not found");
        Set<String> inSql = Arrays.stream(m.group(1).split(","))
                .map(s -> s.replace("'", "").trim()).filter(s -> !s.isEmpty()).collect(Collectors.toCollection(TreeSet::new));
        Set<String> inCode = Arrays.stream(AccountType.values()).map(Enum::name).collect(Collectors.toCollection(TreeSet::new));
        assertEquals(inCode, inSql);
    }

    @Test
    void perTicketAccountsMatchTheTicketCheck() throws IOException {
        String sql = migration("V2__chit_ledger_support.sql");
        Matcher m = Pattern.compile("CONSTRAINT ck_ledger_account_ticket CHECK \\(\\s*\\(account_type IN \\(([^)]*)\\)\\)").matcher(sql);
        assertEquals(true, m.find(), "ticket check not found");
        Set<String> inSql = Arrays.stream(m.group(1).split(","))
                .map(s -> s.replace("'", "").trim()).collect(Collectors.toCollection(TreeSet::new));
        Set<String> inCode = Arrays.stream(AccountType.values()).filter(AccountType::perTicket).map(Enum::name)
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(inCode, inSql);
    }

    @Test
    void v1StatusListsMatchTheEnums() throws IOException {
        String sql = migration("V1__chit_core.sql");
        assertEquals(enumNames(com.bank.lending.chit.domain.GroupStatus.values()), listAfter(sql, "chit_group", "status"));
        assertEquals(enumNames(com.bank.lending.chit.domain.TicketStatus.values()), listAfter(sql, "chit_ticket", "status"));
        assertEquals(enumNames(com.bank.lending.chit.domain.CycleStatus.values()), listAfter(sql, "chit_cycle", "status"));
    }

    private static Set<String> enumNames(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.toCollection(TreeSet::new));
    }

    /** The IN (...) list of the first "status ... CHECK (status IN (...))" after "CREATE TABLE <table>". */
    private static Set<String> listAfter(String sql, String table, String column) {
        int start = sql.indexOf("CREATE TABLE " + table + " ");
        Matcher m = Pattern.compile(column + "\\s+VARCHAR\\(\\d+\\)\\s+NOT NULL DEFAULT '[A-Z_]+'\\s+CHECK \\(" + column + " IN \\(([^)]*)\\)")
                .matcher(sql.substring(start));
        assertEquals(true, m.find(), table + "." + column + " check not found");
        return Arrays.stream(m.group(1).split(",")).map(s -> s.replace("'", "").trim())
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
