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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.spi.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        assertTrue(output.toString().contains("com.example.benchmark"));
        assertFalse(output.toString().contains("com.example.application"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    public void failsWhenBenchmarkSetupFails(int warmupIterations, @TempDir Path temporaryDirectory) throws Exception {
        assertNotEquals(0, runFailingBenchmark(temporaryDirectory, warmupIterations, List.of()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-foe false", "-foe=false", "--foe false", "--foe=false"})
    public void allowsExplicitFailOnErrorOverride(String option, @TempDir Path temporaryDirectory) throws Exception {
        assertEquals(0, runFailingBenchmark(temporaryDirectory, 0, List.of(option.split(" "))));
    }

    private static int runFailingBenchmark(Path temporaryDirectory, int warmupIterations, List<String> options) throws Exception {
        Path modules = compileBenchmark(temporaryDirectory,
                """
                @org.openjdk.jmh.annotations.Setup
                public void setup() {
                    throw new IllegalStateException("benchmark setup failed");
                }
                """);
        String modulePath = modules + System.getProperty("path.separator") + runtimeModulePath();
        var output = new StringWriter();
        var error = new StringWriter();

        var arguments = new ArrayList<>(
                List.of(
                        "--module-path",
                        modulePath,
                        "--add-modules",
                        "com.example.benchmark,com.netflix.tools.jmh",
                        "ExampleBenchmark.answer",
                        "-f",
                        "1",
                        "-wi",
                        Integer.toString(warmupIterations),
                        "-w",
                        "10ms",
                        "-i",
                        "1",
                        "-r",
                        "10ms"));
        arguments.addAll(options);
        int result = new JmhToolProvider().run(new PrintWriter(output, true), new PrintWriter(error, true), arguments.toArray(String[]::new));

        assertTrue(output.toString().contains("benchmark setup failed"),
                () -> output + "\n" + error);
        return result;
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "com.example.benchmark.ExampleBenchmark", BENCHMARK, "ExampleBenchmark", "ExampleBenchmark.answer"})
    public void checkRunsDiscoveredSelections(String selector, @TempDir Path temporaryDirectory) throws Exception {
        Path modules = compileBenchmark(temporaryDirectory);
        var arguments = new ArrayList<>(List.of("--check"));
        if (!selector.isEmpty()) {
            arguments.add(selector);
        }
        var output = new StringWriter();
        var error = new StringWriter();

        assertEquals(0, runBench(modules, output, error, arguments), () -> output + "\n" + error);
        assertTrue(output.toString().contains("Checked " + BENCHMARK), output::toString);
        assertTrue(output.toString().contains("not performance measurements"), output::toString);
        assertFalse(output.toString().contains("ops/s"), output::toString);
        assertFalse(output.toString().contains("Score"), output::toString);
        assertFalse(output.toString().contains("# Measurement:"), output::toString);
        assertEquals(
                1,
                output.toString()
                      .lines()
                      .filter(line -> line.startsWith("Processing "))
                      .count(),
                output::toString);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MissingBenchmark", "ExampleBenchmark.missing", "ExampleBenchmark.*", "--unknown"})
    public void rejectsUnknownSelectionsBeforeRunning(String selector, @TempDir Path temporaryDirectory) throws Exception {
        Path modules = compileBenchmark(temporaryDirectory);
        var output = new StringWriter();
        var error = new StringWriter();

        assertNotEquals(0, runBench(modules, output, error, List.of("--check", BENCHMARK, selector)));
        assertTrue(error.toString().contains(selector),
                error::toString);
        assertFalse(output.toString().contains("# Benchmark:"),
                output::toString);
    }

    @Test
    public void normalRunsRespectBenchmarkAnnotations(@TempDir Path temporaryDirectory) throws Exception {
        Path modules = compileBenchmark(temporaryDirectory);
        var output = new StringWriter();
        var error = new StringWriter();

        assertEquals(0, runBench(modules, output, error, List.of(BENCHMARK)), () -> output + "\n" + error);
        assertTrue(output.toString().contains("# Warmup: 1 iterations, 10 ms each"),
                output::toString);
        assertTrue(output.toString().contains("# Measurement: 2 iterations, 10 ms each"),
                output::toString);
        assertFalse(output.toString().contains("Benchmark check"),
                output::toString);
        assertTrue(output.toString().contains("Score"), output::toString);
        assertTrue(output.toString().contains("ops/s"), output::toString);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Setup", "Benchmark", "TearDown"})
    public void checkFailuresIncludeDiagnostics(String annotation, @TempDir Path temporaryDirectory) throws Exception {
        Path modules = compileBenchmark(temporaryDirectory,
                """
                @org.openjdk.jmh.annotations.%s
                public void failure() {
                    throw new IllegalStateException("benchmark lifecycle failed");
                }
                """.formatted(annotation));
        var output = new StringWriter();
        var error = new StringWriter();

        assertNotEquals(0, runBench(modules, output, error, List.of("--check")));
        assertTrue(error.toString().contains("benchmark lifecycle failed"), () -> output + "\n" + error);
        assertFalse(output.toString().contains("Checked "), output::toString);
    }

    @Test
    public void checkReportsEveryParameterConfiguration(@TempDir Path temporaryDirectory) throws Exception {
        Path modules = compileBenchmark(temporaryDirectory,
                """
                @org.openjdk.jmh.annotations.Param({"one", "two"})
                public String input;
                """);
        var output = new StringWriter();
        var error = new StringWriter();

        assertEquals(0, runBench(modules, output, error, List.of("--check")), () -> output + "\n" + error);
        assertEquals(2, output.toString().lines().filter(line -> line.startsWith("Checked " + BENCHMARK)).count(), output::toString);
        assertTrue(output.toString().contains("input=one"), output::toString);
        assertTrue(output.toString().contains("input=two"), output::toString);
        assertFalse(output.toString().contains("ops/s"), output::toString);
    }

    @Test
    public void discoveryDoesNotRunSetup(@TempDir Path temporaryDirectory) throws Exception {
        Path modules = compileBenchmark(temporaryDirectory,
                """
                @org.openjdk.jmh.annotations.Setup
                public void setup() {
                    throw new IllegalStateException("setup must not run during discovery");
                }
                """);
        var output = new StringWriter();
        var error = new StringWriter();

        assertEquals(0, runBench(modules, output, error, List.of("--ja-list-selectors")), error::toString);
        assertTrue(output.toString()
                         .lines()
                         .anyMatch(BENCHMARK::equals),
                output::toString);
        assertFalse(output.toString().contains("setup must not run"),
                output::toString);
        assertFalse(output.toString().contains("# Benchmark:"),
                output::toString);
    }

    private static int runBench(Path modules, StringWriter output, StringWriter error,
            List<String> options) {
        var arguments = new ArrayList<>(List.of("--module-path", modules + System.getProperty("path.separator") + runtimeModulePath(),
                "--add-modules", "com.example.benchmark,com.netflix.tools.jmh", "--ja-bench"));
        arguments.addAll(options);
        return new JmhToolProvider().run(new PrintWriter(output, true), new PrintWriter(error, true), arguments.toArray(String[]::new));
    }

    private static Path compileBenchmark(Path temporaryDirectory) throws Exception {
        return compileBenchmark(temporaryDirectory, "");
    }

    private static Path compileBenchmark(Path temporaryDirectory, String setup) throws Exception {
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

                @org.openjdk.jmh.annotations.State(org.openjdk.jmh.annotations.Scope.Thread)
                @org.openjdk.jmh.annotations.Warmup(iterations = 1, time = 10, timeUnit = java.util.concurrent.TimeUnit.MILLISECONDS)
                @org.openjdk.jmh.annotations.Measurement(iterations = 2, time = 10, timeUnit = java.util.concurrent.TimeUnit.MILLISECONDS)
                @org.openjdk.jmh.annotations.Fork(1)
                public class ExampleBenchmark {
                    %s

                    @Benchmark
                    public int answer() {
                        return 42;
                    }
                }
                """
                        .formatted(setup));

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
