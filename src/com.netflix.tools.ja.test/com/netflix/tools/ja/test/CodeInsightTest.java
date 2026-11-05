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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.spi.ToolProvider;
import javax.tools.OptionChecker;

import com.netflix.tools.cli.CommandLine.Completion;
import com.netflix.tools.cli.CommandLine.CompletionRequest;
import com.netflix.tools.cli.CommandLine.ToolInvocation;
import com.netflix.tools.ja.Command.Category;
import com.netflix.tools.ja.CommandRunner;
import com.netflix.tools.ja.ToolCatalog;
import com.netflix.tools.ja.Ja;
import com.netflix.tools.ja.JaInvocation;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeInsightTest {
    @Test
    void describesExplicitSubjectKindsAndFindsExplicitRelationships() throws Exception {
        for (var command : List.of("describe", "find")) {
            var subjects = command.equals("describe") ? List.of("module", "type", "member", "file", "types", "origin")
                    : List.of("declarations", "usages", "callers", "callees", "impl", "supertypes");
            for (var subject : subjects) {
                var invocation = JaInvocation.parse(Path.of(""), new String[] {"-p", ":client", command, subject, "example.Store"});
                assertEquals(command, invocation.command().commandName());
                assertEquals(Category.DEVELOPMENT, invocation.command().category());
                assertEquals(List.of(":client"), invocation.requestedProjects());
                assertEquals(List.of(), invocation.rootModules());
            }
        }
    }

    @Test
    void requiresAnExplicitQuestionAndSingleSubject() {
        for (var arguments : List.of(List.of("describe"), List.of("describe", "example.Store"), List.of("describe", "type"),
                List.of("describe", "--module", "example.app"), List.of("find", "implementations", "example.Store"),
                List.of("find", "impl"), List.of("find", "usages", "One", "Two"), List.of("find", "--callers", "example.Store.run"))) {
            assertThrows(IllegalArgumentException.class, () -> JaInvocation.parse(Path.of(""), arguments.toArray(String[]::new)), arguments.toString());
        }
    }

    @Test
    void completesSubjectKindsAndQuestions() {
        assertEquals(List.of("file", "member", "module", "origin", "type", "types"), complete(List.of("describe"), ""));
        assertEquals(List.of("callees", "callers", "declarations", "impl", "supertypes", "usages"), complete(List.of("find"), ""));
        assertEquals(List.of("impl"), complete(List.of("find"), "im"));
    }

    @Test
    void completesFileSubjectsRelativeToTheWorkingDirectory(@TempDir Path directory) throws Exception {
        Files.createDirectories(directory.resolve("src/example"));
        Files.writeString(directory.resolve("src/example/Store.java"), "class Store {}");
        Files.writeString(directory.resolve("src/example/ignored.txt"), "text");
        for (var prefix : List.of("src/example/", "./src/example/", directory.resolve("src/example") + "/")) {
            var request = new CompletionRequest(new ToolInvocation(directory, List.of("describe", "file")), prefix);
            assertEquals(List.of(prefix + "Store.java"), Ja.complete(request, ToolRuntime.of()).stream().map(Completion::value).toList());
        }
        var request = new CompletionRequest(new ToolInvocation(directory, List.of("describe", "file")), "");
        assertEquals(List.of("src/"), Ja.complete(request, ToolRuntime.of()).stream().map(Completion::value).toList());
    }

    @Test
    void delegatesQuestionsWithTheSelectedCompilationContext(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("module-info.java"), "module example.app {}");
        Files.writeString(directory.resolve("module-info.hash"), "");
        for (var query : List.of(
                List.of("describe", "module", "java.base"), List.of("describe", "type", "java.lang.String"),
                List.of("describe", "member", "java.lang.String.repeat"), List.of("describe", "file", "Example.java"),
                List.of("describe", "types", "Example.java"), List.of("describe", "origin", "java.lang.String"),
                List.of("find", "usages", "java.lang.String"),
                List.of("find", "callers", "java.lang.String.repeat"), List.of("find", "callees", "java.lang.String.repeat"),
                List.of("find", "impl", "java.lang.Runnable"), List.of("find", "supertypes", "java.lang.String"))) {
            var jist = new Probe("jist");
            var jig = new Probe("jig");
            var tools = ToolRuntime.of(jig, jist);
            var output = new ByteArrayOutputStream();
            var errors = new ByteArrayOutputStream();
            int result = new CommandRunner(ModuleLayer.boot(), tools, new ToolCatalog(List.of()), () -> null).run(
                    JaInvocation.parse(directory, query.toArray(String[]::new)), InputStream.nullInputStream(), new PrintStream(output), new PrintStream(errors));
            assertEquals(0, result, errors.toString());
            var expected = new ArrayList<>(List.of("--module-source-path", "example.app=" + directory, "--module", "example.app"));
            if (query.getFirst().equals("describe")) {
                if (query.get(1).equals("types")) expected.add("--referenced-types");
                else if (query.get(1).equals("origin")) expected.addAll(List.of("-l", "--source", "none", "--show-origin", "--visibility", "all"));
                else {
                    if (query.get(1).equals("module")) expected.addAll(List.of("--kind", "module"));
                    else if (query.get(1).equals("member")) expected.addAll(List.of("--kind", "method", "--kind", "field"));
                    else expected.addAll(List.of("--kind", "type", "--kind", "method", "--kind", "field"));
                    expected.addAll(List.of("--source", switch (query.get(1)) {
                        case "module" -> "definition";
                        case "member" -> "doc";
                        default -> "signature";
                    }));
                }
            } else {
                expected.addAll(List.of("--exclude-application-classes", "--exclude-system-modules"));
                expected.add(query.get(1).equals("impl") ? "--subtypes" : "--" + query.get(1));
            }
            boolean sourceFile = query.get(1).equals("file") || query.get(1).equals("types") && query.getLast().endsWith(".java");
            expected.add(sourceFile ? directory.resolve("Example.java").toString() : query.getLast());
            assertEquals(expected, jist.arguments, query.toString());
        }
    }

    @Test
    void findsDeclarationsByNameAndKind(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("module-info.java"), "module example.app {}");
        var jist = new Probe("jist");
        var errors = new ByteArrayOutputStream();

        int result = new CommandRunner(ModuleLayer.boot(), ToolRuntime.of(new Probe("jig"), jist),
                new ToolCatalog(List.of()), () -> null).run(
                        JaInvocation.parse(directory,
                                new String[] {"find", "declarations", "--kind", "type", "Queue"}),
                        InputStream.nullInputStream(), new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(errors));

        assertEquals(0, result, errors.toString());
        assertEquals(List.of(
                "--module-source-path", "example.app=" + directory,
                "--module", "example.app",
                "--exclude-application-classes", "--exclude-system-modules",
                "-l", "--source", "none", "--visibility", "all",
                "--kind", "type", "*Queue*"), jist.arguments);
    }

    @Test
    void findSearchOptionsAddExternalAndSystemCode(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("module-info.java"), "module example.app {}");
        for (var query : List.of(
                List.of("find", "callers", "example.Target.run"),
                List.of("find", "callers", "--external", "example.Target.run"),
                List.of("find", "callers", "--all", "example.Target.run"))) {
            var jist = new Probe("jist");
            var errors = new ByteArrayOutputStream();

            int result = new CommandRunner(ModuleLayer.boot(), ToolRuntime.of(new Probe("jig"), jist),
                    new ToolCatalog(List.of()), () -> null).run(
                            JaInvocation.parse(directory, query.toArray(String[]::new)),
                            InputStream.nullInputStream(), new PrintStream(new ByteArrayOutputStream()),
                            new PrintStream(errors));

            assertEquals(0, result, errors.toString());
            assertEquals(query.size() == 3, jist.arguments.contains("--exclude-application-classes"), query.toString());
            assertEquals(!query.contains("--all"), jist.arguments.contains("--exclude-system-modules"), query.toString());
        }
    }

    @Test
    void semanticCompletionUsesTheSelectedProjectContext(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("module-info.java"), "module example.app {}");
        Files.writeString(directory.resolve("module-info.hash"), "");
        var jist = new Probe("jist");
        var tools = ToolRuntime.of(new Probe("jig"), jist);
        for (var query : List.of(
                List.of("describe", "module", "--kind", "module"), List.of("describe", "type", "--kind", "type"),
                List.of("describe", "member", "--kind", "method", "--kind", "field"),
                List.of("describe", "types", "--kind", "type"), List.of("describe", "origin"),
                List.of("find", "usages", "--usages"),
                List.of("find", "callers", "--callers"), List.of("find", "callees", "--callees"),
                List.of("find", "impl", "--kind", "type", "--kind", "method"),
                List.of("find", "supertypes", "--supertypes"))) {
            var arguments = new ArrayList<>(List.of("-p", "app"));
            arguments.addAll(query.subList(0, 2));
            var request = new CompletionRequest(new ToolInvocation(directory, arguments), "java.lang.Str");
            assertEquals(List.of("java.lang.String"), Ja.complete(request, tools).stream().map(Completion::value).toList());
            var expected = new ArrayList<>(List.of("__complete", "--module-source-path", "example.app=" + directory, "--module", "example.app", "-C", directory.toString()));
            expected.addAll(query.subList(2, query.size()));
            expected.add("java.lang.Str");
            assertEquals(expected, jist.arguments, query.toString());
        }
    }

    @Test
    void buildInsightQueriesEveryContextConcurrentlyAndMergesResults(@TempDir Path directory) throws Exception {
        var first = Files.createDirectories(directory.resolve("first"));
        var second = Files.createDirectories(directory.resolve("second"));
        var context = new ProjectContext(
                new ProjectCatalog(
                        new ProjectAnchor(ProjectType.GRADLE, directory),
                        List.of(
                                new Project(":first", first, List.of(new ProjectScope.Gradle("main", "compile"))),
                                new Project(":second", second, List.of(new ProjectScope.Gradle("main", "compile"))))),
                new BuildScope());
        var parsed = JaInvocation.parse(directory, new String[] {"find", "callers", "example.Target.run"});
        var invocation = new JaInvocation(
                parsed.workingDirectory(),
                parsed.verbose(),
                parsed.command(),
                parsed.moduleSourcePath(),
                parsed.rootModules(),
                parsed.resolutionArguments(),
                parsed.toolArguments(),
                parsed.requestedProjects(),
                Optional.of(context));
        var jist = new ConcurrentJist(2);
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();

        int result = new CommandRunner(ModuleLayer.boot(), ToolRuntime.of(new GlobalJig(), jist),
                new ToolCatalog(List.of()), () -> null).run(
                        invocation, InputStream.nullInputStream(), new PrintStream(output), new PrintStream(errors));

        assertEquals(0, result, errors.toString());
        assertEquals("result :first\nresult :second\nshared result\n", output.toString());
        assertEquals(2, jist.invocations.size());
        for (var arguments : jist.invocations) {
            assertEquals(List.of("--exclude-application-classes", "--exclude-system-modules", "--format", "json"),
                    arguments.subList(arguments.size() - 6, arguments.size() - 2));
            assertEquals(List.of("--callers", "example.Target.run"), arguments.subList(arguments.size() - 2, arguments.size()));
        }
    }

    @Test
    void globalDeclarationSearchTreatsNoMatchesAsAnEmptyResult(@TempDir Path directory) throws Exception {
        var first = Files.createDirectories(directory.resolve("first"));
        var second = Files.createDirectories(directory.resolve("second"));
        var context = new ProjectContext(
                new ProjectCatalog(
                        new ProjectAnchor(ProjectType.GRADLE, directory),
                        List.of(
                                new Project(":first", first, List.of(new ProjectScope.Gradle("main", "compile"))),
                                new Project(":second", second, List.of(new ProjectScope.Gradle("main", "compile"))))),
                new BuildScope());
        var parsed = JaInvocation.parse(directory, new String[] {"find", "declarations", "Missing"});
        var invocation = new JaInvocation(
                parsed.workingDirectory(), parsed.verbose(), parsed.command(), parsed.moduleSourcePath(),
                parsed.rootModules(), parsed.resolutionArguments(), parsed.toolArguments(),
                parsed.requestedProjects(), Optional.of(context));
        var jist = new NotFoundJist();
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();

        int result = new CommandRunner(ModuleLayer.boot(), ToolRuntime.of(new GlobalJig(), jist),
                new ToolCatalog(List.of()), () -> null).run(
                        invocation, InputStream.nullInputStream(), new PrintStream(output), new PrintStream(errors));

        assertEquals(0, result, errors.toString());
        assertEquals("", output.toString());
        assertEquals("", errors.toString());
        assertEquals(2, jist.invocations.size());
    }

    @Test
    void globalDeclarationSearchRendersBoundedSourceLocations(@TempDir Path directory) throws Exception {
        var first = Files.createDirectories(directory.resolve("first"));
        var second = Files.createDirectories(directory.resolve("second"));
        var source = Files.createDirectories(directory.resolve("src/example")).resolve("Root.java");
        Files.writeString(source, """
                package example;
                class Root {
                    void run() {}
                }
                """);
        var context = new ProjectContext(
                new ProjectCatalog(
                        new ProjectAnchor(ProjectType.GRADLE, directory),
                        List.of(
                                new Project(":first", first, List.of(new ProjectScope.Gradle("main", "compile"))),
                                new Project(":second", second, List.of(new ProjectScope.Gradle("main", "compile"))))),
                new BuildScope());
        var parsed = JaInvocation.parse(directory, new String[] {"find", "declarations", "Root"});
        var invocation = new JaInvocation(
                parsed.workingDirectory(), parsed.verbose(), parsed.command(), parsed.moduleSourcePath(),
                parsed.rootModules(), parsed.resolutionArguments(), parsed.toolArguments(),
                parsed.requestedProjects(), Optional.of(context));
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();

        int result = new CommandRunner(ModuleLayer.boot(), ToolRuntime.of(new GlobalJig(), new SourceRangeJist(source)),
                new ToolCatalog(List.of()), () -> null).run(
                        invocation, InputStream.nullInputStream(), new PrintStream(output), new PrintStream(errors));

        assertEquals(0, result, errors.toString());
        assertEquals("src/example/Root.java:2-4 of 4: class example.Root\n", output.toString());
    }

    @Test
    void globalSearchesScanIdenticalExternalAndSystemInputsOnce(@TempDir Path directory) throws Exception {
        var first = Files.createDirectories(directory.resolve("first"));
        var second = Files.createDirectories(directory.resolve("second"));
        var context = new ProjectContext(
                new ProjectCatalog(
                        new ProjectAnchor(ProjectType.GRADLE, directory),
                        List.of(
                                new Project(":first", first, List.of(new ProjectScope.Gradle("main", "compile"))),
                                new Project(":second", second, List.of(new ProjectScope.Gradle("main", "compile"))))),
                new BuildScope());
        var parsed = JaInvocation.parse(directory, new String[] {"find", "callers", "--all", "example.Target.run"});
        var invocation = new JaInvocation(
                parsed.workingDirectory(), parsed.verbose(), parsed.command(), parsed.moduleSourcePath(),
                parsed.rootModules(), parsed.resolutionArguments(), parsed.toolArguments(),
                parsed.requestedProjects(), Optional.of(context));
        var jist = new ConcurrentJist(2, true);
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();

        int result = new CommandRunner(ModuleLayer.boot(), ToolRuntime.of(new SharedResolutionJig(), jist),
                new ToolCatalog(List.of()), () -> null).run(
                        invocation, InputStream.nullInputStream(), new PrintStream(output), new PrintStream(errors));

        assertEquals(0, result, errors.toString());
        assertEquals(
                "java.lang.String(String.java:4034): java.lang.String.indent(int) -> java.lang.String.repeat(int)\n",
                output.toString());
        assertEquals(2, jist.invocations.size());
        assertEquals(1, jist.invocations.stream().filter(arguments -> arguments.contains("--exclude-application-classes")).count());
        assertEquals(1, jist.invocations.stream().filter(arguments -> arguments.contains("--exclude-system-modules")).count());
    }

    @Test
    void globalSearchesScanOverlappingPathsOnce(@TempDir Path directory) throws Exception {
        var first = Files.createDirectories(directory.resolve("first"));
        var second = Files.createDirectories(directory.resolve("second"));
        var context = new ProjectContext(
                new ProjectCatalog(
                        new ProjectAnchor(ProjectType.GRADLE, directory),
                        List.of(
                                new Project(":first", first, List.of(new ProjectScope.Gradle("main", "compile"))),
                                new Project(":second", second, List.of(new ProjectScope.Gradle("main", "compile"))))),
                new BuildScope());
        var parsed = JaInvocation.parse(directory, new String[] {"find", "callers", "--all", "example.Target.run"});
        var invocation = new JaInvocation(
                parsed.workingDirectory(), parsed.verbose(), parsed.command(), parsed.moduleSourcePath(),
                parsed.rootModules(), parsed.resolutionArguments(), parsed.toolArguments(),
                parsed.requestedProjects(), Optional.of(context));
        var jist = new ConcurrentJist(2);
        var errors = new ByteArrayOutputStream();

        int result = new CommandRunner(ModuleLayer.boot(), ToolRuntime.of(new OverlappingResolutionJig(directory), jist),
                new ToolCatalog(List.of()), () -> null).run(
                        invocation, InputStream.nullInputStream(), new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(errors));

        assertEquals(0, result, errors.toString());
        assertEquals(2, jist.invocations.size());
        var excluded = jist.invocations.stream()
                .filter(arguments -> arguments.contains("--exclude-path"))
                .map(arguments -> arguments.get(arguments.indexOf("--exclude-path") + 1))
                .toList();
        assertEquals(List.of(directory + java.io.File.pathSeparator
                + directory.resolve("shared.jar") + java.io.File.pathSeparator
                + directory.resolve("shared-sources.jar")), excluded);
        for (var arguments : jist.invocations) {
            var classPath = arguments.get(arguments.indexOf("--class-path") + 1);
            assertTrue(classPath.contains("shared.jar"), classPath);
            assertTrue(classPath.contains("unique.jar"), classPath);
            assertFalse(arguments.contains("--exclude-application-classes"), arguments.toString());
        }
    }

    @Test
    void globalSearchesRetainDistinctSystemImages(@TempDir Path directory) throws Exception {
        var first = Files.createDirectories(directory.resolve("first"));
        var second = Files.createDirectories(directory.resolve("second"));
        var context = new ProjectContext(
                new ProjectCatalog(
                        new ProjectAnchor(ProjectType.GRADLE, directory),
                        List.of(
                                new Project(":first", first, List.of(new ProjectScope.Gradle("main", "compile"))),
                                new Project(":second", second, List.of(new ProjectScope.Gradle("main", "compile"))))),
                new BuildScope());
        var parsed = JaInvocation.parse(directory, new String[] {"find", "callers", "--all", "example.Target.run"});
        var invocation = new JaInvocation(
                parsed.workingDirectory(), parsed.verbose(), parsed.command(), parsed.moduleSourcePath(),
                parsed.rootModules(), parsed.resolutionArguments(), parsed.toolArguments(),
                parsed.requestedProjects(), Optional.of(context));
        var jist = new ConcurrentJist(2, true);
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();

        int result = new CommandRunner(ModuleLayer.boot(), ToolRuntime.of(new DistinctSystemJig(), jist),
                new ToolCatalog(List.of()), () -> null).run(
                        invocation, InputStream.nullInputStream(), new PrintStream(output),
                        new PrintStream(errors));

        assertEquals(0, result, errors.toString());
        assertEquals("""
                java.base@21/java.lang.String(String.java:3724): java.lang.String.indent(int) -> java.lang.String.repeat(int)
                java.base@25/java.lang.String(String.java:4034): java.lang.String.indent(int) -> java.lang.String.repeat(int)
                """, output.toString());
        assertEquals(0, jist.invocations.stream().filter(arguments -> arguments.contains("--exclude-application-classes")).count());
        assertEquals(0, jist.invocations.stream().filter(arguments -> arguments.contains("--exclude-system-modules")).count());
    }

    @Test
    void globalImplementationSearchResolvesTheQuestionInEachContext(@TempDir Path directory) throws Exception {
        var first = Files.createDirectories(directory.resolve("first"));
        var second = Files.createDirectories(directory.resolve("second"));
        var context = new ProjectContext(
                new ProjectCatalog(
                        new ProjectAnchor(ProjectType.GRADLE, directory),
                        List.of(
                                new Project(":first", first, List.of(new ProjectScope.Gradle("main", "compile"))),
                                new Project(":second", second, List.of(new ProjectScope.Gradle("main", "compile"))))),
                new BuildScope());
        var parsed = JaInvocation.parse(directory, new String[] {"find", "impl", "example.Root"});
        var invocation = new JaInvocation(
                parsed.workingDirectory(), parsed.verbose(), parsed.command(), parsed.moduleSourcePath(),
                parsed.rootModules(), parsed.resolutionArguments(), parsed.toolArguments(),
                parsed.requestedProjects(), Optional.of(context));
        var jist = new ImplementationJist();
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();

        int result = new CommandRunner(ModuleLayer.boot(), ToolRuntime.of(new GlobalJig(), jist),
                new ToolCatalog(List.of()), () -> null).run(
                        invocation, InputStream.nullInputStream(), new PrintStream(output), new PrintStream(errors));

        assertEquals(0, result, errors.toString());
        assertEquals("implementation :first\nimplementation :second\n", output.toString());
        assertEquals(4, jist.invocations.size());
        assertEquals(2, jist.invocations.stream().filter(arguments -> arguments.contains("--exclude-application-classes")).count());
        assertEquals(2, jist.invocations.stream().filter(arguments -> arguments.contains("--exclude-system-modules")).count());
        assertEquals(2, jist.invocations.stream().filter(arguments -> arguments.contains("--subtypes")).count());
    }

    @Test
    void fileInsightNarrowsBuildScopeToItsProject(@TempDir Path directory) throws Exception {
        var first = Files.createDirectories(directory.resolve("first"));
        var second = Files.createDirectories(directory.resolve("second"));
        var source = first.resolve("Example.java");
        Files.writeString(source, "class Example {}");
        var context = new ProjectContext(
                new ProjectCatalog(
                        new ProjectAnchor(ProjectType.GRADLE, directory),
                        List.of(
                                new Project(":first", first, List.of(new ProjectScope.Gradle("main", "compile"))),
                                new Project(":second", second, List.of(new ProjectScope.Gradle("main", "compile"))))),
                new BuildScope());
        var parsed = JaInvocation.parse(directory, new String[] {"describe", "file", source.toString()});
        var invocation = new JaInvocation(
                parsed.workingDirectory(), parsed.verbose(), parsed.command(), parsed.moduleSourcePath(),
                parsed.rootModules(), parsed.resolutionArguments(), parsed.toolArguments(),
                parsed.requestedProjects(), Optional.of(context));
        var jist = new ConcurrentJist(1);
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();

        int result = new CommandRunner(ModuleLayer.boot(), ToolRuntime.of(new GlobalJig(), jist),
                new ToolCatalog(List.of()), () -> null).run(
                        invocation, InputStream.nullInputStream(), new PrintStream(output), new PrintStream(errors));

        assertEquals(0, result, errors.toString());
        assertEquals("result :first\nshared result\n", output.toString());
        assertEquals(1, jist.invocations.size());
    }

    @Test
    void implementationSearchUsesReportedTypeKinds(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("module-info.java"), "module example.app {}");
        for (var kind : List.of("class", "interface", "enum", "record", "annotation")) {
            var jig = new Probe("jig");
            var jist = new Probe("jist");
            jist.symbols = kind + " example.Root\nclass example.Root.Nested\n";
            var output = new ByteArrayOutputStream();
            var errors = new ByteArrayOutputStream();
            int result = new CommandRunner(ModuleLayer.boot(), ToolRuntime.of(jig, jist), new ToolCatalog(List.of()), () -> null).run(
                    JaInvocation.parse(directory, new String[] {"find", "impl", "example.Root"}), InputStream.nullInputStream(),
                    new PrintStream(output), new PrintStream(errors));
            assertEquals(0, result, errors.toString());
            var context = List.of("--module-source-path", "example.app=" + directory, "--module", "example.app");
            var lookup = new ArrayList<>(context);
            lookup.addAll(List.of("-l", "--kind", "type", "--source", "none", "--visibility", "all", "example.Root"));
            var query = new ArrayList<>(context);
            query.addAll(List.of("--exclude-application-classes", "--exclude-system-modules", "--subtypes", "example.Root"));
            assertEquals(List.of(lookup, query), jist.invocations, kind);
            assertEquals(1, jig.invocations.size());
            assertEquals("", output.toString());
        }
    }

    @Test
    void originReportsOnlyTheExactSymbolAndItsOverloads(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("module-info.java"), "module example.app {}");
        var jist = new Probe("jist");
        jist.symbols = """
                class example.Root(/classes/example/Root.class)
                method example.Root.run()(/classes/example/Root.class)
                method example.Root.run(int)(/classes/example/Root.class)
                class example.Root.run.Nested(/classes/example/Root$run$Nested.class)
                """;
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();

        int result = new CommandRunner(ModuleLayer.boot(), ToolRuntime.of(new Probe("jig"), jist), new ToolCatalog(List.of()), () -> null).run(
                JaInvocation.parse(directory, new String[] {"describe", "origin", "example.Root.run"}), InputStream.nullInputStream(),
                new PrintStream(output), new PrintStream(errors));

        assertEquals(0, result, errors.toString());
        assertEquals("""
                method example.Root.run()(/classes/example/Root.class)
                method example.Root.run(int)(/classes/example/Root.class)
                """, output.toString());
    }

    @Test
    void originMatchesModuleQualifiedSymbols(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("module-info.java"), "module example.app {}");
        var jist = new Probe("jist");
        jist.symbols = """
                class java.base@25/java.lang.String.CaseInsensitiveComparator
                class java.base@25/java.lang.String
                field java.base@25/java.lang.String.value
                """;
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();

        int result = new CommandRunner(ModuleLayer.boot(), ToolRuntime.of(new Probe("jig"), jist), new ToolCatalog(List.of()), () -> null).run(
                JaInvocation.parse(directory, new String[] {"describe", "origin", "java.lang.String"}), InputStream.nullInputStream(),
                new PrintStream(output), new PrintStream(errors));

        assertEquals(0, result, errors.toString());
        assertEquals("class java.base@25/java.lang.String\n", output.toString());
    }

    @Test
    void originRetainsJistSimpleNameMatching(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("module-info.java"), "module example.app {}");
        var jist = new Probe("jist");
        jist.symbols = """
                class example.first.Root(/classes/example/first/Root.class)
                class example.first.Root.Nested(/classes/example/first/Root$Nested.class)
                class example.second.Root(/classes/example/second/Root.class)
                """;
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();

        int result = new CommandRunner(ModuleLayer.boot(), ToolRuntime.of(new Probe("jig"), jist), new ToolCatalog(List.of()), () -> null).run(
                JaInvocation.parse(directory, new String[] {"describe", "origin", "Root"}), InputStream.nullInputStream(),
                new PrintStream(output), new PrintStream(errors));

        assertEquals(0, result, errors.toString());
        assertEquals("""
                class example.first.Root(/classes/example/first/Root.class)
                class example.second.Root(/classes/example/second/Root.class)
                """, output.toString());
    }

    @Test
    void implementationSearchLeavesMethodValidationAndInheritanceToJist(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("module-info.java"), "module example.app {}");
        for (var symbols : List.of("", "class example.Root.run.Nested\n")) {
            var jist = new Probe("jist");
            jist.symbols = symbols;
            var errors = new ByteArrayOutputStream();
            int result = new CommandRunner(ModuleLayer.boot(), ToolRuntime.of(new Probe("jig"), jist), new ToolCatalog(List.of()), () -> null).run(
                    JaInvocation.parse(directory, new String[] {"find", "impl", "example.Root.run"}), InputStream.nullInputStream(),
                    new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors));
            assertEquals(0, result, errors.toString());
            assertEquals(List.of("--overrides", "example.Root.run"), jist.arguments.subList(jist.arguments.size() - 2, jist.arguments.size()));
            assertEquals(2, jist.invocations.size());
        }
    }

    @Test
    void failedImplementationLookupDoesNotRunARelationshipQuery(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("module-info.java"), "module example.app {}");
        var jist = new Probe("jist");
        jist.lookupStatus = 9;
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        int result = new CommandRunner(ModuleLayer.boot(), ToolRuntime.of(new Probe("jig"), jist), new ToolCatalog(List.of()), () -> null).run(
                JaInvocation.parse(directory, new String[] {"find", "impl", "java.lang.Runnable"}), InputStream.nullInputStream(),
                new PrintStream(output), new PrintStream(errors));
        assertEquals(9, result);
        assertEquals(1, jist.invocations.size());
        assertEquals("", output.toString());
        assertEquals("lookup failed\n", errors.toString());
    }

    private static final class GlobalJig implements ToolProvider {
        @Override public String name() { return "jig"; }
        @Override public int run(PrintWriter out, PrintWriter err, String... args) {
            var arguments = List.of(args);
            int project = arguments.indexOf("--project-path");
            out.println("--class-path");
            out.println(arguments.get(project + 1));
            return 0;
        }
    }

    private static final class SharedResolutionJig implements ToolProvider {
        @Override public String name() { return "jig"; }
        @Override public int run(PrintWriter out, PrintWriter err, String... args) {
            out.println("--class-path");
            out.println("shared-classes");
            out.println("--system");
            out.println("shared-system");
            return 0;
        }
    }

    private static final class OverlappingResolutionJig implements ToolProvider {
        private final Path directory;

        OverlappingResolutionJig(Path directory) {
            this.directory = directory;
        }

        @Override public String name() { return "jig"; }
        @Override public int run(PrintWriter out, PrintWriter err, String... args) {
            var arguments = List.of(args);
            var project = directory.resolve(arguments.get(arguments.indexOf("--project-path") + 1));
            out.println("--class-path");
            out.println(project.resolve("unique.jar") + java.io.File.pathSeparator + project.getParent().resolve("shared.jar"));
            out.println("--source-path");
            out.println(project.getParent().resolve("shared-sources.jar"));
            return 0;
        }
    }

    private static final class DistinctSystemJig implements ToolProvider {
        @Override public String name() { return "jig"; }
        @Override public int run(PrintWriter out, PrintWriter err, String... args) {
            var arguments = List.of(args);
            int project = arguments.indexOf("--project-path");
            out.println("--class-path");
            out.println("shared-classes");
            out.println("--system");
            out.println(arguments.get(project + 1) + "-system");
            return 0;
        }
    }

    private static final class SourceRangeJist implements ToolProvider, OptionChecker {
        private final Path source;

        SourceRangeJist(Path source) {
            this.source = source;
        }

        @Override public String name() { return "jist"; }
        @Override public int isSupportedOption(String option) {
            return switch (option) {
                case "--class-path", "--format" -> 1;
                default -> -1;
            };
        }
        @Override public int run(PrintWriter out, PrintWriter err, String... args) {
            out.print("{\"format\":\"jist\",\"version\":1,\"results\":[{\"text\":\"class example.Root\","
                    + "\"source\":{\"location\":\"" + source.toUri() + "\",\"lineCount\":4,"
                    + "\"range\":{\"startLine\":2,\"endLine\":4}}}]}\n");
            return 0;
        }
    }

    private static final class NotFoundJist implements ToolProvider, OptionChecker {
        private final List<List<String>> invocations = Collections.synchronizedList(new ArrayList<>());

        @Override public String name() { return "jist"; }
        @Override public int isSupportedOption(String option) {
            return switch (option) {
                case "--class-path", "--format" -> 1;
                default -> -1;
            };
        }
        @Override public int run(PrintWriter out, PrintWriter err, String... args) {
            invocations.add(List.of(args));
            err.println("Symbol not found: *Missing*");
            return 1;
        }
    }

    private static final class ImplementationJist implements ToolProvider, OptionChecker {
        private final List<List<String>> invocations = Collections.synchronizedList(new ArrayList<>());

        @Override public String name() { return "jist"; }
        @Override public int isSupportedOption(String option) {
            return switch (option) {
                case "--class-path", "--format" -> 1;
                default -> -1;
            };
        }
        @Override public int run(PrintWriter out, PrintWriter err, String... args) {
            var arguments = List.of(args);
            invocations.add(arguments);
            if (arguments.contains("-l")) {
                out.println("class example.Root");
                return 0;
            }
            int classPath = arguments.indexOf("--class-path");
            out.print("{\"format\":\"jist\",\"version\":1,\"results\":[{\"text\":\"implementation "
                    + arguments.get(classPath + 1) + "\"}]}\n");
            return 0;
        }
    }

    private static final class ConcurrentJist implements ToolProvider, OptionChecker {
        private final CountDownLatch started;
        private final boolean systemOrigins;
        private final List<List<String>> invocations = Collections.synchronizedList(new ArrayList<>());

        ConcurrentJist(int contexts) {
            this(contexts, false);
        }

        ConcurrentJist(int contexts, boolean systemOrigins) {
            started = new CountDownLatch(contexts);
            this.systemOrigins = systemOrigins;
        }

        @Override public String name() { return "jist"; }
        @Override public int isSupportedOption(String option) {
            return switch (option) {
                case "--class-path", "--source-path", "--format", "--system", "--exclude-path" -> 1;
                default -> -1;
            };
        }
        @Override public int run(PrintWriter out, PrintWriter err, String... args) {
            var arguments = List.of(args);
            invocations.add(arguments);
            started.countDown();
            try {
                if (!started.await(5, TimeUnit.SECONDS)) {
                    err.println("insight contexts did not run concurrently");
                    return 2;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 2;
            }
            if (systemOrigins) {
                int system = arguments.indexOf("--system");
                var systemPath = arguments.get(system + 1);
                var version = systemPath.endsWith("first-system") ? "21" : "25";
                var line = version.equals("21") ? "3724" : "4034";
                out.print("{\"format\":\"jist\",\"version\":1,\"results\":["
                        + "{\"text\":\"java.lang.String(String.java:" + line
                        + "): java.lang.String.indent(int) -> java.lang.String.repeat(int)\","
                        + "\"origin\":{\"kind\":\"system\",\"location\":\"jrt:/java.base/java/lang/String.class\","
                        + "\"system\":\"" + systemPath + "\",\"module\":\"java.base\",\"version\":\""
                        + version + "\",\"resource\":\"java/lang/String.class\",\"source\":\"String.java\",\"line\":"
                        + line + "}}]}\n");
                return 0;
            }
            int classPath = arguments.indexOf("--class-path");
            out.print("{\"format\":\"jist\",\"version\":1,\"results\":["
                    + "{\"text\":\"shared result\"},{\"text\":\"result "
                    + arguments.get(classPath + 1) + "\"}]}\n");
            return 0;
        }
    }

    private static final class Probe implements ToolProvider, OptionChecker {
        private final String name;
        private final List<List<String>> invocations = new ArrayList<>();
        private List<String> arguments;
        private String symbols = "interface java.lang.Runnable\n";
        private int lookupStatus;

        Probe(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public int isSupportedOption(String option) {
            return switch (option) {
                case "--module-source-path", "--module" -> 1;
                case "__complete" -> 0;
                default -> -1;
            };
        }
        @Override public int run(PrintWriter out, PrintWriter err, String... args) {
            arguments = List.of(args);
            invocations.add(arguments);
            if (name.equals("jig")) {
                int source = arguments.indexOf("--module-source-path");
                out.print("--module-source-path\n" + arguments.get(source + 1) + "\n--module\nexample.app\n");
            } else if (!arguments.isEmpty() && arguments.getFirst().equals("__complete")) {
                out.println("java.lang.String\tclass");
                out.println(":0");
            } else if (arguments.contains("-l")) {
                out.print(symbols);
                if (lookupStatus != 0) err.println("lookup failed");
                return lookupStatus;
            }
            return 0;
        }
    }

    private static List<String> complete(List<String> arguments, String current) {
        return Ja.complete(new CompletionRequest(new ToolInvocation(arguments), current), ToolRuntime.of()).stream()
                .map(Completion::value).filter(value -> !value.startsWith("-")).toList();
    }
}
