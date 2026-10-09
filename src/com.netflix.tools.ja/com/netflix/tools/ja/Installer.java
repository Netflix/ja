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
import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Requires.Modifier;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.netflix.tools.ja.InstalledCommandModule.Generated;

/**
 * Installs a modular application as an isolated runtime image and activates its
 * native command.
 */
public final class Installer {
    @FunctionalInterface
    public interface CommandModuleFactory {
        Generated generate(
                String commandName,
                ApplicationTarget target,
                List<String> launchArguments,
                Path output,
                InputStream in,
                PrintStream out,
                PrintStream err)
                throws IOException;
    }

    @FunctionalInterface
    public interface NativeLauncher {
        void install(Path destination) throws IOException;
    }

    @FunctionalInterface
    public interface RuntimeModuleResolver {
        Set<String> resolve(ApplicationTarget target, List<String> launchArguments, boolean includeStatic);
    }

    private final ToolRuntime tools;
    private final InstallationDirectories directories;
    private final boolean windows;
    private final ApplicationStager applicationStager;
    private final RuntimeModuleResolver runtimeModuleResolver;

    public Installer(ToolRuntime tools, InstallationDirectories directories, boolean windows) {
        this(tools, directories, windows, null, null,
                Installer::runtimeModules);
    }

    public Installer(ToolRuntime tools, InstallationDirectories directories, boolean windows,
                     CommandModuleFactory commandModules, NativeLauncher nativeLauncher) {
        this(tools, directories, windows, commandModules, nativeLauncher,
                Installer::runtimeModules);
    }

    public Installer(ToolRuntime tools, InstallationDirectories directories, boolean windows,
                     CommandModuleFactory commandModules, NativeLauncher nativeLauncher, RuntimeModuleResolver runtimeModuleResolver) {
        this.tools = tools;
        this.directories = directories;
        this.windows = windows;
        this.applicationStager = new ApplicationStager(tools, commandModules, nativeLauncher);
        this.runtimeModuleResolver = runtimeModuleResolver;
    }

    public int install(ApplicationTarget target, InstallRequest request, List<String> launchArguments,
                       InputStream in, PrintStream out, PrintStream err)
            throws IOException {
        ApplicationStager.mainClass(target.moduleName(), launchArguments);
        AutomaticModules.warnInstall(launchArguments, err);
        String name = request.name().orElseGet(() -> defaultName(target.moduleName()));
        validateName(name);
        Files.createDirectories(directories.applications());
        boolean managed = request.output().isEmpty();
        if (managed) {
            Files.createDirectories(directories.commands());
        }

        Path logicalImage = request.output().orElseGet(() -> directories.applications().resolve(target.moduleName() + target.version()
                .map(version -> "@" + encodePathComponent(version))
                .orElse("")));
        Path command = commandPath(name);
        if (managed) {
            checkWindowsCommandConflicts(name, request.force());
            checkCommandConflict(command, target.moduleName(), request.force());
        }

        if (Files.exists(logicalImage) && !request.force()) {
            throw new FileAlreadyExistsException("Application version is already installed: " + logicalImage + "; use --force to replace it");
        }
        Path image = managed && Files.exists(logicalImage)
                ? logicalImage.resolveSibling(logicalImage.getFileName() + "+" + UUID.randomUUID())
                : logicalImage;

        Path publicationDirectory = image.getParent();
        if (publicationDirectory == null) {
            throw new IllegalArgumentException("Install output has no parent: " + image);
        }
        Files.createDirectories(publicationDirectory);
        Path staging = Files.createTempDirectory(publicationDirectory, ".install-");
        Path stagedImage = staging.resolve(image.getFileName());
        try {
            Generated commandModule = applicationStager.generateCommandModule(
                    name, target, launchArguments, staging.resolve("command"), in, out, err);
            linkRuntime(target, launchArguments, request, stagedImage, in, out, err);
            applicationStager.stage(
                    stagedImage,
                    Map.of(name, stagedImage.resolve("bin").resolve(windows ? name + ".exe" : name)),
                    commandModule.warmup() ? Set.of(name) : Set.of(),
                    target,
                    launchArguments,
                    commandModule,
                    applicationModules(target, launchArguments, request.includeStatic()));
            publish(stagedImage, image, request.force() && !managed);
            if (managed) {
                activate(command, entrypoint(image, name), target.moduleName(),
                        request.force());
            }
            return 0;
        } finally {
            deleteRecursively(staging);
        }
    }

    private void linkRuntime(
            ApplicationTarget target,
            List<String> launchArguments,
            InstallRequest request,
            Path output,
            InputStream in,
            PrintStream out,
            PrintStream err) {
        var arguments = new ArrayList<String>();
        arguments.add("--module-path");
        arguments.add(runtimeModulePath());
        Set<String> modules = new LinkedHashSet<>(runtimeModuleResolver.resolve(target, launchArguments, request.includeStatic()));
        Path jmods = Path.of(System.getProperty("java.home"), "jmods");
        if (Files.isRegularFile(jmods.resolve("openj9.sharedclasses.jmod")) || ModuleLayer.boot()
                .findModule("openj9.sharedclasses")
                .isPresent()) {
            modules.add("openj9.sharedclasses");
        }
        arguments.add("--add-modules");
        arguments.add(String.join(",", modules));
        arguments.add("--output");
        arguments.add(output.toString());
        if (!tools.contains("jlink")) {
            throw new IllegalArgumentException("Tool jlink is not installed");
        }
        int result = tools.run("jlink", in, out, err, arguments.toArray(String[]::new));
        if (result != 0) {
            throw new ToolExecutionException(result);
        }
    }

    private static String runtimeModulePath() {
        var paths = new LinkedHashSet<Path>();
        ModuleLayer.boot()
                .configuration()
                .findModule("com.netflix.tools.launcher")
                .flatMap(module -> module.reference().location())
                .filter(location -> location.getScheme().equals("file"))
                .map(Path::of)
                .ifPresent(paths::add);
        Path retained = Path.of(System.getProperty("java.home"), "lib", "ja", "modules");
        if (Files.isDirectory(retained)) {
            paths.add(retained);
        }
        Path jmods = Path.of(System.getProperty("java.home"), "jmods");
        if (paths.isEmpty() && Files.isDirectory(jmods)) {
            paths.add(jmods);
        }
        if (paths.isEmpty()) {
            throw new IllegalArgumentException("Application installation requires Launcher modules");
        }
        return paths.stream()
                .map(Path::toString)
                .collect(Collectors.joining(System.getProperty("path.separator")));
    }

    private static Set<String> runtimeModules(ApplicationTarget target, List<String> launchArguments, boolean includeStatic) {
        ModuleFinder applications = ModuleFinder.of(ToolArguments.applicationModulePath(launchArguments)
                .toArray(Path[]::new));
        ModuleFinder system = ModuleFinder.ofSystem();
        ModuleFinder launcher = launcherFinder(system);
        var roots = applicationRoots(target, launchArguments, applications, system, includeStatic);
        roots.add("com.netflix.tools.launcher");

        Configuration configuration = Configuration.empty().resolve(ModuleFinder.compose(applications, launcher, system),
                ModuleFinder.of(), roots);
        var linkableModules = new LinkedHashSet<ModuleReference>();
        linkableModules.addAll(system.findAll());
        linkableModules.addAll(launcher.findAll());
        var modules = new LinkedHashSet<String>();
        configuration.modules().stream()
                .filter(module -> linkableModules.contains(module.reference()))
                .map(module -> module.name())
                .sorted()
                .forEach(modules::add);
        if (configuration.modules().stream()
                .map(module -> module.reference().descriptor())
                .anyMatch(ModuleDescriptor::isAutomatic)) {
            modules.add("java.se");
        }
        return Collections.unmodifiableSet(modules);
    }

    static List<ModuleReference> applicationModules(ApplicationTarget target, List<String> launchArguments, boolean includeStatic) {
        ModuleFinder applications = ModuleFinder.of(ToolArguments.applicationModulePath(launchArguments)
                .toArray(Path[]::new));
        ModuleFinder system = ModuleFinder.ofSystem();
        ModuleFinder launcher = launcherFinder(system);
        var roots = applicationRoots(target, launchArguments, applications, system, includeStatic);
        Configuration configuration = Configuration.empty().resolve(ModuleFinder.compose(applications, launcher, system),
                ModuleFinder.of(), roots);
        return configuration.modules().stream()
                .filter(module -> applications.find(module.name())
                        .filter(module.reference()::equals)
                        .isPresent())
                .map(module -> module.reference())
                .sorted(Comparator.comparing(reference -> reference.descriptor().name()))
                .toList();
    }

    private static LinkedHashSet<String> applicationRoots(ApplicationTarget target, List<String> launchArguments, ModuleFinder applications,
            ModuleFinder system, boolean includeStatic) {
        var roots = new LinkedHashSet<String>();
        roots.add(target.moduleName());
        addLaunchRoots(roots, launchArguments, applications, system);
        if (!includeStatic) {
            return roots;
        }

        var pending = new ArrayDeque<>(roots);
        var visited = new LinkedHashSet<String>();
        while (!pending.isEmpty()) {
            String module = pending.removeFirst();
            if (!visited.add(module)) {
                continue;
            }
            applications.find(module).ifPresent(reference -> {
                for (var requirement : reference.descriptor().requires()) {
                    String dependency = requirement.name();
                    if (requirement.modifiers().contains(Modifier.STATIC)) {
                        roots.add(dependency);
                    }
                    if (applications.find(dependency).isPresent()) {
                        pending.addLast(dependency);
                    }
                }
            });
        }
        return roots;
    }

    private static ModuleFinder launcherFinder(ModuleFinder system) {
        if (system.find("com.netflix.tools.launcher").isPresent()) {
            return ModuleFinder.of();
        }
        return ModuleLayer.boot()
                .configuration()
                .findModule("com.netflix.tools.launcher")
                .flatMap(module -> module.reference().location())
                .filter(location -> location.getScheme().equals("file"))
                .map(Path::of)
                .map(path -> ModuleFinder.of(path))
                .orElseGet(ModuleFinder::of);
    }

    private static void addLaunchRoots(Set<String> roots, List<String> arguments, ModuleFinder applications,
            ModuleFinder system) {
        for (int i = 0; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            String value = null;
            if (argument.equals("--add-modules") && i + 1 < arguments.size()) {
                value = arguments.get(++i);
            } else if (argument.startsWith("--add-modules=")) {
                value = argument.substring("--add-modules=".length());
            }
            if (value == null) {
                continue;
            }
            for (String module : value.split(",")) {
                switch (module.strip()) {
                    case "ALL-MODULE-PATH" -> applications.findAll().stream()
                            .map(reference -> reference.descriptor().name())
                            .forEach(roots::add);
                    case "ALL-SYSTEM" -> system.findAll().stream()
                            .map(reference -> reference.descriptor().name())
                            .forEach(roots::add);
                    case "ALL-DEFAULT" -> throw new IllegalArgumentException("Managed installation does not support --add-modules=ALL-DEFAULT");
                    default -> roots.add(module.strip());
                }
            }
        }
    }

    private Path commandPath(String name) {
        return directories.commands().resolve(windows ? name + ".exe" : name);
    }

    private Path currentPath(Path command) {
        String name = command.getFileName().toString();
        if (windows && name.toLowerCase(Locale.ROOT).endsWith(".exe")) {
            name = name.substring(0, name.length() - ".exe".length());
        }
        return command.getParent().resolve(name + ".current");
    }

    private Path entrypoint(Path image, String name) {
        return image.resolve("bin").resolve(windows ? name + ".exe" : name);
    }

    private void activate(Path command, Path entrypoint, String moduleName,
                          boolean force)
            throws IOException {
        checkCommandConflict(command, moduleName, force);
        Path current = currentPath(command);
        if (!Files.exists(command, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(command) || requestLauncherReplacement(current, force)) {
            Path temporary = directories.commands().resolve(".ja-launcher-" + Long.toUnsignedString(System.nanoTime()) + (windows ? ".exe" : ""));
            try {
                LauncherCatalog.copyDispatcher(LauncherCatalog.currentPlatform(), temporary);
                move(temporary, command, true);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }

        Path temporary = directories.commands().resolve(".ja-current-" + Long.toUnsignedString(System.nanoTime()));
        try {
            Files.writeString(temporary, entrypoint.toAbsolutePath().normalize() + System.lineSeparator(), StandardCharsets.UTF_8);
            Files.move(temporary, current, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void checkCommandConflict(Path command, String moduleName, boolean force) throws IOException {
        if (!Files.exists(command, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path target;
        if (Files.isSymbolicLink(command)) {
            target = command.getParent()
                            .resolve(Files.readSymbolicLink(command))
                            .normalize();
        } else {
            Path current = currentPath(command);
            if (!Files.isRegularFile(current)) {
                if (force) {
                    return;
                }
                throw new FileAlreadyExistsException("Launcher already exists: " + command);
            }
            String value = Files.readString(current, StandardCharsets.UTF_8).strip();
            if (value.isEmpty()) {
                throw new FileAlreadyExistsException("Launcher has no target: " + command);
            }
            Path configured = Path.of(value);
            target = (configured.isAbsolute() ? configured : command.getParent().resolve(configured)).normalize();
        }
        if (!target.startsWith(directories.applications())) {
            if (force) {
                return;
            }
            throw new FileAlreadyExistsException("Launcher already exists: " + command);
        }
        Path relative;
        try {
            relative = directories.applications().relativize(target);
        } catch (IllegalArgumentException e) {
            if (force) {
                return;
            }
            throw new FileAlreadyExistsException("Launcher is not managed by ja: " + command);
        }
        if (relative.getNameCount() == 0) {
            if (force) {
                return;
            }
            throw new FileAlreadyExistsException("Launcher is owned by another module: " + command);
        }
        String installation = relative.getName(0).toString();
        if (!installation.equals(moduleName) && !installation.startsWith(moduleName + "@")) {
            if (force) {
                return;
            }
            throw new FileAlreadyExistsException("Launcher is owned by another module: " + command);
        }
    }

    private static boolean requestLauncherReplacement(Path current, boolean force) {
        return force && !Files.isRegularFile(current);
    }

    private void checkWindowsCommandConflicts(String name, boolean force) throws IOException {
        if (!windows) {
            return;
        }
        for (String extension : List.of(".com", ".bat", ".cmd")) {
            Path candidate = directories.commands().resolve(name + extension);
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            if (!force) {
                throw new FileAlreadyExistsException("Launcher name is already present in PATH: " + candidate);
            }
            deleteRecursively(candidate);
        }
    }

    private static void publish(Path staged, Path destination, boolean replace) throws IOException {
        if (!replace || !Files.exists(destination)) {
            move(staged, destination, false);
            return;
        }
        Path backup = destination.resolveSibling("." + destination.getFileName() + ".old-" + Long.toUnsignedString(System.nanoTime()));
        move(destination, backup, false);
        try {
            move(staged, destination, false);
        } catch (IOException failure) {
            move(backup, destination, false);
            throw failure;
        }
    }

    private static void move(Path source, Path destination, boolean replace) throws IOException {
        var options = replace ? new StandardCopyOption[] {StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING} : new StandardCopyOption[] {StandardCopyOption.ATOMIC_MOVE};
        try {
            Files.move(source, destination, options);
        } catch (AtomicMoveNotSupportedException e) {
            if (replace) {
                Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, destination);
            }
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path value : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(value);
            }
        }
    }

    private static String defaultName(String moduleName) {
        int separator = moduleName.lastIndexOf('.');
        return separator < 0 ? moduleName : moduleName.substring(separator + 1);
    }

    private static void validateName(String name) {
        if (!Pattern.matches("[A-Za-z0-9][A-Za-z0-9._-]*", name) || name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException("Invalid launcher name: " + name);
        }
    }

    private static String encodePathComponent(String value) {
        var encoded = new StringBuilder();
        for (byte element : value.getBytes(StandardCharsets.UTF_8)) {
            int character = Byte.toUnsignedInt(element);
            if (character >= 'A' && character <= 'Z'
                    || character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9'
                    || character == '.'
                    || character == '_'
                    || character == '+'
                    || character == '-') {
                encoded.append((char) character);
            } else {
                encoded.append('%');
                encoded.append(Character.forDigit(character >>> 4, 16));
                encoded.append(Character.forDigit(character & 0xf, 16));
            }
        }
        return encoded.toString();
    }

    private static String namePart(Path command) {
        return command.getFileName()
                      .toString()
                      .replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
