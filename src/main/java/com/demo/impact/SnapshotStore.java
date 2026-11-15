package com.demo.impact;

import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static com.demo.impact.Model.*;

public final class SnapshotStore {
    private static final String METHODS_HEADER = "method_id\towner\tname\tparameter_types\tsource_path\tline\tcolumn";
    private static final String CALLS_HEADER = "caller_id\tcallee_id\tsource_path\tline\tcolumn\tkind";
    private static final String ENDPOINTS_HEADER_V1 = "http_method\troute\thandler_id\tsource_path\tline\tcolumn";
    private static final String ENDPOINTS_HEADER = "http_method\troute\tparams\thandler_id\tsource_path\tline\tcolumn";
    private static final String PROBLEMS_HEADER = "caller_id\tsource_path\tline\tcolumn\treason\tdetail";
    private final Path directory;
    public SnapshotStore(Path repo) { directory = repo.resolve(".impact-index"); }

    /** Hold this lock for the complete index operation, including compiler analysis. */
    public WriterLock lock() throws IOException {
        Files.createDirectories(directory);
        FileChannel channel = FileChannel.open(directory.resolve("write.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("Một tiến trình khác đang index repo");
            return new WriterLock(channel, lock);
        } catch (IOException | RuntimeException ex) { channel.close(); throw new IOException("Không thể khóa repo để index", ex); }
    }
    public record WriterLock(FileChannel channel, FileLock lock) implements AutoCloseable {
        @Override public void close() throws IOException { try { lock.release(); } finally { channel.close(); } }
    }
    public Snapshot publish(Snapshot data) throws IOException, ToolException {
        Properties manifest = new Properties(); manifest.putAll(data.manifest());
        String id = UUID.randomUUID().toString();
        manifest.setProperty("schemaVersion", "2"); manifest.setProperty("snapshotId", id);
        manifest.setProperty("indexedAtUtc", Instant.now().toString());
        manifest.setProperty("methods", Integer.toString(data.methods().size()));
        manifest.setProperty("calls", Integer.toString(data.calls().size()));
        manifest.setProperty("endpoints", Integer.toString(data.endpoints().size()));
        manifest.setProperty("diagnostics", Integer.toString(data.problems().size()));
        Snapshot snapshot = new Snapshot(manifest, data.methods(), data.calls(), data.endpoints(), data.problems());
        validate(snapshot, id);
        Path destination = directory.resolve("snapshots").resolve(id);
        Files.createDirectories(destination);
        try (Writer writer = Files.newBufferedWriter(destination.resolve("manifest.properties"), StandardCharsets.UTF_8)) {
            manifest.store(writer, "Java/Spring impact index");
        }
        Tsv.write(destination.resolve("methods.tsv"), METHODS_HEADER, snapshot.methods().stream().map(m ->
                List.of(m.id(), m.owner(), m.name(), m.parameters(), m.source(), num(m.line()), num(m.column()))).toList());
        Tsv.write(destination.resolve("calls.tsv"), CALLS_HEADER, snapshot.calls().stream().map(c ->
                List.of(c.caller(), c.callee(), c.source(), num(c.line()), num(c.column()), c.kind().name())).toList());
        Tsv.write(destination.resolve("endpoints.tsv"), ENDPOINTS_HEADER, snapshot.endpoints().stream().map(e ->
                List.of(e.verb(), e.route(), encodeParams(e.params()), e.handler(), e.source(), num(e.line()), num(e.column()))).toList());
        Tsv.write(destination.resolve("unresolved.tsv"), PROBLEMS_HEADER, snapshot.problems().stream().map(p ->
                List.of(p.caller(), p.source(), num(p.line()), num(p.column()), p.reason(), p.detail())).toList());
        Snapshot checked = readDirectory(destination, id);
        atomicWrite(directory.resolve("current"), id + "\n");
        return checked;
    }
    public Snapshot load() throws ToolException {
        try {
            String id = Files.readString(directory.resolve("current"), StandardCharsets.UTF_8).strip();
            if (!id.matches("[A-Za-z0-9_-]+")) throw new IOException("Invalid snapshot ID");
            return readDirectory(directory.resolve("snapshots").resolve(id), id);
        } catch (IOException | IllegalArgumentException ex) {
            throw new ToolException(3, "Chỉ mục thiếu hoặc hỏng: " + ex.getMessage(), ex);
        }
    }
    private Snapshot readDirectory(Path path, String id) throws IOException, ToolException {
        try {
            Properties manifest = new Properties();
            try (Reader reader = Files.newBufferedReader(path.resolve("manifest.properties"), StandardCharsets.UTF_8)) { manifest.load(reader); }
            List<Method> methods = new ArrayList<>();
            for (List<String> r : Tsv.read(path.resolve("methods.tsv"), METHODS_HEADER))
                methods.add(new Method(r.get(0), r.get(1), r.get(2), r.get(3), r.get(4), number(r.get(5)), number(r.get(6))));
            List<Call> calls = new ArrayList<>();
            for (List<String> r : Tsv.read(path.resolve("calls.tsv"), CALLS_HEADER))
                calls.add(new Call(r.get(0), r.get(1), r.get(2), number(r.get(3)), number(r.get(4)), Kind.valueOf(r.get(5))));
            List<Endpoint> endpoints = new ArrayList<>();
            String schemaVersion = manifest.getProperty("schemaVersion");
            if ("1".equals(schemaVersion)) {
                for (List<String> r : Tsv.read(path.resolve("endpoints.tsv"), ENDPOINTS_HEADER_V1))
                    endpoints.add(new Endpoint(r.get(0), r.get(1), List.of(), r.get(2), r.get(3), number(r.get(4)), number(r.get(5))));
            } else if ("2".equals(schemaVersion)) {
                for (List<String> r : Tsv.read(path.resolve("endpoints.tsv"), ENDPOINTS_HEADER))
                    endpoints.add(new Endpoint(r.get(0), r.get(1), decodeParams(r.get(2)), r.get(3), r.get(4), number(r.get(5)), number(r.get(6))));
            } else throw new IOException("Schema không được hỗ trợ: " + schemaVersion);
            List<Problem> problems = new ArrayList<>();
            for (List<String> r : Tsv.read(path.resolve("unresolved.tsv"), PROBLEMS_HEADER))
                problems.add(new Problem(r.get(0), r.get(1), number(r.get(2)), number(r.get(3)), r.get(4), r.get(5)));
            Snapshot snapshot = new Snapshot(manifest, methods, calls, endpoints, problems);
            validate(snapshot, id);
            return new Snapshot(manifest, sorted(methods, METHODS), sorted(calls, CALLS), sorted(endpoints, ENDPOINTS), sorted(problems, PROBLEMS));
        } catch (IllegalArgumentException ex) { throw new IOException("Invalid snapshot value: " + ex.getMessage(), ex); }
    }
    private static void validate(Snapshot s, String id) throws ToolException {
        Properties p = s.manifest();
        require("1".equals(p.getProperty("schemaVersion")) || "2".equals(p.getProperty("schemaVersion")), "Schema không được hỗ trợ");
        for (String key : List.of("snapshotId", "indexedAtUtc", "status", "jdkVersion", "release", "files", "methods", "calls", "endpoints", "diagnostics"))
            require(p.getProperty(key) != null && !p.getProperty(key).isBlank(), "Thiếu manifest key: " + key);
        require(id.equals(p.getProperty("snapshotId")), "Snapshot ID không khớp");
        require(Set.of("COMPLETE", "PARTIAL").contains(p.getProperty("status")), "Invalid snapshot status");
        try {
            Instant.parse(p.getProperty("indexedAtUtc"));
            require(Integer.parseInt(p.getProperty("release")) > 0 && Integer.parseInt(p.getProperty("files")) > 0, "Invalid release/files");
            require(Integer.parseInt(p.getProperty("methods")) == s.methods().size() &&
                    Integer.parseInt(p.getProperty("calls")) == s.calls().size() &&
                    Integer.parseInt(p.getProperty("endpoints")) == s.endpoints().size() &&
                    Integer.parseInt(p.getProperty("diagnostics")) == s.problems().size(), "Số record không khớp manifest");
        } catch (RuntimeException ex) { throw new ToolException(3, "Manifest không hợp lệ", ex); }
        Set<String> ids = new HashSet<>();
        require(!s.methods().isEmpty(), "Snapshot không có method");
        for (Method m : s.methods()) {
            require(ids.add(m.id()), "Trùng ID method: " + m.id());
            require(!m.owner().isBlank() && !m.name().isBlank() &&
                    m.id().equals(m.owner() + "#" + m.name() + "(" + m.parameters() + ")"), "Method ID không hợp lệ");
            location(m.source(), m.line(), m.column(), false);
        }
        for (Call c : s.calls()) {
            require(ids.contains(c.caller()) && (c.kind() == Kind.EXTERNAL || ids.contains(c.callee())) && !c.callee().isBlank(), "Tham chiếu cạnh bị hỏng");
            location(c.source(), c.line(), c.column(), false);
        }
        for (Endpoint e : s.endpoints()) {
            require(ids.contains(e.handler()) && Set.of("ANY", "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE").contains(e.verb()), "Endpoint không hợp lệ");
            location(e.source(), e.line(), e.column(), false);
        }
        for (Problem d : s.problems()) {
            require(d.caller().isEmpty() || ids.contains(d.caller()), "Diagnostic caller không hợp lệ");
            require(!d.reason().isBlank(), "Diagnostic reason trống");
            location(d.source(), d.line(), d.column(), true);
        }
        require(new HashSet<>(s.calls()).size() == s.calls().size() && new HashSet<>(s.endpoints()).size() == s.endpoints().size()
                && new HashSet<>(s.problems()).size() == s.problems().size(), "Record trùng trong snapshot");
    }
    private static void location(String source, long line, long column, boolean diagnostic) throws ToolException {
        require((diagnostic || !source.isBlank()) && !source.contains("\\") && !source.startsWith("/")
                && !source.matches("^[A-Za-z]:.*") && Arrays.stream(source.split("/")).noneMatch(".."::equals)
                && line >= (diagnostic ? 0 : 1) && column >= (diagnostic ? 0 : 1), "Vị trí source không hợp lệ");
    }
    private static void require(boolean condition, String message) throws ToolException { if (!condition) throw new ToolException(3, message); }
    private static String num(long number) { return Long.toString(number); }
    private static long number(String value) { return Long.parseLong(value); }
    private static String encodeParams(List<String> params) {
        StringBuilder encoded = new StringBuilder();
        for (String param : params) encoded.append(param.length()).append(':').append(param);
        return encoded.toString();
    }
    private static List<String> decodeParams(String encoded) throws IOException {
        List<String> params = new ArrayList<>();
        int position = 0;
        while (position < encoded.length()) {
            int colon = encoded.indexOf(':', position);
            if (colon < 0) throw new IOException("Invalid endpoint params encoding");
            int length;
            try { length = Integer.parseInt(encoded.substring(position, colon)); }
            catch (NumberFormatException ex) { throw new IOException("Invalid endpoint params length", ex); }
            int start = colon + 1, end = start + length;
            if (length < 0 || end > encoded.length()) throw new IOException("Invalid endpoint params length");
            params.add(encoded.substring(start, end)); position = end;
        }
        return List.copyOf(params);
    }
    public static void atomicWrite(Path output, String contents) throws IOException {
        Path absolute = output.toAbsolutePath().normalize();
        Path temporary = Files.createTempFile(absolute.getParent(), ".impact-", ".tmp");
        try {
            Files.writeString(temporary, contents, StandardCharsets.UTF_8);
            try { Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { throw new IOException("Filesystem không hỗ trợ thay file nguyên tử: " + absolute, ex); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
