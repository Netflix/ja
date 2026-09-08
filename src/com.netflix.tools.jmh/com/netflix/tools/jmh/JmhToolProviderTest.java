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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.module.ResolvedModule;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.spi.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JmhToolProviderTest {
    private static final String BENCHMARK = "com.example.benchmark.ExampleBenchmark.answer";

    @Test
    public void discoversAndRunsAModularBenchmark(@TempDir Path temporaryDirectory) throws Exception {
        Path modules = compileBenchmark(temporaryDirectory);
        String modulePath = modules + System.getProperty("path.separator") + runtimeModulePath();
        ToolProvider jmh = new JmhToolProvider();

        var listingOutput = new StringWriter();
        var listingError = new StringWriter();
        int listingResult = jmh.run(new PrintWriter(listingOutput, true), new PrintWriter(listingError, true), "--module-path", modulePath,
                "--add-modules", "com.example.benchmark,com.netflix.tools.jmh", "-l");

        assertEquals(0, listingResult, listingError::toString);
        assertTrue(listingOutput.toString()
                                .lines()
                                .anyMatch(BENCHMARK::equals));

        var benchmarkOutput = new StringWriter();
        var benchmarkError = new StringWriter();
        int benchmarkResult = jmh.run(
                new PrintWriter(benchmarkOutput, true),
                new PrintWriter(benchmarkError, true),
                "--module-path",
                modulePath,
                "--add-modules",
                "com.example.benchmark,com.netflix.tools.jmh",
                "ExampleBenchmark.answer",
                "-f",
                "1",
                "-wi",
                "0",
                "-i",
                "1",
                "-r",
                "10ms");

        assertEquals(0, benchmarkResult, benchmarkError::toString);
        assertTrue(benchmarkOutput.toString()
                .contains(BENCHMARK));
    }

    @Test
    public void processesOnlyRootsActivatedForBenchmarking(@TempDir Path temporaryDirectory) throws Exception {
        Path modules = compileBenchmark(temporaryDirectory);
        compileApplication(temporaryDirectory, modules);
        String modulePath = modules + System.getProperty("path.separator") + runtimeModulePath();
        ToolProvider jmh = new JmhToolProvider();
        var output = new StringWriter();
        var error = new StringWriter();

        int result = jmh.run(new PrintWriter(output, true), new PrintWriter(error, true), "--module-path", modulePath,
                "--add-modules", "com.example.application,com.example.benchmark,com.netflix.tools.jmh", "-l");

        assertEquals(0, result, error::toString);
        assertTrue(output.toString()
                         .contains("com.example.benchmark"));
        assertFalse(output.toString()
                          .contains("com.example.application"));
    }

    private static Path compileBenchmark(Path temporaryDirectory) throws Exception {
        Path source = temporaryDirectory.resolve("src");
        Path packageDirectory = Files.createDirectories(source.resolve("com.example.benchmark/com/example/benchmark"));
        Files.writeString(source.resolve("com.example.benchmark/module-info.java"),
                """
                module com.example.benchmark {
                    requires static org.openjdk.jmh.core;
                }
                """);
        Files.writeString(packageDirectory.resolve("ExampleBenchmark.java"),
                """
                package com.example.benchmark;

                import org.openjdk.jmh.annotations.Benchmark;

                public class ExampleBenchmark {
                    @Benchmark
                    public int answer() {
                        return 42;
                    }
                }
                """);

        Path modules = temporaryDirectory.resolve("modules");
        var output = new StringWriter();
        ToolProvider javac = ToolProvider.findFirst("javac").orElseThrow();
        int result = javac.run(
                new PrintWriter(output, true),
                new PrintWriter(output, true),
                "--module-path",
                runtimeModulePath(),
                "--module-source-path",
                source.toString(),
                "-d",
                modules.toString(),
                "--module",
                "com.example.benchmark");
        assertEquals(0, result, output::toString);
        return modules;
    }

    private static void compileApplication(Path temporaryDirectory, Path modules) throws Exception {
        Path source = temporaryDirectory.resolve("application-source");
        Path packageDirectory = Files.createDirectories(source.resolve("com.example.application/com/example/application"));
        Files.writeString(source.resolve("com.example.application/module-info.java"), "module com.example.application {}\n");
        Files.writeString(packageDirectory.resolve("Main.java"),
                """
                package com.example.application;

                public final class Main {}
                """);

        var output = new StringWriter();
        ToolProvider javac = ToolProvider.findFirst("javac").orElseThrow();
        int result = javac.run(
                new PrintWriter(output, true),
                new PrintWriter(output, true),
                "--module-source-path",
                source.toString(),
                "-d",
                modules.toString(),
                "--module",
                "com.example.application");
        assertEquals(0, result, output::toString);
    }

    private static String runtimeModulePath() {
        var paths = new LinkedHashSet<Path>();
        addModulePaths(JmhToolProviderTest.class.getModule().getLayer(), paths,
                new LinkedHashSet<>());
        return String.join(System.getProperty("path.separator"),
                paths.stream()
                        .map(Path::toString)
                        .toList());
    }

    private static void addModulePaths(ModuleLayer layer, LinkedHashSet<Path> paths, LinkedHashSet<ModuleLayer> visited) {
        if (!visited.add(layer)) {
            return;
        }
        layer.configuration().modules().stream()
                .map(ResolvedModule::reference)
                .map(reference -> reference.location().orElse(null))
                .filter(location -> location != null && location.getScheme().equals("file"))
                .map(JmhToolProviderTest::path)
                .forEach(paths::add);
        layer.parents().forEach(parent -> addModulePaths(parent, paths, visited));
    }

    private static Path path(URI location) {
        return Path.of(location);
    }
}
