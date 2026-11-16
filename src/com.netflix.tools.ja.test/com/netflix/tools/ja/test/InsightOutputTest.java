/*
 * Copyright 2026 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.netflix.tools.ja.test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.spi.ToolProvider;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.tools.OptionChecker;

import com.netflix.tools.ja.CommandRunner;
import com.netflix.tools.ja.JaInvocation;
import com.netflix.tools.ja.ToolCatalog;
import com.netflix.tools.ja.ToolRuntime;
import com.netflix.tools.ja.project.Project;
import com.netflix.tools.ja.project.ProjectAnchor;
import com.netflix.tools.ja.project.ProjectCatalog;
import com.netflix.tools.ja.project.ProjectContext;
import com.netflix.tools.ja.project.ProjectScope;
import com.netflix.tools.ja.project.ProjectSelection.BuildScope;
import com.netflix.tools.ja.project.ProjectSelection.Origin;
import com.netflix.tools.ja.project.ProjectSelection.SelectedProjects;
import com.netflix.tools.ja.project.ProjectType;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InsightOutputTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void successfulQueriesRetainDiagnostics(boolean global, @TempDir Path directory) throws Exception {
        for (var query : List.of(List.of("doc", "example.Root"), List.of("describe", "type", "example.Root"),
                List.of("describe", "origin", "example.Root"), List.of("find", "callers", "example.Root.run"))) {
            var jist = new ResultJist("{\"text\":\"class example.Root\"}", "class example.Root\n", "Warning: source unavailable", 0);
            var result = run(directory, global, jist, query);
            assertEquals(0, result.status(), result.error());
            assertEquals("class example.Root\n", result.output());
            assertEquals(global ? "ja: :first/main/compile: Warning: source unavailable\n"
                    + "ja: :second/main/compile: Warning: source unavailable\n" : "Warning: source unavailable\n", result.error());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void originRetainsMatchingPartialResults(boolean global, @TempDir Path directory) throws Exception {
        var jist = new ResultJist("{\"text\":\"class example.Root\"},{\"text\":\"class example.Root.Nested\"}",
                "class example.Root\nclass example.Root.Nested\n", "Error: query interrupted", 2);
        var result = run(directory, global, jist, List.of("describe", "origin", "example.Root"));
        assertEquals(2, result.status());
        assertEquals("class example.Root\n", result.output());
        assertEquals(global ? "ja: :first/main/compile: Error: query interrupted\n"
                + "ja: :second/main/compile: Error: query interrupted\n" : "Error: query interrupted\n", result.error());
    }

    @ParameterizedTest
    @ValueSource(strings = {"type", "file"})
    void descriptionsPreserveSourceOrder(String subject, @TempDir Path directory) throws Exception {
        var lines = IntStream.rangeClosed(1, 12).mapToObj(line -> "example.Root(Root.java:" + line + "): row " + line).toList();
        var rows = IntStream.rangeClosed(1, 12).mapToObj(line -> "{\"text\":\"" + lines.get(line - 1)
                + "\",\"origin\":{\"kind\":\"source\",\"location\":\"/source/Root.java\",\"line\":" + line + "}}")
                .collect(Collectors.joining(","));
        var result = run(directory, true, new ResultJist(rows, "", "", 0),
                List.of("describe", subject, "--source", "unit", subject.equals("file") ? "Root.java" : "example.Root"));
        assertEquals(0, result.status(), result.error());
        assertEquals(String.join("\n", lines) + "\n", result.output());
    }

    @ParameterizedTest
    @ValueSource(strings = {"doc", "describe"})
    void documentationRetainsRepeatedSourceLines(String command, @TempDir Path directory) throws Exception {
        var rows = IntStream.rangeClosed(1, 4).mapToObj(line -> "{\"text\":\"example.Root:  *\","
                + "\"origin\":{\"kind\":\"source\",\"location\":\"/source/Root.java\",\"line\":" + line + "}}")
                .collect(Collectors.joining(","));
        var query = command.equals("doc") ? List.of("doc", "example.Root")
                : List.of("describe", "member", "example.Root.run");
        var result = run(directory, true, new ResultJist(rows, "", "", 0), query);
        assertEquals(0, result.status(), result.error());
        assertEquals("example.Root:  *\n".repeat(4), result.output());
    }

    @ParameterizedTest
    @ValueSource(strings = {"doc", "describe"})
    void systemDescriptionsRetainImageIdentityAndBlockOrder(String command, @TempDir Path directory) throws Exception {
        var query = command.equals("doc") ? List.of("doc", "java.lang.String") : List.of("describe", "module", "java.base");
        var result = run(directory, true, new SystemJist(), query);
        assertEquals(0, result.status(), result.error());
        assertEquals("""
                java.base@21/module-info(module-info.java:1): module java.base {
                java.base@21/module-info(module-info.java:2): }
                java.base@25/module-info(module-info.java:1): module java.base {
                java.base@25/module-info(module-info.java:2): }
                """, result.output());
    }

    private static Result run(Path directory, boolean global, ToolProvider jist, List<String> arguments) throws Exception {
        var first = new Project(":first", Files.createDirectories(directory.resolve("first")),
                List.of(new ProjectScope.Gradle("main", "compile")));
        var second = new Project(":second", Files.createDirectories(directory.resolve("second")),
                List.of(new ProjectScope.Gradle("main", "compile")));
        var context = new ProjectContext(new ProjectCatalog(new ProjectAnchor(ProjectType.GRADLE, directory), List.of(first, second)),
                global ? new BuildScope() : new SelectedProjects(first.targets(), Origin.REQUESTED));
        var parsed = JaInvocation.parse(directory, arguments.toArray(String[]::new));
        var invocation = new JaInvocation(parsed.workingDirectory(), parsed.verbose(), parsed.command(), parsed.moduleSourcePath(),
                parsed.rootModules(), parsed.resolutionArguments(), parsed.toolArguments(), parsed.requestedProjects(), Optional.of(context));
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        int status = new CommandRunner(ModuleLayer.boot(), ToolRuntime.of(new ResolutionJig(), jist),
                new ToolCatalog(List.of()), () -> null).run(invocation, InputStream.nullInputStream(), new PrintStream(output), new PrintStream(errors));
        return new Result(status, output.toString(), errors.toString());
    }

    private record Result(int status, String output, String error) {}

    private record ResultJist(String rows, String text, String diagnostic, int status) implements ToolProvider, OptionChecker {
        @Override public String name() { return "jist"; }
        @Override public int isSupportedOption(String option) {
            return switch (option) {
                case "--class-path", "--format" -> 1;
                default -> -1;
            };
        }
        @Override public int run(PrintWriter out, PrintWriter err, String... args) {
            if (List.of(args).contains("--format")) {
                out.println("{\"format\":\"jist\",\"version\":1,\"results\":[" + rows + "],\"exitCode\":" + status + "}");
            } else {
                out.print(text);
            }
            if (!diagnostic.isEmpty()) err.println(diagnostic);
            return status;
        }
    }

    private static final class SystemJist implements ToolProvider, OptionChecker {
        @Override public String name() { return "jist"; }
        @Override public int isSupportedOption(String option) {
            return switch (option) {
                case "--class-path", "--format" -> 1;
                default -> -1;
            };
        }
        @Override public int run(PrintWriter out, PrintWriter err, String... args) {
            var arguments = List.of(args);
            var version = arguments.get(arguments.indexOf("--class-path") + 1).equals(":first") ? "21" : "25";
            var origin = "\"origin\":{\"kind\":\"system\",\"system\":\"jdk-" + version + "\",\"module\":\"java.base\",\"version\":\"" + version + "\"}";
            out.println("{\"format\":\"jist\",\"version\":1,\"results\":["
                    + "{\"text\":\"module-info(module-info.java:1): module java.base {\"," + origin + "},"
                    + "{\"text\":\"module-info(module-info.java:2): }\"," + origin + "}]}");
            return 0;
        }
    }

    private static final class ResolutionJig implements ToolProvider {
        @Override public String name() { return "jig"; }
        @Override public int run(PrintWriter out, PrintWriter err, String... args) {
            var arguments = List.of(args);
            out.println("--class-path\n" + arguments.get(arguments.indexOf("--project-path") + 1));
            return 0;
        }
    }
}
