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
        int status = new CommandRunner(ModuleLayer.boot(), ToolRuntime.of(new ResolutionJig(), jist, new UnusedDocServer()),
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

    private static final class ResolutionJig implements ToolProvider {
        @Override public String name() { return "jig"; }
        @Override public int run(PrintWriter out, PrintWriter err, String... args) {
            out.println("--class-path\nshared-classes");
            return 0;
        }
    }

    private static final class UnusedDocServer implements ToolProvider {
        @Override public String name() { return "jdocserver"; }
        @Override public int run(PrintWriter out, PrintWriter err, String... args) {
            throw new AssertionError("Terminal documentation must use jist");
        }
    }
}
