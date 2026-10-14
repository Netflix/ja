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

import java.util.List;
import java.util.Objects;

import com.netflix.tools.ja.project.ProjectSelection.BuildScope;
import com.netflix.tools.ja.project.ProjectSelection.SelectedProjects;

public record ProjectContext(ProjectCatalog catalog, ProjectSelection selection) {
    public ProjectContext {
        Objects.requireNonNull(catalog);
        Objects.requireNonNull(selection);
    }

    public List<ProjectTarget> matchingTargets(String prefix) {
        int separator = prefix.indexOf('/');
        String projectPrefix = separator < 0 ? prefix : prefix.substring(0, separator);
        return catalog.projects().stream()
                .filter(project -> ProjectSelector.matchesPrefix(catalog.anchor().type(), project, projectPrefix))
                .flatMap(project -> separator < 0
                        ? java.util.stream.Stream.of(new ProjectTarget(project, java.util.Optional.empty()))
                        : project.targets().stream().filter(target -> target.scope()
                                .map(ProjectScope::qualifier).orElse("").startsWith(prefix.substring(separator + 1))))
                .toList();
    }

    public List<ProjectTarget> selectedTargets() {
        return switch (selection) {
            case BuildScope ignored -> catalog.projects().stream().map(Project::defaultTarget).toList();
            case SelectedProjects selected -> selected.targets();
        };
    }

    public List<Project> selectedProjects() {
        return selectedTargets().stream().map(ProjectTarget::project).distinct().toList();
    }
}
