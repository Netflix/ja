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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.spi.ToolProvider;

import com.netflix.tools.ja.Ja;
import com.netflix.tools.ja.ToolCatalog;
import com.netflix.tools.ja.ToolDefinition;
import com.netflix.tools.ja.ToolDefinition.Launch;
import com.netflix.tools.ja.ToolServices;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JaHelpTest {
    @Test
    void groupsCommandsByUserIntent() {
        var bytes = new ByteArrayOutputStream();
        var tools = ToolServices.of(
                tool("javac"),
                tool("jist"),
                tool("jdocserver"),
                tool("jshell"),
                tool("jdeps"),
                tool("jar"),
                tool("javadoc"),
                tool("jmod"),
                tool("jlink"));
        var catalog = new ToolCatalog(List.of(definition("junit"), definition("jmh"), definition("jfmt")));

        try (var output = new PrintStream(bytes)) {
            Ja.help(output, tools, catalog);
        }

        assertEquals(
                """
                Usage: ja [--verbose] <command> [command-arguments]
                       ja [--verbose] tool [<name> [tool-arguments]]

                Develop:
                  init         Initialize a source module
                  require      Add or update dependencies
                  generate     Update generated source
                  fmt          Format source
                  compile      Compile source modules
                  run          Run a module
                  test         Run tests
                  bench        Run benchmarks

                Explore:
                  list         List observable modules
                  describe     Describe a module
                  doc          Find or browse Java APIs
                  source       Show Java source

                Build and distribute:
                  assemble     Assemble module artifacts
                  jar          Create or update a modular JAR
                  mod          Create a JMOD archive
                  install      Install an application command
                  maven        Export Maven projects or install and deploy modules

                Tools:
                  tool         List or run tools
                  completion   Print completion shims for all completion-aware tools

                Options:
                  --verbose  Enable verbose output
                  -h, --help  Print help
                  -C <DIRECTORY>  Run in the specified directory

                Run 'ja <command> --help' for command-specific help.
                """,
                bytes.toString());
    }

    @Test
    void doesNotReportAProjectActivatedBenchmarkToolAsMissing() throws Exception {
        var bytes = new ByteArrayOutputStream();
        var catalog = ToolCatalog.load(ModuleLayer.boot(), Set.of("com.netflix.tools.ja"));
        try (var output = new PrintStream(bytes)) {
            Ja.help(output, ToolServices.of(), catalog);
        }

        assertFalse(bytes.toString().contains("bench is unavailable"),
                bytes.toString());
    }

    @Test
    void separatesUnavailableCommandsAndExplainsMissingTools() {
        var bytes = new ByteArrayOutputStream();
        try (var output = new PrintStream(bytes)) {
            Ja.help(output, ToolServices.of(), new ToolCatalog(List.of()));
        }

        String help = bytes.toString();
        String available = help.substring(0, help.indexOf("Unavailable commands:"));
        assertTrue(available.contains("  init         Initialize a source module\n"), help);
        assertTrue(available.contains("  compile      Compile source modules\n"), help);
        assertTrue(available.contains("  list         List observable modules\n"), help);
        assertFalse(available.contains("  fmt          Format source\n"), help);
        assertTrue(help.contains("Unavailable commands:\n"), help);
        assertTrue(help.contains("  fmt is unavailable; missing tool: jfmt\n"), help);
        assertTrue(help.contains("  install is unavailable; missing tool: jlink\n"), help);
    }

    private static ToolDefinition definition(String name) {
        return new ToolDefinition(
                name,
                Launch.PROVIDER,
                Optional.empty(),
                Optional.empty(),
                name,
                Optional.of("1"),
                Set.of(),
                List.of());
    }

    private static ToolProvider tool(String name) {
        return new ToolProvider() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public int run(PrintWriter out, PrintWriter err, String... arguments) {
                return 0;
            }
        };
    }
}
