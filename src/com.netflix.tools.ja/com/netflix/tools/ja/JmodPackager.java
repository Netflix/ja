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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.netflix.tools.ja.LauncherCatalog.Platform;
import com.netflix.tools.ja.ToolCommands.Discovery;

/**
 * Creates JMOD artifacts, including native commands and launcher runtime
 * configuration when required.
 */
final class JmodPackager {
    record Artifacts(
            String moduleName,
            Path moduleSource,
            Map<String, Path> observableSources,
            Path compilationRoot,
            Path moduleContent,
            Path workDirectory,
            Path destination,
            String baseName,
            List<String> runtimeArguments,
            List<String> moduleArguments,
            String targetPlatform,
            boolean requested) {
        Artifacts {
            observableSources = Map.copyOf(observableSources);
            runtimeArguments = List.copyOf(runtimeArguments);
            moduleArguments = List.copyOf(moduleArguments);
        }
    }

    private final ToolServices tools;

    JmodPackager(ToolServices tools) {
        this.tools = tools;
    }

    boolean creates(Artifacts artifacts) {
        return artifacts.requested() || jmodLayout(artifacts.moduleSource()) != null || !discovery(artifacts).commands().isEmpty();
    }

    int create(Artifacts artifacts, InputStream in, PrintStream out,
               PrintStream err)
            throws IOException {
        var discovery = discovery(artifacts);
        var layout = jmodLayout(artifacts.moduleSource());
        if (!artifacts.requested() && layout == null && discovery.commands().isEmpty()) {
            return 0;
        }
        if (!tools.contains("jmod")) {
            throw new IllegalArgumentException("Tool jmod is not installed");
        }

        var existingConfiguration = layout == null ? null : layout.resolve("conf");
        if (existingConfiguration != null && !Files.isDirectory(existingConfiguration)) {
            existingConfiguration = null;
        }
        var configuration = LauncherRuntimeOptions.stage(
                artifacts.workDirectory().resolve("jmod-conf"),
                existingConfiguration,
                discovery.commands(),
                discovery.warmupCommands(),
                artifacts.runtimeArguments());
        var platforms = discovery.commands().isEmpty() ? List.<Platform>of() : LauncherCatalog.platforms(artifacts.targetPlatform());
        if (platforms.isEmpty()) {
            return create(artifacts, layout, configuration, null, discovery.commands(),
                    in, out, err);
        }
        for (var platform : platforms) {
            var result = create(artifacts, layout, configuration, platform, discovery.commands(),
                    in, out, err);
            if (result != 0) {
                return result;
            }
        }
        return 0;
    }

    private int create(
            Artifacts artifacts,
            Path jmodLayout,
            Path configuration,
            Platform platform,
            Set<String> commands,
            InputStream in,
            PrintStream out,
            PrintStream err)
            throws IOException {
        var arguments = new ArrayList<String>();
        arguments.add("create");
        arguments.add("--class-path");
        arguments.add(artifacts.moduleContent()
                               .toString());
        arguments.addAll(artifacts.moduleArguments());
        var commandsDirectory = commandsDirectory(artifacts, jmodLayout, commands, platform);
        addSection(arguments, "--cmds", commandsDirectory);
        addSection(arguments, "--config", configuration);
        if (jmodLayout != null) {
            addSection(arguments, "--header-files", jmodLayout.resolve("include"));
            addSection(arguments, "--legal-notices", jmodLayout.resolve("legal"));
            addSection(arguments, "--libs", jmodLayout.resolve("lib"));
            addSection(arguments, "--man-pages", jmodLayout.resolve("man"));
        }
        var classifier = platform == null ? "" : "-" + platform.classifier();
        arguments.add(artifacts.destination()
                               .resolve(artifacts.baseName() + classifier + ".jmod")
                               .toString());
        return tools.run("jmod", in, out, err, arguments.toArray(String[]::new));
    }

    private static Discovery discovery(Artifacts artifacts) {
        return ToolCommands.discover(artifacts.moduleName(), ToolArguments.modulePath(artifacts.runtimeArguments()));
    }

    private static Path commandsDirectory(Artifacts artifacts, Path jmodLayout, Set<String> commands,
            Platform platform)
            throws IOException {
        var explicit = jmodLayout == null ? null : jmodLayout.resolve("bin");
        if (commands.isEmpty()) {
            return explicit;
        }

        var directory = artifacts.workDirectory().resolve("jmod-bin-" + platform.classifier());
        Files.createDirectory(directory);
        if (explicit != null && Files.isDirectory(explicit)) {
            copyTree(explicit, directory);
        }
        var extension = platform.executable().endsWith(".exe") ? ".exe" : "";
        for (var command : commands) {
            var destination = directory.resolve(command + extension);
            if (Files.exists(destination)) {
                throw new IllegalArgumentException("Tool command collides with explicit JMOD command: " + destination);
            }
            LauncherCatalog.copy(platform, artifacts.compilationRoot(), artifacts.observableSources(), destination);
        }
        return directory;
    }

    private static void copyTree(Path source, Path destination) throws IOException {
        try (var paths = Files.walk(source)) {
            for (var input : paths.toList()) {
                var output = destination.resolve(source.relativize(input));
                if (Files.isDirectory(input)) {
                    Files.createDirectories(output);
                } else {
                    Files.copy(input, output, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static Path jmodLayout(Path moduleSource) {
        if (moduleSource.getFileName() != null && moduleSource.getFileName()
                .toString()
                .equals("classes")
                && Files.isRegularFile(moduleSource.resolve("module-info.java"))) {
            return moduleSource.getParent();
        }
        return null;
    }

    private static void addSection(List<String> arguments, String option, Path directory) {
        if (directory != null && Files.isDirectory(directory)) {
            arguments.add(option);
            arguments.add(directory.toString());
        }
    }
}
