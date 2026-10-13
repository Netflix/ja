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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Describes discovered source modules, their enclosing module, and their
 * standard source-path arguments.
 */
public record ModuleSourcePath(List<Entry> entries, Map<String, Path> modules, Optional<String> enclosingModule) {

    public ModuleSourcePath {
        entries = List.copyOf(entries);
        modules = Collections.unmodifiableMap(new LinkedHashMap<>(modules));
    }

    public sealed interface Entry permits Directory, ModuleMapping {
        String argument();
    }

    public record Directory(Path path) implements Entry {
        public Directory {
            path = path.toAbsolutePath().normalize();
        }

        @Override
        public String argument() {
            return path.toString();
        }
    }

    public record ModuleMapping(String moduleName, Path path) implements Entry {
        public ModuleMapping {
            if (moduleName.isBlank()) {
                throw new IllegalArgumentException("Module name must not be empty");
            }
            path = path.toAbsolutePath().normalize();
        }

        @Override
        public String argument() {
            return moduleName + "=" + path;
        }
    }

    static Path moduleDirectory(Path start, String moduleName) throws IOException {
        Path directory = start.toAbsolutePath().normalize();
        if (isNamedSourcePath(directory) && isInitializationSourcePath(directory, true)) {
            return directory.resolve(moduleName);
        }

        Path child = directory.resolve("src");
        if (isInitializationSourcePath(child, true)) {
            return child.resolve(moduleName);
        }

        for (Path parent = directory.getParent();
             parent != null;
             parent = parent.getParent()) {
            if (isNamedSourcePath(parent) && isInitializationSourcePath(parent, false)) {
                return parent.resolve(moduleName);
            }
        }
        return directory;
    }

    public static Optional<ModuleSourcePath> discover(Path start) throws IOException {
        return discover(start, true);
    }

    public static Optional<ModuleSourcePath> discover(Path start, boolean parseDescriptors) throws IOException {
        Path directory = start.toAbsolutePath().normalize();
        if (!Files.isDirectory(directory)) {
            directory = directory.getParent();
        }

        for (Path current = directory;
             current != null;
             current = current.getParent()) {
            Path descriptor = current.resolve("module-info.java");
            if (Files.isRegularFile(descriptor)) {
                Path moduleDirectory = current.getFileName() != null && current.getFileName()
                                .toString()
                                .equals("classes")
                        ? current.getParent()
                        : current;
                return Optional.of(module(current, moduleDirectory, descriptor, parseDescriptors));
            }

            if (current.equals(directory)) {
                Path classes = current.resolve("classes");
                Path classesDescriptor = classes.resolve("module-info.java");
                if (Files.isRegularFile(classesDescriptor)) {
                    return Optional.of(module(classes, current, classesDescriptor, parseDescriptors));
                }

                Optional<ModuleSourcePath> sourcePath = sourcePathAt(current, parseDescriptors);
                if (sourcePath.isPresent()) {
                    return sourcePath;
                }
            }

            if (isNamedSourcePath(current)) {
                Optional<ModuleSourcePath> sourcePath = sourcePath(current, parseDescriptors);
                if (sourcePath.isPresent()) {
                    return sourcePath;
                }
            }
        }
        return Optional.empty();
    }

    public List<String> moduleNames() {
        return List.copyOf(modules.keySet());
    }

    public List<String> defaultRoots() {
        return enclosingModule.map(List::of).orElseGet(this::moduleNames);
    }

    public List<String> arguments() {
        return entries.stream()
                .map(Entry::argument)
                .toList();
    }

    private static boolean isJmodLayoutSource(Path sourceDirectory) {
        return sourceDirectory.getFileName() != null && sourceDirectory.getFileName()
                .toString()
                .equals("classes")
                && Files.isRegularFile(sourceDirectory.resolve("module-info.java"));
    }

    private static ModuleSourcePath module(Path moduleSource, Path moduleDirectory, Path descriptor,
            boolean parseDescriptors)
            throws IOException {
        Path parent = moduleDirectory == null ? null : moduleDirectory.getParent();
        if (parent != null && isNamedSourcePath(parent)) {
            return sourcePath(parent.getParent(),
                    moduleDirectory.getFileName().toString(), parseDescriptors);
        }
        String moduleName = ModuleInfo.name(descriptor);
        Path normalizedSource = moduleSource.toAbsolutePath().normalize();
        return new ModuleSourcePath(List.of(new ModuleMapping(moduleName, normalizedSource)),
                Map.of(moduleName, normalizedSource), Optional.of(moduleName));
    }

    private static Optional<ModuleSourcePath> sourcePathAt(Path directory, boolean parseDescriptors) throws IOException {
        return sourcePath(directory.resolve("src"), parseDescriptors);
    }

    private static Optional<ModuleSourcePath> sourcePath(Path sourcePath, boolean parseDescriptors) throws IOException {
        if (!Files.isDirectory(sourcePath)) {
            return Optional.empty();
        }
        Map<String, Path> modules = modules(sourcePath, parseDescriptors);
        return modules.isEmpty() ? Optional.empty() : Optional.of(sourcePath(sourcePath, modules, Optional.empty()));
    }

    private static ModuleSourcePath sourcePath(Path root, String enclosingModule, boolean parseDescriptors) throws IOException {
        if (root == null) {
            throw new IllegalArgumentException("Module " + enclosingModule + " has no module source path root");
        }
        Path sourcePath = root.resolve("src");
        Map<String, Path> modules = modules(sourcePath, parseDescriptors);
        if (!modules.containsKey(enclosingModule)) {
            throw new IllegalArgumentException("Module " + enclosingModule + " is not present on " + sourcePath);
        }
        return sourcePath(sourcePath, modules, Optional.of(enclosingModule));
    }

    private static ModuleSourcePath sourcePath(Path sourcePath, Map<String, Path> modules, Optional<String> enclosingModule) {
        List<Entry> entries;
        if (modules.values().stream()
                .noneMatch(ModuleSourcePath::isJmodLayoutSource)) {
            entries = List.of(new Directory(sourcePath));
        } else {
            entries = modules.entrySet().stream()
                    .map(entry -> (Entry) new ModuleMapping(entry.getKey(), entry.getValue()))
                    .toList();
        }
        return new ModuleSourcePath(entries, modules, enclosingModule);
    }

    private static boolean isNamedSourcePath(Path directory) {
        return directory.getFileName() != null && directory.getFileName()
                .toString()
                .equals("src");
    }

    private static boolean isInitializationSourcePath(Path sourcePath, boolean allowEmpty) throws IOException {
        if (!Files.isDirectory(sourcePath)) {
            return false;
        }
        if (!modules(sourcePath, true).isEmpty()) {
            return true;
        }
        if (!allowEmpty) {
            return false;
        }
        try (var children = Files.list(sourcePath)) {
            return children.noneMatch(Files::isDirectory);
        }
    }

    private static Map<String, Path> modules(Path sourcePath, boolean parseDescriptors) throws IOException {
        var modules = new TreeMap<String, Path>();
        try (var children = Files.list(sourcePath)) {
            for (Path child : children.filter(Files::isDirectory).toList()) {
                Path moduleSource = moduleSource(child);
                Path descriptor = moduleSource.resolve("module-info.java");
                if (!Files.isRegularFile(descriptor)) {
                    continue;
                }
                String directoryName = child.getFileName().toString();
                if (parseDescriptors) {
                    String declaredName = ModuleInfo.name(descriptor);
                    if (!directoryName.equals(declaredName)) {
                        throw new IllegalArgumentException(descriptor + " declares " + declaredName + " but its module source path directory is " + directoryName);
                    }
                }
                modules.put(directoryName, moduleSource.toAbsolutePath()
                        .normalize());
            }
        }
        return modules;
    }

    private static Path moduleSource(Path moduleDirectory) {
        if (Files.isRegularFile(moduleDirectory.resolve("module-info.java"))) {
            return moduleDirectory;
        }
        Path classes = moduleDirectory.resolve("classes");
        return Files.isRegularFile(classes.resolve("module-info.java")) ? classes : moduleDirectory;
    }
}
