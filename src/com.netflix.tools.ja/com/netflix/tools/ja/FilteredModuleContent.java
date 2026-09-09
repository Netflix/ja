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
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Filters compile-only tool content from a complete exploded module.
 *
 * <p>Closing the instance removes any temporary filtered copy; a module with
 * no excluded content is returned directly.
 */
final class FilteredModuleContent implements AutoCloseable {
    private record Suffixes(String classSuffix, String packageSuffix) {
        boolean excludes(Path path) {
            var name = path.toString().replace('\\', '/');
            var separator = name.lastIndexOf('/');
            var packagePath = separator < 0 ? "" : name.substring(0, separator);
            if (packageSuffix != null && (packagePath.equals(packageSuffix) || packagePath.endsWith("/" + packageSuffix))) {
                return true;
            }
            if (classSuffix == null || !name.endsWith(".class")) {
                return false;
            }

            var className = name.substring(separator + 1, name.length() - ".class".length());
            var nested = className.indexOf('$');
            var topLevelClass = nested < 0 ? className : className.substring(0, nested);
            return topLevelClass.endsWith(classSuffix);
        }
    }

    private final Path path;
    private final Path temporaryDirectory;

    private FilteredModuleContent(Path path, Path temporaryDirectory) {
        this.path = path;
        this.temporaryDirectory = temporaryDirectory;
    }

    static FilteredModuleContent prepare(String moduleName, List<String> compileArguments, List<String> runtimeArguments,
            List<ToolDefinition> tools)
            throws IOException {
        var source = ToolArguments.moduleLocation(moduleName, runtimeArguments).orElseThrow(() -> new IllegalArgumentException("Resolved arguments do not contain complete module " + moduleName));
        if (!Files.isDirectory(source)) {
            throw new IllegalArgumentException("Complete module is not an exploded directory: " + source);
        }

        var excluded = exclusions(compileArguments, runtimeArguments, tools);
        Path publicationMetadata = source.resolve(moduleName + ".pom");
        if (!Files.isRegularFile(publicationMetadata)
                && (excluded.isEmpty() || !containsExcludedContent(source, excluded))) {
            return new FilteredModuleContent(source, null);
        }

        var staging = Files.createTempDirectory("ja-filter-");
        var destination = staging.resolve(moduleName);
        try {
            copy(source, destination);
            Files.deleteIfExists(destination.resolve(moduleName + ".pom"));
            removeExcludedContent(destination, excluded);
        } catch (IOException | RuntimeException | Error failure) {
            deleteTree(staging);
            throw failure;
        }
        return new FilteredModuleContent(destination, staging);
    }

    private static void copy(Path source, Path destination) throws IOException {
        try (var paths = Files.walk(source)) {
            for (var input : paths.filter(Files::isRegularFile).toList()) {
                var output = destination.resolve(source.relativize(input)
                        .toString());
                Files.createDirectories(output.getParent());
                Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    private static void removeExcludedContent(Path module, List<Suffixes> excluded) throws IOException {
        try (var paths = Files.walk(module)) {
            for (var path : paths.filter(Files::isRegularFile).toList()) {
                if (excludes(module.relativize(path), excluded)) {
                    Files.delete(path);
                }
            }
        }
    }

    private static List<Suffixes> exclusions(List<String> compileArguments, List<String> runtimeArguments, List<ToolDefinition> tools) {
        var compileModules = modules(compileArguments);
        var runtimeModules = modules(runtimeArguments);
        var excluded = new ArrayList<Suffixes>();
        for (var tool : tools) {
            var activation = tool.activation().orElse(null);
            if (activation == null
                    || !compileModules.contains(activation)
                    || runtimeModules.contains(activation)
                    || tool.classSuffix().isEmpty() && tool.packageSuffix().isEmpty()) {
                continue;
            }
            excluded.add(
                    new Suffixes(tool.classSuffix().orElse(null),
                            tool.packageSuffix().orElse(null)));
        }
        return List.copyOf(excluded);
    }

    private static Set<String> modules(List<String> arguments) {
        var paths = ToolArguments.applicationModulePath(arguments);
        if (paths.isEmpty()) {
            return Set.of();
        }
        var modules = new TreeSet<String>();
        ModuleFinder.of(paths.toArray(Path[]::new))
                .findAll()
                .forEach(reference -> modules.add(reference.descriptor()
                        .name()));
        return Set.copyOf(modules);
    }

    private static boolean containsExcludedContent(Path source, List<Suffixes> excluded) throws IOException {
        try (var paths = Files.walk(source)) {
            return paths.filter(path -> !Files.isDirectory(path))
                        .map(source::relativize)
                        .anyMatch(path -> excludes(path, excluded));
        }
    }

    private static boolean excludes(Path path, List<Suffixes> excluded) {
        return excluded.stream().anyMatch(suffixes -> suffixes.excludes(path));
    }

    Path path() {
        return path;
    }

    @Override
    public void close() throws IOException {
        if (temporaryDirectory != null) {
            deleteTree(temporaryDirectory);
        }
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
