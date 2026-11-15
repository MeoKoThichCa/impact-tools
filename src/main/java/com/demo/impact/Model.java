package com.demo.impact;

import java.util.*;

/** Immutable records shared by the compiler, persistence and query layers. */
public final class Model {
    private Model() {}
    public enum Kind { DECLARED, POSSIBLE_DISPATCH, REFERENCE, DEFERRED, EXTERNAL }
    public record Method(String id, String owner, String name, String parameters,
                         String source, long line, long column) {}
    public record Call(String caller, String callee, String source, long line, long column, Kind kind) {}
    public record Endpoint(String verb, String route, List<String> params, String handler, String source, long line, long column) {
        public Endpoint { params = List.copyOf(params); }
    }
    public record Problem(String caller, String source, long line, long column, String reason, String detail) {}
    public record Snapshot(Properties manifest, List<Method> methods, List<Call> calls,
                           List<Endpoint> endpoints, List<Problem> problems) {}
    public static final Comparator<Method> METHODS = Comparator.comparing(Method::id);
    public static final Comparator<Call> CALLS = Comparator.comparing(Call::caller).thenComparing(Call::callee)
            .thenComparing(Call::source).thenComparingLong(Call::line).thenComparingLong(Call::column)
            .thenComparing(c -> c.kind().name());
    public static final Comparator<Endpoint> ENDPOINTS = Comparator.comparing(Endpoint::verb)
            .thenComparing(Endpoint::route).thenComparing(e -> String.join("\u0000", e.params()))
            .thenComparing(Endpoint::handler).thenComparing(Endpoint::source)
            .thenComparingLong(Endpoint::line).thenComparingLong(Endpoint::column);
    public static final Comparator<Problem> PROBLEMS = Comparator.comparing(Problem::caller)
            .thenComparing(Problem::source).thenComparingLong(Problem::line).thenComparingLong(Problem::column)
            .thenComparing(Problem::reason).thenComparing(Problem::detail);
    public static <T> List<T> sorted(Collection<T> records, Comparator<T> comparator) {
        return records.stream().distinct().sorted(comparator).toList();
    }
}
