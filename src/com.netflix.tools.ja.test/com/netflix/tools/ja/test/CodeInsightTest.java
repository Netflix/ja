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
import java.util.List;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CodeInsightTest {
    @Test
    void describesExplicitSubjectKindsAndFindsExplicitRelationships() throws Exception {
        for (var command : List.of("describe", "find")) {
            var subjects = command.equals("describe") ? List.of("module", "type", "member", "file") : List.of("usages", "callers", "impl");
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
        assertEquals(List.of("file", "member", "module", "type"), complete(List.of("describe"), ""));
        assertEquals(List.of("callers", "impl", "usages"), complete(List.of("find"), ""));
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
                List.of("find", "usages", "java.lang.String"), List.of("find", "callers", "java.lang.String.repeat"),
                List.of("find", "impl", "java.lang.Runnable"))) {
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
                if (query.get(1).equals("module")) expected.addAll(List.of("--kind", "module"));
                else if (query.get(1).equals("member")) expected.addAll(List.of("--kind", "method", "--kind", "field"));
                else expected.addAll(List.of("--kind", "type", "--kind", "method", "--kind", "field"));
                expected.addAll(List.of("--source", switch (query.get(1)) {
                    case "module" -> "definition";
                    case "member" -> "doc";
                    default -> "signature";
                }));
            } else expected.add(query.get(1).equals("impl") ? "--subtypes" : "--" + query.get(1));
            expected.add(query.get(1).equals("file") ? directory.resolve("Example.java").toString() : query.getLast());
            assertEquals(expected, jist.arguments, query.toString());
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
                List.of("find", "usages", "--usages"), List.of("find", "callers", "--callers"),
                List.of("find", "impl", "--kind", "type", "--kind", "method"))) {
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
            query.addAll(List.of("--subtypes", "example.Root"));
            assertEquals(List.of(lookup, query), jist.invocations, kind);
            assertEquals(1, jig.invocations.size());
            assertEquals("", output.toString());
        }
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
