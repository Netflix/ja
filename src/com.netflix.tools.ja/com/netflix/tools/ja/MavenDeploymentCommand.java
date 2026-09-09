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
import java.util.ArrayList;
import java.util.List;

import com.netflix.tools.ja.ModuleAssembler.Options;

/**
 * Assembles source modules and delegates Maven installation or deployment to
 * Jig.
 */
final class MavenDeploymentCommand {
    private record Request(
            String operation,
            Options assembly,
            String repository,
            boolean sign,
            String name,
            boolean manual) {}

    private final ToolServices tools;
    private final ModuleAssembler assembler;

    MavenDeploymentCommand(ToolServices tools, List<ToolDefinition> definitions) {
        this.tools = tools;
        assembler = new ModuleAssembler(tools, definitions);
    }

    int run(JaInvocation commandLine, ModuleSourcePath moduleSourcePath, InputStream in,
            PrintStream out, PrintStream err)
            throws IOException {
        Request request = request(commandLine.toolArguments());
        try (var temporary = TemporaryDirectory.create()) {
            Path artifacts = temporary.root().resolve("artifacts");
            int result = assembler.assemble(commandLine, moduleSourcePath, request.assembly(), artifacts, in,
                    out, err);
            if (result != 0) {
                return result;
            }
            return deploy(request, artifacts, in, out, err);
        }
    }

    private int deploy(Request request, Path artifacts, InputStream in,
                       PrintStream out, PrintStream err) {
        var arguments = new ArrayList<String>();
        arguments.add("maven");
        arguments.add(request.operation());
        if (request.repository() != null) {
            arguments.add("--repository");
            arguments.add(request.repository());
        }
        if (request.sign()) {
            arguments.add("--sign");
        }
        if (request.name() != null) {
            arguments.add("--name");
            arguments.add(request.name());
        }
        if (request.manual()) {
            arguments.add("--manual");
        }
        arguments.add(artifacts.toString());
        return tools.run("jig", in, out, err, arguments.toArray(String[]::new));
    }

    private static Request request(List<String> arguments) {
        if (arguments.isEmpty()) {
            throw new IllegalArgumentException("maven requires an operation: export, install, deploy, or deploy-central");
        }
        String operation = arguments.getFirst();
        if (!operation.equals("install") && !operation.equals("deploy") && !operation.equals("deploy-central")) {
            throw new IllegalArgumentException("Unknown Maven deployment operation: " + operation);
        }
        String version = null;
        String targetPlatform = null;
        String repository = null;
        String name = null;
        boolean jmod = false;
        boolean sign = false;
        boolean manual = false;
        for (int i = 1; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            if (argument.equals("--module-version")) {
                version = singleValue("--module-version", version, arguments, ++i);
            } else if (argument.startsWith("--module-version=")) {
                version = singleValue("--module-version", version, argument.substring("--module-version=".length()));
            } else if (argument.equals("--target-platform")) {
                targetPlatform = singleValue("--target-platform", targetPlatform, arguments, ++i);
            } else if (argument.startsWith("--target-platform=")) {
                targetPlatform = singleValue("--target-platform", targetPlatform, argument.substring("--target-platform=".length()));
            } else if (argument.equals("--repository")) {
                repository = singleValue("--repository", repository, arguments, ++i);
            } else if (argument.startsWith("--repository=")) {
                repository = singleValue("--repository", repository, argument.substring("--repository=".length()));
            } else if (argument.equals("--name")) {
                name = singleValue("--name", name, arguments, ++i);
            } else if (argument.startsWith("--name=")) {
                name = singleValue("--name", name, argument.substring("--name=".length()));
            } else if (argument.equals("--jmod")) {
                if (jmod) {
                    throw new IllegalArgumentException("--jmod may only be specified once");
                }
                jmod = true;
            } else if (argument.equals("--sign")) {
                if (sign) {
                    throw new IllegalArgumentException("--sign may only be specified once");
                }
                sign = true;
            } else if (argument.equals("--manual")) {
                if (manual) {
                    throw new IllegalArgumentException("--manual may only be specified once");
                }
                manual = true;
            } else if (argument.startsWith("-")) {
                throw new IllegalArgumentException("Unknown maven " + operation + " option: " + argument);
            } else {
                throw new IllegalArgumentException("maven " + operation + " does not accept an operand");
            }
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("maven " + operation + " requires --module-version");
        }
        validateOperation(operation, repository, sign, name, manual);
        return new Request(operation, new Options(version, targetPlatform, jmod), repository, sign, name, manual);
    }

    private static void validateOperation(String operation, String repository,
            boolean sign, String name, boolean manual) {
        if (operation.equals("deploy") && repository == null) {
            throw new IllegalArgumentException("maven deploy requires --repository");
        }
        if (!operation.equals("deploy") && repository != null) {
            throw new IllegalArgumentException("--repository is only supported by maven deploy");
        }
        if (!operation.equals("deploy") && sign) {
            throw new IllegalArgumentException("--sign is only supported by maven deploy");
        }
        if (!operation.equals("deploy-central") && name != null) {
            throw new IllegalArgumentException("--name is only supported by maven deploy-central");
        }
        if (!operation.equals("deploy-central") && manual) {
            throw new IllegalArgumentException("--manual is only supported by maven deploy-central");
        }
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
}
