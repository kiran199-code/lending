package com.bank.lending.e2e.report;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Append-only CSV writer. The audit trail lives in files (or the separate results store) - never in lending tables. */
public final class AuditWriter implements AutoCloseable {

    private final BufferedWriter out;

    public AuditWriter(Path file, List<String> header) {
        try {
            Files.createDirectories(file.getParent());
            out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
            row(header);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot open " + file + ": " + e.getMessage(), e);
        }
    }

    public synchronized void row(List<?> cells) {
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cells.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(escape(cells.get(i)));
            }
            out.write(sb.toString());
            out.newLine();
            out.flush();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String escape(Object o) {
        String s = o == null ? "" : String.valueOf(o);
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"").replace("\n", " ") + "\"";
        }
        return s;
    }

    @Override
    public void close() {
        try {
            out.close();
        } catch (IOException ignored) {
            // best effort
        }
    }
}
