package com.demo.impact;

import java.util.*;
import static com.demo.impact.Model.*;

public final class ImpactQuery {
    private final Snapshot snapshot;
    private record State(String method, boolean possible) {}
    private record Route(State state, List<Call> calls) {}
    private record EndpointKey(String verb, String route, String handler, List<String> params) {}
    public ImpactQuery(Snapshot snapshot) { this.snapshot = snapshot; }
    public Method select(String selector) throws ToolException {
        List<Method> matches = snapshot.methods().stream().filter(m -> m.id().equals(selector) ||
                !selector.contains("(") && (selector.equals(m.name()) || selector.equals(m.owner() + "#" + m.name()))).toList();
        if (matches.isEmpty()) throw new ToolException(2, "Không tìm thấy method: " + selector);
        if (matches.size() > 1) throw new ToolException(2, "Selector mơ hồ; chọn chữ ký đầy đủ:\n" +
                String.join("\n", matches.stream().map(Method::id).sorted().toList()));
        return matches.get(0);
    }
    public String report(Method target, int depth) {
        Map<String, List<Call>> incoming = new HashMap<>();
        for (Call call : sorted(snapshot.calls(), CALLS)) if (call.kind() != Kind.EXTERNAL)
            incoming.computeIfAbsent(call.callee(), ignored -> new ArrayList<>()).add(call);
        Map<State, Route> visited = new LinkedHashMap<>();
        State initial = new State(target.id(), false);
        Route first = new Route(initial, List.of());
        visited.put(initial, first);
        Deque<Route> queue = new ArrayDeque<>(); queue.add(first);
        List<Route> boundary = new ArrayList<>();
        while (!queue.isEmpty()) {
            Route route = queue.removeFirst();
            if (route.calls().size() == depth) { boundary.add(route); continue; }
            for (Call edge : incoming.getOrDefault(route.state().method(), List.of())) {
                State next = new State(edge.caller(), route.state().possible() || edge.kind() != Kind.DECLARED);
                if (visited.containsKey(next)) continue;
                List<Call> chain = new ArrayList<>(); chain.add(edge); chain.addAll(route.calls());
                Route newRoute = new Route(next, List.copyOf(chain));
                visited.put(next, newRoute); queue.addLast(newRoute);
            }
        }
        boolean truncated = boundary.stream().anyMatch(route -> incoming.getOrDefault(route.state().method(), List.of()).stream()
                .anyMatch(edge -> !visited.containsKey(new State(edge.caller(), route.state().possible() || edge.kind() != Kind.DECLARED))));
        Properties manifest = snapshot.manifest();
        StringBuilder out = new StringBuilder("# Báo cáo ảnh hưởng thay đổi method\n\n");
        out.append("- Method: ").append(escape(target.id())).append('\n');
        out.append("- Khai báo: ").append(escape(target.source())).append(':').append(target.line()).append(':').append(target.column()).append('\n');
        out.append("- Snapshot: ").append(escape(manifest.getProperty("snapshotId"))).append('\n');
        out.append("- Lập chỉ mục UTC: ").append(escape(manifest.getProperty("indexedAtUtc"))).append('\n');
        out.append("- Trạng thái: **").append(manifest.getProperty("status")).append("**\n");
        out.append("- JDK: ").append(escape(manifest.getProperty("jdkVersion"))).append("; --release: ").append(manifest.getProperty("release")).append('\n');
        out.append("- Độ sâu tối đa: ").append(depth).append('\n');
        if ("PARTIAL".equals(manifest.getProperty("status"))) out.append("\n**PARTIAL: compiler có lỗi; kết quả có thể thiếu lời gọi và endpoint.**\n");
        if (truncated) out.append("\n**Cảnh báo: còn cạnh hợp lệ chưa duyệt do giới hạn độ sâu.**\n");
        out.append("\n## API có thể bị ảnh hưởng\n\n");
        List<Endpoint> affected = sorted(snapshot.endpoints(), ENDPOINTS).stream().filter(e ->
                visited.containsKey(new State(e.handler(), false)) || visited.containsKey(new State(e.handler(), true))).toList();
        if (affected.isEmpty()) out.append("Không tìm thấy đường gọi tới endpoint trong snapshot này\n");
        else {
            out.append("| HTTP verb | Route | Params | Handler | Mức bằng chứng | Chuỗi gọi |\n|---|---|---|---|---|---|\n");
            Set<EndpointKey> shown = new HashSet<>();
            for (Endpoint endpoint : affected) {
                if (!shown.add(new EndpointKey(endpoint.verb(), endpoint.route(), endpoint.handler(), endpoint.params()))) continue;
                Route route = visited.get(new State(endpoint.handler(), false));
                if (route == null) route = visited.get(new State(endpoint.handler(), true));
                out.append("| ").append(escape(endpoint.verb())).append(" | ").append(escape(displayRoute(endpoint))).append(" | ")
                        .append(escape(String.join(", ", endpoint.params()))).append(" | ")
                        .append(escape(endpoint.handler())).append(" | ").append(evidence(route)).append(" | ")
                        .append(chain(route, target.id())).append(" |\n");
            }
        }
        // Tạm ẩn bảng caller trong cả Markdown và HTML. Bỏ comment dòng sau để bật lại.
        // appendCallerTable(out, target, visited);
        appendReverseTree(out, target.id(), depth, incoming);
        Set<String> visitedMethods = new HashSet<>(); visited.keySet().forEach(s -> visitedMethods.add(s.method()));
        Set<String> sources = new HashSet<>(); snapshot.methods().stream().filter(m -> visitedMethods.contains(m.id())).forEach(m -> sources.add(m.source()));
        List<Problem> relevant = snapshot.problems().stream().filter(p -> visitedMethods.contains(p.caller()) ||
                p.caller().isEmpty() && (p.source().isEmpty() || sources.contains(p.source()))).toList();
        out.append("\n## Diagnostics và giới hạn\n\nTổng diagnostics trong snapshot: ").append(snapshot.problems().size())
                .append("; liên quan đến phạm vi đã duyệt: ").append(relevant.size()).append(".\n\n");
        if (!relevant.isEmpty()) {
            out.append("| Vị trí | Method | Lý do | Chi tiết |\n|---|---|---|---|\n");
            for (Problem p : relevant) out.append("| ").append(escape(p.source())).append(':').append(p.line()).append(':').append(p.column())
                    .append(" | ").append(escape(p.caller())).append(" | ").append(escape(p.reason())).append(" | ").append(escape(p.detail())).append(" |\n");
        }
        out.append("\n- Đã phân giải tĩnh: đường chỉ có cạnh DECLARED; đây không phải bằng chứng nhánh đó thực sự chạy.\n")
                .append("- Có thể: đường chứa POSSIBLE_DISPATCH, REFERENCE hoặc DEFERRED. Method reference và lambda có thể chưa được thực thi.\n")
                .append("- Dispatch chưa xét lựa chọn bean theo @Qualifier, @Primary, @Profile hoặc điều kiện runtime, kể cả khi chỉ thấy một implementation.\n")
                .append("- Phạm vi gồm Java source roots được chọn và Spring MVC annotation chuẩn; COMPLETE không bảo đảm độ phủ runtime.\n")
                .append("- Không theo reflection, proxy động, AOP, event, message broker, scheduler, routing runtime hoặc ranh giới service.\n")
                .append("- Field/static initializer chưa có caller được đặt tên.\n")
                .append("- Annotation tùy biến và mapping thừa kế chưa hỗ trợ; tổ hợp verb ở cả class và method được bỏ qua kèm diagnostic.\n")
                .append("- Route là mapping trong code; placeholder, context path, reverse proxy và điều kiện runtime có thể làm URL thực tế khác. ANY nghĩa là mapping không giới hạn verb.\n")
                .append("- Báo cáo giữ một đường ngắn nhất cho mỗi method và mức bằng chứng; mỗi endpoint ưu tiên đường đã phân giải tĩnh.\n")
                .append("- Chưa có tên tính năng nghiệp vụ do team cung cấp; chỉ hiển thị endpoint và handler. Hành vi thực tế còn phụ thuộc thay đổi code, đầu vào và nhánh thực thi.\n");
        return out.toString();
    }
    private static void appendCallerTable(StringBuilder out, Method target, Map<State, Route> visited) {
        out.append("\n## Method gọi trực tiếp và gián tiếp\n\n");
        List<Route> callers = visited.values().stream().filter(r -> !r.state().method().equals(target.id()))
                .sorted(Comparator.comparingInt((Route r) -> r.calls().size()).thenComparing(r -> r.state().method()).thenComparing(r -> r.state().possible())).toList();
        if (callers.isEmpty()) out.append("Không tìm thấy caller trong phạm vi độ sâu đã chọn.\n");
        else {
            out.append("| Chữ ký | Độ sâu | Vị trí lời gọi | Mức bằng chứng | Chuỗi gọi |\n|---|---|---|---|---|\n");
            for (Route route : callers) {
                Call call = route.calls().get(0);
                out.append("| ").append(escape(route.state().method())).append(" | ").append(route.calls().size()).append(" | ")
                        .append(escape(call.source())).append(':').append(call.line()).append(':').append(call.column())
                        .append(" | ").append(evidence(route)).append(" | ").append(chain(route, target.id())).append(" |\n");
            }
        }
    }
    private record TreeNode(String method, int level, List<Call> edges, boolean leaving) {}
    private void appendReverseTree(StringBuilder out, String target, int depth, Map<String, List<Call>> incoming) {
        out.append("\n## Cây lời gọi ngược\n\n");
        Map<String, List<Endpoint>> handlers = new HashMap<>();
        for (Endpoint endpoint : sorted(snapshot.endpoints(), ENDPOINTS))
            handlers.computeIfAbsent(endpoint.handler(), ignored -> new ArrayList<>()).add(endpoint);
        Set<String> branch = new HashSet<>();
        Deque<TreeNode> pending = new ArrayDeque<>();
        pending.push(new TreeNode(target, 0, List.of(), false));
        while (!pending.isEmpty()) {
            TreeNode node = pending.pop();
            if (node.leaving()) { branch.remove(node.method()); continue; }
            String indent = "    ".repeat(node.level());
            out.append(indent).append("- ").append(escape(node.method()));
            if (!node.edges().isEmpty()) {
                out.append(" — vị trí gọi: ");
                StringJoiner sites = new StringJoiner("; ");
                for (Call edge : node.edges()) {
                    String site = escape(edge.source()) + ":" + edge.line() + ":" + edge.column();
                    if (edge.kind() != Kind.DECLARED) site += " (**Có thể**, " + edge.kind() + ")";
                    sites.add(site);
                }
                out.append(sites);
            }
            Set<EndpointKey> shown = new HashSet<>();
            for (Endpoint endpoint : handlers.getOrDefault(node.method(), List.of())) {
                if (!shown.add(new EndpointKey(endpoint.verb(), endpoint.route(), endpoint.handler(), endpoint.params()))) continue;
                out.append(" — ").append(escape(endpoint.verb())).append(' ').append(escape(displayRoute(endpoint)));
                if (!endpoint.params().isEmpty())
                    out.append(" (Params: ").append(escape(String.join(", ", endpoint.params()))).append(')');
            }
            if (branch.contains(node.method())) {
                out.append(" — **Gọi vòng**\n");
                continue;
            }
            out.append('\n');
            List<Call> edges = incoming.getOrDefault(node.method(), List.of());
            if (node.level() == depth) {
                if (!edges.isEmpty()) out.append(indent).append("    - **Giới hạn độ sâu: còn nhánh chưa hiển thị.**\n");
                continue;
            }
            // Group only within this parent: shared callers remain visible on every branch.
            Map<String, List<Call>> callers = new TreeMap<>();
            for (Call edge : edges) callers.computeIfAbsent(edge.caller(), ignored -> new ArrayList<>()).add(edge);
            branch.add(node.method());
            pending.push(new TreeNode(node.method(), node.level(), List.of(), true));
            List<String> ids = new ArrayList<>(callers.keySet());
            Collections.reverse(ids);
            for (String caller : ids) pending.push(new TreeNode(caller, node.level() + 1, callers.get(caller), false));
        }
    }
    private static String displayRoute(Endpoint endpoint) {
        if (endpoint.params().isEmpty() || !endpoint.params().stream().allMatch(ImpactQuery::isSimpleEquality)) return endpoint.route();
        return endpoint.route() + "?" + String.join("&", endpoint.params());
    }
    private static boolean isSimpleEquality(String expression) {
        return expression.matches("[^!<>=&]+=[^!<>=&]*");
    }
    private static String evidence(Route route) { return route.state().possible() ? "Có thể" : "Đã phân giải tĩnh"; }
    private static String chain(Route route, String target) {
        StringBuilder result = new StringBuilder();
        for (Call call : route.calls()) result.append(escape(call.caller())).append(" —[").append(call.kind())
                .append("; ").append(escape(call.source())).append(':').append(call.line()).append(':').append(call.column()).append("]→ ");
        return result.append(escape(target)).toString();
    }
    public static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\\", "\\\\").replace("|", "\\|").replace("`", "\\`").replace("*", "\\*")
                .replace("_", "\\_").replace("[", "\\[").replace("]", "\\]").replace("#", "\\#")
                .replace("\r\n", "\n").replace("\r", "\n").replace("\n", "<br>");
    }
}
