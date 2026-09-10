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
import java.io.InputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Prepares an install request and delegates installation. */
final class InstallCommand {
    private final ToolServices tools;
    private final List<ToolDefinition> definitions;
    private final Installer installer;

    InstallCommand(ToolServices tools, List<ToolDefinition> definitions, Installer installer) {
        this.tools = tools;
        this.definitions = List.copyOf(definitions);
        this.installer = installer;
    }

    int run(
            JaInvocation commandLine,
            InstallRequest request,
            ResolvedToolArguments resolved,
            List<String> compileArguments,
            Optional<ApplicationTarget> target,
            InputStream in,
            PrintStream out,
            PrintStream err)
            throws IOException {
        var installTarget = target.orElseGet(() -> sourceTarget(commandLine, resolved.arguments()));
        try (var filtered = FilteredModulePath.prepare(commandLine.moduleSourcePath()
                .map(ModuleSourcePath::modules)
                .orElseGet(Map::of),
                compileArguments, resolved.arguments(), definitions)) {
            var actualInstaller = installer == null ? new Installer(tools, InstallationDirectories.defaults(),
                    System.getProperty("os.name").startsWith("Windows"))
                    : installer;
            return actualInstaller.install(installTarget, request, filtered.arguments(), in, out,
                    err);
        }
    }

    private static ApplicationTarget sourceTarget(JaInvocation commandLine, List<String> arguments) {
        var launchTarget = optionValue(arguments, "--module");
        var separator = launchTarget == null ? -1 : launchTarget.indexOf('/');
        if (separator <= 0) {
            throw new IllegalArgumentException("Source application launch arguments have no module target");
        }
        var module = launchTarget.substring(0, separator);
        var version = optionValue(commandLine.resolutionArguments(), "--module-version");
        if (version == null) {
            version = ToolArguments.moduleVersion(module, arguments).orElse(null);
        }
        return new ApplicationTarget(module, Optional.ofNullable(version));
    }

    private static String optionValue(List<String> arguments, String option) {
        for (int i = 0; i < arguments.size(); i++) {
            var argument = arguments.get(i);
            if (argument.equals(option) && i + 1 < arguments.size()) {
                return arguments.get(i + 1);
            }
            if (argument.startsWith(option + "=")) {
                return argument.substring(option.length() + 1);
            }
        }
        return null;
    }
}
