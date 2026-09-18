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
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Generates the small provider module that adapts an installed application to
 * the tool launcher.
 */
public final class InstalledCommandModule {
    static final String PROVIDER_CLASS = "com.netflix.tools.launcher.command.MainTool";

    public record Generated(String moduleName, Path classes, Optional<String> mainPackage,
                            boolean warmup) {
        public Generated(String moduleName, Path classes, Optional<String> mainPackage) {
            this(moduleName, classes, mainPackage, false);
        }
    }

    private InstalledCommandModule() {}

    public static Generated generate(
            String commandName,
            String targetModule,
            String mainClass,
            List<Path> applicationModulePath,
            Path output,
            ToolRuntime tools,
            InputStream in,
            PrintStream out,
            PrintStream err)
            throws IOException {
        return generate(commandName, targetModule, mainClass, Optional.empty(), applicationModulePath,
                output, tools, in, out, err);
    }

    public static Generated generate(
            String commandName,
            String targetModule,
            String mainClass,
            Optional<String> version,
            List<Path> applicationModulePath,
            Path output,
            ToolRuntime tools,
            InputStream in,
            PrintStream out,
            PrintStream err)
            throws IOException {
        return generate(
                commandName,
                targetModule,
                Optional.of(mainClass),
                version,
                false,
                applicationModulePath,
                output,
                tools,
                in,
                out,
                err);
    }

    static Generated generateAnchor(
            String targetModule,
            Optional<String> version,
            boolean warmup,
            List<Path> applicationModulePath,
            Path output,
            ToolRuntime tools,
            InputStream in,
            PrintStream out,
            PrintStream err)
            throws IOException {
        return generate(
                null,
                targetModule,
                Optional.empty(),
                version,
                warmup,
                applicationModulePath,
                output,
                tools,
                in,
                out,
                err);
    }

    private static Generated generate(
            String commandName,
            String targetModule,
            Optional<String> mainClass,
            Optional<String> version,
            boolean warmup,
            List<Path> applicationModulePath,
            Path output,
            ToolRuntime tools,
            InputStream in,
            PrintStream out,
            PrintStream err)
            throws IOException {
        mainClass.ifPresent(_ -> validateCommandName(commandName));
        ModuleDescriptor.newModule(targetModule).build();
        String commandModule = moduleName(targetModule);
        if (ModuleFinder.of(applicationModulePath.toArray(Path[]::new))
                .find(commandModule)
                .isPresent()) {
            throw new IllegalArgumentException("Application module path already contains " + commandModule);
        }
        Optional<String> mainPackage = mainClass.map(value -> {
            int packageSeparator = value.lastIndexOf('.');
            if (packageSeparator <= 0 || packageSeparator == value.length() - 1) {
                throw new IllegalArgumentException("Main class must be in a named package: " + value);
            }
            return value.substring(0, packageSeparator);
        });

        Path source = Files.createDirectories(output.resolve("source"));
        Path classes = Files.createDirectories(output.resolve("classes"));
        Path moduleInfo = source.resolve("module-info.java");
        Files.writeString(moduleInfo, moduleInfo(commandModule, targetModule, mainClass.isPresent()), StandardCharsets.UTF_8);
        List<Path> sources;
        if (mainClass.isPresent()) {
            Path packageDirectory = Files.createDirectories(source.resolve("com/netflix/tools/launcher/command"));
            Path provider = packageDirectory.resolve("MainTool.java");
            Files.writeString(provider, providerSource(commandName, targetModule, mainClass.orElseThrow()), StandardCharsets.UTF_8);
            sources = List.of(moduleInfo, provider);
        } else {
            sources = List.of(moduleInfo);
        }

        var compilerArguments = new ArrayList<String>();
        launcherPath().ifPresent(path -> {
            compilerArguments.add("--upgrade-module-path");
            compilerArguments.add(path.toString());
        });
        if (!applicationModulePath.isEmpty()) {
            compilerArguments.add("--module-path");
            compilerArguments.add(joinPaths(applicationModulePath));
        }
        version.ifPresent(value -> {
            compilerArguments.add("--module-version");
            compilerArguments.add(value);
        });
        compilerArguments.add("-d");
        compilerArguments.add(classes.toString());
        sources.stream()
                .map(Path::toString)
                .forEach(compilerArguments::add);
        int result = tools.run("javac", in, out, err, compilerArguments.toArray(String[]::new));
        if (result != 0) {
            throw new ToolExecutionException(result);
        }
        boolean automaticTarget = ModuleFinder.of(applicationModulePath.toArray(Path[]::new))
                .find(targetModule)
                .orElseThrow(() -> new IllegalArgumentException("Target module is not on the application module path: " + targetModule))
                .descriptor()
                .isAutomatic();
        return new Generated(commandModule, classes, automaticTarget ? Optional.empty() : mainPackage,
                warmup);
    }

    public static String moduleName(String targetModule) {
        ModuleDescriptor.newModule(targetModule).build();
        String name = targetModule + ".launcher";
        ModuleDescriptor.newModule(name).build();
        return name;
    }

    private static Optional<Path> launcherPath() {
        return ModuleLayer.boot()
                .configuration()
                .findModule("com.netflix.tools.launcher")
                .flatMap(module -> module.reference().location())
                .filter(location -> location.getScheme().equals("file"))
                .map(Path::of);
    }

    private static String joinPaths(List<Path> paths) {
        return paths.stream()
                .map(Path::toString)
                .collect(Collectors.joining(System.getProperty("path.separator")));
    }

    private static String moduleInfo(String commandModule, String targetModule, boolean provider) {
        String service =
                provider
                        ? """

                            provides javax.tools.Tool
                                with com.netflix.tools.launcher.command.MainTool;
                        """
                        : "";
        return """
        module %s {
            requires com.netflix.tools.launcher;
            requires %s;%s
        }
        """
                .formatted(commandModule, targetModule, service);
    }

    private static String providerSource(String commandName, String targetModule, String mainClass) {
        return """
        package com.netflix.tools.launcher.command;

        import java.io.InputStream;
        import java.io.OutputStream;
        import java.io.PrintStream;
        import java.lang.reflect.InvocationTargetException;
        import java.nio.charset.StandardCharsets;
        import java.util.Set;
        import javax.lang.model.SourceVersion;
        import javax.tools.Tool;

        public final class MainTool implements Tool {
            @Override
            public String name() {
                return %s;
            }

            @Override
            public Set<SourceVersion> getSourceVersions() {
                return Set.of();
            }

            @Override
            public int run(
                    InputStream in,
                    OutputStream out,
                    OutputStream err,
                    String... arguments) {
                InputStream actualIn = in == null ? System.in : in;
                PrintStream actualOut = printStream(out, System.out);
                PrintStream actualErr = printStream(err, System.err);
                synchronized (MainTool.class) {
                    InputStream previousIn = System.in;
                    PrintStream previousOut = System.out;
                    PrintStream previousErr = System.err;
                    try {
                        System.setIn(actualIn);
                        System.setOut(actualOut);
                        System.setErr(actualErr);
                        ModuleLayer layer = MainTool.class.getModule().getLayer();
                        Module target = layer.findModule(%s).orElseThrow();
                        Class<?> type = Class.forName(target, %s);
                        if (type == null) throw new ClassNotFoundException(%s);
                        var main = type.getDeclaredMethod("main", String[].class);
                        main.setAccessible(true);
                        main.invoke(null, (Object) arguments);
                        return 0;
                    } catch (InvocationTargetException failure) {
                        Throwable cause = failure.getCause();
                        if (cause instanceof RuntimeException runtime) throw runtime;
                        if (cause instanceof Error error) throw error;
                        throw new RuntimeException(cause);
                    } catch (ReflectiveOperationException failure) {
                        throw new RuntimeException(failure);
                    } finally {
                        actualOut.flush();
                        actualErr.flush();
                        System.setIn(previousIn);
                        System.setOut(previousOut);
                        System.setErr(previousErr);
                    }
                }
            }

            private static PrintStream printStream(
                    OutputStream requested,
                    PrintStream fallback) {
                if (requested == null) return fallback;
                if (requested instanceof PrintStream stream) return stream;
                return new PrintStream(requested, true, StandardCharsets.UTF_8);
            }
        }
        """
                .formatted(javaString(commandName), javaString(targetModule), javaString(mainClass),
                        javaString(mainClass));
    }

    private static String javaString(String value) {
        var result = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '\\' -> result.append("\\\\");
                case '"' -> result.append("\\\"");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> result.append(character);
            }
        }
        return result.append('"').toString();
    }

    private static void validateCommandName(String name) {
        if (!Pattern.matches("[A-Za-z0-9][A-Za-z0-9._-]*", name) || name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException("Invalid launcher name: " + name);
        }
    }
}
