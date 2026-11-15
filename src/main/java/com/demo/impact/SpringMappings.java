package com.demo.impact;

import com.sun.source.util.TreePath;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeKind;
import java.util.*;
import static com.demo.impact.Model.*;

/** Deliberately conservative Spring MVC subset. Unsupported combinations produce coverage diagnostics. */
final class SpringMappings {
    private static final String WEB = "org.springframework.web.bind.annotation.";
    private static final String CONTROLLER = "org.springframework.stereotype.Controller";
    private static final Set<String> MAPPINGS = Set.of(WEB + "RequestMapping", WEB + "GetMapping", WEB + "PostMapping",
            WEB + "PutMapping", WEB + "PatchMapping", WEB + "DeleteMapping");
    private final JavaIndexer index;
    private record Mapping(List<String> paths, List<String> verbs, List<String> params) {}
    SpringMappings(JavaIndexer index) { this.index = index; }
    void collect(TypeElement type) {
        TreePath classPath = index.trees().getPath(type);
        if (classPath == null) return;
        boolean controller = type.getAnnotationMirrors().stream().anyMatch(a ->
                a.getAnnotationType().getKind() != TypeKind.ERROR && Set.of(CONTROLLER, WEB + "RestController").contains(name(a)));
        for (AnnotationMirror a : type.getAnnotationMirrors()) {
            if (!Set.of(CONTROLLER, WEB + "RestController").contains(name(a)) &&
                    meta(a, Set.of(CONTROLLER, WEB + "RestController"), new HashSet<>()))
                index.problem(classPath, "", "CUSTOM_CONTROLLER", "Controller annotation tùy biến chưa hỗ trợ: " + name(a));
        }
        if (!controller) return;
        Mapping prefix = mapping(type, classPath, "");
        if (prefix == null) return;
        for (Element member : index.elements().getAllMembers(type)) {
            if (!member.getEnclosingElement().equals(type) && member instanceof ExecutableElement && hasMapping(member))
                index.problem(classPath, "", "INHERITED_HANDLER", "Handler thừa kế chưa hỗ trợ: " + member);
        }
        inspectSupertypes(type, classPath, new HashSet<>());
        for (Element member : type.getEnclosedElements()) {
            if (!(member instanceof ExecutableElement executable) || member.getKind() != ElementKind.METHOD) continue;
            String id = index.signature(executable);
            if (id == null || !index.methods().containsKey(id)) continue;
            TreePath path = index.trees().getPath(member);
            if (path == null || !hasMapping(member)) continue;
            Mapping mapping = mapping(member, path, id);
            if (mapping == null) continue;
            if (!prefix.verbs().isEmpty() && !mapping.verbs().isEmpty()) {
                index.problem(path, id, "MAPPING_BOTH_VERBS", "Cả class và method giới hạn HTTP verb; chưa kiểm chứng kết hợp với phiên bản Spring của repo");
                continue;
            }
            List<String> verbs = mapping.verbs().isEmpty() ? prefix.verbs() : mapping.verbs();
            if (verbs.isEmpty()) verbs = List.of("ANY");
            for (String left : prefix.paths()) for (String right : mapping.paths()) {
                String route = join(left, right);
                if (route.contains("${") || route.contains("#{"))
                    index.problem(path, id, "MAPPING_PLACEHOLDER", "Route phụ thuộc cấu hình runtime: " + route);
                List<String> conditions = new ArrayList<>(prefix.params());
                conditions.addAll(mapping.params());
                for (String verb : verbs) index.endpoint(new Endpoint(verb, route, conditions, id, index.source(path), index.line(path), index.column(path)));
            }
        }
    }
    private void inspectSupertypes(TypeElement type, TreePath path, Set<String> visited) {
        if (!visited.add(type.getQualifiedName().toString())) return;
        List<javax.lang.model.type.TypeMirror> parents = new ArrayList<>(type.getInterfaces());
        if (type.getSuperclass().getKind() != TypeKind.NONE) parents.add(type.getSuperclass());
        for (var parent : parents) {
            if (!(parent instanceof javax.lang.model.type.DeclaredType declared) || !(declared.asElement() instanceof TypeElement t)) continue;
            if (hasMapping(t) || t.getEnclosedElements().stream().anyMatch(this::hasMapping))
                index.problem(path, "", "INHERITED_MAPPING", "Mapping trên kiểu cha/interface chưa hỗ trợ: " + t.getQualifiedName());
            inspectSupertypes(t, path, visited);
        }
    }
    private boolean hasMapping(Element element) {
        return element.getAnnotationMirrors().stream().anyMatch(a -> MAPPINGS.contains(name(a)) || meta(a, MAPPINGS, new HashSet<>()));
    }
    private Mapping mapping(Element element, TreePath path, String caller) {
        List<AnnotationMirror> found = new ArrayList<>();
        boolean custom = false;
        for (AnnotationMirror a : element.getAnnotationMirrors()) {
            if (a.getAnnotationType().getKind() == TypeKind.ERROR) continue;
            if (MAPPINGS.contains(name(a))) found.add(a);
            else if (meta(a, MAPPINGS, new HashSet<>())) {
                index.problem(path, caller, "CUSTOM_MAPPING", "Mapping annotation tùy biến chưa hỗ trợ: " + name(a)); custom = true;
            }
        }
        if (custom) return null;
        if (found.isEmpty()) return new Mapping(List.of(""), List.of(), List.of());
        if (found.size() > 1) {
            index.problem(path, caller, "MULTIPLE_MAPPINGS", "Nhiều mapping annotation trên cùng khai báo chưa hỗ trợ"); return null;
        }
        AnnotationMirror a = found.get(0);
        TreePath annotationPath = index.trees().getPath(element, a);
        if (annotationPath != null && index.hasError(annotationPath)) {
            index.problem(path, caller, "UNRESOLVED_MAPPING", a.toString()); return null;
        }
        Map<String, List<String>> values = new HashMap<>();
        for (var entry : a.getElementValues().entrySet()) {
            List<String> strings = new ArrayList<>();
            if (!flatten(entry.getValue(), strings)) {
                index.problem(path, caller, "UNRESOLVED_MAPPING", a.toString()); return null;
            }
            values.put(entry.getKey().getSimpleName().toString(), strings);
        }
        List<String> value = values.getOrDefault("value", List.of()), paths = values.getOrDefault("path", List.of());
        if (!value.isEmpty() && !paths.isEmpty() && !value.equals(paths)) {
            index.problem(path, caller, "MAPPING_ALIAS_CONFLICT", "value và path không khớp"); return null;
        }
        if (paths.isEmpty()) paths = value;
        if (paths.isEmpty()) paths = List.of("");
        String name = name(a).substring(WEB.length());
        List<String> verbs = name.equals("RequestMapping") ? values.getOrDefault("method", List.of())
                : List.of(name.substring(0, name.length() - "Mapping".length()).toUpperCase(Locale.ROOT));
        List<String> params = values.getOrDefault("params", List.of());
        if (values.keySet().stream().anyMatch(k -> Set.of("headers", "consumes", "produces").contains(k)))
            index.problem(path, caller, "MAPPING_CONDITIONS", "Điều kiện headers/consumes/produces không được lọc trong báo cáo");
        return new Mapping(paths, verbs, params);
    }
    private boolean flatten(AnnotationValue value, List<String> out) {
        Object raw = value.getValue();
        if (raw instanceof List<?> list) {
            for (Object item : list) if (!(item instanceof AnnotationValue v) || !flatten(v, out)) return false;
            return true;
        }
        if (raw instanceof String s) { if (s.equals("<error>")) return false; out.add(s); return true; }
        if (raw instanceof VariableElement v) { out.add(v.getSimpleName().toString()); return true; }
        return false;
    }
    private boolean meta(AnnotationMirror a, Set<String> targets, Set<String> visited) {
        String name = name(a);
        if (a.getAnnotationType().getKind() == TypeKind.ERROR || name.startsWith("java.lang.annotation.") || !visited.add(name)) return false;
        for (AnnotationMirror parent : a.getAnnotationType().asElement().getAnnotationMirrors())
            if (targets.contains(name(parent)) || meta(parent, targets, visited)) return true;
        return false;
    }
    private static String name(AnnotationMirror a) { return a.getAnnotationType().toString(); }
    private static String join(String prefix, String suffix) {
        if (prefix.isEmpty()) return suffix.isEmpty() ? "" : suffix.startsWith("/") ? suffix : "/" + suffix;
        String left = prefix.startsWith("/") ? prefix : "/" + prefix;
        if (suffix.isEmpty()) return left;
        if (left.endsWith("/") && suffix.startsWith("/")) return left + suffix.substring(1);
        return left.endsWith("/") || suffix.startsWith("/") ? left + suffix : left + "/" + suffix;
    }
}
