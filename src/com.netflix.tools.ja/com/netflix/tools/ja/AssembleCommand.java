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
import java.nio.file.Path;
import java.util.List;

import com.netflix.tools.ja.ModuleAssembler.Options;

/** Assembles selected source modules into a flat artifact directory. */
final class AssembleCommand {
    private final ModuleAssembler assembler;

    AssembleCommand(ToolRuntime tools, List<ToolDefinition> definitions, ResolutionOptions javadocOptions) {
        assembler = new ModuleAssembler(tools, definitions, javadocOptions);
    }

    int run(JaInvocation commandLine, ModuleSourcePath moduleSourcePath, InputStream in,
            PrintStream out, PrintStream err)
            throws IOException {
        Request request = request(commandLine.workingDirectory(), commandLine.toolArguments());
        return assembler.assemble(commandLine, moduleSourcePath, request.options(), request.destination(),
                in, out, err);
    }

    private static Request request(Path workingDirectory, List<String> arguments) {
        String version = null;
        String targetPlatform = null;
        Path destination = null;
        boolean jmod = false;
        for (int i = 0; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            if (argument.equals("--module-version")) {
                version = singleValue("--module-version", version, arguments, ++i);
            } else if (argument.startsWith("--module-version=")) {
                version = singleValue("--module-version", version, argument.substring("--module-version=".length()));
            } else if (argument.equals("--jmod")) {
                if (jmod) {
                    throw new IllegalArgumentException("--jmod may only be specified once");
                }
                jmod = true;
            } else if (argument.equals("--target-platform")) {
                targetPlatform = singleValue("--target-platform", targetPlatform, arguments, ++i);
            } else if (argument.startsWith("--target-platform=")) {
                targetPlatform = singleValue("--target-platform", targetPlatform, argument.substring("--target-platform=".length()));
            } else if (argument.startsWith("-")) {
                throw new IllegalArgumentException("Unknown assemble option: " + argument);
            } else if (destination == null) {
                destination = workingDirectory.resolve(argument).normalize();
            } else {
                throw new IllegalArgumentException("assemble accepts one destination directory");
            }
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("assemble requires --module-version");
        }
        if (destination == null) {
            throw new IllegalArgumentException("assemble requires a destination directory");
        }
        return new Request(new Options(version, targetPlatform, jmod), destination);
    }

    private static String singleValue(String option, String current, List<String> arguments,
            int index) {
        if (index >= arguments.size()) {
            throw new IllegalArgumentException(option + " requires a value");
        }
        return singleValue(option, current, arguments.get(index));
    }

    private static String singleValue(String option, String current, String value) {
        if (current != null) {
            throw new IllegalArgumentException(option + " may only be specified once");
        }
        return value;
    }

    private record Request(Options options, Path destination) {}
}
