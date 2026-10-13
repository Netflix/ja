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
import java.nio.file.Path;
import java.util.List;

import com.netflix.tools.ja.ModuleSourcePath;

final class ModuleProjectDiscovery implements ProjectDiscovery {
    private final boolean parseDescriptors;

    ModuleProjectDiscovery(boolean parseDescriptors) {
        this.parseDescriptors = parseDescriptors;
    }

    @Override
    public ProjectType type() {
        return ProjectType.MODULE;
    }

    @Override
    public List<ProjectAnchor> findCandidates(Path workingDirectory) throws IOException {
        var sourcePath = ModuleSourcePath.discover(workingDirectory, false);
        if (sourcePath.isEmpty()) {
            return List.of();
        }
        Path module = projectDirectory(sourcePath.orElseThrow()
                .modules()
                .values()
                .iterator()
                .next());
        Path parent = module.getParent();
        Path root = parent != null && parent.getFileName() != null && parent.getFileName()
                        .toString()
                        .equals("src")
                ? parent.getParent()
                : module;
        return List.of(new ProjectAnchor(type(), root));
    }

    @Override
    public ProjectCatalog discover(ProjectAnchor anchor, InputStream in, PrintStream err) throws IOException {
        var sources = ModuleSourcePath.discover(anchor.root(), parseDescriptors).orElseThrow(() -> new IllegalArgumentException("No module projects found at " + anchor.root()));
        var projects = sources.modules()
                              .entrySet()
                              .stream()
                              .map(entry -> new Project(entry.getKey(), projectDirectory(entry.getValue())))
                              .toList();
        return new ProjectCatalog(anchor, projects);
    }

    @Override
    public boolean matches(Project project, String selector) {
        return project.selector().equals(selector) || project.selector().endsWith("." + selector);
    }

    private static Path projectDirectory(Path source) {
        return source.getFileName()
                     .toString()
                     .equals("classes")
                ? source.getParent() : source;
    }
}
