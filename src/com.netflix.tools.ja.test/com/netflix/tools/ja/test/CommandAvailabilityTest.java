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

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.netflix.tools.ja.BuiltinCommand;
import com.netflix.tools.ja.Command;
import com.netflix.tools.ja.Command.Builtin;
import com.netflix.tools.ja.Command.Doc;
import com.netflix.tools.ja.Command.Source;
import com.netflix.tools.ja.CommandAvailability;
import com.netflix.tools.ja.DocRequest.Terminal;
import com.netflix.tools.ja.JaInvocation;
import com.netflix.tools.ja.ToolCatalog;
import com.netflix.tools.ja.ToolDefinition;
import com.netflix.tools.ja.ToolDefinition.Launch;
import com.netflix.tools.ja.ToolServices;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommandAvailabilityTest {
    @Test
    void reportsTheUpstreamToolForAnUnavailableTypedCommand() {
        var commandLine = commandLine(BuiltinCommand.FMT);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> CommandAvailability.require(commandLine, ToolServices.of(), new ToolCatalog(List.of())));

        assertEquals("fmt is unavailable; missing tool: jfmt", failure.getMessage());
    }

    @Test
    void reportsAWorkflowToolDependency() {
        var commandLine = commandLine(BuiltinCommand.GENERATE);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> CommandAvailability.require(commandLine, ToolServices.of(), new ToolCatalog(List.of())));

        assertEquals("generate is unavailable; missing tool: javac", failure.getMessage());
    }

    @Test
    void reportsTheJarWorkflowDependency() {
        var commandLine = commandLine(BuiltinCommand.JAR);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> CommandAvailability.require(commandLine, ToolServices.of(), new ToolCatalog(List.of())));

        assertEquals("jar is unavailable; missing tool: jar", failure.getMessage());
    }

    @Test
    void reportsDocumentationWorkflowDependencies() {
        var commandLine = commandLine(new Doc(new Terminal("java.lang.String")));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> CommandAvailability.require(commandLine, ToolServices.of(), new ToolCatalog(List.of())));

        assertEquals("doc is unavailable; missing tools: jdocserver, jist", failure.getMessage());
    }

    @Test
    void reportsSourceWorkflowDependency() {
        var commandLine = commandLine(new Source("java.lang.String"));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> CommandAvailability.require(commandLine, ToolServices.of(), new ToolCatalog(List.of())));

        assertEquals("source is unavailable; missing tool: jist", failure.getMessage());
    }

    @Test
    void aDifferentToolDoesNotMakeAFixedCommandAvailable() {
        ToolDefinition formatter = new ToolDefinition(
                "alternative-formatter",
                Launch.PROVIDER,
                Optional.empty(),
                Optional.of("com.example.formatter"),
                "alternative-formatter",
                Optional.of("1"),
                Set.of("module-source-path"),
                List.of());

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> CommandAvailability.require(commandLine(BuiltinCommand.FMT), ToolServices.of(), new ToolCatalog(List.of(formatter))));

        assertEquals("fmt is unavailable; missing tool: jfmt", failure.getMessage());
    }

    private static JaInvocation commandLine(BuiltinCommand command) {
        return commandLine(new Builtin(command));
    }

    private static JaInvocation commandLine(Command command) {
        return new JaInvocation(
                Path.of("").toAbsolutePath(),
                command,
                Optional.empty(),
                List.of(),
                List.of(),
                List.of());
    }
}
