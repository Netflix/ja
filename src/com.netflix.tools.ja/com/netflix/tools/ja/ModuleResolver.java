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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Requests from {@code jig} the standard Java options required by a
 * module-aware tool.
 *
 * <p>This type is the resolution boundary with {@code jig}; it does not
 * interpret source descriptors or construct a parallel project model.
 */
public final class ModuleResolver {
    private final ToolRuntime tools;

    public ModuleResolver(ToolRuntime tools) {
        this.tools = tools;
    }

    public List<String> resolve(List<String> resolutionArguments, ResolutionOptions options, InputStream in,
            PrintStream err) {
        return resolve(resolutionArguments, options, List.of(), in, err);
    }

    public List<String> resolve(List<String> resolutionArguments, ResolutionOptions options, List<String> suppliedArguments,
            InputStream in, PrintStream err) {
        if (!options.active()) {
            return List.of();
        }
        return run(arguments(resolutionArguments, options, suppliedArguments), in, err);
    }

    private static ArrayList<String> arguments(List<String> resolutionArguments, ResolutionOptions options, List<String> suppliedArguments) {
        var arguments = new ArrayList<>(resolutionArguments);
        if (!options.options().isEmpty()) {
            arguments.add("--resolve-options");
            arguments.add(optionSpecifications(options.options()));
        }
        if (options.validateRuntimeAccess()) {
            arguments.add("--validate-runtime-access");
        }
        if (!options.emitCompileDiagnostics()) {
            arguments.add("--no-compile-diagnostics");
        }
        if (!suppliedArguments.isEmpty()) {
            arguments.add("--");
            arguments.addAll(suppliedArguments);
        }
        return arguments;
    }

    private List<String> run(List<String> arguments, InputStream in, PrintStream err) {
        var outputBytes = new ByteArrayOutputStream();
        int exitCode;
        try (var output = new PrintStream(outputBytes, true, StandardCharsets.UTF_8)) {
            exitCode = tools.resolveModules(in, output, err, arguments.toArray(String[]::new));
        }
        if (exitCode != 0) {
            throw new ToolExecutionException(exitCode);
        }
        return ArgumentFiles.parse(outputBytes.toString(StandardCharsets.UTF_8));
    }

    private static String optionSpecifications(Set<String> options) {
        return options.stream()
                .sorted()
                .collect(Collectors.joining(","));
    }
}
