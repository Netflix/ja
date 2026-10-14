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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

import com.netflix.tools.ja.project.ProjectSelection.BuildScope;
import com.netflix.tools.ja.project.ProjectSelection.Origin;
import com.netflix.tools.ja.project.ProjectSelection.SelectedProjects;

final class ProjectSelector {
    private ProjectSelector() {}

    static ProjectSelection select(ProjectCatalog catalog, ProjectDiscovery discovery, Path workingDirectory,
            List<String> requested) {
        if (!requested.isEmpty()) {
            var selected = new LinkedHashSet<ProjectTarget>();
            for (String selector : requested) {
                int separator = selector.indexOf('/');
                String projectSelector = separator < 0 ? selector : selector.substring(0, separator);
                var matches = catalog.projects()
                                     .stream()
                                     .filter(project -> discovery.matches(project, projectSelector))
                                     .toList();
                if (matches.isEmpty()) {
                    throw new IllegalArgumentException("No project matches '" + selector + "' in " + catalog.anchor().root());
                }
                if (matches.size() != 1) {
                    throw ambiguous(selector, matches);
                }
                var project = matches.getFirst();
                if (separator < 0) {
                    selected.add(project.defaultTarget());
                } else {
                    String qualifier = selector.substring(separator + 1);
                    var scopes = project.scopes().stream()
                            .filter(scope -> matchesQualifier(scope, qualifier)).toList();
                    if (scopes.size() != 1) {
                        throw new IllegalArgumentException("No context matches '" + selector + "'; available contexts: "
                                + project.targets().stream().map(ProjectTarget::selector).collect(Collectors.joining(", ")));
                    }
                    selected.add(new ProjectTarget(project, java.util.Optional.of(scopes.getFirst())));
                }
            }
            return new SelectedProjects(List.copyOf(selected), Origin.REQUESTED);
        }
        if (workingDirectory.equals(catalog.anchor().root()) && catalog.projects().size() > 1
                || workingDirectory.equals(catalog.anchor().root()) && catalog.anchor().type() != ProjectType.MODULE) {
            return new BuildScope();
        }
        var enclosing = new ArrayList<Project>();
        int depth = -1;
        for (var project : catalog.projects()) {
            if (workingDirectory.startsWith(project.directory())) {
                int currentDepth = project.directory().getNameCount();
                if (currentDepth > depth) {
                    enclosing.clear();
                    depth = currentDepth;
                }
                if (currentDepth == depth) {
                    enclosing.add(project);
                }
            }
        }
        if (enclosing.size() > 1) {
            throw ambiguous(workingDirectory.toString(), enclosing);
        }
        return enclosing.isEmpty() ? new BuildScope() : new SelectedProjects(enclosing.stream().map(Project::defaultTarget).toList(), Origin.WORKING_DIRECTORY);
    }

    private static boolean matchesQualifier(ProjectScope scope, String qualifier) {
        if (qualifier.isEmpty() || scope instanceof ProjectScope.Module) {
            return false;
        }
        return scope.qualifier().equals(qualifier)
                || scope instanceof ProjectScope.Gradle(var sourceSet, var classpath)
                        && sourceSet.equals(qualifier) && classpath.equals("compile");
    }

    static boolean matchesPrefix(ProjectType type, Project project, String current) {
        String selector = project.selector();
        if (selector.startsWith(current)) {
            return true;
        }
        if (type == ProjectType.MAVEN && current.startsWith(":")) {
            return selector.substring(selector.indexOf(':')).startsWith(current);
        }
        if (type == ProjectType.MODULE) {
            for (int index = selector.indexOf('.');
                 index >= 0;
                 index = selector.indexOf('.', index + 1)) {
                if (selector.substring(index + 1).startsWith(current)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static IllegalArgumentException ambiguous(String selector, List<Project> projects) {
        return new IllegalArgumentException("Project selection '" + selector + "' is ambiguous: " + projects.stream()
                .map(Project::selector)
                .collect(Collectors.joining(", ")));
    }
}
