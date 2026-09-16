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

/** Resolves the Java launcher arguments for a modular tool. */
final class ToolResolver {
    private final ModuleResolver moduleResolver;

    ToolResolver(ToolServices tools) {
        this.moduleResolver = new ModuleResolver(tools);
    }

    List<String> resolveLauncher(
            ToolDefinition definition,
            String version,
            List<String> resolutionArguments,
            List<String> suppliedArguments,
            InputStream in,
            PrintStream err) {
        String module = requiredModule(definition);
        var arguments = new ArrayList<>(ResolutionArguments.rootsAsAddedModules(resolutionArguments));
        arguments.add("--add-requires");
        arguments.add(module + "@" + version);
        return moduleResolver.resolve(arguments, ResolutionOptions.JAVA,
                suppliedArguments, in, err);
    }

    private static String requiredModule(ToolDefinition definition) {
        return definition.module().orElseThrow(() -> new IllegalArgumentException("Tool " + definition.name() + " is not installed and has no module"));
    }
}
