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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import com.netflix.tools.ja.ToolExecutionException;
import com.netflix.tools.ja.ToolRuntime;

final class BuildProjectDiscovery implements ProjectDiscovery {
    private final ProjectType type;
    private final ToolRuntime tools;

    BuildProjectDiscovery(ProjectType type, ToolRuntime tools) {
        if (type == ProjectType.MODULE) {
            throw new IllegalArgumentException("Module projects do not use build discovery");
        }
        this.type = type;
        this.tools = tools;
    }

    @Override
    public ProjectType type() {
        return type;
    }

    @Override
    public List<ProjectAnchor> findCandidates(Path workingDirectory) throws IOException {
        var markers = type == ProjectType.MAVEN ? List.of("pom.xml") : List.of("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts");
        var candidates = new ArrayList<ProjectAnchor>();
        for (Path current = workingDirectory;
             current != null;
             current = current.getParent()) {
            Path directory = current;
            if (markers.stream().anyMatch(marker -> Files.isRegularFile(directory.resolve(marker)))) {
                candidates.add(new ProjectAnchor(type, current.toRealPath()));
            }
        }
        return List.copyOf(candidates);
    }

    @Override
    public ProjectCatalog discover(ProjectAnchor anchor, InputStream in, PrintStream err) throws IOException {
        String rootOption = type == ProjectType.MAVEN ? "--project-base-dir" : "--root-project-dir";
        String listOption = type == ProjectType.MAVEN ? "--list-projects" : "--list-project-paths";
        var output = new ByteArrayOutputStream();
        int status;
        try (var stream = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            status = tools.resolveModules(
                    in,
                    stream,
                    err,
                    type.toString(),
                    rootOption,
                    anchor.root().toString(),
                    listOption,
                    "--format",
                    "tsv");
        }
        if (status != 0) {
            throw new ToolExecutionException(status);
        }
        var projects = new TreeMap<String, Project>();
        String[] lines = output.toString(StandardCharsets.UTF_8).split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (index == lines.length - 1 && line.isEmpty()) {
                continue;
            }
            String[] fields = line.split("\t", -1);
            if (fields.length != (type == ProjectType.MAVEN ? 3 : 4) || line.indexOf('\r') >= 0) {
                throw new IOException("Invalid " + type + " project listing at line " + (index + 1));
            }
            String selector = fields[1];
            if (type == ProjectType.MAVEN
                    ? !selector.matches("[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+")
                    : !selector.startsWith(":")
                            || selector.contains("::")
                            || selector.length() > 1 && selector.endsWith(":")) {
                throw new IOException("Invalid " + type + " project selector: " + selector);
            }
            Path directory;
            try {
                directory = Path.of(fields[0]);
                if (!directory.isAbsolute()) {
                    throw new IOException("Invalid " + type + " project directory: expected an absolute path");
                }
                directory = directory.toFile()
                                     .getCanonicalFile()
                                     .toPath();
            } catch (InvalidPathException failure) {
                throw new IOException("Invalid " + type + " project directory", failure);
            }
            ProjectScope scope = scope(fields);
            Project previous = projects.get(selector);
            if (previous != null && !previous.directory().equals(directory)) {
                throw new IOException("Conflicting " + type + " project directory for " + selector);
            }
            if (previous != null && previous.scopes().isEmpty() != (scope == null)) {
                throw new IOException("Conflicting " + type + " project contexts for " + selector);
            }
            var scopes = new ArrayList<ProjectScope>(previous == null ? List.of() : previous.scopes());
            if (scope != null) {
                scopes.add(scope);
            }
            projects.put(selector, new Project(selector, directory, scopes));
        }
        if (projects.isEmpty()) {
            throw new IOException("No projects returned by " + type + " for " + anchor.root());
        }
        return new ProjectCatalog(anchor, List.copyOf(projects.values()));
    }

    private ProjectScope scope(String[] fields) throws IOException {
        if (type == ProjectType.MAVEN) {
            if (!Set.of("compile", "runtime", "test").contains(fields[2])) {
                throw new IOException("Invalid Maven scope: " + fields[2]);
            }
            return new ProjectScope.Maven(fields[2]);
        }
        if (fields[2].isEmpty() && fields[3].isEmpty()) {
            return null;
        }
        if (fields[2].isBlank() || fields[2].contains("/") || !Set.of("compile", "runtime").contains(fields[3])) {
            throw new IOException("Invalid Gradle context: " + fields[2] + "/" + fields[3]);
        }
        return new ProjectScope.Gradle(fields[2], fields[3]);
    }

    @Override
    public boolean matches(Project project, String selector) {
        return project.selector().equals(selector)
                || type == ProjectType.MAVEN && selector.startsWith(":") && project.selector().endsWith(selector);
    }
}
