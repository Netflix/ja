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

package com.netflix.tools.ja.project;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.netflix.tools.ja.ToolRuntime;

public final class ProjectLocator {
    private final Map<ProjectType, ProjectDiscovery> discoveries;

    public ProjectLocator(ToolRuntime tools, boolean parseDescriptors) {
        discoveries = Map.of(
                ProjectType.MODULE, new ModuleProjectDiscovery(parseDescriptors),
                ProjectType.MAVEN, new BuildProjectDiscovery(ProjectType.MAVEN, tools),
                ProjectType.GRADLE, new BuildProjectDiscovery(ProjectType.GRADLE, tools));
    }

    public Optional<ProjectContext> locate(Path workingDirectory, List<String> requested, InputStream in,
            PrintStream err)
            throws IOException {
        Path directory = Files.isDirectory(workingDirectory) ? workingDirectory : workingDirectory.getParent();
        if (directory == null || !Files.isDirectory(directory)) {
            return Optional.empty();
        }
        directory = directory.toRealPath();
        var candidates = new ArrayList<ProjectAnchor>();
        candidates.addAll(discoveries.get(ProjectType.MAVEN)
                .findCandidates(directory));
        candidates.addAll(discoveries.get(ProjectType.GRADLE)
                .findCandidates(directory));
        candidates.sort(Comparator.comparingInt(anchor -> anchor.root().getNameCount()));
        for (var candidate : candidates) {
            if (candidates.stream().anyMatch(other -> other.type() != candidate.type() && other.root().equals(candidate.root()))) {
                throw new IllegalArgumentException("Both maven and gradle builds found at " + candidate.root());
            }
        }
        for (var candidate : candidates) {
            var discovery = discoveries.get(candidate.type());
            var catalog = discovery.discover(candidate, in, err);
            boolean independentBuild = candidates.stream()
                    .filter(other -> !other.equals(candidate) && other.root().startsWith(candidate.root()))
                    .anyMatch(other ->
                            other.type() != candidate.type() || catalog.projects()
                                    .stream()
                                    .noneMatch(project -> project.directory().equals(other.root())));
            if (!independentBuild) {
                return Optional.of(new ProjectContext(catalog, ProjectSelector.select(catalog, discovery, directory, requested)));
            }
        }
        var discovery = discoveries.get(ProjectType.MODULE);
        var modules = discovery.findCandidates(directory);
        if (modules.isEmpty()) {
            return Optional.empty();
        }
        var catalog = discovery.discover(modules.getFirst(), in, err);
        return Optional.of(new ProjectContext(catalog, ProjectSelector.select(catalog, discovery, directory, requested)));
    }
}
