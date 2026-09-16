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
import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Module descriptors reachable from roots resolved by jig. */
final class ResolvedModules {
    private final Map<String, ModuleDescriptor> descriptors;
    private final Set<String> sourceModules;

    private ResolvedModules(Map<String, ModuleDescriptor> descriptors, Set<String> sourceModules) {
        this.descriptors = Map.copyOf(descriptors);
        this.sourceModules = Set.copyOf(sourceModules);
    }

    static ResolvedModules read(List<String> arguments, Configuration parent) throws IOException {
        var applicationModules = new LinkedHashMap<String, ModuleDescriptor>();
        var paths = ToolArguments.applicationModulePath(arguments);
        ModuleFinder.of(paths.toArray(Path[]::new)).findAll().stream()
                .map(reference -> reference.descriptor())
                .forEach(descriptor -> applicationModules.putIfAbsent(descriptor.name(), descriptor));

        var sourceModules = new LinkedHashSet<String>();
        for (var entry : ToolArguments.moduleSourcePath(arguments).entrySet()) {
            var descriptor = ModuleInfo.descriptor(entry.getValue().resolve("module-info.java"));
            applicationModules.put(descriptor.name(), descriptor);
            sourceModules.add(descriptor.name());
        }

        var observable = new LinkedHashMap<String, ModuleDescriptor>();
        observable.putAll(applicationModules);
        parentModules(parent, observable);
        ModuleFinder.ofSystem().findAll().stream()
                .map(reference -> reference.descriptor())
                .forEach(descriptor -> observable.putIfAbsent(descriptor.name(), descriptor));

        var resolved = new LinkedHashMap<String, ModuleDescriptor>();
        var pending = new ArrayDeque<>(ToolArguments.addedModules(arguments));
        while (!pending.isEmpty()) {
            String name = pending.removeFirst();
            var descriptor = observable.get(name);
            if (descriptor == null || resolved.putIfAbsent(name, descriptor) != null) {
                continue;
            }
            descriptor.requires().stream()
                    .filter(requirement -> !requirement.modifiers()
                            .contains(ModuleDescriptor.Requires.Modifier.STATIC))
                    .map(ModuleDescriptor.Requires::name)
                    .forEach(pending::addLast);
        }
        resolved.keySet().retainAll(applicationModules.keySet());
        return new ResolvedModules(resolved, sourceModules);
    }

    Set<String> names() {
        return descriptors.keySet();
    }

    List<String> rootsRequiredFor(List<String> roots, String requiredModule) {
        var activatedRoots = roots.stream()
                .filter(root -> reachableFrom(root).contains(requiredModule))
                .toList();
        var required = activatedRoots.stream()
                .flatMap(root -> reachableFrom(root).stream())
                .collect(Collectors.toUnmodifiableSet());
        return roots.stream()
                .filter(required::contains)
                .toList();
    }

    Set<ModuleDescriptor> descriptors(List<String> roots) {
        var names = roots.stream()
                .flatMap(root -> reachableFrom(root).stream())
                .collect(Collectors.toUnmodifiableSet());
        return names.stream()
                .map(descriptors::get)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    Set<String> sourceModules() {
        return sourceModules.stream()
                .filter(descriptors::containsKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    Set<String> toolProviderModules() {
        return descriptors.values().stream()
                .filter(descriptor -> descriptor.provides().stream()
                        .map(ModuleDescriptor.Provides::service)
                        .anyMatch(ToolServices.TOOL_SERVICE_NAMES::contains))
                .map(ModuleDescriptor::name)
                .collect(Collectors.toUnmodifiableSet());
    }

    private Set<String> reachableFrom(String root) {
        var reachable = new LinkedHashSet<String>();
        var pending = new ArrayDeque<String>();
        pending.add(root);
        while (!pending.isEmpty()) {
            String name = pending.removeFirst();
            var descriptor = descriptors.get(name);
            if (descriptor == null || !reachable.add(name)) {
                continue;
            }
            descriptor.requires().stream()
                    .filter(requirement -> !requirement.modifiers()
                            .contains(ModuleDescriptor.Requires.Modifier.STATIC))
                    .map(ModuleDescriptor.Requires::name)
                    .forEach(pending::addLast);
        }
        return Set.copyOf(reachable);
    }

    private static void parentModules(Configuration configuration, Map<String, ModuleDescriptor> descriptors) {
        configuration.modules().stream()
                .map(module -> module.reference().descriptor())
                .forEach(descriptor -> descriptors.putIfAbsent(descriptor.name(), descriptor));
        configuration.parents().forEach(parent -> parentModules(parent, descriptors));
    }
}
