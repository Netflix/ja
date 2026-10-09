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

package com.netflix.tools.ja;

import java.util.ArrayList;
import java.util.List;

import com.netflix.tools.ja.BuiltinCommand.ToolRequirement;
import com.netflix.tools.ja.BuiltinCommand.ToolRequirement.Provider;

/**
 * Checks that the tools required to run a command are available in the selected
 * catalog.
 */
public final class CommandAvailability {
    private CommandAvailability() {}

    public static void require(JaInvocation commandLine, ToolRuntime tools, ToolCatalog catalog) {
        var missing = missingTools(commandLine, tools, catalog);
        if (missing.isEmpty()) {
            return;
        }
        throw new IllegalArgumentException(message(commandName(commandLine.command()), missing));
    }

    public static List<String> missingTools(BuiltinCommand command, ToolRuntime tools, ToolCatalog catalog) {
        return missingTools(command.requiredTools(), tools, catalog);
    }

    private static List<String> missingTools(JaInvocation commandLine, ToolRuntime tools, ToolCatalog catalog) {
        var command = BuiltinCommand.from(commandLine.command()).orElse(null);
        if (command == null) {
            return List.of();
        }
        return missingTools(command.requiredTools(), tools, catalog);
    }

    private static List<String> missingTools(List<ToolRequirement> requirements, ToolRuntime tools, ToolCatalog catalog) {
        var missing = new ArrayList<String>();
        for (ToolRequirement requirement : requirements) {
            switch (requirement) {
                case Provider(var name) -> {
                    if (!tools.contains(name)) {
                        missing.add(name);
                    }
                }
                case BuiltinCommand.ToolRequirement.Tool(var name) -> {
                    if (catalog.find(name).isEmpty()) {
                        missing.add(name);
                    }
                }
            }
        }
        return missing.stream()
                .distinct()
                .sorted()
                .toList();
    }

    static String message(String command, List<String> missingTools) {
        var noun = missingTools.size() == 1 ? "tool" : "tools";
        return command + " is unavailable; missing " + noun + ": " + String.join(", ", missingTools);
    }

    private static String commandName(Command command) {
        if (command instanceof Command.Tool(var name)) {
            return "tool " + name;
        }
        return BuiltinCommand.from(command)
                .orElseThrow()
                .commandName();
    }
}
