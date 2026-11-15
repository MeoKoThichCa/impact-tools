package com.demo.impact;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.*;
import javax.tools.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static com.demo.impact.Model.*;

/** Compiler-based source analysis with narrowly scoped Lombok member generation. */
public final class JavaIndexer {
    private final SourceInputs input;
    private Trees trees;
    private Elements elements;
    private Types types;
    private List<Diagnostic<? extends JavaFileObject>> diagnostics;
    private final Map<JavaFileObject, String> sourcePaths = new IdentityHashMap<>();
    private final Map<String, String> sourceTexts = new HashMap<>();
    private final Map<String, List<Diagnostic<? extends JavaFileObject>>> errorsBySource = new HashMap<>();
    private final Map<String, Method> methods = new TreeMap<>();
    private final Map<String, ExecutableElement> executables = new TreeMap<>();
    private final Map<String, TreePath> methodPaths = new TreeMap<>();
    private final Map<String, TypeElement> sourceTypes = new TreeMap<>();
    private final Map<String, List<Map.Entry<String, ExecutableElement>>> candidatesByName = new HashMap<>();
    private final Map<TypeElement, Set<? extends Element>> runtimeMembers = new HashMap<>();
    private final Map<String, List<String>> dispatchCache = new HashMap<>();
    private final List<Call> calls = new ArrayList<>();
    private final List<Endpoint> endpoints = new ArrayList<>();
    private final List<Problem> problems = new ArrayList<>();
    public JavaIndexer(SourceInputs input) { this.input = input; }

    public Snapshot analyze() throws IOException, ToolException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new ToolException(1, "Cần chạy bằng JDK có JavaCompiler");
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(collector, Locale.ROOT, StandardCharsets.UTF_8)) {
            manager.setLocationFromPaths(StandardLocation.CLASS_PATH, input.classpath());
            // Empty source path prevents javac from implicitly reading sources outside the selected roots.
            manager.setLocationFromPaths(StandardLocation.SOURCE_PATH, List.of());
            Path lombok = input.classpath().stream().filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).matches("lombok(-[^/]*)?\\.jar")).findFirst().orElse(null);
            boolean lombokAnnotations = input.files().stream().anyMatch(this::usesLombok);
            List<String> options = new ArrayList<>(List.of(lombok == null ? "-proc:none" : "-processor",
                    lombok == null ? "-implicit:none" : "lombok.launch.AnnotationProcessorHider$AnnotationProcessor",
                    "-implicit:none", "--release", Integer.toString(input.release()), "-encoding", "UTF-8", "-Xmaxerrs", "1000000", "-Xmaxwarns", "1000000"));
            if (lombok != null) { options.add("-processorpath"); options.add(lombok.toString()); }
            JavacTask task;
            try { task = (JavacTask) compiler.getTask(new StringWriter(), manager, collector, options, null,
                    manager.getJavaFileObjectsFromPaths(input.files())); }
            catch (IllegalArgumentException ex) { throw new ToolException(2, "Cấu hình javac/release không được hỗ trợ: " + ex.getMessage(), ex); }
            List<CompilationUnitTree> units = new ArrayList<>();
            task.parse().forEach(units::add);
            checkDuplicateTypes(units);
            task.analyze();
            trees = Trees.instance(task); elements = task.getElements(); types = task.getTypes();
            diagnostics = collector.getDiagnostics();
            if (lombokAnnotations && lombok == null)
                problems.add(new Problem("", "", 0, 0, "LOMBOK_PROCESSOR_MISSING", "Phát hiện annotation Lombok nhưng compile classpath không có lombok.jar; method/field Lombok sinh sẽ không được phân giải."));
            for (var diagnostic : diagnostics) if (diagnostic.getKind() == Diagnostic.Kind.ERROR && diagnostic.getSource() != null)
                errorsBySource.computeIfAbsent(source(diagnostic.getSource()), ignored -> new ArrayList<>()).add(diagnostic);
            for (CompilationUnitTree unit : units) new DeclarationScanner().scan(unit, null);
            if (methods.isEmpty()) throw new ToolException(1, "Không tạo được method hợp lệ; giữ snapshot trước");
            for (var entry : executables.entrySet()) candidatesByName.computeIfAbsent(
                    entry.getValue().getSimpleName().toString(), ignored -> new ArrayList<>()).add(entry);
            collectCompilerDiagnostics();
            for (CompilationUnitTree unit : units) new CallScanner().scan(unit, null);
            SpringMappings spring = new SpringMappings(this);
            for (TypeElement type : sourceTypes.values()) spring.collect(type);
            Properties manifest = new Properties();
            manifest.setProperty("status", diagnostics.stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR) ? "PARTIAL" : "COMPLETE");
            manifest.setProperty("jdkVersion", System.getProperty("java.version"));
            manifest.setProperty("release", Integer.toString(input.release()));
            manifest.setProperty("files", Integer.toString(input.files().size()));
            return new Snapshot(manifest, sorted(methods.values(), METHODS), sorted(calls, CALLS), sorted(endpoints, ENDPOINTS), sorted(problems, PROBLEMS));
        } catch (ToolException | IOException ex) { throw ex; }
        catch (RuntimeException | LinkageError ex) { throw new ToolException(1, "Compiler ngừng bất thường: " + ex.getMessage(), ex); }
    }
    private void checkDuplicateTypes(List<CompilationUnitTree> units) throws ToolException {
        Map<String, String> owners = new HashMap<>();
        List<String> duplicates = new ArrayList<>();
        for (CompilationUnitTree unit : units) new TreeScanner<Void, String>() {
            @Override public Void visitClass(ClassTree tree, String prefix) {
                if (tree.getSimpleName().length() == 0) return null;
                String name = prefix + tree.getSimpleName();
                String source = unit.getSourceFile().getName();
                String previous = owners.putIfAbsent(name, source);
                if (previous != null) duplicates.add(name + " (" + previous + ", " + source + ")");
                for (Tree member : tree.getMembers()) if (member instanceof ClassTree) scan(member, name + "$");
                return null;
            }
        }.scan(unit, unit.getPackageName() == null ? "" : unit.getPackageName() + ".");
        if (!duplicates.isEmpty()) throw new ToolException(2, "Các input cung cấp cùng tên kiểu Java: " + String.join(", ", duplicates));
    }
    private class DeclarationScanner extends TreePathScanner<Void, Void> {
        @Override public Void visitClass(ClassTree node, Void unused) {
            Element element = trees.getElement(getCurrentPath());
            if (element instanceof TypeElement type) sourceTypes.put(elements.getBinaryName(type).toString(), type);
            return super.visitClass(node, unused);
        }
        @Override public Void visitMethod(MethodTree node, Void unused) {
            Element element = trees.getElement(getCurrentPath());
            if (element instanceof ExecutableElement executable) {
                String id = signature(executable);
                long start = start(getCurrentPath());
                if (id != null) {
                    String owner = elements.getBinaryName((TypeElement) executable.getEnclosingElement()).toString();
                    String params = String.join(",", executable.getParameters().stream().map(p -> typeName(p.asType())).toList());
                    TreePath location = start >= 0 ? getCurrentPath() : enclosingClassPath(getCurrentPath());
                    if (location == null) { problem(getCurrentPath(), "", "INVALID_METHOD", node.getName().toString()); return super.visitMethod(node, unused); }
                    Method method = new Method(id, owner, executable.getSimpleName().toString(), params,
                            source(location), Math.max(1, line(location)), Math.max(1, column(location)));
                    if (methods.putIfAbsent(id, method) != null) {
                        if (start >= 0) problem(getCurrentPath(), "", "DUPLICATE_METHOD", id);
                    } else { executables.put(id, executable); if (start >= 0) methodPaths.put(id, getCurrentPath()); }
                } else problem(getCurrentPath(), "", "INVALID_METHOD", node.getName().toString());
            }
            return super.visitMethod(node, unused);
        }
    }
    private TreePath enclosingClassPath(TreePath path) {
        for (TreePath current = path.getParentPath(); current != null; current = current.getParentPath())
            if (current.getLeaf() instanceof ClassTree) return current;
        return null;
    }
    private boolean usesLombok(Path source) {
        try {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            return text.contains("import lombok.") || text.matches("(?s).*@lombok\\..*")
                    || text.matches("(?s).*@(?:Getter|Setter|Data|Value|Builder|SuperBuilder|With|Wither|ToString|EqualsAndHashCode|AllArgsConstructor|NoArgsConstructor|RequiredArgsConstructor|Slf4j|Log4j2|Log)\\b.*");
        } catch (IOException ex) { return false; }
    }
    String signature(ExecutableElement method) {
        if (!(method.getEnclosingElement() instanceof TypeElement owner) || invalid(owner.asType())) return null;
        List<String> params = new ArrayList<>();
        for (VariableElement parameter : method.getParameters()) {
            String type = typeName(parameter.asType());
            if (type == null) return null;
            params.add(type);
        }
        return elements.getBinaryName(owner) + "#" + method.getSimpleName() + "(" + String.join(",", params) + ")";
    }
    private String typeName(TypeMirror mirror) {
        if (mirror == null || invalid(mirror)) return null;
        TypeMirror erased = types.erasure(mirror);
        if (erased.getKind().isPrimitive()) return erased.getKind().name().toLowerCase(Locale.ROOT);
        if (erased.getKind() == TypeKind.ARRAY) {
            String component = typeName(((ArrayType) erased).getComponentType());
            return component == null ? null : component + "[]";
        }
        if (erased instanceof DeclaredType d && d.asElement() instanceof TypeElement t)
            return elements.getBinaryName(t).toString();
        return erased.toString();
    }
    private boolean invalid(TypeMirror mirror) {
        return invalid(mirror, new HashSet<>());
    }
    private boolean invalid(TypeMirror mirror, Set<String> visiting) {
        if (mirror == null || mirror.getKind() == TypeKind.ERROR || mirror.getKind() == TypeKind.NONE) return true;
        if (!visiting.add(mirror.getKind() + ":" + mirror)) return false;
        if (mirror instanceof ArrayType a) return invalid(a.getComponentType(), visiting);
        if (mirror instanceof TypeVariable t) return invalid(t.getUpperBound(), visiting);
        if (mirror instanceof IntersectionType i) return i.getBounds().stream().anyMatch(b -> invalid(b, visiting));
        if (mirror instanceof DeclaredType d) return d.getTypeArguments().stream().anyMatch(a -> invalid(a, visiting));
        if (mirror instanceof WildcardType w) return w.getExtendsBound() != null && invalid(w.getExtendsBound(), visiting)
                || w.getSuperBound() != null && invalid(w.getSuperBound(), visiting);
        return false;
    }
    private class CallScanner extends TreePathScanner<Void, Void> {
        private String caller = "";
        private int lambdaDepth;
        @Override public Void visitClass(ClassTree tree, Void unused) {
            String previous = caller; int previousDepth = lambdaDepth;
            caller = ""; lambdaDepth = 0;
            try { return super.visitClass(tree, unused); } finally { caller = previous; lambdaDepth = previousDepth; }
        }
        @Override public Void visitMethod(MethodTree tree, Void unused) {
            String previous = caller; int previousDepth = lambdaDepth;
            Element element = trees.getElement(getCurrentPath());
            caller = element instanceof ExecutableElement e ? signature(e) : "";
            if (caller == null || !methods.containsKey(caller)) caller = "";
            lambdaDepth = 0;
            try { scan(tree.getBody(), unused); return null; } finally { caller = previous; lambdaDepth = previousDepth; }
        }
        @Override public Void visitLambdaExpression(LambdaExpressionTree tree, Void unused) {
            lambdaDepth++;
            try { return super.visitLambdaExpression(tree, unused); } finally { lambdaDepth--; }
        }
        @Override public Void visitMethodInvocation(MethodInvocationTree tree, Void unused) {
            recordCall(tree, tree.getMethodSelect(), false); return super.visitMethodInvocation(tree, unused);
        }
        @Override public Void visitNewClass(NewClassTree tree, Void unused) {
            recordCall(tree, null, false); return super.visitNewClass(tree, unused);
        }
        @Override public Void visitMemberReference(MemberReferenceTree tree, Void unused) {
            recordCall(tree, tree.getQualifierExpression(), true); return super.visitMemberReference(tree, unused);
        }
        private void recordCall(Tree tree, ExpressionTree select, boolean reference) {
            TreePath path = getCurrentPath();
            if (start(path) < 0 || end(path) < 0) return; // javac-generated implicit super() has no source expression
            if (caller.isEmpty()) {
                String context = inInitializer(path) ? "Lời gọi trong phần khởi tạo. " : "";
                problem(path, "", "UNASSIGNED_CALLER", context + "Công cụ chưa xác định được phương thức gọi. Biểu thức " + originalExpression(path) + ".");
                return;
            }
            if (hasError(path)) { problem(path, caller, "COMPILER_ERROR_AT_CALL", tree.toString()); return; }
            Element element = trees.getElement(path);
            if (!(element instanceof ExecutableElement target)) { problem(path, caller, "UNRESOLVED_SYMBOL", tree.toString()); return; }
            String callee = signature(target);
            if (callee == null || invalid(target.getReturnType())) { problem(path, caller, "UNRESOLVED_TYPE", tree.toString()); return; }
            TypeMirror receiver = receiver(path, select, reference);
            if (receiver != null && invalid(receiver)) { problem(path, caller, "UNRESOLVED_RECEIVER", tree.toString()); return; }
            Kind base = reference ? Kind.REFERENCE : lambdaDepth > 0 ? Kind.DEFERRED : Kind.DECLARED;
            addCall(path, caller, callee, methods.containsKey(callee) ? base : Kind.EXTERNAL);
            if (target.getSimpleName().contentEquals("registerMapping") &&
                    target.getEnclosingElement().toString().startsWith("org.springframework.web."))
                problem(path, caller, "DYNAMIC_MAPPING", "Đăng ký router runtime chưa được phân tích");
            if (direct(target, select, receiver)) return;
            String cacheKey = callee + "@" + (receiver == null ? "" : types.erasure(receiver));
            List<String> targets = dispatchCache.computeIfAbsent(cacheKey, ignored -> dispatchTargets(target, callee, receiver));
            for (String possible : targets) addCall(path, caller, possible, base == Kind.DECLARED ? Kind.POSSIBLE_DISPATCH : base);
        }
    }
    private boolean inInitializer(TreePath path) {
        for (TreePath current = path; current != null && !(current.getLeaf() instanceof ClassTree); current = current.getParentPath()) {
            Tree leaf = current.getLeaf();
            TreePath parent = current.getParentPath();
            if (parent != null && parent.getLeaf() instanceof ClassTree
                    && (leaf instanceof VariableTree || leaf instanceof BlockTree)) return true;
        }
        return false;
    }
    private String originalExpression(TreePath path) {
        long start = start(path), end = end(path);
        if (start < 0 || end < start || end > Integer.MAX_VALUE) return path.getLeaf().toString();
        String relative = source(path);
        try {
            String text = sourceTexts.computeIfAbsent(relative, file -> {
                try { return Files.readString(input.repo().resolve(file), StandardCharsets.UTF_8); }
                catch (IOException ex) { return ""; }
            });
            if (end <= text.length()) return text.substring((int) start, (int) end);
        } catch (RuntimeException ignored) { }
        return path.getLeaf().toString();
    }
    private List<String> dispatchTargets(ExecutableElement target, String callee, TypeMirror receiver) {
            List<String> targets = new ArrayList<>();
            for (Map.Entry<String, ExecutableElement> entry : candidatesByName.getOrDefault(target.getSimpleName().toString(), List.of())) {
                ExecutableElement candidate = entry.getValue();
                if (entry.getKey().equals(callee) || candidate.getModifiers().contains(Modifier.ABSTRACT)
                        || !candidate.getSimpleName().contentEquals(target.getSimpleName()) || candidate.getKind() != ElementKind.METHOD) continue;
                TypeElement candidateOwner = (TypeElement) candidate.getEnclosingElement();
                boolean compatible = false;
                for (TypeElement runtimeType : sourceTypes.values()) {
                    if (runtimeType.getKind().isInterface() || runtimeType.getModifiers().contains(Modifier.ABSTRACT)) continue;
                    if (receiver != null && !types.isSubtype(types.erasure(runtimeType.asType()), types.erasure(receiver))) continue;
                    if (types.isSubtype(types.erasure(runtimeType.asType()), types.erasure(candidateOwner.asType()))
                            && elements.overrides(candidate, target, runtimeType)
                            && runtimeMembers.computeIfAbsent(runtimeType, t -> new HashSet<>(elements.getAllMembers(t))).contains(candidate))
                        { compatible = true; break; }
                }
                if (compatible) targets.add(entry.getKey());
            }
            return List.copyOf(targets);
    }
    private TypeMirror receiver(TreePath path, ExpressionTree expression, boolean reference) {
        if (expression == null) return null;
        if (reference) return trees.getTypeMirror(new TreePath(path, expression));
        if (expression instanceof MemberSelectTree member)
            return trees.getTypeMirror(new TreePath(new TreePath(path, expression), member.getExpression()));
        for (TreePath enclosing = path; enclosing != null; enclosing = enclosing.getParentPath())
            if (enclosing.getLeaf() instanceof ClassTree) return trees.getTypeMirror(enclosing);
        return null;
    }
    private boolean direct(ExecutableElement target, ExpressionTree select, TypeMirror receiver) {
        if (target.getKind() == ElementKind.CONSTRUCTOR || target.getModifiers().stream().anyMatch(m ->
                m == Modifier.PRIVATE || m == Modifier.STATIC || m == Modifier.FINAL)) return true;
        if (target.getEnclosingElement().getModifiers().contains(Modifier.FINAL)) return true;
        if (receiver != null && types.asElement(receiver) instanceof TypeElement t && t.getModifiers().contains(Modifier.FINAL)) return true;
        String text = select == null ? "" : select.toString();
        return text.equals("super") || text.startsWith("super.") || text.endsWith(".super") || text.contains(".super.");
    }
    private void addCall(TreePath path, String caller, String callee, Kind kind) {
        calls.add(new Call(caller, callee, source(path), line(path), column(path), kind));
    }
    boolean hasError(TreePath path) {
        long start = start(path), end = end(path);
        return errorsBySource.getOrDefault(source(path), List.of()).stream().anyMatch(d ->
                (d.getPosition() >= start && d.getPosition() < end ||
                    d.getStartPosition() >= 0 && d.getStartPosition() < end && d.getEndPosition() > start));
    }
    private void collectCompilerDiagnostics() {
        Map<String, List<Map.Entry<String, TreePath>>> methodsBySource = new HashMap<>();
        for (var entry : methodPaths.entrySet()) methodsBySource.computeIfAbsent(source(entry.getValue()), ignored -> new ArrayList<>()).add(entry);
        for (Diagnostic<? extends JavaFileObject> d : diagnostics) {
            String caller = "", source = "";
            if (d.getSource() != null) {
                source = source(d.getSource());
                long smallest = Long.MAX_VALUE;
                for (Map.Entry<String, TreePath> entry : methodsBySource.getOrDefault(source, List.of())) {
                    TreePath path = entry.getValue();
                    if (d.getPosition() >= start(path) && d.getPosition() < end(path)
                            && end(path) - start(path) < smallest) { caller = entry.getKey(); smallest = end(path) - start(path); }
                }
            }
            problems.add(new Problem(caller, source, Math.max(0, d.getLineNumber()), Math.max(0, d.getColumnNumber()),
                    "COMPILER_" + d.getKind(), d.getCode() + ": " + d.getMessage(Locale.ROOT)));
        }
    }
    void problem(TreePath path, String caller, String reason, String detail) {
        problems.add(new Problem(caller, source(path), line(path), column(path), reason, detail));
    }
    String source(TreePath path) { return source(path.getCompilationUnit().getSourceFile()); }
    private String source(JavaFileObject file) {
        // On Windows PathFileObject.toUri() performs a filesystem stat; never repeat it per edge/diagnostic.
        return sourcePaths.computeIfAbsent(file, f -> input.relative(Path.of(f.toUri())));
    }
    long start(TreePath path) { return trees.getSourcePositions().getStartPosition(path.getCompilationUnit(), path.getLeaf()); }
    long end(TreePath path) { return trees.getSourcePositions().getEndPosition(path.getCompilationUnit(), path.getLeaf()); }
    long line(TreePath path) { return start(path) < 0 ? 0 : path.getCompilationUnit().getLineMap().getLineNumber(start(path)); }
    long column(TreePath path) { return start(path) < 0 ? 0 : path.getCompilationUnit().getLineMap().getColumnNumber(start(path)); }
    Trees trees() { return trees; }
    Elements elements() { return elements; }
    Map<String, Method> methods() { return methods; }
    void endpoint(Endpoint endpoint) { endpoints.add(endpoint); }
}
