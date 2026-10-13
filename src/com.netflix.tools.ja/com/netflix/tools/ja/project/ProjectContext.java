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

    public List<Project> matchingProjects(String prefix) {
        return catalog.projects().stream()
                .filter(project -> ProjectSelector.matchesPrefix(catalog.anchor().type(), project, prefix))
                .toList();
    }

    public List<Project> selectedProjects() {
        return switch (selection) {
            case BuildScope ignored -> catalog.projects();
            case SelectedProjects selected -> selected.projects();
        };
    }
}
