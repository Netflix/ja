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
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.spi.ToolProvider;
import javax.tools.OptionChecker;

import com.netflix.tools.cli.CommandLine;
import com.netflix.tools.cli.CommandLine.CompletionRequest;
import com.netflix.tools.cli.CommandLine.ToolInvocation;
import com.netflix.tools.cli.CommandLine.ToolOption;
import com.netflix.tools.ja.ToolCatalog;
import com.netflix.tools.ja.ToolRuntime;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRuntimeTest {
    @Test
    void recognizesTheCompilerFromItsJavaCompilerContract() {
        var tools = ToolRuntime.load(ModuleLayer.boot());

        var options = tools.resolutionOptions("javac", Set.of());
        assertTrue(options.containsAll(Set.of("module-path", "processor-module-path", "module-source-path", "module=list",
                "class-path", "source-path", "processor-path", "source", "target", "encoding", "release", "d")), options.toString());
    }

    @Test
    void addsCompilerToolsToACatalogWithoutMetadata() {
        var tools = ToolRuntime.load(ModuleLayer.boot());

        var definition = new ToolCatalog(List.of()).withDiscovered(tools).definition("javac");

        assertEquals("javac", definition.provider());
        assertEquals(Optional.of("jdk.compiler"), definition.module());
    }

    @Test
    void declaredOptionsSupplementDiscoveryAndPreserveTheDeclaredModuleForm() {
        var tools = ToolRuntime.of(new CheckingTool());

        assertEquals(Set.of("module-path", "module=list", "enable-preview"),
                tools.resolutionOptions("probe", Set.of("module-path", "module=list", "verbose")));
        assertEquals(Set.of("module=single", "enable-preview"),
                tools.resolutionOptions("probe", Set.of("module=single")));
    }

    @Test
    void infersResolutionOptionsWhenNoneAreDeclared() {
        var tools = ToolRuntime.of(new CheckingTool());

        assertEquals(Set.of("module", "enable-preview"), tools.resolutionOptions("probe", Set.of()));
    }

    @Test
    void bundledToolMetadataDoesNotHideTheirClasspathOptions() {
        var tools = ToolRuntime.load(ModuleLayer.boot());
        for (String name : List.of("jfmt", "jist", "jdocserver")) {
            var options = tools.resolutionOptions(name, Set.of("module-path", "module-source-path", "module=list"));
            assertTrue(options.containsAll(Set.of("class-path", "source-path", "module=list")), name + ": " + options);
        }
    }

    @Test
    void bundledInsightToolSupportsTheModuleCompilationContext() {
        var tools = ToolRuntime.load(ModuleLayer.boot());
        var checker = tools.optionChecker("jist", _ -> -1);
        for (var option : List.of("--module-path", "--upgrade-module-path", "--patch-module", "--module-source-path",
                "--module", "--add-modules", "--add-reads", "--add-exports", "--limit-modules", "--system")) {
            assertEquals(1, checker.isSupportedOption(option), option);
        }
        var options = tools.resolutionOptions("jist", Set.of("module=list"));
        assertTrue(options.containsAll(Set.of("module-path", "upgrade-module-path", "patch-module", "module-source-path",
                "module=list", "add-modules", "add-reads", "add-exports", "system")), options.toString());
    }

    @Test
    void completesThroughTheStandardToolProtocol() {
        var tools = ToolRuntime.of(new CompletingProbe(), new CheckingTool());

        assertEquals(List.of("--help"),
                tools.complete("completing", new CompletionRequest(ToolInvocation.of(), "--h")).stream()
                        .map(completion -> completion.value())
                        .toList());
        assertEquals(List.of("--help"),
                tools.complete(
                                "completing",
                                new CompletionRequest(new ToolInvocation(Path.of("project"), List.of()),
                                        "--h"))
                        .stream()
                        .map(completion -> completion.value())
                        .toList());
        assertEquals(List.of(), tools.complete("probe", new CompletionRequest(ToolInvocation.of(), "--h")));
    }

    @Test
    void discoversAndPrintsToolSkills() {
        var tools = ToolRuntime.of(new SkilledProbe(), new CheckingTool());
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();

        int result = tools.runSkill("skilled", new PrintStream(output), new PrintStream(errors));

        assertEquals(0, result);
        assertEquals(Set.of("skilled"), tools.skillNames());
        assertEquals(SkilledProbe.SKILL, output.toString(StandardCharsets.UTF_8));
        assertEquals("", errors.toString(StandardCharsets.UTF_8));
    }

    @Test
    void usesThePlatformOptionCheckerBeforeManifestFallback() {
        var tools = ToolRuntime.load(ModuleLayer.boot());
        var checker = tools.optionChecker("javac", option -> option.equals("--module-path") ? 1 : -1);

        assertEquals(1, checker.isSupportedOption("--release"));
        assertEquals(1, checker.isSupportedOption("--module-path"));
        assertEquals(1, checker.isSupportedOption("--class-path"));
        assertEquals(1, checker.isSupportedOption("--source-path"));
        assertEquals(1, checker.isSupportedOption("--processor-path"));
        assertEquals(-1, checker.isSupportedOption("--not-an-option"));
    }

    private record CompletingProbe() implements ToolProvider, OptionChecker {
        private static final CommandLine COMMAND_LINE = CommandLine.builder()
                .option(ToolOption.flag("--help", "Print help", "-h"))
                .completion()
                .workingDirectory()
                .build();

        @Override
        public String name() {
            return "completing";
        }

        @Override
        public int run(PrintWriter out, PrintWriter err, String... arguments) {
            return COMMAND_LINE.runCompletion(out, err, arguments).orElse(0);
        }

        @Override
        public int isSupportedOption(String option) {
            return COMMAND_LINE.isSupportedOption(option);
        }
    }

    private record SkilledProbe() implements ToolProvider, OptionChecker {
        private static final String SKILL = "---\nname: skilled\ndescription: Exercise tool skills\n---\n";
        private static final CommandLine COMMAND_LINE = CommandLine.builder()
                .skill()
                .build();

        @Override
        public String name() {
            return "skilled";
        }

        @Override
        public int run(PrintWriter out, PrintWriter err, String... arguments) {
            return COMMAND_LINE.runSkill(out, SKILL, arguments).orElse(2);
        }

        @Override
        public int isSupportedOption(String option) {
            return COMMAND_LINE.isSupportedOption(option);
        }
    }

    private record CheckingTool() implements ToolProvider, OptionChecker {
        @Override
        public String name() {
            return "probe";
        }

        @Override
        public int run(PrintWriter out, PrintWriter err, String... arguments) {
            return 0;
        }

        @Override
        public int isSupportedOption(String option) {
            return switch (option) {
                case "--module" -> 1;
                case "--enable-preview", "--verbose" -> 0;
                default -> -1;
            };
        }
    }
}
