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
import java.lang.module.ResolvedModule;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.netflix.tools.ja.ToolDefinition.Launch;

/**
 * Loads tool metadata and selects active tool definitions from the resolved
 * module graph.
 */
public final class ToolCatalog {
    private static final String DIRECTORY = "META-INF/com.netflix.tools/tools/";
    private static final String SUFFIX = ".properties";

    private final Map<String, ToolDefinition> definitions;

    public ToolCatalog(List<ToolDefinition> definitions) {
        var byName = new LinkedHashMap<String, ToolDefinition>();
        definitions.stream()
                .sorted(Comparator.comparing(ToolDefinition::name))
                .forEach(definition -> {
                    var previous = byName.putIfAbsent(definition.name(), definition);
                    if (previous != null) {
                        throw new IllegalArgumentException("Duplicate tool definition: " + definition.name());
                    }
                });
        this.definitions = Map.copyOf(byName);
    }

    public static ToolCatalog load(ModuleLayer layer) throws IOException {
        var moduleNames = layer.configuration().modules().stream()
                .map(ResolvedModule::name)
                .collect(Collectors.toUnmodifiableSet());
        return load(layer, moduleNames);
    }

    public static ToolCatalog load(ModuleLayer layer, Set<String> moduleNames) throws IOException {
        var selectedModules = Set.copyOf(moduleNames);
        var definitions = new ArrayList<ToolDefinition>();
        for (ResolvedModule module : layer.configuration().modules().stream()
                .filter(module -> selectedModules.contains(module.name()))
                .sorted(Comparator.comparing(ResolvedModule::name))
                .toList()) {
            try (var reader = module.reference().open();
                 var resources = reader.list()) {
                for (String resource : resources.filter(name -> name.startsWith(DIRECTORY) && name.endsWith(SUFFIX))
                        .sorted()
                        .toList()) {
                    var name = resource.substring(DIRECTORY.length(), resource.length() - SUFFIX.length());
                    try (var input = reader.open(resource).orElseThrow()) {
                        definitions.add(ToolDefinition.read(name, input));
                    }
                }
            }
        }
        return new ToolCatalog(definitions);
    }

    public List<ToolDefinition> definitions() {
        return definitions.values().stream()
                .sorted(Comparator.comparing(ToolDefinition::name))
                .toList();
    }

    public ToolCatalog withDiscovered(ToolRuntime tools) {
        var combined = new ArrayList<>(definitions());
        for (String name : tools.names()) {
            if (definitions.containsKey(name)) {
                continue;
            }
            combined.add(
                    new ToolDefinition(
                            name,
                            Launch.PROVIDER,
                            Optional.empty(),
                            tools.moduleName(name),
                            name,
                            Optional.empty(),
                            Set.of(),
                            List.of()));
        }
        return combined.size() == definitions.size() ? this : new ToolCatalog(combined);
    }

    public Optional<ToolDefinition> find(String name) {
        return Optional.ofNullable(definitions.get(name));
    }

    public ToolDefinition definition(String name) {
        return find(name).orElseThrow(() -> new IllegalArgumentException("Unknown tool: " + name));
    }

    public ToolDefinition named(String name, Set<String> selectedModules) {
        var definition = definition(name);
        if (!active(definition, selectedModules)) {
            throw new IllegalArgumentException("Tool " + name + " requires " + definition.activation().orElseThrow());
        }
        return definition;
    }

    private static boolean active(ToolDefinition definition, Set<String> selectedModules) {
        return definition.activation()
                         .map(selectedModules::contains)
                         .orElse(true);
    }
}
