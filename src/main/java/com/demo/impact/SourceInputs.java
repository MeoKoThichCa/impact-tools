package com.demo.impact;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

public record SourceInputs(Path repo, List<Path> roots, List<Path> files, List<Path> classpath, int release) {
    public static SourceInputs collect(Path requestedRepo, List<String> requestedRoots, Path classpathFile,
                                       int release) throws IOException, ToolException {
        if (!Files.isDirectory(requestedRepo)) throw new ToolException(2, "Repo không tồn tại: " + requestedRepo);
        Path repo = requestedRepo.toRealPath();
        boolean mavenProject = Files.isRegularFile(repo.resolve("pom.xml"));
        List<Path> roots = new ArrayList<>();
        if (requestedRoots.isEmpty()) {
            Files.walkFileTree(repo, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(repo) && (dir.getFileName().toString().startsWith(".") ||
                            Set.of("target", "build", "node_modules").contains(dir.getFileName().toString())))
                        return FileVisitResult.SKIP_SUBTREE;
                    if (dir.endsWith(Path.of("src", "main", "java"))) {
                        roots.add(dir); return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            if (roots.size() > 1) throw new ToolException(2, "Có nhiều source root; dùng --source-root cho từng module: " + roots);
            Path generated = repo.resolve("target/generated-sources");
            if (mavenProject && Files.isDirectory(generated, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(generated) && generated.toRealPath().startsWith(repo)) roots.add(generated);
        } else {
            for (String input : requestedRoots) {
                Path path = repo.resolve(input).normalize();
                if (!path.startsWith(repo) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    throw new ToolException(2, "Source root phải là thư mục trong repo: " + input);
                Path current = path;
                while (!current.equals(repo)) {
                    if (Files.isSymbolicLink(current)) throw new ToolException(2, "Không đi theo symlink: " + input);
                    current = current.getParent();
                }
                path = path.toRealPath();
                if (!path.startsWith(repo)) throw new ToolException(2, "Source root nằm ngoài repo: " + input);
                for (Path root : roots) if (path.startsWith(root) || root.startsWith(path))
                    throw new ToolException(2, "Source root trùng hoặc chồng lấn: " + input);
                roots.add(path);
            }
        }
        List<Path> files = new ArrayList<>();
        for (Path root : roots) Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && file.toString().endsWith(".java")) files.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(Comparator.comparing(p -> repo.relativize(p).toString().replace('\\', '/')));
        if (files.isEmpty()) throw new ToolException(1, "Không có file Java trong phạm vi source");
        List<Path> classpath = new ArrayList<>();
        if (classpathFile != null) {
            Path absolute = classpathFile.toAbsolutePath().normalize();
            List<String> lines = Files.readAllLines(absolute, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String entry = lines.get(i).strip();
                if (i == 0 && entry.startsWith("\uFEFF")) entry = entry.substring(1);
                if (entry.isBlank()) continue;
                Path path = absolute.getParent().resolve(entry).normalize();
                if (!Files.exists(path) || !(Files.isDirectory(path) ||
                        Files.isRegularFile(path) && path.toString().toLowerCase(Locale.ROOT).endsWith(".jar")))
                    throw new ToolException(2, "Classpath entry không tồn tại hoặc không phải JAR/thư mục: " + path);
                classpath.add(path);
            }
        } else if (mavenProject) {
            classpath.addAll(MavenClasspath.resolve(repo));
        }
        return new SourceInputs(repo, List.copyOf(roots), List.copyOf(files), List.copyOf(classpath), release);
    }
    public String relative(Path path) { return repo.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/'); }
}
