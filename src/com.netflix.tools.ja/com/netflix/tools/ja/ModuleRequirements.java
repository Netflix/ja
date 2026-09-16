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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.netflix.tools.ja.RequireRequest.Dependency;

/**
 * Applies dependency additions and version updates to source modules
 * transactionally.
 */
final class ModuleRequirements {
    private record VersionUpdate(Path descriptor, String moduleName, String current,
            String selected) {}

    private final ModuleResolver moduleResolver;
    private final ToolServices tools;

    ModuleRequirements(ModuleResolver moduleResolver, ToolServices tools) {
        this.moduleResolver = moduleResolver;
        this.tools = tools;
    }

    int apply(
            ModuleSourcePath moduleSourcePath,
            List<String> rootModules,
            List<String> resolutionArguments,
            RequireRequest request,
            InputStream in,
            PrintStream out,
            PrintStream err)
            throws IOException {
        List<String> targets = request.updatesVersions() ? rootModules : List.of(rootModules.getFirst());
        Path staging = Files.createTempDirectory("ja-require-");
        var snapshots = new LinkedHashMap<Path, byte[]>();
        try {
            var stagedModules = new LinkedHashMap<String, Path>();
            for (var entry : moduleSourcePath.modules().entrySet()) {
                Path sourceModule = entry.getValue();
                Path stagedModule = Files.createDirectories(staging.resolve(entry.getKey()));
                copy(sourceModule.resolve("module-info.java"), stagedModule.resolve("module-info.java"), snapshots);
                Path hash = sourceModule.resolve("module-info.hash");
                if (Files.exists(hash)) {
                    copy(hash, stagedModule.resolve("module-info.hash"), snapshots);
                } else {
                    snapshots.put(hash, null);
                }
                stagedModules.put(entry.getKey(), stagedModule);
            }

            var stagedDescriptors = targets.stream()
                    .map(target -> stagedModules.get(target).resolve("module-info.java"))
                    .toList();
            var versionUpdates = request.updatesVersions() ? versionUpdates(stagedDescriptors, request,
                    moduleSourcePath.modules().keySet(), err)
                    : List.<VersionUpdate>of();
            var requestedDependencies = new ArrayList<>(request.dependencies());
            for (String value : request.unresolvedTargets()) {
                ApplicationTarget target = ApplicationTarget.resolve(value, tools, in, err);
                requestedDependencies.add(new Dependency(target.moduleName(),
                        target.version().orElseThrow()));
            }
            for (Path stagedDescriptor : stagedDescriptors) {
                var dependencies = request.updatesVersions() ? versionUpdates.stream()
                        .filter(update -> update.descriptor().equals(stagedDescriptor))
                        .filter(update -> !update.current().equals(update.selected()))
                        .map(update -> new Dependency(update.moduleName(), update.selected()))
                        .toList()
                        : requestedDependencies;
                if (!dependencies.isEmpty()) {
                    ModuleInfo.require(stagedDescriptor,
                            new RequireRequest(dependencies, request.staticPhase(), request.transitive()));
                }
                if (!request.runtimeAccess().isEmpty()) {
                    ModuleInfo.authorize(stagedDescriptor, request.runtimeAccess());
                }
            }
            var stagedResolutionArguments = stagedResolutionArguments(resolutionArguments, stagedModules);
            Set<String> automaticModules = update(stagedResolutionArguments, in, err);

            var replacements = new LinkedHashMap<Path, Path>();
            for (String target : targets) {
                Path stagedDescriptor = stagedModules.get(target).resolve("module-info.java");
                Path sourceDescriptor = moduleSourcePath.modules()
                        .get(target)
                        .resolve("module-info.java");
                if (!Arrays.equals(snapshots.get(sourceDescriptor), Files.readAllBytes(stagedDescriptor))) {
                    replacements.put(sourceDescriptor, stagedDescriptor);
                }
            }
            for (var entry : stagedModules.entrySet()) {
                Path stagedHash = entry.getValue().resolve("module-info.hash");
                if (Files.exists(stagedHash)) {
                    Path sourceHash = moduleSourcePath.modules()
                            .get(entry.getKey())
                            .resolve("module-info.hash");
                    if (!Arrays.equals(snapshots.get(sourceHash), Files.readAllBytes(stagedHash))) {
                        replacements.put(sourceHash, stagedHash);
                    }
                }
            }
            for (Path targetFile : replacements.keySet()) {
                ensureUnchanged(targetFile, snapshots);
            }
            for (var replacement : replacements.entrySet()) {
                replace(replacement.getKey(), replacement.getValue());
            }
            versionUpdates.stream()
                    .filter(update -> !update.current().equals(update.selected()))
                    .forEach(update -> out.println(update.moduleName()
                            + " "
                            + update.current()
                            + " -> "
                            + update.selected()));
            AutomaticModules.warn(automaticModules, err);
            return 0;
        } finally {
            deleteTree(staging);
        }
    }

    private Set<String> update(List<String> resolutionArguments, InputStream in, PrintStream err) {
        var arguments = new ArrayList<>(resolutionArguments);
        arguments.add("--update-module-hashes");
        List<String> resolved = moduleResolver.resolve(
                arguments,
                new ResolutionOptions(Set.of("module-path", "module-source-path", "enable-native-access", "enable-final-field-mutation", "add-opens", "add-exports"), true),
                in,
                err);
        return AutomaticModules.find(resolved);
    }

    private List<VersionUpdate> versionUpdates(List<Path> descriptors, RequireRequest request, Set<String> sourceModules,
            PrintStream err)
            throws IOException {
        var requested = Set.copyOf(request.updateModules());
        var found = new HashSet<String>();
        var versionsByModule = new LinkedHashMap<String, List<String>>();
        var updates = new ArrayList<VersionUpdate>();
        for (Path descriptor : descriptors) {
            for (var requirement : ModuleInfo.versionedRequirements(descriptor)) {
                String moduleName = requirement.moduleName();
                if (sourceModules.contains(moduleName) || (!requested.isEmpty() && !requested.contains(moduleName))) {
                    continue;
                }
                found.add(moduleName);
                var versions = versionsByModule.computeIfAbsent(moduleName, module -> ModuleVersions.list(tools, module, err));
                var version = SemanticVersion.latest(requirement.version(), versions, request.updatePolicy());
                updates.add(new VersionUpdate(descriptor, moduleName, requirement.version(), version));
            }
        }
        for (String module : request.updateModules()) {
            if (!found.contains(module)) {
                throw new IllegalArgumentException("Module is not a direct versioned requirement: " + module);
            }
        }
        return List.copyOf(updates);
    }

    private static List<String> stagedResolutionArguments(List<String> arguments, Map<String, Path> stagedModules) {
        var staged = new ArrayList<String>();
        for (int i = 0; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            if (argument.equals("--module-source-path") || argument.equals("-d")) {
                i++;
                continue;
            }
            if (argument.startsWith("--module-source-path=") || argument.equals("--update-module-hashes") || argument.equals("--verify-module-hashes")) {
                continue;
            }
            staged.add(argument);
        }
        for (var entry : stagedModules.entrySet()) {
            staged.add("--module-source-path");
            staged.add(entry.getKey() + "=" + entry.getValue());
        }
        return staged;
    }

    private static void copy(Path source, Path target, Map<Path, byte[]> snapshots) throws IOException {
        byte[] content = Files.readAllBytes(source);
        snapshots.put(source, content);
        Files.write(target, content);
    }

    private static void ensureUnchanged(Path target, Map<Path, byte[]> snapshots) throws IOException {
        byte[] expected = snapshots.get(target);
        byte[] current = Files.exists(target) ? Files.readAllBytes(target) : null;
        if (!Arrays.equals(expected, current)) {
            throw new IllegalStateException("File changed while resolving: " + target);
        }
    }

    private static void replace(Path target, Path staged) throws IOException {
        Path replacement = Files.createTempFile(target.getParent(),
                target.getFileName().toString(), ".tmp");
        try {
            if (Files.exists(target)) {
                Files.copy(target, replacement, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
                Files.write(replacement, Files.readAllBytes(staged));
            } else {
                Files.copy(staged, replacement, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(replacement, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException _) {
                Files.move(replacement, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(replacement);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
