package com.demo.impact;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class Tsv {
    private Tsv() {}
    public static String encode(String value) {
        return value.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n");
    }
    public static String decode(String value) throws IOException {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\') { out.append(c); continue; }
            if (++i == value.length()) throw new IOException("Truncated TSV escape");
            out.append(switch (value.charAt(i)) {
                case '\\' -> '\\'; case 't' -> '\t'; case 'r' -> '\r'; case 'n' -> '\n';
                default -> throw new IOException("Invalid TSV escape");
            });
        }
        return out.toString();
    }
    public static void write(Path file, String header, List<List<String>> rows) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write(header); writer.write('\n');
            for (List<String> row : rows) {
                writer.write(String.join("\t", row.stream().map(Tsv::encode).toList())); writer.write('\n');
            }
        }
    }
    public static List<List<String>> read(Path file, String header) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            if (!header.equals(reader.readLine())) throw new IOException("Invalid TSV header: " + file.getFileName());
            int width = header.split("\t", -1).length;
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split("\t", -1);
                if (fields.length != width) throw new IOException("Invalid TSV row: " + file.getFileName());
                List<String> row = new ArrayList<>();
                for (String field : fields) row.add(decode(field));
                rows.add(row);
            }
        }
        return rows;
    }
}
