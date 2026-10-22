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

import java.io.IOException;
import java.io.PrintStream;
import java.lang.module.ModuleFinder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.netflix.tools.launcher.ModuleOptions;

/**
 * Discovers native command names and warmup support provided by a resolved
 * module.
 */
final class ToolCommands {
    record Discovery(Set<String> commands, Set<String> warmupCommands) {
        Discovery {
            commands = Set.copyOf(commands);
            warmupCommands = Set.copyOf(warmupCommands);
        }
    }

    private ToolCommands() {}

    static Discovery discover(String moduleName, List<String> launchArguments, PrintStream out, PrintStream err) throws IOException {
        var modulePath = ToolArguments.applicationModulePath(launchArguments);
        var target = ModuleFinder.of(modulePath.toArray(Path[]::new))
                .find(moduleName)
                .or(() -> ModuleLayer.boot()
                        .configuration()
                        .findModule(moduleName)
                        .map(module -> module.reference()))
                .orElseThrow(() -> new IllegalArgumentException("Module not found: " + moduleName));
        boolean providesTools = target.descriptor()
                .provides()
                .stream()
                .anyMatch(provider -> ToolRuntime.TOOL_SERVICE_NAMES.contains(provider.service()));
        if (!providesTools) {
            return new Discovery(Set.of(), Set.of());
        }
        var resolution = Configurations.resolveAndBindBeforeParent(ModuleLayer.boot().configuration(), modulePath,
                Set.of(moduleName));
        ToolRuntime tools;
        if (resolution.requiresFork()) {
            var arguments = new ArrayList<>(ToolArguments.select(launchArguments,
                    ModuleOptions.checker(ResolutionOptions.JAVA.options()),
                    ModuleOptions.checker(ResolutionOptions.COMPLETE_RUNTIME_WITH_ACCESS.options())));
            arguments.add("--add-modules");
            arguments.add(moduleName);
            tools = ToolRuntime.forked(arguments, Set.of(moduleName), JavaProcess::launch, out, err);
        } else {
            var layer = resolution.defineLayer(ModuleLayer.boot()).layer();
            tools = ToolRuntime.load(layer, Set.of(moduleName));
        }

        var commands = new TreeSet<String>();
        var warmupCommands = new TreeSet<String>();
        for (String discovered : tools.names()) {
            String name = requireCommandName(discovered);
            commands.add(name);
            if (tools.warmup(name).isPresent()) {
                warmupCommands.add(name);
            }
        }
        return new Discovery(commands, warmupCommands);
    }

    private static String requireCommandName(String name) {
        if (name == null
                || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]*")
                || name.equals(".")
                || name.equals("..")) {
            throw new IllegalArgumentException("Invalid tool command name: " + name);
        }
        return name;
    }
}
