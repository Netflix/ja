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
import java.util.ArrayList;

/** Exports source modules as a Maven build project. */
final class MavenExporter {
    private final ToolRuntime tools;

    MavenExporter(ToolRuntime tools) {
        this.tools = tools;
    }

    int run(JaInvocation commandLine, ModuleSourcePath moduleSourcePath, InputStream in,
            PrintStream out, PrintStream err)
            throws IOException {
        if (commandLine.rootModules().isEmpty()) {
            throw new IllegalArgumentException("maven export requires at least one source module");
        }
        for (String module : commandLine.rootModules()) {
            if (!moduleSourcePath.modules().containsKey(module)) {
                throw new IllegalArgumentException("Maven export requires a source module: " + module);
            }
        }

        var arguments = new ArrayList<>(commandLine.resolutionArguments());
        arguments.add("--verify-module-hashes");
        arguments.add("--no-compile-diagnostics");
        arguments.add("--generate-module-poms");
        arguments.add(commandLine.workingDirectory().toString());
        return tools.run("jig", in, out, err, arguments.toArray(String[]::new));
    }
}
