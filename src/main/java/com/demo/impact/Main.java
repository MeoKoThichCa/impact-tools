package com.demo.impact;

import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.demo.impact.Model.*;

public final class Main {
    public static final String VERSION = "1.0.0";
    private static final String HELP = """
            Java/Spring impact CLI 1.0.0 (JDK 17+)
            java -jar impact-tool.jar index <repo> [--source-root <path>]... [--release <n>] [--classpath-file <path>]
            java -jar impact-tool.jar query --repo <path> --method <signature> [--max-depth <n>] [--out <path>]
            --help | --version

            Defaults: release=17, max-depth=10, out=impact.md in the current directory.
            Method: package.Class#name(parameter.Type,int,java.lang.String[])
            A bare name or package.Class#name must select exactly one method.
            Exit codes: 0 success; 1 environment/I/O/compiler; 2 arguments/selector; 3 missing/corrupt index.
            """;
    private Main() {}
    public static void main(String[] args) {
        System.exit(run(args, new PrintStream(System.out, true, StandardCharsets.UTF_8),
                new PrintStream(System.err, true, StandardCharsets.UTF_8)));
    }
    public static int run(String[] args, PrintStream stdout, PrintStream stderr) {
        try {
            if (Arrays.asList(args).contains("--help")) { stdout.print(HELP); return 0; }
            if (args.length == 1 && args[0].equals("--version")) { stdout.println(VERSION); return 0; }
            if (args.length == 0 || !Set.of("index", "query").contains(args[0])) throw new ToolException(2, "Cần lệnh index hoặc query; dùng --help");
            boolean index = args[0].equals("index");
            Map<String, String> options = new HashMap<>();
            List<String> roots = new ArrayList<>();
            String positional = null;
            Set<String> allowed = index ? Set.of("--source-root", "--release", "--classpath-file")
                    : Set.of("--repo", "--method", "--max-depth", "--out");
            for (int i = 1; i < args.length; i++) {
                String arg = args[i];
                if (!arg.startsWith("--")) {
                    if (!index || positional != null) throw new ToolException(2, "Tham số không hợp lệ: " + arg);
                    positional = arg; continue;
                }
                if (!allowed.contains(arg)) throw new ToolException(2, "Tùy chọn không hợp lệ: " + arg);
                if (++i >= args.length || args[i].startsWith("--") || args[i].isBlank()) throw new ToolException(2, "Thiếu giá trị cho " + arg);
                if (arg.equals("--source-root")) roots.add(args[i]);
                else if (options.putIfAbsent(arg, args[i]) != null) throw new ToolException(2, "Tùy chọn bị lặp: " + arg);
            }
            if (index) {
                if (positional == null) throw new ToolException(2, "Thiếu repo cho index");
                int release = number(options.getOrDefault("--release", "17"), "release", 1);
                SourceInputs inputs = SourceInputs.collect(Path.of(positional), roots,
                        options.containsKey("--classpath-file") ? Path.of(options.get("--classpath-file")) : null, release);
                SnapshotStore store = new SnapshotStore(inputs.repo());
                Snapshot snapshot;
                try (var ignored = store.lock()) { snapshot = store.publish(new JavaIndexer(inputs).analyze()); }
                long unresolved = snapshot.problems().stream().filter(p -> Set.of("COMPILER_ERROR_AT_CALL", "UNRESOLVED_SYMBOL", "UNRESOLVED_TYPE", "UNRESOLVED_RECEIVER", "UNASSIGNED_CALLER").contains(p.reason())).count();
                stdout.printf(Locale.ROOT, "Indexed files=%s methods=%d calls=%d endpoints=%d unresolved=%d diagnostics=%d status=%s release=%d snapshot=%s%n",
                        snapshot.manifest().getProperty("files"), snapshot.methods().size(), snapshot.calls().size(), snapshot.endpoints().size(),
                        unresolved, snapshot.problems().size(), snapshot.manifest().getProperty("status"), release, snapshot.manifest().getProperty("snapshotId"));
                if (!snapshot.problems().isEmpty()) stderr.println("Cảnh báo: có " + snapshot.problems().size() + " diagnostics; xem unresolved.tsv và báo cáo query.");
            } else {
                if (!options.containsKey("--repo") || !options.containsKey("--method")) throw new ToolException(2, "query cần --repo và --method");
                int depth = number(options.getOrDefault("--max-depth", "10"), "max-depth", 0);
                Snapshot snapshot = new SnapshotStore(Path.of(options.get("--repo")).toAbsolutePath().normalize()).load();
                ImpactQuery query = new ImpactQuery(snapshot);
                Path output = Path.of(options.getOrDefault("--out", "impact.md")).toAbsolutePath().normalize();
                String report = query.report(query.select(options.get("--method")), depth);
                Path htmlOutput = HtmlReport.outputPath(output);
                String html = HtmlReport.render(report);
                SnapshotStore.atomicWrite(output, report);
                SnapshotStore.atomicWrite(htmlOutput, html);
                if (snapshot.manifest().getProperty("status").equals("PARTIAL")) stderr.println("Cảnh báo: snapshot PARTIAL; kết quả có thể chưa đầy đủ.");
                if (report.contains("còn cạnh hợp lệ chưa duyệt")) stderr.println("Cảnh báo: truy vấn bị giới hạn độ sâu.");
                stdout.println(output);
                stdout.println(htmlOutput);
            }
            return 0;
        } catch (ToolException ex) { stderr.println(ex.getMessage()); return ex.exitCode(); }
        catch (InvalidPathException ex) { stderr.println("Đường dẫn không hợp lệ: " + ex.getMessage()); return 2; }
        catch (IOException | SecurityException ex) { stderr.println("Lỗi môi trường/I/O: " + ex.getMessage()); return 1; }
    }
    private static int number(String input, String name, int minimum) throws ToolException {
        try {
            int value = Integer.parseInt(input);
            if (value < minimum) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException ex) { throw new ToolException(2, name + " phải là số nguyên >= " + minimum); }
    }
}
