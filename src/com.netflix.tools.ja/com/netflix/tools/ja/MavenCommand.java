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
import java.io.InputStream;
import java.io.PrintStream;
import java.util.List;

/** Exports Maven build projects and deploys assembled source modules. */
final class MavenCommand {
    private final ToolRuntime tools;
    private final List<ToolDefinition> definitions;
    private final ResolutionOptions javadocOptions;

    MavenCommand(ToolRuntime tools, List<ToolDefinition> definitions, ResolutionOptions javadocOptions) {
        this.tools = tools;
        this.definitions = List.copyOf(definitions);
        this.javadocOptions = javadocOptions;
    }

    int run(JaInvocation commandLine, ModuleSourcePath moduleSourcePath, InputStream in,
            PrintStream out, PrintStream err)
            throws IOException {
        if (commandLine.toolArguments().isEmpty()) {
            throw new IllegalArgumentException("maven requires an operation: export, install, deploy, or deploy-central");
        }
        return switch (commandLine.toolArguments().getFirst()) {
            case "export" -> new MavenExporter(tools).run(commandLine, moduleSourcePath, in, out, err);
            case "install", "deploy", "deploy-central" -> new MavenDeploymentCommand(tools, definitions, javadocOptions).run(commandLine, moduleSourcePath, in, out, err);
            default -> throw new IllegalArgumentException("Unknown maven operation: " + commandLine.toolArguments().getFirst());
        };
    }
}
