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
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.spi.ToolProvider;

import com.netflix.tools.cli.CommandLine.CompletionRequest;
import com.netflix.tools.cli.CommandLine.ToolInvocation;
import com.netflix.tools.ja.BuiltinCommand;
import com.netflix.tools.ja.Command;
import com.netflix.tools.ja.CommandRunner;
import com.netflix.tools.ja.Ja;
import com.netflix.tools.ja.JaInvocation;
import com.netflix.tools.ja.ToolCatalog;
import com.netflix.tools.ja.ToolExecutionException;
import com.netflix.tools.ja.ToolRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectCommandsTest {
    @TempDir
    Path directory;

    @Test
    void parsingDoesNotReadSourceDescriptors() throws Exception {
        Path source = Files.createDirectories(directory.resolve("src/com.example.app"));
        Files.writeString(source.resolve("module-info.java"), "not a module declaration");
        var invocation = JaInvocation.parse(source, new String[] {"-p", "app", "projects"});
        assertTrue(invocation.moduleSourcePath().isEmpty());
        assertEquals(List.of(), invocation.rootModules());
    }

    @Test
    void listsModuleProjectsAndMarksTheWorkingDirectorySelection() throws Exception {
        Path server = module("com.example.tools.server");
        module("com.example.client");
        var result = run(server, ToolRuntime.of(), "projects");
        assertEquals(0, result.status(), result.error());
        assertTrue(result.output().contains("[module]"),
                result.output());
        assertTrue(line(result, "com.example.tools.server").endsWith("(current - working directory)"),
                result.output());
        assertFalse(line(result, "com.example.client").contains("current"),
                result.output());
        assertFalse(result.output().contains("SELECTED"),
                result.output());
    }

    @Test
    void requestedSuffixOverridesTheWorkingDirectorySelection() throws Exception {
        Path server = module("com.example.tools.server");
        module("com.example.client");
        for (String selector : List.of("client", "example.client", "com.example.client")) {
            var result = run(server, ToolRuntime.of(), "-p", selector, "projects");
            assertEquals(0, result.status(), result.error());
            assertTrue(line(result, "com.example.client").endsWith("(current - requested)"),
                    result.output());
            assertFalse(line(result, "com.example.tools.server").contains("current"),
                    result.output());
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void symlinkWorkingDirectoriesCanSelectSiblingModuleProjects() throws Exception {
        Path server = module("com.example.server");
        module("com.example.client");
        Path link = Files.createSymbolicLink(directory.resolve("server-link"), server);
        var result = run(link, ToolRuntime.of(), "-p", "client", "projects");
        assertEquals(0, result.status(), result.error());
        assertTrue(line(result, "com.example.client").endsWith("(current - requested)"),
                result.output());
        var calls = new ArrayList<List<String>>();
        Path sourcePath = server.getParent().toRealPath();
        var tools = ToolRuntime.of(jig((out, arguments) -> {
            calls.add(arguments);
            assertTrue(arguments.contains("com.example.client"), arguments.toString());
            assertTrue(arguments.contains(sourcePath.toString()), arguments.toString());
            return 0;
        }));
        assertEquals(0, run(link, tools, "-p", "client", "compile").status());
        assertFalse(calls.isEmpty());
    }

    @Test
    void requestedProjectsCanBeRepeatedWithoutDuplicatingSelection() throws Exception {
        module("com.example.server");
        module("com.example.client");
        var result = run(directory, ToolRuntime.of(), "--project=server", "-p", "client",
                "-p", "server", "projects");
        assertEquals(0, result.status(), result.error());
        assertEquals(2,
                result.output()
                      .lines()
                      .filter(value -> value.endsWith("(current - requested)"))
                      .count());
    }

    @Test
    void ambiguousModuleSuffixesFailAndListTheMatches() throws Exception {
        module("com.example.server");
        module("org.example.server");
        var failure = assertThrows(IllegalArgumentException.class, () -> run(directory, ToolRuntime.of(), "-p", "server", "projects"));
        assertTrue(failure.getMessage().contains("com.example.server"),
                failure.getMessage());
        assertTrue(failure.getMessage().contains("org.example.server"),
                failure.getMessage());
    }

    @Test
    void moduleSuffixesOnlyMatchAtDotBoundaries() throws Exception {
        module("com.example.server");
        var failure = assertThrows(IllegalArgumentException.class, () -> run(directory, ToolRuntime.of(), "-p", "ver", "projects"));
        assertTrue(failure.getMessage().contains("No project matches"),
                failure.getMessage());
    }

    @Test
    void buildRootSelectionIsShownOnTheHeading() throws Exception {
        module("com.example.server");
        module("com.example.client");
        var result = run(directory, ToolRuntime.of(), "projects");
        assertEquals(0, result.status(), result.error());
        assertTrue(
                result.output()
                      .lines()
                      .findFirst()
                      .orElseThrow()
                      .contains("(current - working directory)"),
                result.output());
        assertEquals(1,
                result.output()
                      .lines()
                      .filter(value -> value.contains("current"))
                      .count());
    }

    @Test
    void globalProjectSelectionScopesExistingModuleCompilation() throws Exception {
        Path server = module("com.example.server");
        module("com.example.client");
        var calls = new ArrayList<List<String>>();
        var tools = ToolRuntime.of(jig((out, arguments) -> {
            calls.add(arguments);
            assertTrue(arguments.contains("com.example.client"), arguments.toString());
            assertFalse(arguments.contains("com.example.server"), arguments.toString());
            return 0;
        }));
        var result = run(server, tools, "--verbose", "--project=client", "compile");
        assertEquals(0, result.status(), result.error());
        assertFalse(calls.isEmpty());
    }

    @Test
    void projectAndExplicitModuleSelectionsMustAgree() throws Exception {
        module("com.example.server");
        module("com.example.client");
        var failure = assertThrows(
                IllegalArgumentException.class,
                () -> run(directory, ToolRuntime.of(), "-p", "server", "run",
                        "-m", "com.example.client"));
        assertTrue(failure.getMessage().contains("selected project"),
                failure.getMessage());
    }

    @Test
    void discoversTheMavenReactorFromInsideAChildProject() throws Exception {
        Path root = maven(directory.resolve("reactor"));
        Path app = maven(root.resolve("app"));
        Path source = Files.createDirectories(app.resolve("src/main/java"));
        Files.writeString(source.resolve("module-info.java"), "module example.app {}\n");
        var calls = new ArrayList<List<String>>();
        var result = run(source, buildTools(Map.of(root, mavenRows(root, "example:root") + mavenRows(app, "example:app")), calls), "projects");
        assertEquals(0, result.status(), result.error());
        assertTrue(result.output().contains("[maven]"),
                result.output());
        assertTrue(line(result, "example:app").endsWith("(current - working directory)"),
                result.output());
        assertFalse(result.output().contains("[module]"),
                result.output());
        assertEquals(1, calls.size());
    }

    @Test
    void nestedIndependentMavenBuildsAreNotClaimedByTheirParent() throws Exception {
        Path root = maven(directory.resolve("outer"));
        Path nested = maven(root.resolve("independent"));
        var calls = new ArrayList<List<String>>();
        var result = run(
                nested,
                buildTools(Map.of(root, mavenRows(root, "example:outer"), nested, mavenRows(nested, "example:inner")),
                        calls),
                "projects");
        assertEquals(0, result.status(), result.error());
        assertTrue(result.output().contains("example:inner"),
                result.output());
        assertFalse(result.output().contains("example:outer"),
                result.output());
        assertEquals(2, calls.size());
    }

    @Test
    void gradleDiscoveryUsesActualProjectDirectoriesAndBuildQualifiedSelectors() throws Exception {
        Path root = gradle(directory.resolve("build"));
        Path app = Files.createDirectories(root.resolve("layout/application"));
        Files.writeString(app.resolve("build.gradle"), "");
        Path included = Files.createDirectories(directory.resolve("included tools"));
        var tools = buildTools(
                Map.of(root, gradleRows(root, ":") + gradleRows(app, ":app") + gradleRows(included, ":tools:plugin")),
                new ArrayList<>());
        var current = run(app, tools, "projects");
        assertEquals(0, current.status(), current.error());
        assertTrue(line(current, ":app").endsWith("(current - working directory)"),
                current.output());
        var requested = run(app, tools, "--project", ":tools:plugin", "projects");
        assertEquals(0, requested.status(), requested.error());
        assertTrue(line(requested, ":tools:plugin").endsWith("(current - requested)"),
                requested.output());
        assertTrue(requested.output().contains(included.toString()),
                requested.output());
    }

    @Test
    void nestedIndependentGradleBuildsUseTheirOwnProjectCatalog() throws Exception {
        Path root = gradle(directory.resolve("outer"));
        Path nested = gradle(root.resolve("independent"));
        var calls = new ArrayList<List<String>>();
        var result = run(
                nested,
                buildTools(Map.of(root, gradleRows(root, ":"), nested, gradleRows(nested, ":") + gradleRows(nested.resolve("app"), ":app")),
                        calls),
                "projects");
        assertEquals(0, result.status(), result.error());
        assertTrue(result.output().contains(":app"),
                result.output());
        assertEquals(2, calls.size());
    }

    @Test
    void discoveryDoesNotSearchChildrenOfTheWorkingDirectory() throws Exception {
        maven(directory.resolve("service"));
        gradle(directory.resolve("client"));
        module(directory.resolve("tools"), "com.example.tools");
        var calls = new ArrayList<List<String>>();
        var failure = assertThrows(IllegalArgumentException.class,
                () -> run(directory, buildTools(Map.of(), calls), "projects"));
        assertTrue(failure.getMessage().contains("No project found"), failure.getMessage());
        assertTrue(calls.isEmpty(), calls.toString());
    }

    @Test
    void requestedProjectsDoNotExpandDiscoveryToSiblingCheckouts() throws Exception {
        Path tools = directory.resolve("tools");
        Path source = module(tools, "com.example.tools");
        maven(directory.resolve("service"));
        gradle(directory.resolve("client"));
        var calls = new ArrayList<List<String>>();
        var runtime = buildTools(Map.of(), calls);
        var current = run(source, runtime, "projects");
        assertTrue(current.output().contains("com.example.tools"), current.output());
        assertFalse(current.output().contains("[maven]"), current.output());
        assertFalse(current.output().contains("[gradle]"), current.output());
        for (String selector : List.of("example:service", ":client")) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> run(source, runtime, "-p", selector, "projects"));
            assertTrue(failure.getMessage().contains("No project matches"), failure.getMessage());
        }
        assertTrue(Ja.complete(new CompletionRequest(new ToolInvocation(source, List.of("-p")), ":"), runtime).isEmpty());
        assertTrue(calls.isEmpty(), calls.toString());
    }

    @Test
    void independentMavenAndGradleRootsCanBeNestedInEitherOrder() throws Exception {
        for (boolean mavenOuter : List.of(true, false)) {
            Path outer = directory.resolve(mavenOuter ? "maven-outer" : "gradle-outer");
            Path inner = outer.resolve("independent");
            if (mavenOuter) {
                maven(outer);
                gradle(inner);
            } else {
                gradle(outer);
                maven(inner);
            }
            var calls = new ArrayList<List<String>>();
            var runtime = buildTools(Map.of(
                    outer, mavenOuter ? mavenRows(outer, "example:outer") : gradleRows(outer, ":"),
                    inner, mavenOuter ? gradleRows(inner, ":") : mavenRows(inner, "example:inner")), calls);
            var result = run(Files.createDirectories(inner.resolve("nested/directory")), runtime, "projects");
            assertEquals(0, result.status(), result.error());
            assertTrue(result.output().contains("[" + (mavenOuter ? "gradle" : "maven") + "]"), result.output());
            assertFalse(result.output().contains("[" + (mavenOuter ? "maven" : "gradle") + "]"), result.output());
            assertEquals(2, calls.size());
        }
    }

    @Test
    void externalRootsNestedInModuleProjectsUseTheirOwnDiscovery() throws Exception {
        Path source = module("com.example.tools");
        for (boolean maven : List.of(true, false)) {
            Path root = maven ? maven(source.resolve("service")) : gradle(source.resolve("client"));
            var calls = new ArrayList<List<String>>();
            var runtime = buildTools(Map.of(root, maven ? mavenRows(root, "example:service") : gradleRows(root, ":")), calls);
            var result = run(Files.createDirectories(root.resolve("nested/directory")), runtime, "projects");
            assertEquals(0, result.status(), result.error());
            assertTrue(result.output().contains("[" + (maven ? "maven" : "gradle") + "]"), result.output());
            assertFalse(result.output().contains("[module]"), result.output());
            assertEquals(1, calls.size());
        }
    }

    @Test
    void gradleProjectsDoNotUseModuleSuffixMatching() throws Exception {
        Path root = gradle(directory.resolve("build"));
        Path app = Files.createDirectories(root.resolve("app"));
        var failure = assertThrows(
                IllegalArgumentException.class,
                () -> run(
                        root,
                        buildTools(Map.of(root, gradleRows(root, ":") + gradleRows(app, ":app")), new ArrayList<>()),
                        "-p",
                        "app",
                        "projects"));
        assertTrue(failure.getMessage().contains("No project matches"),
                failure.getMessage());
    }

    @Test
    void mavenShortArtifactSelectorsMustBeUnambiguous() throws Exception {
        Path root = maven(directory.resolve("reactor"));
        Path first = Files.createDirectories(root.resolve("first"));
        Path second = Files.createDirectories(root.resolve("second"));
        var tools = buildTools(Map.of(root, mavenRows(first, "one:app") + mavenRows(second, "two:app")), new ArrayList<>());
        var failure = assertThrows(IllegalArgumentException.class, () -> run(root, tools, "-p", ":app", "projects"));
        assertTrue(failure.getMessage().contains("one:app"),
                failure.getMessage());
        assertTrue(failure.getMessage().contains("two:app"),
                failure.getMessage());
    }

    @Test
    void overlappingMavenAndGradleBuildsRequireDisambiguation() throws Exception {
        Path root = maven(directory.resolve("build"));
        Files.writeString(root.resolve("settings.gradle.kts"), "");
        assertThrows(IllegalArgumentException.class, () -> run(root, ToolRuntime.of(), "projects"));
    }

    @Test
    void moduleCommandsDoNotFallBackToModuleSourcesInBuildProjects() throws Exception {
        Path root = maven(directory.resolve("build"));
        Path source = Files.createDirectories(root.resolve("src/main/java"));
        Files.writeString(source.resolve("module-info.java"), "module example.app {}\n");
        var tools = buildTools(Map.of(root, mavenRows(root, "example:app")), new ArrayList<>());
        var failure = assertThrows(IllegalArgumentException.class, () -> run(source, tools, "compile"));
        assertTrue(failure.getMessage().contains("maven"),
                failure.getMessage());
        assertTrue(failure.getMessage().contains("compile requires a module project"),
                failure.getMessage());
        assertFalse(failure.getMessage().contains("not yet supported"), failure.getMessage());
    }

    @Test
    void everyModuleWorkflowRejectsMavenAndGradleContextsBeforeResolution() throws Exception {
        var commands = List.of(List.of("require", "-u"), List.of("generate"), List.of("compile"), List.of("run"),
                List.of("test"), List.of("bench"), List.of("list"), List.of("assemble", "artifacts"),
                List.of("install"), List.of("maven", "export"));
        for (boolean maven : List.of(true, false)) {
            var root = maven ? maven(directory.resolve("maven")) : gradle(directory.resolve("gradle"));
            var source = Files.createDirectories(root.resolve("src/main/java"));
            Files.writeString(source.resolve("module-info.java"), "module example.app {}\n");
            for (var command : commands) {
                var calls = new ArrayList<List<String>>();
                var tools = buildTools(Map.of(root, maven ? mavenRows(root, "example:app") : gradleRows(root, ":")), calls);
                var failure = assertThrows(IllegalArgumentException.class,
                        () -> run(source, tools, command.toArray(String[]::new)));
                assertTrue(failure.getMessage().contains(command.getFirst() + " requires a module project"), failure.getMessage());
                assertFalse(failure.getMessage().contains("not yet supported"), failure.getMessage());
                assertEquals(1, calls.size(), calls.toString());
            }
        }
    }

    @Test
    void alreadyResolvedBuildContextsCannotBypassTheModuleCommandBoundary() throws Exception {
        var root = maven(directory.resolve("build"));
        var calls = new ArrayList<List<String>>();
        var tools = buildTools(Map.of(root, mavenRows(root, "example:app")), calls);
        var projects = JaInvocation.parse(root, new String[] {"projects"})
                .resolveProjects(tools, InputStream.nullInputStream(), System.err);
        assertSame(projects, projects.resolveProjects(tools, InputStream.nullInputStream(), System.err));
        var test = new JaInvocation(projects.workingDirectory(), projects.verbose(), new Command.Builtin(BuiltinCommand.TEST),
                projects.moduleSourcePath(), projects.rootModules(), projects.resolutionArguments(), List.of(),
                projects.requestedProjects(), projects.projectContext());
        var failure = assertThrows(IllegalArgumentException.class,
                () -> test.resolveProjects(tools, InputStream.nullInputStream(), System.err));
        assertTrue(failure.getMessage().contains("test requires a module project"), failure.getMessage());
        assertEquals(1, calls.size());
    }

    @Test
    void alreadyResolvedModuleContextsKeepTheirOriginalSourceSelection() throws Exception {
        var source = module("example.app");
        var invocation = JaInvocation.parse(source, new String[] {"compile"})
                .resolveProjects(ToolRuntime.of(), InputStream.nullInputStream(), System.err);
        assertEquals(List.of("example.app"), invocation.rootModules());
        assertSame(invocation, invocation.resolveProjects(ToolRuntime.of(), InputStream.nullInputStream(), System.err));
    }

    @Test
    void discoveryFailuresDoNotFallBackToModuleSources() throws Exception {
        Path root = gradle(directory.resolve("build"));
        Path source = Files.createDirectories(root.resolve("src/main/java"));
        Files.writeString(source.resolve("module-info.java"), "module example.app {}\n");
        var tools = ToolRuntime.of(jig((out, arguments) -> {
            out.print(gradleRows(root, ":"));
            return 7;
        }));
        assertThrows(ToolExecutionException.class, () -> run(source, tools, "projects"));
    }

    @Test
    void malformedListingsDoNotProducePartialProjectOutput() throws Exception {
        Path root = gradle(directory.resolve("build"));
        for (String listing : List.of("relative\t:\tmain\tcompile\n", root + "\t:\tmain\n", gradleRows(root, ":") + "\n",
                gradleRows(root, ":") + gradleRows(root.resolve("other"), ":"),
                root + "\t:\tmain\tunknown\n", root + "\t:\t\tcompile\n",
                ":\t" + root + "\n")) {
            var tools = buildTools(Map.of(root, listing), new ArrayList<>());
            assertThrows(IOException.class, () -> run(root, tools, "projects"));
        }
    }

    @Test
    void projectCompletionUsesTheDiscoveredCatalog() throws Exception {
        Path server = module("com.example.tools.server");
        module("com.example.client");
        var completions = Ja.complete(new CompletionRequest(new ToolInvocation(server, List.of("-p")), "tools"), ToolRuntime.of());
        assertEquals(List.of("com.example.tools.server"),
                completions.stream()
                           .map(value -> value.value())
                           .toList());
    }

    @Test
    void mavenAndGradleCompletionUsesTheirFullSelectors() throws Exception {
        Path root = maven(directory.resolve("reactor"));
        var tools = buildTools(Map.of(root, mavenRows(root.resolve("app"), "example:app")), new ArrayList<>());
        var maven = Ja.complete(new CompletionRequest(new ToolInvocation(root, List.of("--project")), ":a"), tools);
        assertEquals(List.of("example:app"),
                maven.stream()
                     .map(value -> value.value())
                     .toList());
        Path gradle = gradle(directory.resolve("gradle"));
        tools = buildTools(
                Map.of(gradle, gradleRows(gradle, ":") + gradleRows(gradle.resolve("plugin"), ":tools:plugin")),
                new ArrayList<>());
        var included = Ja.complete(new CompletionRequest(new ToolInvocation(gradle, List.of()), "--project=:tools"), tools);
        assertEquals(List.of("--project=:tools:plugin"),
                included.stream()
                        .map(value -> value.value())
                        .toList());
    }

    @Test
    void projectOptionsRequireNonemptySelectors() {
        for (String[] arguments : List.of(new String[] {"-p"}, new String[] {"--project=", "projects"},
                new String[] {"-p", " ", "projects"})) {
            assertThrows(IllegalArgumentException.class, () -> JaInvocation.parse(directory, arguments));
        }
    }

    @Test
    void listsEveryDiscoveredContextAndMarksOnlyTheSelectedOne() throws Exception {
        Path root = gradle(directory.resolve("build"));
        String listing = gradleRows(root, ":app") + root + "\t:app\tintegrationTest\tcompile\n";
        var result = run(root, buildTools(Map.of(root, listing), new ArrayList<>()),
                "-p", ":app/integrationTest", "projects");
        assertTrue(line(result, ":app/integrationTest/compile").endsWith("(current - requested)"), result.output());
        assertFalse(line(result, ":app/main/compile").contains("current"), result.output());
        assertFalse(line(result, ":app/main/runtime").contains("current"), result.output());
    }

    @Test
    void defaultSelectionsAreCompileForMavenAndMainCompileForGradle() throws Exception {
        Path maven = maven(directory.resolve("maven"));
        var result = run(maven, buildTools(Map.of(maven, mavenRows(maven, "example:app")), new ArrayList<>()),
                "-p", "example:app", "projects");
        assertTrue(line(result, "example:app/compile").endsWith("(current - requested)"), result.output());
        Path gradle = gradle(directory.resolve("gradle"));
        result = run(gradle, buildTools(Map.of(gradle, gradleRows(gradle, ":app")), new ArrayList<>()),
                "-p", ":app", "projects");
        assertTrue(line(result, ":app/main/compile").endsWith("(current - requested)"), result.output());
    }

    @Test
    void qualifiedSelectionMustNameADiscoveredContext() throws Exception {
        Path root = gradle(directory.resolve("build"));
        var runtime = buildTools(Map.of(root, gradleRows(root, ":app")), new ArrayList<>());
        for (String selector : List.of(":app/missing", ":app/test/unknown", ":app/main/compile/extra", ":app/")) {
            assertThrows(IllegalArgumentException.class, () -> run(root, runtime, "-p", selector, "projects"));
        }
    }

    @Test
    void nonJavaGradleProjectsDoNotAcquireAMainSourceSet() throws Exception {
        Path root = gradle(directory.resolve("build"));
        var runtime = buildTools(Map.of(root, root + "\t:\t\t\n"), new ArrayList<>());
        var result = run(root, runtime, "-p", ":", "projects");
        assertTrue(line(result, ":").endsWith("(current - requested)"), result.output());
        assertFalse(result.output().contains("/main"), result.output());
        assertThrows(IllegalArgumentException.class, () -> run(root, runtime, "-p", ":/main", "projects"));
    }

    @Test
    void qualifiedCompletionIncludesCustomSourceSetsAndClasspathChoices() throws Exception {
        Path root = gradle(directory.resolve("build"));
        var runtime = buildTools(Map.of(root, gradleRows(root, ":app")), new ArrayList<>());
        var completions = Ja.complete(new CompletionRequest(new ToolInvocation(root, List.of("-p")), ":app/test/"), runtime);
        assertEquals(List.of(":app/test/compile", ":app/test/runtime"), completions.stream().map(value -> value.value()).toList());
    }

    private static String mavenRows(Path directory, String selector) {
        return List.of("compile", "runtime", "test").stream()
                .map(scope -> directory + "\t" + selector + "\t" + scope + "\n")
                .collect(java.util.stream.Collectors.joining());
    }

    private static String gradleRows(Path directory, String selector) {
        return List.of("main", "test").stream()
                .flatMap(sourceSet -> List.of("compile", "runtime").stream()
                        .map(classpath -> directory + "\t" + selector + "\t" + sourceSet + "\t" + classpath + "\n"))
                .collect(java.util.stream.Collectors.joining());
    }

    private Path module(String name) throws Exception {
        return module(directory, name);
    }

    private static Path module(Path root, String name) throws Exception {
        Path source = Files.createDirectories(root.resolve("src")
                .resolve(name));
        Files.writeString(source.resolve("module-info.java"), "module " + name + " {}\n");
        return source;
    }

    private static Path maven(Path root) throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve("pom.xml"), "<project/>\n");
        return root;
    }

    private static Path gradle(Path root) throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'fixture'\n");
        return root;
    }

    private static ToolRuntime buildTools(Map<Path, String> listings, List<List<String>> calls) {
        return ToolRuntime.of(jig(
                (out, arguments) -> {
                    calls.add(arguments);
                    assertTrue(arguments.contains("--format"), arguments.toString());
                    assertTrue(arguments.contains("tsv"), arguments.toString());
                    String option = arguments.getFirst().equals("maven") ? "--project-base-dir" : "--root-project-dir";
                    Path root = Path.of(arguments.get(arguments.indexOf(option) + 1));
                    String listing = listings.entrySet()
                            .stream()
                            .filter(entry -> {
                                try {
                                    return entry.getKey()
                                                .toRealPath()
                                                .equals(root);
                                } catch (IOException failure) {
                                    throw new UncheckedIOException(failure);
                                }
                            })
                            .map(Entry::getValue)
                            .findFirst()
                            .orElseThrow(() -> new AssertionError("Unexpected build: " + root));
                    out.print(listing);
                    return 0;
                }));
    }

    private static ToolProvider jig(Query query) {
        return new ToolProvider() {
            public String name() {
                return "jig";
            }

            public int run(PrintWriter out, PrintWriter err, String... arguments) {
                return query.run(out, List.of(arguments));
            }
        };
    }

    private static Result run(Path workingDirectory, ToolRuntime tools, String... arguments) throws Exception {
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        int status = new CommandRunner(ModuleLayer.boot(), tools, new ToolCatalog(List.of()), () -> null)
                .run(JaInvocation.parse(workingDirectory, arguments), InputStream.nullInputStream(), new PrintStream(output), new PrintStream(errors));
        return new Result(status, output.toString(), errors.toString());
    }

    private static String line(Result result, String selector) {
        return result.output()
                     .lines()
                     .filter(value -> value.startsWith(selector + " ") || value.startsWith(selector + "/"))
                     .findFirst()
                     .orElseThrow();
    }

    private interface Query {
        int run(PrintWriter out, List<String> arguments);
    }

    private record Result(int status, String output, String error) {}
}
