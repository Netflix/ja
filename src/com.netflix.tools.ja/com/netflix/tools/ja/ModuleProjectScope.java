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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.netflix.tools.ja.Command.Builtin;
import com.netflix.tools.ja.Command.Install;
import com.netflix.tools.ja.Command.Require;
import com.netflix.tools.ja.Command.Run;
import com.netflix.tools.ja.Command.Tool;
import com.netflix.tools.ja.Command.Tools;

final class ModuleProjectScope {
    private ModuleProjectScope() {}

    static JaInvocation apply(JaInvocation invocation, ProjectContext context) throws IOException {
        var command = invocation.command();
        if (command.equals(new Builtin(BuiltinCommand.PROJECTS))) {
            return scoped(invocation, context, Optional.empty(), invocation.rootModules(),
                    invocation.resolutionArguments());
        }
        if (context.catalog()
                   .anchor()
                   .type()
                != ProjectType.MODULE) {
            if ((command instanceof Tool || command instanceof Tools) && invocation.requestedProjects().isEmpty()) {
                return scoped(invocation, context, Optional.empty(), invocation.rootModules(),
                        invocation.resolutionArguments());
            }
            String name = command instanceof Builtin(var builtin) ? builtin.commandName() : command.getClass()
                    .getSimpleName()
                    .toLowerCase(Locale.ROOT);
            throw new IllegalArgumentException(name + " is not yet supported for " + context.catalog()
                    .anchor()
                    .type()
                    + " projects");
        }
        boolean parseDescriptors = !command.equals(new Builtin(BuiltinCommand.COMPILE));
        var sources = ModuleSourcePath.discover(invocation.workingDirectory(), parseDescriptors).orElseThrow(() -> new IllegalArgumentException("No module source path found from " + invocation.workingDirectory()));
        var projectNames = context.catalog()
                .projects()
                .stream()
                .map(Project::selector)
                .toList();
        if (!sources.moduleNames().containsAll(projectNames)) {
            var complete = ModuleSourcePath.discover(context.catalog()
                    .anchor()
                    .root(),
                    parseDescriptors)
                    .orElseThrow();
            sources = new ModuleSourcePath(complete.entries(), complete.modules(), sources.enclosingModule());
        }
        var roots = new ArrayList<>(invocation.rootModules());
        var arguments = new ArrayList<>(invocation.resolutionArguments());
        boolean explicitRoots = arguments.contains("-m");
        var selected = context.selectedProjects()
                              .stream()
                              .map(Project::selector)
                              .toList();
        if (!invocation.requestedProjects().isEmpty() && explicitRoots) {
            for (int index = 0; index < arguments.size(); index++) {
                if (arguments.get(index).equals("-m") && !selected.contains(arguments.get(index + 1))) {
                    throw new IllegalArgumentException("Module " + arguments.get(index + 1) + " is not in the selected project(s)");
                }
            }
        }
        for (String sourcePath : sources.arguments()) {
            arguments.add("--module-source-path");
            arguments.add(sourcePath);
        }
        boolean sourceInstall = command instanceof Install(var request) && request.target().isEmpty();
        if (!explicitRoots) {
            boolean inferLaunchRoot = invocation.requestedProjects().isEmpty()
                    && selected.size() > 1
                    && (sourceInstall || command instanceof Run);
            if (!inferLaunchRoot) {
                for (String root : selected) {
                    roots.add(root);
                    arguments.add("-m");
                    arguments.add(root);
                }
            }
        }
        if (sourceInstall && roots.size() == 1 && !sources.modules().containsKey(roots.getFirst())) {
            throw new IllegalArgumentException("install requires a source module: " + roots.getFirst());
        }
        if (command instanceof Require(var request)) {
            for (String root : roots) {
                if (!sources.modules().containsKey(root)) {
                    throw new IllegalArgumentException("require needs a source module: " + root);
                }
            }
            if (!request.updatesVersions() && roots.size() != 1) {
                throw new IllegalArgumentException("require needs exactly one selected source module");
            }
        }
        return scoped(invocation, context, Optional.of(sources), roots, arguments);
    }

    private static JaInvocation scoped(JaInvocation invocation, ProjectContext context, Optional<ModuleSourcePath> sources,
            List<String> roots, List<String> arguments) {
        return new JaInvocation(
                invocation.workingDirectory(),
                invocation.verbose(),
                invocation.command(),
                sources,
                roots,
                arguments,
                invocation.toolArguments(),
                invocation.requestedProjects(),
                Optional.of(context));
    }
}
