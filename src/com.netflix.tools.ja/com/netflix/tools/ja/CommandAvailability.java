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

import java.lang.module.ModuleFinder;
import java.util.ArrayList;
import java.util.List;

import com.netflix.tools.ja.BuiltinCommand.ToolRequirement;
import com.netflix.tools.ja.Command.Category;
import com.netflix.tools.ja.BuiltinCommand.ToolRequirement.Provider;

/**
 * Checks that the tools required to run a command are available in the selected
 * catalog.
 */
public final class CommandAvailability {
    private static final String JAVA_SE = "java.se";

    private CommandAvailability() {}

    public static void require(JaInvocation commandLine, ToolRuntime tools, ToolCatalog catalog) {
        require(commandLine, tools, catalog, ModuleFinder.ofSystem());
    }

    public static void require(JaInvocation commandLine, ToolRuntime tools, ToolCatalog catalog,
                               ModuleFinder systemModules) {
        requireRuntime(commandLine, systemModules);
        var missing = missingTools(commandLine, tools, catalog);
        if (missing.isEmpty()) {
            return;
        }
        throw new IllegalArgumentException(message(commandLine.command().commandName(), missing));
    }

    public static void requireRuntime(JaInvocation commandLine) {
        requireRuntime(commandLine, ModuleFinder.ofSystem());
    }

    public static void requireRuntime(JaInvocation commandLine, ModuleFinder systemModules) {
        BuiltinCommand.from(commandLine.command()).ifPresent(command -> requireRuntime(command, systemModules));
    }

    public static void requireRuntime(BuiltinCommand command, ModuleFinder systemModules) {
        if (!available(command, systemModules)) {
            throw new IllegalArgumentException(moduleDevelopmentMessage(command.commandName()));
        }
    }

    public static boolean available(BuiltinCommand command, ModuleFinder systemModules) {
        return command.category() != Category.MODULE || moduleDevelopmentAvailable(systemModules);
    }

    public static boolean moduleDevelopmentAvailable(ModuleFinder systemModules) {
        return systemModules.find(JAVA_SE).isPresent();
    }

    public static String moduleDevelopmentMessage(String command) {
        return command + " is unavailable; java.se is not present in this runtime";
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
}
