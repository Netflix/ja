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

import java.io.PrintStream;
import java.nio.file.Path;

import com.netflix.tools.ja.project.ProjectContext;
import com.netflix.tools.ja.project.ProjectSelection.BuildScope;
import com.netflix.tools.ja.project.ProjectSelection.Origin;
import com.netflix.tools.ja.project.ProjectSelection.SelectedProjects;

final class ProjectsCommand {
    private ProjectsCommand() {}

    static int run(ProjectContext context, PrintStream out) {
        var anchor = context.catalog().anchor();
        out.print("Build: "
                + anchor.root()
                + " ["
                + anchor.type()
                + "]");
        if (context.selection() instanceof BuildScope) {
            out.print(" (current - working directory)");
        }
        out.println();
        out.println();
        for (var project : context.catalog().projects()) {
            Path directory = project.directory();
            String location = directory.startsWith(anchor.root()) ? anchor.root()
                    .relativize(directory)
                    .toString()
                    : directory.toString();
            out.print(project.selector()
                    + "  "
                    + (location.isEmpty() ? "." : location));
            if (context.selection() instanceof SelectedProjects selected && selected.projects().contains(project)) {
                out.print(selected.origin() == Origin.REQUESTED ? " (current - requested)" : " (current - working directory)");
            }
            out.println();
        }
        return 0;
    }
}
