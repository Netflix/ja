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
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Collectors;

import com.netflix.tools.ja.project.Project;
import com.netflix.tools.ja.project.ProjectContext;
import com.netflix.tools.ja.project.ProjectScope;
import com.netflix.tools.ja.project.ProjectSelection.BuildScope;
import com.netflix.tools.ja.project.ProjectSelection.Origin;
import com.netflix.tools.ja.project.ProjectSelection.SelectedProjects;

final class ProjectsCommand {
    private ProjectsCommand() {}

    static int run(ProjectContext context, PrintStream out) {
        var anchor = context.catalog().anchor();
        out.print(anchor.type() + " " + anchor.root());
        if (context.selection() instanceof BuildScope) {
            out.print(" (current)");
        }
        out.println();

        for (var project : context.catalog().projects()) {
            out.println();
            out.print(displayDirectory(anchor.root(), project.directory()) + "  " + project.selector());
            out.print(selection(context, project));
            out.println();
            printScopes(project, out);
        }
        return 0;
    }

    private static String displayDirectory(Path root, Path directory) {
        if (!directory.startsWith(root)) {
            String absolute = directory.toString().replace('\\', '/');
            return absolute.endsWith("/") ? absolute : absolute + "/";
        }
        String relative = root.relativize(directory).toString().replace('\\', '/');
        return relative.isEmpty() ? "./" : "./" + relative + "/";
    }

    private static String selection(ProjectContext context, Project project) {
        if (!(context.selection() instanceof SelectedProjects selected)) {
            return "";
        }
        var targets = selected.targets().stream()
                .filter(target -> target.project().equals(project))
                .toList();
        if (targets.isEmpty()) {
            return "";
        }
        String label = selected.origin() == Origin.REQUESTED ? "requested" : "current";
        String scopes = targets.stream()
                .flatMap(target -> target.scope().stream())
                .map(ProjectScope::qualifier)
                .filter(qualifier -> !qualifier.isEmpty())
                .collect(Collectors.joining(", "));
        return scopes.isEmpty() ? " (" + label + ")" : " (" + label + ": " + scopes + ")";
    }

    private static void printScopes(Project project, PrintStream out) {
        var gradle = new TreeMap<String, List<String>>();
        var maven = new ArrayList<String>();
        for (var scope : project.scopes()) {
            switch (scope) {
                case ProjectScope.Gradle(var sourceSet, var classpath) -> gradle.computeIfAbsent(sourceSet,
                        ignored -> new ArrayList<>()).add(classpath);
                case ProjectScope.Maven(var name) -> maven.add(name);
                case ProjectScope.Module ignored -> {}
            }
        }
        for (var entry : gradle.entrySet()) {
            out.println("  " + entry.getKey() + "  " + String.join(" ", entry.getValue()));
        }
        if (!maven.isEmpty()) {
            out.println("  " + String.join(" ", maven));
        }
    }
}
