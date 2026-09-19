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

import java.lang.module.ModuleDescriptor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Carries resolved tool arguments together with the standard descriptors of
 * the selected modules.
 */
public record ResolvedToolArguments(List<String> arguments,
        Set<ModuleDescriptor> moduleDescriptors,
        Map<String, ModuleDescriptor.Version> moduleVersionOverrides) {

    public ResolvedToolArguments {
        arguments = List.copyOf(arguments);
        moduleDescriptors = Set.copyOf(moduleDescriptors);
        moduleVersionOverrides = Map.copyOf(moduleVersionOverrides);
    }

    public static ResolvedToolArguments resolve(List<String> arguments,
            List<String> configurationArguments,
            ModuleLayer parent) {
        var roots = ToolArguments.addedModules(configurationArguments);
        if (roots.isEmpty())
            return new ResolvedToolArguments(arguments, Set.of(), Map.of());

        var resolution = Configurations.resolve(parent.configuration(), configurationArguments);
        var descriptors = resolution.reachablePathModules(roots).stream()
                .map(module -> module.reference().descriptor())
                .collect(Collectors.toUnmodifiableSet());
        return new ResolvedToolArguments(arguments, descriptors, Map.of());
    }

    public Set<String> modules() {
        return moduleDescriptors.stream()
                .map(ModuleDescriptor::name)
                .collect(Collectors.toUnmodifiableSet());
    }

    public Optional<ModuleDescriptor.Version> moduleVersion(String moduleName) {
        var override = moduleVersionOverrides.get(moduleName);
        if (override != null)
            return Optional.of(override);
        return moduleDescriptors.stream()
                .filter(descriptor -> descriptor.name().equals(moduleName))
                .findFirst()
                .flatMap(ModuleDescriptor::version);
    }

    public ResolvedToolArguments withModuleVersion(String moduleName, String version) {
        var overrides = new LinkedHashMap<>(moduleVersionOverrides);
        overrides.put(moduleName, ModuleDescriptor.Version.parse(version));
        return new ResolvedToolArguments(arguments, moduleDescriptors, overrides);
    }
}
