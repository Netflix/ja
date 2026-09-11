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

package com.netflix.tools.cli.test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import javax.tools.ToolProvider;

import com.netflix.tools.cli.CommandLine;
import com.netflix.tools.cli.CommandLine.Cardinality;
import com.netflix.tools.cli.CommandLine.Completion;
import com.netflix.tools.cli.CommandLine.ConfigurationException;
import com.netflix.tools.cli.CommandLine.ToolInvocation;
import com.netflix.tools.cli.CommandLine.ToolOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandLineTest {
    private static final ToolOption CHECK = ToolOption.flag("--check", "Check without making changes");
    private static final ToolOption OUTPUT = ToolOption.option("--output", "PATH", "Write output to PATH", "-o");

    @Test
    void parsesAndDescribesACommandLine() {
        var commandLine = CommandLine.builder()
                .description("Process files")
                .options(CHECK, OUTPUT)
                .operand("FILE", "Input file", Cardinality.ZERO_OR_MORE)
                .build();

        var parsed = commandLine.parse("--check", "-o", "output", "input");

        assertTrue(parsed.contains(CHECK));
        assertEquals(List.of("output"), parsed.values(OUTPUT));
        assertEquals(List.of("input"), parsed.operands());
        assertTrue(commandLine.help("probe")
                              .contains("Usage: probe [OPTIONS] [FILE...]"));
        assertEquals(1, commandLine.isSupportedOption("-o"));
    }

    @Test
    void preservesOptionOccurrenceOrder() {
        var commandLine = CommandLine.builder()
                .options(CHECK, OUTPUT)
                .build();

        var parsed = commandLine.parse("--output=first", "--check", "-o", "second");

        assertEquals(List.of(OUTPUT, CHECK, OUTPUT), parsed.optionOccurrences());
        assertEquals(List.of("first", "second"), parsed.values(OUTPUT));
    }

    @Test
    void completesOptionsAndSubcommands() {
        var commandLine = CommandLine.builder()
                .option(OUTPUT)
                .command("check", "Check source",
                        CommandLine.builder()
                                .option(CHECK)
                                .build())
                .build();

        assertEquals(List.of("check"),
                commandLine.complete(List.of("ch")).stream()
                        .map(Completion::value)
                        .toList());
        assertEquals(List.of("--check"),
                commandLine.complete(List.of("check", "--ch")).stream()
                        .map(Completion::value)
                        .toList());
    }

    @Test
    void completesAttachedAndSeparateOptionChoices() {
        var source = ToolOption.builder("--source")
                .alias("-s")
                .argument("SCOPE")
                .choices("none", "signature", "symbol")
                .description("Select source scope")
                .build();
        var commandLine = CommandLine.builder()
                .option(source)
                .build();

        assertEquals(List.of("--source=signature"),
                commandLine.complete(List.of("--source=si")).stream()
                        .map(Completion::value)
                        .toList());
        assertEquals(List.of("signature"),
                commandLine.complete(List.of("--source", "si")).stream()
                        .map(Completion::value)
                        .toList());
        assertEquals(List.of("symbol"),
                commandLine.complete(List.of("-s", "sy")).stream()
                        .map(Completion::value)
                        .toList());
    }

    @Test
    void preparesOptInWorkingDirectoriesAndArgumentFiles(@TempDir Path directory) throws Exception {
        Path arguments = directory.resolve("arguments.txt");
        Files.writeString(arguments, "--check 'source file'");
        var invocation = new ToolInvocation(directory, List.of("-C", "source", "@" + arguments));

        assertEquals(
                new ToolInvocation(directory.resolve("source"), List.of("--check", "source file")),
                CommandLine.builder()
                        .workingDirectory()
                        .argumentFiles()
                        .build()
                        .prepare(invocation));
    }

    @Test
    void loadsOptInAmbientToolOptions(@TempDir Path directory) throws Exception {
        Path options = Files.createDirectories(directory.resolve(".java-tool-options"));
        Files.writeString(options.resolve("probe.args"), "--check\n");
        var diagnostics = new StringWriter();

        var prepared = CommandLine.builder()
                .javaToolOptions()
                .build()
                .prepare("probe", new ToolInvocation(directory, List.of("explicit")), new PrintWriter(diagnostics, true));

        assertEquals(List.of("--check", "explicit"), prepared.arguments());
        assertTrue(diagnostics.toString()
                              .contains("picked up options"));
    }

    @Test
    void ambientToolOptionsSelectTheDeepestMirroredScope(@TempDir Path directory) throws Exception {
        Path options = Files.createDirectories(directory.resolve(".java-tool-options"));
        Files.writeString(options.resolve("probe.args"), "root\n");
        Path main = Files.createDirectories(options.resolve("app/src/main"));
        Path test = Files.createDirectories(options.resolve("app/src/test"));
        Files.writeString(main.resolve("probe.args"), "main\n");
        Files.writeString(test.resolve("probe.args"), "test\n");
        Path source = Files.createDirectories(directory.resolve("app/src/test/java"));

        var prepared = CommandLine.builder()
                .javaToolOptions()
                .build()
                .prepare("probe", new ToolInvocation(source, List.of("explicit")),
                        new PrintWriter(new StringWriter()));

        assertEquals(List.of("test", "explicit"), prepared.arguments());
    }

    @Test
    void ambientToolOptionsSelectTheOnlyContainedScope(@TempDir Path directory) throws Exception {
        Path options = Files.createDirectories(directory.resolve(".java-tool-options/app/src/main"));
        Files.writeString(options.resolve("probe.args"), "main\n");
        Files.createDirectories(directory.resolve("app/src/main"));

        var prepared = CommandLine.builder()
                .javaToolOptions()
                .build()
                .prepare("probe", new ToolInvocation(directory, List.of()),
                        new PrintWriter(new StringWriter()));

        assertEquals(List.of("main"), prepared.arguments());
    }

    @Test
    void ambientToolOptionsRejectInvalidToolNames(@TempDir Path directory) {
        var failure = assertThrows(ConfigurationException.class,
                () -> CommandLine.builder()
                        .javaToolOptions()
                        .build()
                        .prepare("../probe", new ToolInvocation(directory, List.of()),
                                new PrintWriter(new StringWriter())));

        assertEquals("Invalid tool name: ../probe", failure.getMessage());
    }

    @Test
    void ambiguousAmbientToolOptionsSuggestWorkingDirectoryOptionWhenAvailable(@TempDir Path directory) throws Exception {
        ambiguousOptions(directory);
        var diagnostics = new StringWriter();

        var failure = assertThrows(ConfigurationException.class,
                () -> CommandLine.builder()
                        .workingDirectory()
                        .javaToolOptions()
                        .build()
                        .prepare("probe", new ToolInvocation(directory, List.of()),
                                new PrintWriter(diagnostics, true)));

        assertTrue(failure.getMessage().contains("select one with -C:"), failure.getMessage());
        assertTrue(failure.getMessage().contains("-C app/src/main"), failure.getMessage());
        assertTrue(failure.getMessage().contains("-C app/src/test"), failure.getMessage());
    }

    @Test
    void ambiguousAmbientToolOptionsSuggestChangingDirectoryWithoutWorkingDirectoryOption(@TempDir Path directory)
            throws Exception {
        ambiguousOptions(directory);
        var diagnostics = new StringWriter();

        var failure = assertThrows(ConfigurationException.class,
                () -> CommandLine.builder()
                        .javaToolOptions()
                        .build()
                        .prepare("probe", new ToolInvocation(directory, List.of()),
                                new PrintWriter(diagnostics, true)));

        assertTrue(failure.getMessage().contains("run from within one of:"), failure.getMessage());
        assertTrue(failure.getMessage().contains("  app/src/main"), failure.getMessage());
        assertTrue(failure.getMessage().contains("  app/src/test"), failure.getMessage());
        assertFalse(failure.getMessage().contains("-C"), failure.getMessage());
    }

    private static void ambiguousOptions(Path directory) throws Exception {
        Path options = Files.createDirectories(directory.resolve(".java-tool-options"));
        Files.createDirectories(directory.resolve("app/src/main"));
        Files.createDirectories(directory.resolve("app/src/test"));
        Files.createDirectories(options.resolve("app/src/main"));
        Files.createDirectories(options.resolve("app/src/test"));
        Files.writeString(options.resolve("app/src/main/probe.args"), "main\n");
        Files.writeString(options.resolve("app/src/test/probe.args"), "test\n");
    }

    @Test
    void acceptsOnlyShortLongAndHiddenOptionNames() {
        assertEquals(List.of("-v"),
                ToolOption.flag("-v", "Verbose").names());
        assertEquals(List.of("-classpath"),
                ToolOption.option("-classpath", "PATH", "Class path").names());
        assertEquals(List.of("--verbose"),
                ToolOption.flag("--verbose", "Verbose").names());
        assertEquals(List.of("__probe"),
                ToolOption.flag("__probe", "Internal probe").names());

        for (String name : List.of("verbose", "-", "--", "---verbose", "__", "--ver bose",
                "--verbose=true")) {
            assertThrows(IllegalArgumentException.class, () -> ToolOption.flag(name, "Verbose"), name);
        }
        assertThrows(IllegalArgumentException.class,
                () -> ToolOption.builder("--verbose")
                        .alias("__verbose")
                        .build());
    }

    @Test
    void hiddenOptionsAreParsedButNotPresented() {
        var probe = ToolOption.flag("__probe", "Internal probe");
        var commandLine = CommandLine.builder()
                .options(CHECK, probe)
                .build();

        assertTrue(probe.hidden());
        assertTrue(commandLine.parse("__probe")
                              .contains(probe));
        assertEquals(0, commandLine.isSupportedOption("__probe"));
        assertFalse(commandLine.help("probe")
                               .contains("__probe"));
        assertEquals(List.of(), commandLine.complete(List.of("__pr")));
    }

    @Test
    void versionIsAnOptInCommandLineConvention(@TempDir Path directory) throws Exception {
        Path source = Files.createDirectories(directory.resolve("src/versioned.probe"));
        Files.writeString(source.resolve("module-info.java"), "module versioned.probe {}\n");
        Path modules = Files.createDirectories(directory.resolve("modules"));
        int compilation = ToolProvider.getSystemJavaCompiler().run(
                null,
                null,
                null,
                "--module-version",
                "1.2.3",
                "-d",
                modules.toString(),
                source.resolve("module-info.java").toString());
        assertEquals(0, compilation);
        var finder = ModuleFinder.of(modules);
        var configuration = ModuleLayer.boot()
                .configuration()
                .resolve(finder, ModuleFinder.of(), Set.of("versioned.probe"));
        var layer = ModuleLayer.boot()
                .defineModulesWithOneLoader(configuration, ClassLoader.getSystemClassLoader());
        var commandLine = CommandLine.builder()
                .version(layer.findModule("versioned.probe").orElseThrow())
                .build();
        var output = new StringWriter();

        var result = commandLine.runVersion("probe", new PrintWriter(output, true), "--version");

        assertEquals(0, result.orElseThrow());
        assertEquals("probe 1.2.3\n", output.toString());
        assertEquals(0, commandLine.isSupportedOption("--version"));
        assertTrue(commandLine.help("probe")
                              .contains("--version"));
        assertFalse(commandLine.runVersion("probe", new PrintWriter(output), "--check")
                               .isPresent());
        assertFalse(commandLine.runVersion("probe", new PrintWriter(output), "--version", "extra")
                               .isPresent());
        assertFalse(CommandLine.builder()
                               .build()
                               .runVersion("probe", new PrintWriter(output), "--version")
                               .isPresent());
    }

    @Test
    void completionCanDelegateToToolSpecificSemantics() {
        var commandLine = CommandLine.builder()
                .completion()
                .build();
        var output = new StringWriter();
        var directory = Path.of("project");

        var result = commandLine.runCompletion(
                new PrintWriter(output, true),
                new PrintWriter(new StringWriter()),
                request -> {
                    assertEquals(directory, request.invocation().workingDirectory());
                    assertEquals(List.of("tool"),
                            request.invocation().arguments());
                    assertEquals("na", request.current());
                    return List.of(new Completion("name", "A dynamic name"));
                },
                new ToolInvocation(directory, List.of("__complete", "tool", "na")));

        assertEquals(0, result.orElseThrow());
        assertEquals("name\tA dynamic name\n:0\n", output.toString());
    }

    @Test
    void hiddenCompletionCommandIsAnOptInOption() {
        var plain = CommandLine.builder()
                .option(CHECK)
                .build();
        var enabled = CommandLine.builder()
                .option(CHECK)
                .completion()
                .build();
        var output = new StringWriter();
        var errors = new StringWriter();

        assertEquals(-1, plain.isSupportedOption("__complete"));
        assertFalse(plain.runCompletion(new PrintWriter(output), new PrintWriter(errors), "--check")
                         .isPresent());
        assertEquals(0, enabled.isSupportedOption("__complete"));
        assertTrue(enabled.options().stream()
                .anyMatch(option -> option.names().contains("__complete") && option.hidden()));

        var result = enabled.runCompletion(new PrintWriter(output, true), new PrintWriter(errors, true), "__complete", "--ch");

        assertTrue(result.isPresent());
        assertEquals(0, result.orElseThrow());
        assertEquals("--check\tCheck without making changes\n:0\n", output.toString());
        assertEquals("", errors.toString());
        assertFalse(enabled.help("probe")
                           .contains("__complete"));
    }
}
