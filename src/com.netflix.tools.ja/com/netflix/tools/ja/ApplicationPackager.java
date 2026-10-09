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
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

import com.netflix.tools.ja.LauncherCatalog.Platform;
import com.netflix.tools.ja.ToolCommands.Discovery;

/** Creates portable application archives that use the prevailing JDK. */
final class ApplicationPackager {
    private static final String LAUNCHER_MODULE = "com.netflix.tools.launcher";

    private final ToolRuntime tools;

    ApplicationPackager(ToolRuntime tools) {
        this.tools = tools;
    }

    int create(
            String moduleName,
            String version,
            Discovery discovery,
            Map<String, Path> observableSources,
            Path compilationRoot,
            List<String> runtimeArguments,
            List<String> moduleArguments,
            Path workDirectory,
            Path destination,
            String baseName,
            String targetPlatform,
            InputStream in,
            PrintStream out,
            PrintStream err)
            throws IOException {
        Optional<String> mainClass = mainClass(moduleArguments);
        if (discovery.commands().isEmpty() && mainClass.isEmpty()) {
            return 0;
        }

        var commandNames = new TreeSet<>(discovery.commands());
        var launchArguments = new ArrayList<>(runtimeArguments);
        if (commandNames.isEmpty()) {
            commandNames.add(defaultName(moduleName));
            launchArguments.add("--module");
            launchArguments.add(moduleName + "/" + mainClass.orElseThrow());
        }

        var target = new ApplicationTarget(moduleName, version);
        var generator = new ApplicationStager(tools, null, _ -> {
            throw new AssertionError("Native launcher requested while generating command module");
        });
        var commandModule = generator.generateCommandModule(
                commandNames.getFirst(), target, launchArguments,
                workDirectory.resolve("application-command"), in, out, err);

        var applicationModules = new ArrayList<>(Installer.applicationModules(target, launchArguments, true));
        if (applicationModules.stream().noneMatch(reference -> reference.descriptor().name().equals(LAUNCHER_MODULE))) {
            applicationModules.add(launcherReference(runtimeArguments));
        }

        for (Platform platform : LauncherCatalog.platforms(targetPlatform)) {
            Path archive = destination.resolve(baseName + "-" + platform.classifier() + ".zip");
            Path temporary = Files.createTempFile(destination, "." + baseName + "-", ".zip");
            Files.delete(temporary);
            try {
                try (var fileSystem = FileSystems.newFileSystem(temporary, Map.of(
                        "create", "true",
                        "enablePosixFileAttributes", "true"))) {
                    Path image = fileSystem.getPath("/");
                    var commands = new LinkedHashMap<String, Path>();
                    String extension = platform.executable().endsWith(".exe") ? ".exe" : "";
                    for (String command : commandNames) {
                        commands.put(command, image.resolve("bin").resolve(command + extension));
                    }
                    var stager = new ApplicationStager(tools, null, command -> {
                        Files.createDirectories(command.getParent());
                        LauncherCatalog.copy(platform, compilationRoot, observableSources, command);
                    });
                    stager.stage(
                            image,
                            commands,
                            discovery.warmupCommands(),
                            target,
                            launchArguments,
                            commandModule,
                            applicationModules);
                    String dispatcherName = platform.executable().endsWith(".exe")
                            ? "dispatcher.exe"
                            : "dispatcher";
                    Path dispatcher = image.resolve("lib/com.netflix.tools.launcher").resolve(dispatcherName);
                    Files.createDirectories(dispatcher.getParent());
                    LauncherCatalog.copyDispatcher(
                            platform, compilationRoot, observableSources, dispatcher);
                }
                move(temporary, archive);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        return 0;
    }

    private ModuleReference launcherReference(List<String> runtimeArguments) {
        ModuleFinder applicationModules = ModuleFinder.of(
                ToolArguments.applicationModulePath(runtimeArguments).toArray(Path[]::new));
        return applicationModules.find(LAUNCHER_MODULE)
                .or(() -> ModuleFinder.ofSystem().find(LAUNCHER_MODULE))
                .orElseThrow(() -> new IllegalArgumentException("Launcher module is not installed"));
    }

    private static void move(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException _) {
            Files.move(source, destination);
        }
    }

    private static Optional<String> mainClass(List<String> arguments) {
        for (int index = 0; index < arguments.size(); index++) {
            String argument = arguments.get(index);
            if (argument.equals("--main-class") && index + 1 < arguments.size()) {
                return Optional.of(arguments.get(index + 1));
            }
            if (argument.startsWith("--main-class=")) {
                return Optional.of(argument.substring("--main-class=".length()));
            }
        }
        return Optional.empty();
    }

    private static String defaultName(String moduleName) {
        int separator = moduleName.lastIndexOf('.');
        return separator < 0 ? moduleName : moduleName.substring(separator + 1);
    }
}
