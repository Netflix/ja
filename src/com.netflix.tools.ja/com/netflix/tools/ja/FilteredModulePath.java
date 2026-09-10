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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Provides filtered exploded source modules ahead of their complete module path
 * entries.
 */
final class FilteredModulePath implements AutoCloseable {
    private final Map<String, FilteredModuleContent> contents;
    private final List<String> arguments;

    private FilteredModulePath(Map<String, FilteredModuleContent> contents, List<String> arguments) {
        this.contents = Map.copyOf(contents);
        this.arguments = List.copyOf(arguments);
    }

    static FilteredModulePath prepare(Map<String, Path> sourceModules, List<String> compileArguments,
            List<String> runtimeArguments, List<ToolDefinition> definitions)
            throws IOException {
        var modules = sourceModules.keySet().stream()
                .filter(module -> ToolArguments.moduleLocation(module, runtimeArguments).isPresent())
                .sorted()
                .toList();
        if (modules.isEmpty()) {
            return new FilteredModulePath(Map.of(), runtimeArguments);
        }

        var contents = new LinkedHashMap<String, FilteredModuleContent>();
        try {
            for (var module : modules) {
                Path deploymentPom = ModuleMetadata.deploymentPom(sourceModules.get(module));
                contents.put(module, FilteredModuleContent.prepare(
                        module, deploymentPom, compileArguments, runtimeArguments, definitions));
            }
        } catch (IOException | RuntimeException | Error failure) {
            try {
                close(contents);
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }

        var arguments = new ArrayList<>(runtimeArguments);
        prepend(arguments, "--upgrade-module-path",
                contents.values().stream()
                        .map(FilteredModuleContent::path)
                        .toList());
        return new FilteredModulePath(contents, arguments);
    }

    private static void prepend(ArrayList<String> arguments, String preferredOption, List<Path> paths) {
        int optionIndex = -1;
        String option = preferredOption;
        for (int i = 0; i < arguments.size(); i++) {
            var argument = arguments.get(i);
            if (argument.equals(option) || argument.startsWith(option + "=")) {
                optionIndex = i;
            }
        }
        if (optionIndex < 0 && preferredOption.equals("--upgrade-module-path")) {
            prepend(arguments, "--module-path", paths);
            return;
        }

        var separator = System.getProperty("path.separator");
        var value = paths.stream()
                .map(Path::toString)
                .collect(Collectors.joining(separator));
        if (optionIndex < 0) {
            arguments.add(0, value);
            arguments.add(0, option);
            return;
        }

        var argument = arguments.get(optionIndex);
        if (argument.equals(option)) {
            if (optionIndex + 1 >= arguments.size()) {
                throw new IllegalArgumentException(option + " requires an argument");
            }
            arguments.set(optionIndex + 1, value + separator + arguments.get(optionIndex + 1));
        } else {
            arguments.set(optionIndex, option + "=" + value + separator + argument.substring(option.length() + 1));
        }
    }

    List<String> arguments() {
        return arguments;
    }

    Path module(String name) {
        var content = contents.get(name);
        if (content == null) {
            throw new IllegalArgumentException("Filtered module is not present: " + name);
        }
        return content.path();
    }

    @Override
    public void close() throws IOException {
        close(contents);
    }

    private static void close(Map<String, FilteredModuleContent> contents) throws IOException {
        IOException failure = null;
        for (var content : new ArrayList<>(contents.values()).reversed()) {
            try {
                content.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
