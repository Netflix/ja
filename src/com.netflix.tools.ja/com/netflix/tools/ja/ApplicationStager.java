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
import java.lang.module.ModuleReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.netflix.tools.ja.InstalledCommandModule.Generated;
import com.netflix.tools.ja.Installer.CommandModuleFactory;
import com.netflix.tools.ja.Installer.NativeLauncher;
import com.netflix.tools.ja.ToolCommands.Discovery;

/** Stages the application content shared by linked and standalone installations. */
final class ApplicationStager {
    private final ToolRuntime tools;
    private final CommandModuleFactory commandModules;
    private final NativeLauncher nativeLauncher;

    ApplicationStager(ToolRuntime tools, CommandModuleFactory commandModules,
                      NativeLauncher nativeLauncher) {
        this.tools = tools;
        this.commandModules = commandModules == null ? this::generateDefaultCommandModule : commandModules;
        this.nativeLauncher = nativeLauncher == null ? ApplicationStager::installNativeLauncher : nativeLauncher;
    }

    Generated generateCommandModule(
            String commandName,
            ApplicationTarget target,
            List<String> launchArguments,
            Path output,
            InputStream in,
            PrintStream out,
            PrintStream err)
            throws IOException {
        return commandModules.generate(commandName, target, launchArguments, output, in, out, err);
    }

    void stage(
            Path image,
            Map<String, Path> commands,
            Set<String> warmupCommands,
            ApplicationTarget target,
            List<String> launchArguments,
            Generated commandModule,
            List<ModuleReference> applicationModules)
            throws IOException {
        stageApplicationModules(image, commandModule, applicationModules);
        LauncherRuntimeOptions.stage(
                image.resolve("conf"),
                null,
                commands.keySet(),
                warmupCommands,
                imageOptions(target, launchArguments, commandModule));
        writeApplicationHash(image);
        for (Path command : commands.values()) {
            nativeLauncher.install(command);
        }
    }

    private Generated generateDefaultCommandModule(
            String commandName,
            ApplicationTarget target,
            List<String> launchArguments,
            Path output,
            InputStream in,
            PrintStream out,
            PrintStream err)
            throws IOException {
        List<Path> modulePath = ToolArguments.applicationModulePath(launchArguments);
        if (modulePath.isEmpty()) {
            throw new IllegalArgumentException("Application launch arguments have no module path");
        }
        Discovery discovery = ToolCommands.discover(target.moduleName(), modulePath);
        if (discovery.commands().contains(commandName)) {
            return InstalledCommandModule.generateAnchor(
                    target.moduleName(),
                    target.version(),
                    discovery.warmupCommands().contains(commandName),
                    modulePath,
                    output,
                    tools,
                    in,
                    out,
                    err);
        }
        return InstalledCommandModule.generate(
                commandName,
                target.moduleName(),
                mainClass(target.moduleName(), launchArguments),
                target.version(),
                modulePath,
                output,
                tools,
                in,
                out,
                err);
    }

    static String mainClass(String targetModule, List<String> arguments) {
        for (int i = 0; i < arguments.size(); i++) {
            String option = arguments.get(i);
            String value = null;
            if (option.equals("--module") && i + 1 < arguments.size()) {
                value = arguments.get(++i);
            } else if (option.startsWith("--module=")) {
                value = option.substring("--module=".length());
            }
            if (value == null) {
                continue;
            }
            int separator = value.indexOf('/');
            if (separator <= 0 || separator == value.length() - 1
                    || !value.substring(0, separator).equals(targetModule)) {
                throw new IllegalArgumentException("Application launch target has no main class: " + value);
            }
            return value.substring(separator + 1);
        }
        throw new IllegalArgumentException("Application launch arguments have no module main class");
    }

    private static List<String> imageOptions(
            ApplicationTarget target,
            List<String> launchArguments,
            Generated commandModule) {
        Set<String> valuedOptions = Set.of("--add-modules", "--enable-native-access",
                "--enable-final-field-mutation", "--add-opens", "--add-exports");
        var options = new ArrayList<String>();
        options.add("--add-modules=" + commandModule.moduleName());
        for (int i = 0; i < launchArguments.size(); i++) {
            String option = launchArguments.get(i);
            if (option.equals("--module-path") || option.equals("--upgrade-module-path")
                    || option.equals("--module")) {
                i++;
                continue;
            }
            if (option.startsWith("--module-path=") || option.startsWith("--upgrade-module-path=")
                    || option.startsWith("--module=")) {
                continue;
            }
            if (option.equals("--enable-preview")) {
                options.add(option);
                continue;
            }
            if (valuedOptions.contains(option)) {
                if (i + 1 >= launchArguments.size()) {
                    throw new IllegalArgumentException(option + " requires an argument");
                }
                options.add(option + "=" + launchArguments.get(++i));
                continue;
            }
            if (valuedOptions.stream().anyMatch(value -> option.startsWith(value + "="))) {
                options.add(option);
                continue;
            }
            throw new IllegalArgumentException("Unsupported application launch argument: " + option);
        }
        commandModule.mainPackage().ifPresent(packageName -> options.add("--add-opens="
                + target.moduleName() + "/" + packageName + "=" + commandModule.moduleName()));
        return List.copyOf(options);
    }

    private static void stageApplicationModules(
            Path image,
            Generated commandModule,
            List<ModuleReference> applicationModules)
            throws IOException {
        Path modules = Files.createDirectories(image.resolve("app/modules"));
        var stagedModules = new HashSet<String>();
        for (ModuleReference reference : applicationModules) {
            if (!stagedModules.add(reference.descriptor().name())) {
                continue;
            }
            var location = reference.location();
            if (location.isPresent() && location.orElseThrow().getScheme().equals("file")) {
                Path module = Path.of(location.orElseThrow());
                Path destination = Files.isDirectory(module)
                        ? modules.resolve(reference.descriptor().name())
                        : modules.resolve(module.getFileName());
                if (Files.isDirectory(module)) {
                    copyRecursively(module, destination);
                } else if (Files.isRegularFile(module)) {
                    Files.copy(module, destination, StandardCopyOption.COPY_ATTRIBUTES);
                } else {
                    throw new IllegalArgumentException(
                            "Application module location is not a file or directory: " + module);
                }
            } else {
                copyModule(reference, modules.resolve(reference.descriptor().name()));
            }
        }
        copyRecursively(commandModule.classes(), modules.resolve(commandModule.moduleName()));
    }

    private static void copyModule(ModuleReference reference, Path destination) throws IOException {
        try (var reader = reference.open(); var resources = reader.list()) {
            List<String> entries = resources.toList();
            for (String resource : entries) {
                if (entries.stream().anyMatch(candidate -> candidate.startsWith(resource + "/"))) {
                    continue;
                }
                Path output = destination.resolve(resource).normalize();
                if (!output.startsWith(destination)) {
                    throw new IllegalArgumentException("Invalid module resource: " + resource);
                }
                var input = reader.open(resource);
                if (input.isEmpty() || Files.isDirectory(output)) {
                    continue;
                }
                Files.createDirectories(output.getParent());
                try (var stream = input.orElseThrow()) {
                    Files.copy(stream, output);
                }
            }
        }
    }

    private static void copyRecursively(Path source, Path destination) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path target = destination.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static void writeApplicationHash(Path image) throws IOException {
        Path application = image.resolve("app");
        Path modules = application.resolve("modules");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var paths = Files.walk(modules)) {
                for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                    BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
                    updateDigest(digest, modules.relativize(path).toString());
                    updateDigest(digest, Long.toString(attributes.size()));
                    updateDigest(digest, Long.toString(attributes.lastModifiedTime().to(TimeUnit.SECONDS)));
                }
            }
            Files.writeString(application.resolve("modules.hash"),
                    HexFormat.of().formatHex(digest.digest()) + "\n", StandardCharsets.UTF_8);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static void updateDigest(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static void installNativeLauncher(Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        LauncherCatalog.copy(LauncherCatalog.currentPlatform(), destination);
    }
}
