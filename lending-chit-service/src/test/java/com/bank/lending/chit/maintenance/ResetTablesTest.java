package com.bank.lending.chit.maintenance;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** A table added by a migration but missing from the reset list would survive a reset; this fails until it is added. */
class ResetTablesTest {

    private static final List<String> MIGRATIONS = List.of("V1__chit_core.sql", "V2__chit_ledger_support.sql");

    @Test
    void resetListIsExactlyTheTablesTheMigrationsCreate() throws IOException {
        Set<String> created = new TreeSet<>();
        for (String name : MIGRATIONS) {
            try (InputStream in = ResetTablesTest.class.getResourceAsStream("/db/migration/" + name)) {
                assertNotNull(in, name + " not found on the classpath");
                String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                Matcher m = Pattern.compile("(?m)^CREATE TABLE (\\w+)").matcher(sql);
                while (m.find()) {
                    created.add(m.group(1));
                }
            }
        }
        Set<String> listed = new TreeSet<>(ResetTables.TABLES);
        assertEquals(created, listed);
        assertEquals(ResetTables.TABLES.size(), new HashSet<>(ResetTables.TABLES).size(), "a table is listed twice");
    }
}
