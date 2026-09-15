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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Runs source annotation processors and publishes their generated files into
 * the owning modules.
 */
final class SourceGenerator {
    private final ToolServices tools;
    private final ModuleResolver moduleResolver;

    SourceGenerator(ToolServices tools) {
        this.tools = tools;
        this.moduleResolver = new ModuleResolver(tools);
    }

    int generate(List<String> resolutionArguments, List<String> initialCompileArguments, Map<String, Path> moduleSources,
                 List<String> javacArguments, PrintStream out, PrintStream err)
            throws IOException {
        if (!tools.contains("javac")) {
            throw new IllegalArgumentException("Tool javac is not installed");
        }
        var modules = sourceModules(initialCompileArguments, moduleSources);
        var initialRoots = compilationModules(initialCompileArguments);
        var reusable = initialRoots.size() == 1 ? initialRoots.getFirst() : null;
        var additionalArguments = withoutGenerationControl(javacArguments);

        var requests = Files.createTempDirectory("ja-generation-");
        try {
            for (int i = 0; i < modules.size(); i++) {
                var module = modules.get(i);
                var request = requests.resolve(Integer.toString(i));
                var resolved = module.equals(reusable) ? initialCompileArguments : resolve(ResolutionArguments.withRoots(resolutionArguments, List.of(module)), request, err);
                if (!hasProcessorModulePath(resolved)) {
                    continue;
                }

                var arguments = new ArrayList<>(observableSourceArguments(resolved, moduleSources));
                var classes = request.resolve("classes");
                var generated = request.resolve("generated");
                var generatedModule = Files.createDirectories(generated.resolve(module));
                arguments = new ArrayList<>(withGeneratedSource(arguments, module, generatedModule));
                arguments.add("-d");
                arguments.add(classes.toString());
                arguments.add("-s");
                arguments.add(generated.toString());
                arguments.addAll(additionalArguments);
                arguments.add("-proc:only");
                arguments.add("-implicit:none");
                int result = tools.run("javac", InputStream.nullInputStream(), out, err,
                        arguments.toArray(String[]::new));
                if (result != 0) {
                    return result;
                }
            }
            for (int i = 0; i < modules.size(); i++) {
                var module = modules.get(i);
                publish(requests.resolve(Integer.toString(i))
                                .resolve("generated")
                                .resolve(module),
                        moduleSources.get(module));
            }
            return 0;
        } finally {
            deleteTree(requests);
        }
    }

    private List<String> resolve(List<String> resolutionArguments, Path request, PrintStream err) throws IOException {
        Files.createDirectories(request);
        return moduleResolver.resolve(resolutionArguments, ResolutionOptions.JAVAC, InputStream.nullInputStream(), err);
    }

    private static boolean hasProcessorModulePath(List<String> arguments) {
        return arguments.stream().anyMatch(argument -> argument.equals("--processor-module-path") || argument.startsWith("--processor-module-path="));
    }

    private static List<String> withoutGenerationControl(List<String> arguments) {
        var result = new ArrayList<String>();
        for (int i = 0; i < arguments.size(); i++) {
            var argument = arguments.get(i);
            if (argument.equals("-d") || argument.equals("-s")) {
                i++;
            } else if (!argument.startsWith("-d=")
                    && !argument.startsWith("-s=")
                    && !argument.startsWith("-proc:")
                    && !argument.startsWith("-implicit:")) {
                result.add(argument);
            }
        }
        return List.copyOf(result);
    }

    private static List<String> withGeneratedSource(List<String> arguments, String module, Path generated) {
        var result = new ArrayList<String>();
        var prefix = module + "=";
        var separator = File.pathSeparator;
        for (int i = 0; i < arguments.size(); i++) {
            var argument = arguments.get(i);
            if (argument.equals("--module-source-path") && i + 1 < arguments.size()) {
                result.add(argument);
                var value = arguments.get(++i);
                result.add(value.startsWith(prefix) ? value + separator + generated : value);
            } else if (argument.startsWith("--module-source-path=")) {
                var value = argument.substring("--module-source-path=".length());
                result.add(value.startsWith(prefix) ? "--module-source-path=" + value + separator + generated : argument);
            } else {
                result.add(argument);
            }
        }
        return List.copyOf(result);
    }

    private static void publish(Path generated, Path moduleSource) throws IOException {
        if (!Files.isDirectory(generated)) {
            return;
        }
        try (var paths = Files.walk(generated)) {
            for (var input : paths.filter(Files::isRegularFile).toList()) {
                var output = moduleSource.resolve(generated.relativize(input));
                Files.createDirectories(output.getParent());
                Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    private static List<String> sourceModules(List<String> arguments, Map<String, Path> moduleSources) {
        var modules = new LinkedHashSet<String>();
        for (int i = 0; i < arguments.size(); i++) {
            var argument = arguments.get(i);
            String value = null;
            if (argument.equals("--module-source-path") && i + 1 < arguments.size()) {
                value = arguments.get(++i);
            } else if (argument.startsWith("--module-source-path=")) {
                value = argument.substring("--module-source-path=".length());
            }
            if (value == null) {
                continue;
            }
            int separator = value.indexOf('=');
            if (separator > 0) {
                var module = value.substring(0, separator);
                if (moduleSources.containsKey(module)) {
                    modules.add(module);
                }
            }
        }
        if (modules.isEmpty()) {
            compilationModules(arguments).stream()
                    .filter(moduleSources::containsKey)
                    .forEach(modules::add);
        }
        return List.copyOf(modules);
    }

    private static List<String> compilationModules(List<String> arguments) {
        var modules = new LinkedHashSet<String>();
        for (int i = 0; i < arguments.size(); i++) {
            var argument = arguments.get(i);
            String value = null;
            if (argument.equals("--module") && i + 1 < arguments.size()) {
                value = arguments.get(++i);
            } else if (argument.startsWith("--module=")) {
                value = argument.substring("--module=".length());
            }
            if (value == null) {
                continue;
            }
            for (var module : value.split(",")) {
                if (!module.isBlank()) {
                    modules.add(module.strip());
                }
            }
        }
        return List.copyOf(modules);
    }

    private static List<String> observableSourceArguments(List<String> arguments, Map<String, Path> moduleSources) {
        var observable = new LinkedHashSet<String>();
        boolean hasModuleSpecificPath = false;
        for (int i = 0; i < arguments.size(); i++) {
            var argument = arguments.get(i);
            String value = null;
            if (argument.equals("--module-source-path") && i + 1 < arguments.size()) {
                value = arguments.get(++i);
            } else if (argument.startsWith("--module-source-path=")) {
                value = argument.substring("--module-source-path=".length());
            }
            if (value == null) {
                continue;
            }
            int separator = value.indexOf('=');
            if (separator > 0) {
                hasModuleSpecificPath = true;
                observable.add(value.substring(0, separator));
            }
        }
        if (!hasModuleSpecificPath || observable.containsAll(moduleSources.keySet())) {
            return arguments;
        }
        var result = new ArrayList<>(arguments);
        moduleSources.entrySet().stream()
                .filter(entry -> !observable.contains(entry.getKey()))
                .sorted(Entry.comparingByKey())
                .forEach(entry -> {
                    result.add("--module-source-path");
                    result.add(entry.getKey() + "=" + entry.getValue());
                });
        return List.copyOf(result);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
