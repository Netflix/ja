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
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.ServiceLoader.Provider;
import java.util.spi.ToolProvider;
import javax.tools.OptionChecker;
import javax.tools.Tool;

import com.netflix.tools.cli.CommandLine;
import com.netflix.tools.cli.CommandLine.Completion;
import com.netflix.tools.cli.CommandLine.CompletionRequest;
import com.netflix.tools.cli.CommandLine.Subcommand;
import com.netflix.tools.cli.CommandLine.ToolInvocation;
import com.netflix.tools.cli.CommandLine.ToolOption;
import com.netflix.tools.ja.Ja;
import com.netflix.tools.ja.JaTool;
import com.netflix.tools.ja.ToolServices;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JaToolTest {
    private final Tool ja = ServiceLoader.load(Tool.class).stream()
            .map(Provider::get)
            .filter(candidate -> candidate.name().equals("ja"))
            .findFirst()
            .orElseThrow();

    @Test
    void isOnlyPublishedAsAStreamAwareTool() {
        assertTrue(ToolProvider.findFirst("ja")
                .isEmpty());

        Result result = run("--help");
        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output()
                         .startsWith("Usage: ja "));
    }

    @Test
    void describesItsOwnedCommandLine() {
        var described = assertInstanceOf(JaTool.class, ja);

        assertEquals(0, described.isSupportedOption("--verbose"));
        assertEquals(0, described.isSupportedOption("--help"));
        assertEquals(0, described.isSupportedOption("-h"));
        assertEquals(1, described.isSupportedOption("-C"));
        assertEquals(0, described.isSupportedOption("__complete"));
        assertEquals(-1, described.isSupportedOption("--completion"));
        assertEquals(-1, described.isSupportedOption("--module-path"));
        assertEquals(
                List.of("--help"),
                described.commandLine().complete(List.of("--h")).stream()
                        .map(completion -> completion.value())
                        .toList());

        var invocation = described.commandLine().prepare(ToolInvocation.of("-C", "project", "compile", "--recompile"));
        assertEquals(Path.of("project"), invocation.workingDirectory());
        assertEquals(List.of("compile", "--recompile"), invocation.arguments());

        var parsed = described.commandLine().parse("--verbose", "compile", "--recompile");
        var command = parsed.command().orElseThrow();
        assertEquals("compile", command.name());
        assertTrue(
                command.arguments().contains(commandLine(described, "compile").options().stream()
                        .filter(option -> option.names().contains("--recompile"))
                        .findFirst()
                        .orElseThrow()));
    }

    @Test
    void describesAndCompletesItsCommands() {
        var described = assertInstanceOf(JaTool.class, ja);

        assertEquals(
                List.of("compile"),
                Ja.complete(new CompletionRequest(ToolInvocation.of(), "co"), ToolServices.of()).stream()
                        .map(completion -> completion.value())
                        .toList());
        assertEquals(
                List.of("--recompile"),
                described.commandLine().complete(List.of("compile", "--r")).stream()
                        .map(completion -> completion.value())
                        .toList());
        assertEquals(19,
                described.commandLine()
                         .commands()
                         .size());
    }

    @Test
    void builtInCommandsExposeExplicitInterfacesWhileToolDelegates() {
        var described = assertInstanceOf(JaTool.class, ja);

        assertThrows(IllegalArgumentException.class, () -> commandLine(described, "fmt").parse("--check"));
        var test = commandLine(described, "test");
        test.parse("-t", "fast", "--tag=unit", "--all");
        assertThrows(IllegalArgumentException.class, () -> test.parse("--include-tag", "fast"));
        assertThrows(IllegalArgumentException.class, () -> commandLine(described, "bench").parse("-wi", "3"));
        assertEquals(List.of("example.ExampleTest", "example.ExampleTest.works"),
                commandLine(described, "test").parse("example.ExampleTest", "example.ExampleTest.works").operands());
        assertEquals(List.of("example.ExampleBenchmark", "example.ExampleBenchmark.works"),
                commandLine(described, "bench").parse("example.ExampleBenchmark", "example.ExampleBenchmark.works").operands());
        assertThrows(IllegalArgumentException.class, () -> commandLine(described, "generate").parse("-Astyle=records"));
        assertThrows(IllegalArgumentException.class, () -> commandLine(described, "list").parse("--add-modules", "java.sql"));
        assertThrows(IllegalArgumentException.class, () -> commandLine(described, "describe").parse("--add-modules", "java.sql"));
        assertThrows(IllegalArgumentException.class, () -> commandLine(described, "jar").parse("--create"));
        assertThrows(IllegalArgumentException.class, () -> commandLine(described, "mod").parse("--module-version", "1"));
        assertTrue(commandLine(described, "test").parse("--all")
                .contains(option(commandLine(described, "test"), "--all")));
        CommandLine assemble = commandLine(described, "assemble");
        assemble.parse("--module-version", "1.0", "artifacts");
        CommandLine deploy = commandLine(commandLine(described, "maven"), "deploy");
        assertTrue(deploy.parse("--sign", "--module-version", "1.0")
                         .contains(option(deploy, "--sign")));
        assertEquals(List.of("--application-option"),
                commandLine(described, "run").parse("--application-option").operands());
        assertEquals(List.of("jfmt", "--unknown"),
                commandLine(described, "tool").parse("jfmt", "--unknown").operands());
    }

    @Test
    void delegatesToolCommandCompletionThroughTheStandardProtocol() {
        Result result = run("__complete", "tool", "ja", "--v");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("--verbose\tEnable verbose output\n"),
                result.output());
        assertTrue(result.output().endsWith(":0\n"),
                result.output());
        assertEquals("", result.error());
    }

    @Test
    void delegatesSourceAndDocumentationValueCompletionToJist() {
        var jist = new CompletingJist();
        var tools = ToolServices.of(jist);

        assertEquals(List.of("candidate"),
                Ja.complete(new CompletionRequest(ToolInvocation.of("source"), "Str"), tools).stream()
                        .map(Completion::value)
                        .toList());
        assertEquals(List.of("candidate"),
                Ja.complete(new CompletionRequest(ToolInvocation.of("doc"), "Str"), tools).stream()
                        .map(Completion::value)
                        .toList());
        assertEquals(List.of("candidate"),
                Ja.complete(new CompletionRequest(ToolInvocation.of("doc", "--browse"), "Str"), tools).stream()
                        .map(Completion::value)
                        .toList());
        assertEquals(
                List.of(List.of("__complete", "--source", "symbol", "Str"), List.of("__complete", "--source", "doc", "--break", "--no-line-number", "Str"), List.of("__complete", "Str")),
                jist.invocations);
    }

    @Test
    void composesAvailableToolCompletions() {
        Result result = run("completion", "powershell");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("Register-ArgumentCompleter"),
                result.output());
        assertTrue(result.output().contains("-CommandName 'ja'"),
                result.output());
        assertEquals("", result.error());
    }

    @Test
    void declaresLauncherMetadata() throws Exception {
        String resource = "META-INF/com.netflix.tools/tools/ja.properties";
        try (var input = ja.getClass()
                           .getModule()
                           .getResourceAsStream(resource)) {
            assertTrue(input != null, resource);
            var properties = new Properties();
            properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            assertEquals("--aot-warmup", properties.getProperty("warmup"));
            assertEquals("verbose", properties.getProperty("options"));
        }
    }

    @Test
    void warmsCommonJaWorkflows() {
        Result result = run("--aot-warmup");

        assertEquals(0, result.exitCode(), result.error());
        assertEquals("", result.output());
        assertEquals("", result.error());
    }

    @Test
    void appliesTheWorkingDirectoryConventionProgrammatically(@TempDir Path directory) {
        Result result = run("-C", directory.toString(), "--module-source-path", directory.toString(),
                "compile");

        assertEquals(2, result.exitCode());
        assertEquals("ja: Unknown ja option: --module-source-path\n", result.error());
    }

    @Test
    void rejectsResolutionArgumentsBeforeLookingForJig(@TempDir Path directory) {
        Result result = run("--module-source-path", directory.toString(), "compile");

        assertEquals(2, result.exitCode());
        assertEquals("ja: Unknown ja option: --module-source-path\n", result.error());
        assertFalse(result.error().contains("jig"),
                result.error());
    }

    @Test
    void installRejectsNativeArguments() {
        Result result = run("install", "com.example.foo@1", "--", "--verbose");

        assertEquals(2, result.exitCode());
        assertEquals("ja: Unknown install argument: --\n", result.error());
    }

    @Test
    void rejectsUnknownCommand() {
        Result result = run("unknown");

        assertEquals(2, result.exitCode());
        assertEquals("ja: Unknown command: unknown\n", result.error());
    }

    @Test
    void rejectsRemovedLinkCommand() {
        Result result = run("link", "--output", "image");

        assertEquals(2, result.exitCode());
        assertEquals("ja: Unknown command: link\n", result.error());
    }

    @Test
    void printsHelp() {
        Result result = run("--help");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().startsWith("Usage: ja "),
                result.output());
        assertFalse(result.output().contains("__complete"),
                result.output());
        assertFalse(result.output().contains("--completion"),
                result.output());
    }

    @Test
    void printsHelpWithVerboseBeforeTheCommand() {
        Result result = run("--verbose", "compile", "--help");

        assertEquals(0, result.exitCode(), result.error());
        var described = assertInstanceOf(JaTool.class, ja);
        assertEquals(commandLine(described, "compile").help("ja compile"), result.output());
        assertEquals("", result.error());
    }

    @Test
    void printsInitHelpWithoutResolvingModules() {
        Result result = run("init", "--help");

        assertEquals(0, result.exitCode(), result.error());
        var described = assertInstanceOf(JaTool.class, ja);
        assertEquals(commandLine(described, "init").help("ja init"), result.output());
        assertEquals("", result.error());
    }

    @Test
    void printsCompileHelp() {
        Result result = run("compile", "--help");

        assertEquals(0, result.exitCode(), result.error());
        var described = assertInstanceOf(JaTool.class, ja);
        assertEquals(commandLine(described, "compile").help("ja compile"), result.output());
        assertEquals("", result.error());
    }

    @Test
    void printsComposableCommandHelp() {
        Result result = run("test", "--help");

        assertEquals(0, result.exitCode(), result.error());
        var described = assertInstanceOf(JaTool.class, ja);
        assertEquals(commandLine(described, "test").help("ja test"), result.output());
        assertEquals("", result.error());
    }

    @Test
    void printsCompletionCommandHelp() {
        Result result = run("completion", "--help");

        assertEquals(0, result.exitCode(), result.error());
        var described = assertInstanceOf(JaTool.class, ja);
        assertEquals(commandLine(described, "completion").help("ja completion"), result.output());
    }

    @Test
    void printsToolCommandHelp() {
        Result result = run("tool", "--help");

        assertEquals(0, result.exitCode(), result.error());
        var described = assertInstanceOf(JaTool.class, ja);
        assertEquals(commandLine(described, "tool").help("ja tool"), result.output());
        assertEquals("", result.error());
    }

    private static ToolOption option(CommandLine commandLine, String name) {
        return commandLine.options().stream()
                .filter(candidate -> candidate.names().contains(name))
                .findFirst()
                .orElseThrow();
    }

    private static CommandLine commandLine(JaTool tool, String command) {
        return commandLine(tool.commandLine(), command);
    }

    private static CommandLine commandLine(CommandLine parent, String command) {
        return parent.commands().stream()
                .filter(candidate -> candidate.name().equals(command))
                .map(Subcommand::commandLine)
                .findFirst()
                .orElseThrow();
    }

    private Result run(String... arguments) {
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        int exitCode = ja.run(InputStream.nullInputStream(), output, errors, arguments);
        return new Result(exitCode, output.toString(StandardCharsets.UTF_8), errors.toString(StandardCharsets.UTF_8));
    }

    private static final class CompletingJist implements ToolProvider, OptionChecker {
        private final List<List<String>> invocations = new ArrayList<>();

        @Override
        public String name() {
            return "jist";
        }

        @Override
        public int isSupportedOption(String option) {
            return option.equals("__complete") ? 0 : -1;
        }

        @Override
        public int run(PrintWriter out, PrintWriter err, String... args) {
            invocations.add(List.of(args));
            out.println("candidate\tJist candidate");
            out.println(":0");
            return 0;
        }
    }

    private record Result(int exitCode, String output, String error) {}
}
