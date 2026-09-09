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
import java.lang.module.ModuleFinder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.netflix.module.ModuleRuntimeAccess;
import com.netflix.tools.ja.JUnitExecutionTrace.Runtime;
import com.netflix.tools.ja.ResolvedClassModels.ModuleInputs;
import com.netflix.tools.ja.TestDiscovery.TestMethod;
import com.netflix.tools.ja.TestResult.Status;

/** Coordinates the test behavior owned by {@code ja test}. */
final class TestRunner {
    private static final String TEST_ENGINE = "org.junit.platform.engine.TestEngine";
    private static final String JUPITER_ENGINE_MODULE = "org.junit.jupiter.engine";

    private record Arguments(boolean runAll, List<String> junitArguments) {}

    private final TestDiscovery discovery = new TestDiscovery();
    private final TestResultStore results;
    private final RuntimeImageHash runtimeImage;

    TestRunner() {
        this(TestResultStore.defaults());
    }

    TestRunner(TestResultStore results) {
        this(
                results,
                new RuntimeImageHash(Path.of(System.getProperty("java.home")),
                        results.root().resolve("runtime-images")));
    }

    TestRunner(TestResultStore results, RuntimeImageHash runtimeImage) {
        this.results = results;
        this.runtimeImage = runtimeImage;
    }

    @FunctionalInterface
    interface Execution {
        int run(List<String> arguments, InputStream in, PrintStream out,
                PrintStream err)
                throws IOException;
    }

    int run(
            ModuleLayer parent,
            List<String> rootModules,
            List<String> applicationArguments,
            Supplier<List<String>> testRuntimeArguments,
            ToolDefinition definition,
            List<String> arguments,
            InputStream in,
            PrintStream out,
            PrintStream err,
            Execution execution)
            throws IOException {
        var parsed = parseArguments(arguments);
        var toolArguments = parsed.junitArguments();
        boolean observedExecution = toolArguments.isEmpty() && supportsLayerExecution(applicationArguments) && hasOnlyJupiterEngine(parent.configuration(), applicationArguments);
        var runtimeArguments = List.<String>of();
        if (observedExecution) {
            runtimeArguments = testRuntimeArguments.get();
            observedExecution = supportsLayerExecution(runtimeArguments);
        }

        ResolvedClassModels classes = null;
        Runtime incrementalRuntime = null;
        if (observedExecution) {
            classes = ResolvedClassModels.resolve(
                    parent.configuration(),
                    new ModuleInputs(Set.copyOf(rootModules), applicationArguments),
                    new ModuleInputs(JUnitExecutionTrace.runtimeModules(), layerArguments(applicationArguments, runtimeArguments)),
                    runtimeImage.hash());
            incrementalRuntime = JUnitExecutionTrace.runtimeIfSupported(classes, parent, layerArguments(applicationArguments, runtimeArguments)).orElse(null);
            observedExecution = incrementalRuntime != null;
        }
        boolean cacheResults = observedExecution && !parsed.runAll();

        List<TestMethod> discovered = List.of();
        Map<String, TestExecution> previousExecutions = Map.of();
        Map<String, TestExecution> pendingPreviousExecutions = Map.of();
        var selected = new ArrayList<String>();
        for (var module : rootModules) {
            selected.add("--select-module");
            selected.add(module);
        }
        int cachedTests = 0;
        var cachedContainerCandidates = new LinkedHashSet<String>();
        var activeContainers = new LinkedHashSet<String>();
        if (observedExecution) {
            discovered = discovery.discover(rootModules, classes);
            previousExecutions = IncrementalTestPlan.create(discovered, classes, results);
            var pending = new LinkedHashMap<String, TestExecution>();
            for (var test : discovered) {
                var previous = previousExecutions.get(test.selector());
                if (cacheResults && previous != null && results.hasSuccessfulResult(previous)) {
                    selected.add("--exclude-methodname");
                    selected.add(methodNamePattern(previous));
                    cachedContainerCandidates.add(test.container());
                    cachedTests++;
                } else {
                    activeContainers.add(test.container());
                    if (previous != null) {
                        pending.put(test.selector(), previous);
                    }
                }
            }
            pendingPreviousExecutions = Map.copyOf(pending);
        }
        cachedContainerCandidates.removeAll(activeContainers);

        boolean structuredOutput = TestOutputCapture.supportsStructuredOutput(definition);
        try (var captured = TestOutputCapture.create()) {
            var junitArguments = new ArrayList<>(selected);
            junitArguments.addAll(toolArguments);
            var selectedArguments = structuredOutput && !junitArguments.isEmpty()
                    ? captured.junitArgumentFile(junitArguments)
                    : List.copyOf(junitArguments);
            var executionArguments = structuredOutput ? captured.junitArguments(definition.defaults(), selectedArguments) : selectedArguments;
            var trace = new ExecutionTrace();
            int result;
            try {
                if (observedExecution) {
                    var directArguments = new ArrayList<>(definition.defaults());
                    directArguments.addAll(executionArguments);
                    try (var ignored = ExecutionTraceInstrumentation.recordWith(layerRecorder(incrementalRuntime.controller()
                            .layer(),
                            trace))) {
                        result = incrementalRuntime.run(captured.out(), captured.err(), directArguments.toArray(String[]::new));
                    }
                } else {
                    result = execution.run(executionArguments, in, captured.out(), captured.err());
                }
            } catch (IOException | RuntimeException | Error failure) {
                record(pendingPreviousExecutions.values(), Status.FAILURE);
                try {
                    captured.replay(out, err);
                } catch (IOException replayFailure) {
                    failure.addSuppressed(replayFailure);
                }
                throw failure;
            }
            if (observedExecution) {
                recordObserved(discovered, classes, trace, result, structuredOutput, captured,
                        pendingPreviousExecutions);
            }
            cachedContainerCandidates.removeAll(trace.containers());
            int cachedContainers = cachedContainerCandidates.size();
            if (result != 0) {
                captured.replayFailure(out, err, cachedContainers, cachedTests);
            } else if (structuredOutput) {
                captured.replaySummary(out, err, cachedContainers, cachedTests);
            }
            return result;
        }
    }

    private void recordObserved(
            List<TestMethod> discovered,
            ResolvedClassModels classes,
            ExecutionTrace trace,
            int exitCode,
            boolean structuredOutput,
            TestOutputCapture captured,
            Map<String, TestExecution> previousExecutions)
            throws IOException {
        var reported = structuredOutput ? captured.testResults(discovered.stream()
                .map(TestMethod::selector)
                .toList())
                : Map.<String, Status>of();
        var tests = new LinkedHashMap<String, TestMethod>();
        discovered.forEach(test -> tests.put(test.selector(), test));
        for (var entry : reported.entrySet()) {
            var test = tests.get(entry.getKey());
            if (test == null) {
                throw new IllegalStateException("JUnit reported an undiscovered test: " + entry.getKey());
            }
            var execution = classes.observedExecution(test, trace.eventsFor(test.selector())).orElseThrow(() -> new IllegalStateException("JUnit executed code that is absent from the resolved modules for " + test.selector()));
            results.record(new TestResult(execution, entry.getValue()));
        }
        if (exitCode != 0) {
            var unreported = previousExecutions.values().stream()
                    .filter(execution -> !reported.containsKey(execution.selector()))
                    .toList();
            record(unreported, Status.FAILURE);
        }
    }

    private void record(Iterable<TestExecution> executions, Status status) throws IOException {
        for (var execution : executions) {
            results.record(new TestResult(execution, status));
        }
    }

    private static BiConsumer<String, Object> layerRecorder(ModuleLayer layer, ExecutionTrace trace) {
        return (method, receiver) -> {
            var type = ExecutionTrace.receiverType(receiver);
            if (type != null && type.getModule().getLayer() == layer) {
                trace.accept(method, receiver);
            }
        };
    }

    private static List<String> layerArguments(List<String> applicationArguments, List<String> testRuntimeArguments) {
        var arguments = new ArrayList<>(applicationArguments);
        arguments.addAll(testRuntimeArguments);
        return List.copyOf(arguments);
    }

    @SuppressWarnings("restricted")
    private static boolean supportsLayerExecution(List<String> arguments) {
        return !arguments.contains("--enable-preview") && ModuleRuntimeAccess.parseArguments(arguments)
                .enableFinalFieldMutation()
                .isEmpty();
    }

    private static boolean hasOnlyJupiterEngine(Configuration parent, List<String> runtimeArguments) {
        for (var module : parent.modules()) {
            if (isUnsupportedEngine(module.reference()
                    .descriptor())) {
                return false;
            }
        }
        var paths = ToolArguments.applicationModulePath(runtimeArguments);
        for (var reference : ModuleFinder.of(paths.toArray(Path[]::new)).findAll()) {
            if (isUnsupportedEngine(reference.descriptor())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isUnsupportedEngine(ModuleDescriptor descriptor) {
        return !descriptor.name().equals(JUPITER_ENGINE_MODULE) && descriptor.provides().stream()
                .anyMatch(provider -> provider.service().equals(TEST_ENGINE));
    }

    private static Arguments parseArguments(List<String> arguments) {
        boolean runAll = false;
        boolean explicitSelector = false;
        var junitArguments = new ArrayList<String>();
        for (int i = 0; i < arguments.size(); i++) {
            var argument = arguments.get(i);
            if (argument.equals("--all")) {
                runAll = true;
            } else if (argument.equals("-t") || argument.equals("--tag")) {
                if (++i >= arguments.size()) {
                    throw new IllegalArgumentException(argument + " requires a value");
                }
                junitArguments.add("--include-tag");
                junitArguments.add(arguments.get(i));
            } else if (argument.startsWith("-t=") || argument.startsWith("--tag=")) {
                int separator = argument.indexOf('=');
                if (separator == argument.length() - 1) {
                    throw new IllegalArgumentException(argument.substring(0, separator) + " requires a value");
                }
                junitArguments.add("--include-tag=" + argument.substring(separator + 1));
            } else if (!argument.startsWith("-")) {
                explicitSelector = true;
                junitArguments.add("--include-methodname");
                junitArguments.add(selectorPattern(argument));
            } else {
                throw new IllegalArgumentException("Unsupported test argument: " + argument);
            }
        }
        if (explicitSelector) {
            junitArguments.add("--fail-if-no-tests");
        }
        return new Arguments(runAll, List.copyOf(junitArguments));
    }

    private static String selectorPattern(String selector) {
        var pattern = new StringBuilder("^");
        var components = selector.split("\\.", -1);
        for (int i = 0; i < components.length; i++) {
            if (i > 0) {
                pattern.append("[.#]");
            }
            pattern.append(Pattern.quote(components[i]));
        }
        return pattern.append("(?:#.+)?$").toString();
    }

    private static String methodNamePattern(TestExecution execution) {
        int parameters = execution.selector().indexOf('(');
        if (parameters < 0) {
            throw new IllegalArgumentException("Test selector has no parameter list: " + execution.selector());
        }
        return "^" + Pattern.quote(execution.selector()
                .substring(0, parameters))
                + "$";
    }
}
