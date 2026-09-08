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

package com.netflix.tools.jmh;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Serial;
import java.lang.ProcessBuilder.Redirect;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.spi.ToolProvider;
import java.util.stream.Collectors;
import javax.tools.OptionChecker;

public final class JmhToolProvider implements ToolProvider, OptionChecker {
    private static final String PROVIDER_MODULE = "com.netflix.tools.jmh";
    private static final String CORE_MODULE = "org.openjdk.jmh.core";
    private static final String GENERATOR_MODULE = "org.openjdk.jmh.generator.bytecode";
    private static final String GENERATOR_MAIN = "org.openjdk.jmh.generators.bytecode.JmhBytecodeGenerator";
    private static final String RUNNER_MAIN = "org.openjdk.jmh.Main";
    private static final String JDK_UNSUPPORTED = "jdk.unsupported";
    private static final Set<String> TOOL_MODULES = Set.of(
            PROVIDER_MODULE,
            "jopt.simple",
            "org.apache.commons.math3",
            "org.objectweb.asm",
            "org.openjdk.jmh.core",
            "org.openjdk.jmh.generator.asm",
            "org.openjdk.jmh.generator.bytecode",
            "org.openjdk.jmh.generator.reflection");
    private static final Map<String, Integer> JAVA_OPTIONS = Map.ofEntries(
            Map.entry("--module-path", 1),
            Map.entry("-p", 1),
            Map.entry("--upgrade-module-path", 1),
            Map.entry("--patch-module", 1),
            Map.entry("--add-modules", 1),
            Map.entry("--add-opens", 1),
            Map.entry("--add-exports", 1),
            Map.entry("--enable-native-access", 1),
            Map.entry("--enable-final-field-mutation", 1),
            Map.entry("--enable-preview", 0));

    @Override
    public String name() {
        return "jmh";
    }

    @Override
    public int isSupportedOption(String option) {
        return JAVA_OPTIONS.getOrDefault(option, -1);
    }

    @Override
    public int run(PrintWriter out, PrintWriter err, String... args) {
        try {
            return run(Invocation.parse(args), out, err);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            err.println("jmh: interrupted");
            return 1;
        } catch (Exception failure) {
            err.println("jmh: " + failure.getMessage());
            return 1;
        }
    }

    private int run(Invocation invocation, PrintWriter out, PrintWriter err) throws IOException, InterruptedException {
        List<Path> toolPaths = toolPaths();
        List<Path> modulePath = distinct(invocation.modulePath(), toolPaths);
        List<String> roots = invocation.modules().stream()
                .filter(module -> !TOOL_MODULES.contains(module))
                .filter(module -> moduleLocation(module, invocation).isPresent())
                .filter(module -> activatesBenchmarks(module, invocation))
                .toList();
        if (roots.isEmpty()) {
            throw new IllegalArgumentException("no benchmark modules were selected");
        }

        Path temporary = Files.createTempDirectory("jmh-");
        try {
            var patches = new ArrayList<Patch>();
            for (String module : roots) {
                Path output = temporary.resolve(module);
                Path bytecode = Files.createDirectories(output.resolve("bytecode"));
                copyModule(moduleLocation(module, invocation).orElseThrow(), bytecode);
                for (Path patch : invocation.patches().getOrDefault(module, List.of())) {
                    copyModule(patch, bytecode);
                }
                patches.add(generate(module, bytecode, output, invocation, modulePath, roots,
                        out, err));
            }
            return launch(invocation, modulePath, roots, patches, out, err);
        } finally {
            deleteTree(temporary);
        }
    }

    private Patch generate(
            String module,
            Path bytecode,
            Path output,
            Invocation invocation,
            List<Path> modulePath,
            List<String> applicationModules,
            PrintWriter out,
            PrintWriter err)
            throws IOException, InterruptedException {
        Path sources = Files.createDirectories(output.resolve("sources"));
        Path resources = Files.createDirectories(output.resolve("resources"));
        Path classes = Files.createDirectories(output.resolve("classes"));

        var generator = javaArguments(invocation, modulePath, invocation.patches());
        generator.add("--add-modules");
        generator.add(addModules(applicationModules, List.of(JmhToolProvider.class
                .getModule()
                .getName(),
                JDK_UNSUPPORTED)));
        generator.add("--module");
        generator.add(GENERATOR_MODULE + "/" + GENERATOR_MAIN);
        generator.add(bytecode.toString());
        generator.add(sources.toString());
        generator.add(resources.toString());
        int generated = runJava(generator, out, err);
        if (generated != 0) {
            throw new ToolFailure(generated);
        }

        List<Path> sourceFiles = javaFiles(sources);
        if (!sourceFiles.isEmpty()) {
            var compiler = new ArrayList<String>();
            addPaths(compiler, "--upgrade-module-path", invocation.upgradeModulePath());
            addPaths(compiler, "--module-path", modulePath);
            compiler.add("--add-modules");
            compiler.add(addModules(applicationModules, List.of(JmhToolProvider.class
                    .getModule()
                    .getName())));
            compiler.add("--patch-module");
            compiler.add(module + "=" + sources);
            compiler.add("-proc:none");
            compiler.add("-d");
            compiler.add(classes.toString());
            sourceFiles.stream()
                    .map(Path::toString)
                    .forEach(compiler::add);
            ToolProvider javac = ToolProvider.findFirst("javac").orElseThrow();
            int compiled = javac.run(out, err, compiler.toArray(String[]::new));
            if (compiled != 0) {
                throw new ToolFailure(compiled);
            }
        }
        copyTree(resources, classes);
        return new Patch(module, classes, generatedPackages(sources));
    }

    private int launch(Invocation invocation, List<Path> modulePath, List<String> applicationModules,
                       List<Patch> generated, PrintWriter out, PrintWriter err)
            throws IOException, InterruptedException {
        var patches = new LinkedHashMap<String, List<Path>>();
        invocation.patches().forEach((module, paths) -> patches.put(module, new ArrayList<>(paths)));
        for (Patch patch : generated) {
            patches.computeIfAbsent(patch.module(), _ -> new ArrayList<>()).add(patch.classes());
        }

        var runner = javaArguments(invocation, modulePath, patches);
        runner.add("--add-modules");
        runner.add(addModules(applicationModules, List.of(JmhToolProvider.class
                .getModule()
                .getName(),
                JDK_UNSUPPORTED)));
        for (Patch patch : generated) {
            for (String packageName : patch.packages()) {
                runner.add("--add-opens");
                runner.add(patch.module() + "/" + packageName + "=" + CORE_MODULE);
            }
        }
        runner.add("--module");
        runner.add(CORE_MODULE + "/" + RUNNER_MAIN);
        runner.addAll(invocation.nativeArguments());
        return runJava(runner, out, err);
    }

    private static ArrayList<String> javaArguments(Invocation invocation, List<Path> modulePath,
            Map<String, ? extends List<Path>> patches) {
        var arguments = new ArrayList<String>();
        arguments.addAll(invocation.javaArguments());
        addPaths(arguments, "--upgrade-module-path", invocation.upgradeModulePath());
        addPaths(arguments, "--module-path", modulePath);
        patches.forEach((module, paths) -> {
            arguments.add("--patch-module");
            arguments.add(module + "=" + joinPaths(paths));
        });
        return arguments;
    }

    private static int runJava(List<String> arguments, PrintWriter out, PrintWriter err) throws IOException, InterruptedException {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java")
                .toString());
        command.addAll(arguments);
        Process process = new ProcessBuilder(command).redirectInput(Redirect.INHERIT).start();
        Thread stdout = copy(process.getInputStream(), out);
        Thread stderr = copy(process.getErrorStream(), err);
        int exitCode = process.waitFor();
        stdout.join();
        stderr.join();
        return exitCode;
    }

    private static Thread copy(InputStream input, PrintWriter output) {
        return Thread.ofVirtual().start(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                reader.lines().forEach(output::println);
            } catch (IOException failure) {
                output.println("jmh: " + failure.getMessage());
            }
        });
    }

    private static boolean activatesBenchmarks(String module, Invocation invocation) {
        for (Path path : distinct(invocation.upgradeModulePath(), invocation.modulePath())) {
            Optional<ModuleReference> reference = ModuleFinder.of(path).find(module);
            if (reference.isPresent() && reference.orElseThrow().descriptor().requires().stream()
                    .anyMatch(require -> require.name().equals(CORE_MODULE))) {
                return true;
            }
        }
        return false;
    }

    private static Optional<Path> moduleLocation(String module, Invocation invocation) {
        for (Path path : distinct(invocation.upgradeModulePath(), invocation.modulePath())) {
            Optional<ModuleReference> reference = ModuleFinder.of(path).find(module);
            if (reference.isEmpty()) {
                continue;
            }
            Optional<URI> location = reference.orElseThrow().location();
            if (location.isPresent() && location.orElseThrow()
                    .getScheme()
                    .equals("file")) {
                return location.map(Path::of);
            }
        }
        return Optional.empty();
    }

    private static List<Path> toolPaths() {
        ModuleLayer layer = JmhToolProvider.class.getModule().getLayer();
        if (layer == null) {
            return List.of();
        }
        Configuration configuration = layer.configuration();
        var paths = new LinkedHashSet<Path>();
        TOOL_MODULES.stream()
                .map(configuration::findModule)
                .flatMap(Optional::stream)
                .map(resolved -> resolved.reference().location())
                .flatMap(Optional::stream)
                .filter(location -> location.getScheme().equals("file"))
                .map(Path::of)
                .forEach(paths::add);
        return List.copyOf(paths);
    }

    private static void copyModule(Path source, Path destination) throws IOException {
        if (Files.isDirectory(source)) {
            copyBytecode(source, destination);
            return;
        }
        try (FileSystem archive = FileSystems.newFileSystem(source)) {
            copyBytecode(archive.getPath("/"), destination);
        }
    }

    private static void copyBytecode(Path source, Path destination) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path input : paths.filter(Files::isRegularFile)
                                   .filter(path -> !path.getFileName()
                                                        .toString()
                                                        .equals("module-info.class"))
                                   .toList()) {
                copy(source, destination, input);
            }
        }
    }

    private static void copyTree(Path source, Path destination) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path input : paths.filter(Files::isRegularFile).toList()) {
                copy(source, destination, input);
            }
        }
    }

    private static void copy(Path source, Path destination, Path input) throws IOException {
        Path relative = source.relativize(input);
        if (relative.toString().isEmpty()) {
            return;
        }
        Path output = destination.resolve(relative.toString());
        Files.createDirectories(output.getParent());
        Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
    }

    private static List<Path> javaFiles(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName()
                                            .toString()
                                            .endsWith(".java"))
                        .sorted()
                        .toList();
        }
    }

    private static Set<String> generatedPackages(Path root) throws IOException {
        var packages = new LinkedHashSet<String>();
        for (Path source : javaFiles(root)) {
            Path parent = source.getParent();
            if (parent != null && parent.getFileName()
                    .toString()
                    .equals("jmh_generated")) {
                packages.add(root.relativize(parent)
                                 .toString()
                                 .replace(root.getFileSystem()
                                              .getSeparator(),
                                         "."));
            }
        }
        return Set.copyOf(packages);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void addPaths(List<String> arguments, String option, List<Path> paths) {
        if (paths.isEmpty()) {
            return;
        }
        arguments.add(option);
        arguments.add(joinPaths(paths));
    }

    private static String joinPaths(List<Path> paths) {
        return paths.stream()
                .map(Path::toString)
                .collect(Collectors.joining(System.getProperty("path.separator")));
    }

    @SafeVarargs
    private static List<Path> distinct(List<Path>... pathLists) {
        var paths = new LinkedHashSet<Path>();
        for (List<Path> pathList : pathLists) {
            paths.addAll(pathList);
        }
        return List.copyOf(paths);
    }

    private static String addModules(List<String> modules, List<String> additions) {
        var result = new LinkedHashSet<>(modules);
        result.addAll(additions);
        return String.join(",", result);
    }

    private record Patch(String module, Path classes, Set<String> packages) {
        Patch {
            packages = Set.copyOf(packages);
        }
    }

    private record Invocation(List<String> javaArguments, List<Path> modulePath, List<Path> upgradeModulePath,
            Map<String, List<Path>> patches, List<String> modules, List<String> nativeArguments) {
        Invocation {
            javaArguments = List.copyOf(javaArguments);
            modulePath = List.copyOf(modulePath);
            upgradeModulePath = List.copyOf(upgradeModulePath);
            patches = Map.copyOf(patches);
            modules = List.copyOf(modules);
            nativeArguments = List.copyOf(nativeArguments);
        }

        static Invocation parse(String[] arguments) {
            var javaArguments = new ArrayList<String>();
            var modulePath = new ArrayList<Path>();
            var upgradeModulePath = new ArrayList<Path>();
            var patches = new LinkedHashMap<String, List<Path>>();
            var modules = new ArrayList<String>();
            int index = 0;
            for (; index < arguments.length; index++) {
                String argument = arguments[index];
                if (argument.equals("--")) {
                    index++;
                    break;
                }
                String option = argument;
                String value = null;
                int separator = argument.indexOf('=');
                if (separator > 0) {
                    option = argument.substring(0, separator);
                    value = argument.substring(separator + 1);
                }
                Integer operands = JAVA_OPTIONS.get(option);
                if (operands == null) {
                    break;
                }
                if (operands == 1 && value == null) {
                    if (++index >= arguments.length) {
                        throw new IllegalArgumentException(option + " requires a value");
                    }
                    value = arguments[index];
                }
                switch (option) {
                    case "--module-path", "-p" -> addPath(modulePath, value);
                    case "--upgrade-module-path" -> addPath(upgradeModulePath, value);
                    case "--patch-module" -> addPatch(patches, value);
                    case "--add-modules" -> addModules(modules, value);
                    default -> {
                        javaArguments.add(option);
                        if (value != null) {
                            javaArguments.add(value);
                        }
                    }
                }
            }
            return new Invocation(javaArguments, modulePath, upgradeModulePath, patches, modules,
                    List.of(arguments).subList(index, arguments.length));
        }

        private static void addPath(List<Path> paths, String value) {
            for (String path : value.split(Pattern.quote(System.getProperty("path.separator")))) {
                if (!path.isEmpty()) {
                    paths.add(Path.of(path));
                }
            }
        }

        private static void addPatch(Map<String, List<Path>> patches, String value) {
            int separator = value.indexOf('=');
            if (separator < 1) {
                throw new IllegalArgumentException("--patch-module requires module=path");
            }
            var paths = patches.computeIfAbsent(value.substring(0, separator), _ -> new ArrayList<>());
            addPath(paths, value.substring(separator + 1));
        }

        private static void addModules(List<String> modules, String value) {
            for (String module : value.split(",")) {
                if (!module.isBlank()) {
                    modules.add(module.strip());
                }
            }
        }
    }

    private static final class ToolFailure extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        ToolFailure(int exitCode) {
            super("tool exited with status " + exitCode);
        }
    }
}
