package com.demo.impact;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

final class MavenClasspath {
    private MavenClasspath() {}

    static List<Path> resolve(Path repo) throws IOException, ToolException {
        Path output = Files.createTempFile("impact-classpath-", ".txt");
        Path log = null;
        try {
            log = Files.createTempFile("impact-maven-", ".log");
            boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
            List<String> command = windows
                    ? List.of("cmd.exe", "/d", "/c", "mvn.cmd -o -B -q dependency:build-classpath -DincludeScope=compile \"-Dmdep.outputFile=%IMPACT_MAVEN_CP%\"")
                    : List.of("mvn", "-o", "-B", "-q", "dependency:build-classpath", "-DincludeScope=compile", "-Dmdep.outputFile=" + output);
            ProcessBuilder builder = new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().put("IMPACT_MAVEN_CP", output.toString());
            Process maven;
            try { maven = builder.start(); }
            catch (IOException ex) { throw unavailable("Cannot start Maven: " + ex.getMessage()); }
            try {
                if (!maven.waitFor(120, TimeUnit.SECONDS)) {
                    maven.destroyForcibly(); throw unavailable("Maven timed out after 120 seconds.");
                }
            } catch (InterruptedException ex) {
                maven.destroyForcibly(); Thread.currentThread().interrupt(); throw unavailable("Maven was interrupted.");
            }
            if (maven.exitValue() != 0) {
                String detail;
                try (var lines = Files.lines(log)) {
                    detail = lines.filter(line -> line.contains("[ERROR]")).findFirst().orElse("Maven exited with code " + maven.exitValue());
                }
                throw unavailable(detail);
            }
            if (!Files.isRegularFile(output)) throw unavailable("Maven did not write its compile classpath output.");
            String value = Files.readString(output).strip();
            if (value.isEmpty()) return List.of();
            List<Path> entries = Arrays.stream(value.split(Pattern.quote(File.pathSeparator)))
                    .filter(entry -> !entry.isBlank()).map(Path::of).map(p -> p.isAbsolute() ? p.normalize() : repo.resolve(p).normalize()).toList();
            for (Path entry : entries) if (!Files.isReadable(entry)) throw unavailable("Maven classpath entry is unreadable: " + entry);
            return entries;
        } finally {
            if (log != null) Files.deleteIfExists(log);
            Files.deleteIfExists(output);
        }
    }
    private static ToolException unavailable(String detail) {
        return new ToolException(1, "Unable to resolve Maven compile classpath offline. Prepare the project's Maven dependencies or supply --classpath-file. " + detail);
    }
}
