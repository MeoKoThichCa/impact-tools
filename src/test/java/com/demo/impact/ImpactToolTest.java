package com.demo.impact;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.demo.impact.Model.*;

class ImpactToolTest {
    @Test void queryWritesMarkdownAndOfflineHtml() throws Exception {
        springFixture();
        Path output = temp.resolve("impact-check.md");
        assertEquals(0, cli("query", "--repo", repo.toString(), "--method", "p.TaskService#validDto(java.lang.String)",
                "--out", output.toString()), err());
        String markdown = Files.readString(output);
        String html = Files.readString(temp.resolve("impact-check.html"));
        for (String heading : List.of("API có thể bị ảnh hưởng", "Cây lời gọi ngược", "Diagnostics và giới hạn")) {
            assertTrue(markdown.contains(heading));
            assertTrue(html.contains(heading));
        }
        assertFalse(markdown.contains("## Method gọi trực tiếp và gián tiếp"));
        assertFalse(html.contains("Method gọi trực tiếp và gián tiếp"));
        assertTrue(html.contains("<details open>"));
        assertTrue(html.contains("/v1/tasks"));
    }
    @TempDir Path temp;
    private Path repo;
    private ByteArrayOutputStream stdout, stderr;
    @BeforeEach void setup() throws Exception { repo = Files.createDirectories(temp.resolve("repo tiếng Việt space")); }
    private Path write(String path, String content) throws IOException {
        Path file = repo.resolve(path); Files.createDirectories(file.getParent());
        return Files.writeString(file, content, StandardCharsets.UTF_8);
    }
    private Path source(String name, String content) throws IOException { return write("src/main/java/" + name, content); }
    private int cli(String... args) {
        stdout = new ByteArrayOutputStream(); stderr = new ByteArrayOutputStream();
        return Main.run(args, new PrintStream(stdout, true, StandardCharsets.UTF_8), new PrintStream(stderr, true, StandardCharsets.UTF_8));
    }
    private String out() { return stdout.toString(StandardCharsets.UTF_8); }
    private String err() { return stderr.toString(StandardCharsets.UTF_8); }
    private Snapshot index(boolean spring) throws Exception {
        List<String> args = new ArrayList<>(List.of("index", repo.toString()));
        if (spring) {
            Path cp = write("classpath.txt", String.join("\n", System.getProperty("java.class.path").split(java.io.File.pathSeparator)));
            args.addAll(List.of("--classpath-file", cp.toString()));
        }
        assertEquals(0, cli(args.toArray(String[]::new)), err());
        return new SnapshotStore(repo).load();
    }
    private Snapshot javaFixture() throws Exception {
        source("p/Fixture.java", """
                package p;
                import java.util.List;
                interface I { void work(); }
                class A implements I { public void work() {} }
                class B implements I { public void work() {} }
                class Base { void inherited() {} void change() {} final void fixed() {} static void stat() {} }
                class Child extends Base { @Override void change() {} void viaSuper() { super.change(); } }
                class Sibling extends Base { @Override void change() {} }
                class TaskService {
                    TaskService() { this(0); }
                    TaskService(int x) {}
                    void validDto(String dto) {}
                    void validDto(int dto) {}
                    void create() { this.validDto("a"); }
                    void other() { validDto(1); new MilestoneService().validDto("b"); }
                    void recurse() { create(); recurse(); }
                    void refs() { java.util.function.Consumer<String> c = this::validDto; }
                    void deferred() { Runnable r = () -> validDto("a"); }
                    void construct() { new TaskService(1); java.util.function.Supplier<TaskService> s = TaskService::new; }
                    void dispatch(I i, Child child, Base base) { i.work(); child.change(); child.inherited(); base.change(); base.fixed(); Base.stat(); }
                    void deferredDispatch(I i) { Runnable a = () -> i.work(); Runnable b = i::work; }
                    <T extends Number> void types(T t, List<String> list, int n, String[] values, long... ids) {}
                    class Nested { void nested(Nested[] values) {} }
                    int field = init();
                    static int init() { return 0; }
                }
                class MilestoneService { void validDto(String dto) {} }
                """);
        source("other/TaskService.java", "package other; public class TaskService { public void validDto(String value) {} }");
        write("src/test/java/NotProduct.java", "class NotProduct { void ignored() {} }");
        return index(false);
    }
    private boolean edge(Snapshot s, String caller, String callee, Kind kind) {
        return s.calls().stream().anyMatch(c -> c.caller().equals(caller) && c.callee().equals(callee) && c.kind() == kind);
    }
    private Snapshot springFixture() throws Exception {
        source("p/Web.java", """
                package p;
                import org.springframework.web.bind.annotation.*;
                import org.springframework.stereotype.Controller;
                interface Job { void run(); }
                class First implements Job { public void run() {} }
                class Second implements Job { public void run() {} }
                class TaskService { void validDto(String dto) {} void create(String dto) { validDto(dto); } }
                class MilestoneService { void validDto(String dto) {} }
                @RestController @RequestMapping(path={"/v1", "/v2"})
                class Web {
                    static final String TASKS = "/tasks";
                    TaskService service;
                    @PostMapping(path={TASKS, "/tasks/new"}) void create(String dto) { service.create(dto); }
                    @GetMapping("/jobs") void jobs(Job job) { job.run(); }
                    @RequestMapping("/any") void any() {}
                    @GetMapping("/${tenant}/tasks") void placeholder() {}
                    @GetMapping("/other") void other() { new MilestoneService().validDto("x"); }
                }
                @Controller @RequestMapping(method=RequestMethod.POST) class ClassVerb {
                    @RequestMapping("/inherited-verb") void post() {}
                    @GetMapping("/unsupported") void both() {}
                }
                @RequestMapping("/custom") @interface Custom {}
                @RestController class CustomWeb { @Custom void custom() {} }
                interface Parent { @GetMapping("/parent") void parent(); }
                @RestController class Inherited implements Parent { public void parent() {} }
                """);
        return index(true);
    }

    @Test void codecRoundTripsUnicodeAndEscapes() throws Exception {
        String value = "C:\\Users\\Tên có khoảng trắng\tline\r\n\\n";
        assertEquals(value, Tsv.decode(Tsv.encode(value)));
        Path file = temp.resolve("data.tsv");
        Tsv.write(file, "a\tb", List.of(List.of(value, "")));
        assertEquals(List.of(List.of(value, "")), Tsv.read(file, "a\tb"));
        assertThrows(IOException.class, () -> Tsv.decode("\\q"));
        assertThrows(IOException.class, () -> Tsv.decode("tail\\"));
    }
    @Test void resolvesExactJavaDeclarationsAndTypes() throws Exception {
        Snapshot s = javaFixture();
        assertEquals("COMPLETE", s.manifest().getProperty("status"));
        assertTrue(edge(s, "p.TaskService#create()", "p.TaskService#validDto(java.lang.String)", Kind.DECLARED));
        assertFalse(edge(s, "p.TaskService#create()", "p.TaskService#validDto(int)", Kind.DECLARED));
        assertFalse(edge(s, "p.TaskService#create()", "p.MilestoneService#validDto(java.lang.String)", Kind.DECLARED));
        assertTrue(edge(s, "p.TaskService#dispatch(p.I,p.Child,p.Base)", "p.Base#inherited()", Kind.DECLARED));
        assertTrue(edge(s, "p.Child#viaSuper()", "p.Base#change()", Kind.DECLARED));
        assertFalse(s.calls().stream().anyMatch(c -> c.caller().equals("p.Child#viaSuper()") && c.kind() == Kind.POSSIBLE_DISPATCH));
        Set<String> ids = new HashSet<>(); s.methods().forEach(m -> ids.add(m.id()));
        assertTrue(ids.contains("p.TaskService#types(java.lang.Number,java.util.List,int,java.lang.String[],long[])"));
        assertTrue(ids.contains("p.TaskService$Nested#nested(p.TaskService$Nested[])"));
        assertTrue(ids.contains("other.TaskService#validDto(java.lang.String)"));
        assertFalse(ids.stream().anyMatch(id -> id.contains("NotProduct")));
        assertTrue(s.calls().stream().allMatch(c -> c.line() > 0 && c.column() > 0 && !c.source().contains("\\")));
    }
    @Test void marksReferencesLambdasConstructorsAndInitializers() throws Exception {
        Snapshot s = javaFixture();
        assertTrue(edge(s, "p.TaskService#refs()", "p.TaskService#validDto(java.lang.String)", Kind.REFERENCE));
        assertTrue(edge(s, "p.TaskService#deferred()", "p.TaskService#validDto(java.lang.String)", Kind.DEFERRED));
        assertTrue(edge(s, "p.TaskService#<init>()", "p.TaskService#<init>(int)", Kind.DECLARED));
        assertTrue(edge(s, "p.TaskService#construct()", "p.TaskService#<init>(int)", Kind.DECLARED));
        assertTrue(edge(s, "p.TaskService#construct()", "p.TaskService#<init>()", Kind.REFERENCE));
        assertTrue(s.problems().stream().anyMatch(p -> p.reason().equals("UNASSIGNED_CALLER")));
    }
    @Test void filtersDispatchByReceiverAndPreservesDeferredKinds() throws Exception {
        Snapshot s = javaFixture(); String caller = "p.TaskService#dispatch(p.I,p.Child,p.Base)";
        assertTrue(edge(s, caller, "p.I#work()", Kind.DECLARED));
        assertTrue(edge(s, caller, "p.A#work()", Kind.POSSIBLE_DISPATCH));
        assertTrue(edge(s, caller, "p.B#work()", Kind.POSSIBLE_DISPATCH));
        long childLine = s.calls().stream().filter(c -> c.caller().equals(caller) && c.callee().equals("p.Child#change()") && c.kind() == Kind.DECLARED).findFirst().orElseThrow().column();
        assertFalse(s.calls().stream().anyMatch(c -> c.caller().equals(caller) && c.column() == childLine && c.callee().equals("p.Sibling#change()")));
        assertFalse(s.calls().stream().anyMatch(c -> c.kind() == Kind.POSSIBLE_DISPATCH && (c.callee().contains("#fixed(") || c.callee().contains("#stat("))));
        assertTrue(edge(s, "p.TaskService#deferredDispatch(p.I)", "p.A#work()", Kind.DEFERRED));
        assertTrue(edge(s, "p.TaskService#deferredDispatch(p.I)", "p.B#work()", Kind.REFERENCE));
        assertFalse(s.calls().stream().anyMatch(c -> c.caller().equals("p.TaskService#deferredDispatch(p.I)") && c.kind() == Kind.POSSIBLE_DISPATCH));
    }
    @Test void selectsExactMethodsAndRejectsAmbiguity() throws Exception {
        ImpactQuery q = new ImpactQuery(javaFixture());
        assertEquals("p.TaskService#create()", q.select("create").id());
        ToolException ex = assertThrows(ToolException.class, () -> q.select("validDto"));
        assertEquals(2, ex.exitCode()); assertTrue(ex.getMessage().contains("other.TaskService#validDto(java.lang.String)"));
        assertThrows(ToolException.class, () -> q.select("p.TaskService#validDto"));
        assertThrows(ToolException.class, () -> q.select("missing"));
        String report = q.report(q.select("p.TaskService#validDto(java.lang.String)"), 10);
        assertTrue(report.contains("TaskService\\#create()"));
        assertFalse(report.contains("MilestoneService\\#validDto"));
        assertFalse(report.contains("TaskService\\#other()"));
    }
    @Test void extractsSpringPathsVerbsConstantsAndDispatch() throws Exception {
        Snapshot s = springFixture();
        assertEquals("COMPLETE", s.manifest().getProperty("status"));
        assertEquals(Set.of("/v1/tasks", "/v1/tasks/new", "/v2/tasks", "/v2/tasks/new"),
                new HashSet<>(s.endpoints().stream().filter(e -> e.handler().equals("p.Web#create(java.lang.String)")).map(Endpoint::route).toList()));
        assertTrue(s.endpoints().stream().anyMatch(e -> e.verb().equals("POST") && e.route().equals("/inherited-verb")));
        assertTrue(s.endpoints().stream().anyMatch(e -> e.verb().equals("ANY") && e.route().equals("/v1/any")));
        assertFalse(s.endpoints().stream().anyMatch(e -> e.route().equals("/unsupported") || e.route().equals("/custom")));
        Set<String> reasons = new HashSet<>(); s.problems().forEach(p -> reasons.add(p.reason()));
        assertTrue(reasons.containsAll(Set.of("MAPPING_BOTH_VERBS", "MAPPING_PLACEHOLDER", "CUSTOM_MAPPING", "INHERITED_MAPPING")));
        ImpactQuery q = new ImpactQuery(s);
        String report = q.report(q.select("p.First#run()"), 10);
        assertTrue(report.contains("/v1/jobs")); assertTrue(report.contains("POSSIBLE_DISPATCH")); assertTrue(report.contains("Có thể"));
        String targetReport = q.report(q.select("p.TaskService#validDto(java.lang.String)"), 10);
        assertTrue(targetReport.contains("/v2/tasks/new")); assertFalse(targetReport.contains("/v1/other"));
    }
    @Test void handlerAtZeroDepthAndNoEndpointAreSuccessful() throws Exception {
        Snapshot s = springFixture(); ImpactQuery q = new ImpactQuery(s);
        assertTrue(q.report(q.select("p.Web#create(java.lang.String)"), 0).contains("/v1/tasks"));
        assertTrue(q.report(q.select("p.TaskService#validDto(java.lang.String)"), 0).contains("Không tìm thấy đường gọi tới endpoint trong snapshot này"));
        Path report = temp.resolve("impact.md");
        assertEquals(0, cli("query", "--repo", repo.toString(), "--method", "p.TaskService#validDto(java.lang.String)", "--max-depth", "0", "--out", report.toString()), err());
        assertTrue(Files.exists(report)); assertTrue(err().contains("độ sâu"));
    }
    @Test void bfsKeepsBothEvidenceClassesAndHandlesCyclesConvergence() throws Exception {
        source("Graph.java", """
                class Graph {
                  void target() { first(); }
                  void first() { target(); }
                  void possible() { Runnable r = this::target; }
                  void top() { first(); possible(); }
                  void diamond() { first(); top(); }
                }
                """);
        Snapshot s = index(false); ImpactQuery q = new ImpactQuery(s);
        String report = q.report(q.select("target"), 10);
        assertEquals(2, report.lines().filter(l -> l.stripLeading().startsWith("- Graph\\#top() — ")).count());
        assertTrue(report.contains("**Có thể**"));
        assertTrue(report.contains("**Gọi vòng**"));
        assertEquals(0, report.lines().filter(l -> l.startsWith("| Graph\\#target() | ")).count());
        assertFalse(report.contains("còn cạnh hợp lệ chưa duyệt"));
        assertTrue(q.report(q.select("target"), 1).contains("còn cạnh hợp lệ chưa duyệt"));
        assertEquals(report, q.report(q.select("target"), 10));
    }
    @Test void partialAnalysisKeepsIndependentCallsAndDiagnostics() throws Exception {
        source("Broken.java", """
                class Broken {
                  void target() {}
                  void caller() { target(); missing(); }
                  void bad(Missing value) { value.go(); }
                }
                """);
        Snapshot s = index(false);
        assertEquals("PARTIAL", s.manifest().getProperty("status"));
        assertTrue(edge(s, "Broken#caller()", "Broken#target()", Kind.DECLARED));
        assertFalse(s.calls().stream().anyMatch(c -> c.callee().contains("#missing(")));
        assertTrue(s.problems().stream().anyMatch(p -> p.reason().equals("COMPILER_ERROR_AT_CALL")));
        ImpactQuery q = new ImpactQuery(s); String report = q.report(q.select("target"), 10);
        assertTrue(report.contains("**PARTIAL**")); assertTrue(report.contains("cannot find symbol"));
    }
    @Test void ambiguousCallNeverCreatesAnEdge() throws Exception {
        source("Ambiguous.java", "class Ambiguous { void f(String s) {} void f(Integer n) {} void caller() { f(null); } }");
        Snapshot s = index(false);
        assertEquals("PARTIAL", s.manifest().getProperty("status"));
        assertFalse(s.calls().stream().anyMatch(c -> c.caller().equals("Ambiguous#caller()")));
    }
    @Test void missingSpringDependenciesDoNotInventEndpoints() throws Exception {
        source("Controller.java", "import org.springframework.web.bind.annotation.*; @RestController class Controller { @GetMapping(\"/x\") void x() {} }");
        Snapshot s = index(false);
        assertEquals("PARTIAL", s.manifest().getProperty("status")); assertTrue(s.endpoints().isEmpty());
    }
    @Test void repeatedIndexProducesIdenticalTsvAndRetainsOldSnapshot() throws Exception {
        javaFixture(); String first = Files.readString(repo.resolve(".impact-index/current")).strip();
        index(false); String second = Files.readString(repo.resolve(".impact-index/current")).strip();
        assertNotEquals(first, second);
        for (String file : List.of("methods.tsv", "calls.tsv", "endpoints.tsv", "unresolved.tsv")) {
            assertArrayEquals(Files.readAllBytes(repo.resolve(".impact-index/snapshots/" + first + "/" + file)),
                    Files.readAllBytes(repo.resolve(".impact-index/snapshots/" + second + "/" + file)));
        }
    }
    @Test void failedIndexPreservesCurrent() throws Exception {
        source("A.java", "class A { void ok() {} }"); index(false);
        String before = Files.readString(repo.resolve(".impact-index/current"));
        assertEquals(2, cli("index", repo.toString(), "--release", "999"), err());
        assertEquals(before, Files.readString(repo.resolve(".impact-index/current")));
        Files.delete(repo.resolve("src/main/java/A.java"));
        assertEquals(1, cli("index", repo.toString()));
        assertEquals(before, Files.readString(repo.resolve(".impact-index/current")));
    }
    @Test void filesystemPublicationFailurePreservesCurrent() throws Exception {
        source("A.java", "class A { void ok() {} }"); Snapshot s = index(false);
        Path current = repo.resolve(".impact-index/current"); byte[] before = Files.readAllBytes(current);
        if (System.getProperty("os.name").startsWith("Windows")) {
            try (var channel = java.nio.channels.FileChannel.open(current, StandardOpenOption.READ, StandardOpenOption.WRITE);
                 var lock = channel.lock()) {
                assertThrows(IOException.class, () -> new SnapshotStore(repo).publish(s));
            }
            assertArrayEquals(before, Files.readAllBytes(current));
        } else {
            Path blocked = temp.resolve("blocked"); Files.createDirectory(blocked); Files.writeString(blocked.resolve("keep"), "old");
            assertThrows(IOException.class, () -> SnapshotStore.atomicWrite(blocked, "new"));
            assertArrayEquals(before, Files.readAllBytes(current));
        }
    }
    @Test void writerLockPreventsConcurrentIndex() throws Exception {
        source("A.java", "class A { void ok() {} }"); index(false);
        String before = Files.readString(repo.resolve(".impact-index/current"));
        try (var ignored = new SnapshotStore(repo).lock()) { assertEquals(1, cli("index", repo.toString())); }
        assertEquals(before, Files.readString(repo.resolve(".impact-index/current")));
    }
    @Test void missingFileAndUnsupportedSchemaReturnThree() throws Exception {
        assertEquals(3, cli("query", "--repo", repo.toString(), "--method", "f"));
        source("A.java", "class A { void ok() {} }"); index(false);
        String id = Files.readString(repo.resolve(".impact-index/current")).strip();
        Path manifest = repo.resolve(".impact-index/snapshots/" + id + "/manifest.properties");
        Files.writeString(manifest, Files.readString(manifest).replaceAll("(?m)^schemaVersion=\\d+", "schemaVersion=99"));
        assertEquals(3, cli("query", "--repo", repo.toString(), "--method", "ok"));
        index(false); id = Files.readString(repo.resolve(".impact-index/current")).strip();
        Files.delete(repo.resolve(".impact-index/snapshots/" + id + "/calls.tsv"));
        assertEquals(3, cli("query", "--repo", repo.toString(), "--method", "ok"));
    }
    @Test void rejectsDuplicateIdsBrokenReferencesAndBadTsv() throws Exception {
        source("A.java", "class A { void ok() {} }"); Snapshot s = index(false);
        List<Method> duplicate = new ArrayList<>(s.methods()); duplicate.add(s.methods().get(0));
        assertThrows(ToolException.class, () -> new SnapshotStore(repo).publish(new Snapshot(s.manifest(), duplicate, s.calls(), s.endpoints(), s.problems())));
        assertThrows(ToolException.class, () -> new SnapshotStore(repo).publish(new Snapshot(s.manifest(), s.methods(),
                List.of(new Call("A#ok()", "missing#target()", "src/main/java/A.java", 1, 1, Kind.DECLARED)), s.endpoints(), s.problems())));
        String id = Files.readString(repo.resolve(".impact-index/current")).strip();
        Files.writeString(repo.resolve(".impact-index/snapshots/" + id + "/calls.tsv"), "wrong header\n");
        assertEquals(3, cli("query", "--repo", repo.toString(), "--method", "ok"));
    }
    @Test void rootScopeAndDuplicateTypesAreValidated() throws Exception {
        write("one/src/main/java/p/A.java", "package p; class A { void a() {} }");
        write("two/src/main/java/p/A.java", "package p; class A { void b() {} }");
        assertEquals(2, cli("index", repo.toString()));
        assertEquals(2, cli("index", repo.toString(), "--source-root", "one/src/main/java", "--source-root", "one/src/main/java"));
        assertEquals(2, cli("index", repo.toString(), "--source-root", "one/src/main/java", "--source-root", "two/src/main/java"));
        assertTrue(err().contains("cùng tên kiểu Java"));
        assertEquals(2, cli("index", repo.toString(), "--source-root", temp.toString()));
        assertEquals(0, cli("index", repo.toString(), "--source-root", "one/src/main/java"), err());
    }
    @Test void classpathRelativeEntriesKeepOrderAndMissingEntryFails() throws Exception {
        source("A.java", "class A { void a() {} }");
        Files.createDirectories(repo.resolve("deps/classes one")); Files.createDirectories(repo.resolve("deps/classes two"));
        Path cp = write("deps/cp.txt", "classes two\n\nclasses one\n");
        SourceInputs inputs = SourceInputs.collect(repo, List.of(), cp, 17);
        assertEquals(List.of(repo.resolve("deps/classes two"), repo.resolve("deps/classes one")), inputs.classpath());
        Files.writeString(cp, "missing.jar\n");
        assertEquals(2, cli("index", repo.toString(), "--classpath-file", cp.toString()));
    }
    @Test void cliContractsAndReportFailurePreserveExistingOutput() throws Exception {
        assertEquals(0, cli("--help")); assertTrue(out().contains("index")); assertEquals(0, cli("--version"));
        assertEquals(2, cli("query", "--repo", repo.toString(), "--method", "a", "--max-depth", "-1"));
        assertEquals(2, cli("index", repo.toString(), "--unknown", "value"));
        assertEquals(2, cli("index", repo.toString(), "--release"));
        assertEquals(2, cli("index", repo.toString(), "--release", "17", "--release", "17"));
        source("A.java", "class A { void ok() {} }"); index(false);
        Path output = temp.resolve("report.md"); Files.writeString(output, "old");
        assertEquals(2, cli("query", "--repo", repo.toString(), "--method", "missing", "--out", output.toString()));
        assertEquals("old", Files.readString(output));
        assertEquals(1, cli("query", "--repo", repo.toString(), "--method", "ok", "--out", temp.resolve("missing/report.md").toString()));
        Path directory = temp.resolve("output-directory"); Files.createDirectory(directory); Files.writeString(directory.resolve("keep"), "old");
        assertEquals(1, cli("query", "--repo", repo.toString(), "--method", "ok", "--out", directory.toString()));
        assertEquals("old", Files.readString(directory.resolve("keep")));
    }
    @Test void markdownEscapesUserControlledNamesRoutesAndPaths() {
        assertEquals("a\\|b\\`\\*\\_\\[x\\]&lt;t&gt;&amp;<br>z", ImpactQuery.escape("a|b`*_[x]<t>&\nz"));
    }
    @Test void dispatchExcludesShadowedImplementationAndIncludesInheritedImplementation() throws Exception {
        source("Dispatch.java", """
                interface I { void run(); }
                interface Narrow extends I {}
                class Base { public void run() {} }
                class Inherited extends Base implements I {}
                class Override extends Base implements Narrow { public void run() {} }
                class Client { void all(I i) { i.run(); } void narrow(Narrow i) { i.run(); } }
                """);
        Snapshot s = index(false);
        assertTrue(edge(s, "Client#all(I)", "Base#run()", Kind.POSSIBLE_DISPATCH));
        assertTrue(edge(s, "Client#narrow(Narrow)", "Override#run()", Kind.POSSIBLE_DISPATCH));
        assertFalse(edge(s, "Client#narrow(Narrow)", "Base#run()", Kind.POSSIBLE_DISPATCH));
    }
    @Test void anonymousAndLocalClassesHaveTheirOwnCaller() throws Exception {
        source("Local.java", """
                class Local {
                  void target() {}
                  void enclosing() {
                    new Runnable() { public void run() { target(); } };
                    class Named { void go() { target(); } }
                  }
                }
                """);
        Snapshot s = index(false);
        assertTrue(s.calls().stream().anyMatch(c -> c.caller().equals("Local$1#run()") && c.callee().equals("Local#target()")));
        assertTrue(s.calls().stream().anyMatch(c -> c.caller().contains("Named#go()") && c.callee().equals("Local#target()")));
        assertFalse(edge(s, "Local#enclosing()", "Local#target()", Kind.DECLARED));
    }
    @Test void neverRunsClasspathAnnotationProcessor() throws Exception {
        Path processor = write("processor/ExplodingProcessor.java", """
                import javax.annotation.processing.*;
                import javax.lang.model.SourceVersion;
                import javax.lang.model.element.TypeElement;
                import java.util.Set;
                @SupportedAnnotationTypes("*") @SupportedSourceVersion(SourceVersion.RELEASE_17)
                public class ExplodingProcessor extends AbstractProcessor {
                    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
                        throw new AssertionError("Annotation processor must never run");
                    }
                }
                """);
        Path classes = Files.createDirectories(repo.resolve("processor/classes"));
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-d", classes.toString(), processor.toString()));
        write("processor/classes/META-INF/services/javax.annotation.processing.Processor", "ExplodingProcessor\n");
        source("A.java", "class A { void ok() {} }");
        Path cp = write("cp.txt", "processor/classes\n");
        assertEquals(0, cli("index", repo.toString(), "--classpath-file", cp.toString()), err());
        assertEquals("COMPLETE", new SnapshotStore(repo).load().manifest().getProperty("status"));
    }
    @Test void resolvesExternalBytecodeButDoesNotReadUnselectedSources() throws Exception {
        Path external = write("dependencies/src/lib/External.java", "package lib; public class External { public static void call(String... values) {} }");
        Path classes = Files.createDirectories(repo.resolve("dependencies/classes"));
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-d", classes.toString(), external.toString()));
        write("dependencies/classes/lib/Hidden.java", "package lib; public class Hidden { public static void hidden() {} }");
        source("App.java", "class App { void ok() { lib.External.call(\"x\"); } void missing() { lib.Hidden.hidden(); } }");
        Path cp = write("classpath.txt", "dependencies/classes\n");
        assertEquals(0, cli("index", repo.toString(), "--classpath-file", cp.toString()), err());
        Snapshot s = new SnapshotStore(repo).load();
        assertTrue(edge(s, "App#ok()", "lib.External#call(java.lang.String[])", Kind.EXTERNAL));
        assertEquals("PARTIAL", s.manifest().getProperty("status"));
        assertFalse(s.methods().stream().anyMatch(m -> m.owner().equals("lib.Hidden")));
        assertFalse(s.calls().stream().anyMatch(c -> c.callee().contains("#hidden()")));
    }
    @Test void typeUseAnnotationsAreNotPartOfCanonicalSignature() throws Exception {
        source("Annotated.java", """
                @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE) @interface Mark {}
                class Annotated { void target(@Mark int number, @Mark long[] values, @Mark String text) {} }
                """);
        Snapshot s = index(false);
        assertTrue(s.methods().stream().anyMatch(m -> m.id().equals("Annotated#target(int,long[],java.lang.String)")));
    }
    @Test void mavenAutomaticallyAddsGeneratedSourcesAndExplicitClasspathWins() throws Exception {
        write("pom.xml", "not a valid Maven POM");
        source("src/main/java/Original.java", "class Original { void main() {} }");
        write("target/generated-sources/Generated.java", "class Generated { void generated() {} }");
        write("lib/classes/Library.java", "class Library { void use() {} }");
        Path classpath = write("classpath.txt", "lib/classes\n");
        SourceInputs inputs = SourceInputs.collect(repo, List.of(), classpath, 17);
        assertEquals(2, inputs.roots().size());
        assertEquals(repo.resolve("lib/classes"), inputs.classpath().get(0));
        assertTrue(inputs.files().stream().anyMatch(p -> p.endsWith("Generated.java")));
        assertEquals(0, cli("index", repo.toString(), "--classpath-file", classpath.toString()), err());
        assertTrue(new SnapshotStore(repo).load().methods().stream().anyMatch(m -> m.owner().equals("Generated")));
    }
    @Test void mavenClasspathFailureLeavesExistingSnapshotUntouched() throws Exception {
        source("A.java", "class A { void ok() {} }"); index(false);
        Path current = repo.resolve(".impact-index/current"); String previous = Files.readString(current);
        write("pom.xml", "<project><modelVersion>broken");
        assertEquals(1, cli("index", repo.toString()));
        assertTrue(err().contains("Unable to resolve Maven compile classpath offline"), err());
        assertEquals(previous, Files.readString(current));
    }
    @Test void lombokGeneratedGetterSetterAndNestedCallsReachMvcEndpoint() throws Exception {
        source("lombokfixture/Api.java", """
                package lombokfixture;
                import lombok.Getter;
                import lombok.Setter;
                import org.springframework.web.bind.annotation.*;
                @Getter class FormResponse {
                    private FieldResponse field;
                    @Getter static class FieldResponse {
                        private String name;
                        private Boolean isRequired;
                        String getName(int overload) { return name; }
                    }
                }
                @Setter class ProductParam {
                    private Long enterpriseId;
                }
                @lombok.extern.java.Log class LoggedService { void run() { log.info("generated logger"); } }
                @RestController @RequestMapping("/forms") class FormController {
                    @GetMapping("/{id}") void show() {
                        String name = new FormResponse().getField().getName();
                        Boolean required = new FormResponse.FieldResponse().getIsRequired();
                        new ProductParam().setEnterpriseId(1L);
                    }
                }
                """);
        Snapshot snapshot = index(true);
        assertEquals("COMPLETE", snapshot.manifest().getProperty("status"), snapshot.problems().toString());
        String controller = "lombokfixture.FormController#show()";
        String getField = "lombokfixture.FormResponse#getField()";
        String getName = "lombokfixture.FormResponse$FieldResponse#getName()";
        String getRequired = "lombokfixture.FormResponse$FieldResponse#getIsRequired()";
        String setId = "lombokfixture.ProductParam#setEnterpriseId(java.lang.Long)";
        String logger = "lombokfixture.LoggedService#run()";
        assertTrue(edge(snapshot, controller, getField, Kind.DECLARED));
        assertTrue(edge(snapshot, controller, getName, Kind.DECLARED));
        assertTrue(snapshot.methods().stream().anyMatch(m -> m.id().equals("lombokfixture.FormResponse$FieldResponse#getName(int)")));
        assertTrue(edge(snapshot, controller, getRequired, Kind.DECLARED));
        assertTrue(edge(snapshot, controller, setId, Kind.DECLARED));
        assertTrue(snapshot.methods().stream().anyMatch(m -> m.id().equals(logger)));
        assertFalse(snapshot.problems().stream().anyMatch(p -> p.reason().equals("COMPILER_ERROR_AT_CALL") && p.detail().contains("log.info")));
        assertFalse(snapshot.calls().stream().anyMatch(c -> c.caller().equals(controller)
                && c.callee().equals("lombokfixture.FormResponse$FieldResponse#getName(int)")));
        String report = new ImpactQuery(snapshot).report(new ImpactQuery(snapshot).select(getName), 10);
        assertTrue(report.contains("GET") && report.contains("/forms/{id}") && report.contains("FormController"));
    }
    @Test void lombokAnnotationWithoutJarGetsSpecificDiagnostic() throws Exception {
        source("MissingLombok.java", "@lombok.Getter class MissingLombok { String name; }");
        SourceInputs input = SourceInputs.collect(repo, List.of(), null, 17);
        Snapshot snapshot = new JavaIndexer(input).analyze();
        assertTrue(snapshot.problems().stream().anyMatch(p -> p.reason().equals("LOMBOK_PROCESSOR_MISSING")));
    }
}
