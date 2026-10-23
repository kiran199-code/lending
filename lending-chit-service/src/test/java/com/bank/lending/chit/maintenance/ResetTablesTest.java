package com.bank.lending.chit.maintenance;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A table added by a migration but missing from the reset list would survive a reset; this fails until it is added. */
class ResetTablesTest {

    /** Every V*.sql on the classpath, so a new migration is picked up without editing this test. */
    private static List<String> migrations() throws Exception {
        URL dir = ResetTablesTest.class.getResource("/db/migration");
        assertNotNull(dir, "db/migration not found on the classpath");
        try (Stream<Path> files = Files.list(Path.of(dir.toURI()))) {
            return files.map(f -> f.getFileName().toString()).filter(n -> n.matches("V\\d+__.*\\.sql")).sorted().toList();
        }
    }

    @Test
    void resetListIsExactlyTheTablesTheMigrationsCreate() throws Exception {
        Set<String> created = new TreeSet<>();
        List<String> migrations = migrations();
        assertTrue(migrations.size() >= 3, "expected at least V1..V3, found " + migrations);
        for (String name : migrations) {
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
