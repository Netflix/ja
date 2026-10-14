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

import java.io.InputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.netflix.tools.ja.project.ProjectContext;
import com.netflix.tools.ja.project.ProjectScope;
import com.netflix.tools.ja.project.ProjectSelection.BuildScope;
import com.netflix.tools.ja.project.ProjectType;

final class ProjectResolver {
    private final ModuleResolver modules;

    ProjectResolver(ModuleResolver modules) {
        this.modules = modules;
    }

    static boolean external(JaInvocation invocation) {
        return invocation.projectContext().map(context -> context.catalog().anchor().type() != ProjectType.MODULE).orElse(false);
    }

    List<String> resolve(JaInvocation invocation, List<String> arguments, ResolutionOptions options,
            InputStream in, PrintStream err) {
        return resolve(invocation, arguments, options, List.of(), in, err);
    }

    List<String> resolve(JaInvocation invocation, List<String> arguments, ResolutionOptions options,
            List<String> suppliedArguments, InputStream in, PrintStream err) {
        if (!external(invocation)) {
            return modules.resolve(arguments, options, suppliedArguments, in, err);
        }
        if (options.validateRuntimeAccess()) {
            throw new IllegalArgumentException("Runtime access validation is not yet supported for build project contexts");
        }
        return resolve(invocation.projectContext().orElseThrow(), options.options(), in, err);
    }

    private List<String> resolve(ProjectContext context, Set<String> requested, InputStream in, PrintStream err) {
        var anchor = context.catalog().anchor();
        var options = requested.stream().sorted().collect(Collectors.joining(","));
        if (options.isEmpty()) {
            return List.of();
        }
        var targets = context.selectedTargets().stream()
                .filter(target -> !(context.selection() instanceof BuildScope && target.project().scopes().isEmpty()))
                .toList();
        if (targets.size() > 1) {
            throw new IllegalArgumentException("Tool argument resolution requires one project context; select one with -p");
        }
        var resolved = new ArrayList<List<String>>();
        for (var target : targets) {
            if (target.scope().isEmpty()) {
                throw new IllegalArgumentException("No default Java context for " + target.project().selector()
                        + "; select a scope or source set from ja projects");
            }
            var arguments = new ArrayList<String>();
            switch (target.scope().orElseThrow()) {
                case ProjectScope.Maven(var scope) -> arguments.addAll(List.of("maven", "--project-base-dir", anchor.root().toString(),
                        "--project", target.project().selector(), "--scope", scope));
                case ProjectScope.Gradle(var sourceSet, var classpath) -> arguments.addAll(List.of("gradle", "--root-project-dir", anchor.root().toString(),
                        "--project-path", target.project().selector(), "--source-set", sourceSet, "--classpath", classpath));
                case ProjectScope.Module _ -> throw new IllegalArgumentException("Module context in an external build");
            }
            arguments.addAll(List.of("--resolve-options", options));
            resolved.add(modules.run(arguments, in, err));
        }
        return resolved.isEmpty() ? List.of() : resolved.getFirst();
    }
}
