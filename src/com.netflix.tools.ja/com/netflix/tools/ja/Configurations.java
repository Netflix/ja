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

import java.lang.ModuleLayer.Controller;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.lang.module.ResolvedModule;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Canonical module selection, graph traversal, and one-loader layer
 * construction.
 */
final class Configurations {
    static final class Resolution {
        private final Configuration parent;
        private final Configuration configuration;
        private final Set<String> pathModules;
        private final Set<String> beforeModules;

        private Resolution(Configuration parent, Configuration configuration, Set<String> pathModules,
                           Set<String> beforeModules) {
            this.parent = parent;
            this.configuration = configuration;
            this.pathModules = Set.copyOf(pathModules);
            this.beforeModules = Set.copyOf(beforeModules);
        }

        Configuration configuration() {
            return configuration;
        }

        Optional<ResolvedModule> findModule(String name) {
            if (beforeModules.contains(name)) {
                return configuration.findModule(name);
            }
            return parent.findModule(name).or(() -> configuration.findModule(name));
        }

        boolean isPathModule(String name) {
            return pathModules.contains(name);
        }

        List<ResolvedModule> reachableModules(Set<String> roots) {
            return reachableModules(roots, _ -> true, _ -> true);
        }

        List<ResolvedModule> reachableModules(Set<String> roots, Predicate<ResolvedModule> include, Predicate<ResolvedModule> followRequires) {
            var reachable = new LinkedHashMap<String, ResolvedModule>();
            var pending = new ArrayDeque<>(roots);
            while (!pending.isEmpty()) {
                var name = pending.removeFirst();
                if (reachable.containsKey(name)) {
                    continue;
                }
                var module = findModule(name).orElse(null);
                if (module == null || !include.test(module)) {
                    continue;
                }
                reachable.put(name, module);
                if (followRequires.test(module)) {
                    module.reads().stream()
                            .map(ResolvedModule::name)
                            .forEach(pending::addLast);
                }
            }
            return reachable.values().stream()
                    .sorted(Comparator.comparing(ResolvedModule::name))
                    .toList();
        }

        List<ResolvedModule> reachablePathModules(Set<String> roots) {
            return reachableModules(roots).stream()
                    .filter(module -> isPathModule(module.name()))
                    .toList();
        }

        Set<String> definedModules() {
            return configuration.modules().stream()
                    .map(ResolvedModule::name)
                    .collect(Collectors.toUnmodifiableSet());
        }

        Controller defineLayer(ModuleLayer parent) {
            return ModuleLayer.defineModulesWithOneLoader(configuration, List.of(parent), ClassLoader.getSystemClassLoader());
        }
    }

    private Configurations() {}

    static Resolution resolve(Configuration parent, List<String> arguments) {
        return resolve(parent, arguments, Set.of());
    }

    /**
     * Resolves selected path modules before the parent and all remaining path
     * modules after it.
     */
    static Resolution resolve(Configuration parent, List<String> arguments, Set<String> beforeModules) {
        var roots = ToolArguments.addedModules(arguments);
        if (roots.isEmpty()) {
            return new Resolution(parent, parent, Set.of(), Set.of());
        }
        var paths = ToolArguments.applicationModulePath(arguments);
        var pathFinder = ModuleFinder.of(paths.toArray(Path[]::new));
        var pathModules = pathFinder.findAll().stream()
                .map(reference -> reference.descriptor().name())
                .collect(Collectors.toUnmodifiableSet());
        var configuration = Configuration.resolve(selectedModules(pathFinder, beforeModules),
                List.of(parent), pathFinder, roots);
        return new Resolution(parent, configuration, pathModules, beforeModules);
    }

    /** Resolves every module on the path before the parent configuration. */
    static Resolution resolveBeforeParent(Configuration parent, List<Path> modulePath, Set<String> roots) {
        var finder = ModuleFinder.of(modulePath.toArray(Path[]::new));
        return resolve(parent, finder, ModuleFinder.of(), roots);
    }

    /**
     * Resolves and service-binds every module on the path before the parent
     * configuration.
     */
    static Resolution resolveAndBindBeforeParent(Configuration parent, List<Path> modulePath, Set<String> roots) {
        var finder = ModuleFinder.of(modulePath.toArray(Path[]::new));
        var configuration = Configuration.resolveAndBind(finder, List.of(parent), ModuleFinder.of(), roots);
        var pathModules = moduleNames(finder);
        return new Resolution(parent, configuration, pathModules, pathModules);
    }

    /** Resolves explicit before-parent and after-parent finders. */
    static Resolution resolve(Configuration parent, ModuleFinder before, ModuleFinder after,
            Set<String> roots) {
        var configuration = Configuration.resolve(before, List.of(parent), after, roots);
        var beforeModules = moduleNames(before);
        var pathModules = new LinkedHashSet<>(beforeModules);
        pathModules.addAll(moduleNames(after));
        return new Resolution(parent, configuration, pathModules, beforeModules);
    }

    private static Set<String> moduleNames(ModuleFinder finder) {
        return finder.findAll().stream()
                .map(reference -> reference.descriptor().name())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static ModuleFinder selectedModules(ModuleFinder finder, Set<String> selected) {
        return new ModuleFinder() {
            @Override
            public Optional<ModuleReference> find(String name) {
                return selected.contains(name) ? finder.find(name) : Optional.empty();
            }

            @Override
            public Set<ModuleReference> findAll() {
                return finder.findAll().stream()
                        .filter(reference -> selected.contains(reference.descriptor()
                                .name()))
                        .collect(Collectors.toUnmodifiableSet());
            }
        };
    }
}
