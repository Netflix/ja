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
import com.netflix.tools.ja.project.ProjectType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InsightFailureTest {
    @Test
    void failedOwnerRetainsPartialRowsAndCannotBeMaskedByAnEmptySuccess(@TempDir Path directory) throws Exception {
        var result = run(directory, new OutcomeJist(2, "error", true, false), "callers", "--external");

        assertEquals(2, result.code());
        assertEquals("example.App.clock() -> java.time.Clock.systemUTC()\n", result.output());
        assertTrue(result.error().contains("scope failed"), result.error());
    }

    @Test
    void invalidDeclarationsAreNotTreatedAsAnEmptySearch(@TempDir Path directory) throws Exception {
        var result = run(directory, new OutcomeJist(1, "error", false, false), "declarations");

        assertEquals(1, result.code());
        assertTrue(result.error().contains("scope failed"), result.error());
    }

    @Test
    void absentDeclarationsAreAnEmptySuccess(@TempDir Path directory) throws Exception {
        var result = run(directory, new OutcomeJist(1, "not_found", false, false), "declarations");

        assertEquals(0, result.code(), result.error());
        assertEquals("", result.output());
        assertEquals("", result.error());
    }

    @Test
    void absentCallerTargetStillFailsWhenAbsentEverywhere(@TempDir Path directory) throws Exception {
        var result = run(directory, new OutcomeJist(1, "not_found", false, false), "callers");

        assertEquals(1, result.code());
        assertTrue(result.error().contains("scope failed"), result.error());
    }

    @Test
    void absentTargetInOneScopeDoesNotFailSuccessfulSearch(@TempDir Path directory) throws Exception {
        var result = run(directory, new OutcomeJist(1, "not_found", true, false), "callers", "--external");

        assertEquals(0, result.code(), result.error());
        assertEquals("", result.error());
    }

    @Test
    void absentImplementationTargetsAreClassifiedDuringLookup(@TempDir Path directory) throws Exception {
        var result = run(directory, new OutcomeJist(1, "not_found", false, false), "impl");

        assertEquals(1, result.code());
        assertTrue(result.error().contains("scope failed"), result.error());
    }

    @Test
    void failuresBeforeJsonOutputRetainTheirDiagnostics(@TempDir Path directory) throws Exception {
        var result = run(directory, new OutcomeJist(2, "error", true, true), "callers", "--external");

        assertEquals(2, result.code());
        assertTrue(result.error().contains("early failure"), result.error());
    }

    @Test
    void legacyFailureWithoutStructuredKindIsNotAssumedAbsent(@TempDir Path directory) throws Exception {
        var result = run(directory, new RawJist(1,
                "{\"format\":\"jist\",\"version\":1,\"results\":[]}"), "declarations");

        assertEquals(1, result.code());
        assertTrue(result.error().contains("original diagnostic"), result.error());
    }

    @Test
    void invalidJsonRetainsTheOriginalDiagnostic(@TempDir Path directory) throws Exception {
        var result = run(directory, new RawJist(2, "{incomplete"), "callers");

        assertEquals(2, result.code());
        assertTrue(result.error().contains("Invalid jist JSON output"), result.error());
        assertTrue(result.error().contains("original diagnostic"), result.error());
    }

    @Test
    void inconsistentExitCodeIsRejected(@TempDir Path directory) throws Exception {
        var result = run(directory, new RawJist(0,
                "{\"format\":\"jist\",\"version\":1,\"results\":[],\"exitCode\":2,\"diagnostics\":[]}"), "callers");

        assertEquals(2, result.code());
        assertTrue(result.error().contains("exitCode does not match provider status"), result.error());
    }

    private record RawJist(int status, String output) implements ToolProvider, OptionChecker {
        @Override public String name() { return "jist"; }
        @Override public int isSupportedOption(String option) {
            return new OutcomeJist(0, "", false, false).isSupportedOption(option);
        }
        @Override public int run(PrintWriter out, PrintWriter err, String... args) {
            out.println(output);
            err.println("original diagnostic");
            return status;
        }
    }

    private static Result run(Path directory, ToolProvider jist, String question, String... options) throws Exception {
        var first = Files.createDirectories(directory.resolve("first"));
        var second = Files.createDirectories(directory.resolve("second"));
        var context = new ProjectContext(
                new ProjectCatalog(new ProjectAnchor(ProjectType.GRADLE, directory), List.of(
                        new Project(":first", first, List.of(new ProjectScope.Gradle("main", "compile"))),
                        new Project(":second", second, List.of(new ProjectScope.Gradle("main", "compile"))))),
                new BuildScope());
        var args = new java.util.ArrayList<>(List.of("find", question));
        args.addAll(List.of(options));
        args.add("java.time.Clock.systemUTC");
        var parsed = JaInvocation.parse(directory, args.toArray(String[]::new));
        var invocation = new JaInvocation(
                parsed.workingDirectory(), parsed.verbose(), parsed.command(), parsed.moduleSourcePath(),
                parsed.rootModules(), parsed.resolutionArguments(), parsed.toolArguments(),
                parsed.requestedProjects(), Optional.of(context));
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        int code = new CommandRunner(ModuleLayer.boot(), ToolRuntime.of(new SharedJig(), jist),
                new ToolCatalog(List.of()), () -> null).run(invocation, InputStream.nullInputStream(),
                        new PrintStream(output), new PrintStream(errors));
        return new Result(code, output.toString(), errors.toString());
    }

    private record Result(int code, String output, String error) {}

    private static final class SharedJig implements ToolProvider {
        @Override public String name() { return "jig"; }
        @Override public int run(PrintWriter out, PrintWriter err, String... args) {
            out.println("--class-path");
            out.println("shared-classes");
            out.println("--source-path");
            out.println("shared-source");
            return 0;
        }
    }

    private record OutcomeJist(int status, String kind, boolean shared, boolean early) implements ToolProvider, OptionChecker {
        @Override public String name() { return "jist"; }
        @Override public int isSupportedOption(String option) {
            return switch (option) {
                case "--class-path", "--source-path", "--format", "--exclude-path" -> 1;
                default -> -1;
            };
        }
        @Override public int run(PrintWriter out, PrintWriter err, String... args) {
            var arguments = List.of(args);
            if (shared && arguments.contains("--exclude-path")) {
                out.println("{\"format\":\"jist\",\"version\":1,\"results\":[],\"exitCode\":0,\"diagnostics\":[]}");
                return 0;
            }
            if (early) {
                err.println("early failure");
                return status;
            }
            var rows = shared && !kind.equals("not_found")
                    ? "{\"text\":\"example.App.clock() -> java.time.Clock.systemUTC()\"}" : "";
            out.println("{\"format\":\"jist\",\"version\":1,\"results\":[" + rows + "],\"exitCode\":" + status
                    + ",\"diagnostics\":[{\"kind\":\"" + kind + "\",\"message\":\"scope failed\"}]}");
            return status;
        }
    }
}
