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

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.BenchmarkList;
import org.openjdk.jmh.runner.BenchmarkListEntry;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.format.OutputFormatFactory;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;
import org.openjdk.jmh.runner.options.VerboseMode;

/** Runs ja's benchmark interface in the JVM containing the generated harness. */
public final class BenchmarkRunner {
    private BenchmarkRunner() {}

    public static void main(String[] args) {
        try {
            var arguments = List.of(args);
            var output = OutputFormatFactory.createFormatInstance(System.out, VerboseMode.NORMAL);
            var benchmarks = BenchmarkList.defaultList()
                    .getAll(output, List.of())
                    .stream()
                    .map(BenchmarkListEntry::getUsername)
                    .distinct()
                    .sorted()
                    .toList();
            if (arguments.equals(List.of("--ja-list-selectors"))) {
                System.out.println("Benchmarks:");
                benchmarks.forEach(System.out::println);
                return;
            }
            var options = options(arguments, benchmarks);
            if (arguments.contains("--check")) {
                System.out.println("# Benchmark check: results are not performance measurements.");
                check(options);
            } else {
                new Runner(options, output).run();
            }
        } catch (IllegalArgumentException | RunnerException | IOException failure) {
            System.err.println("bench: " + failure.getMessage());
            System.exit(1);
        }
    }

    private static void check(Options options) throws IOException, RunnerException {
        Path log = Files.createTempFile("ja-bench-check-", ".log");
        try {
            Collection<RunResult> results;
            try (var captured = new PrintStream(Files.newOutputStream(log), true, StandardCharsets.UTF_8)) {
                try {
                    results = new Runner(options, OutputFormatFactory.createFormatInstance(captured, VerboseMode.NORMAL))
                            .run();
                } catch (RunnerException failure) {
                    captured.flush();
                    Files.copy(log, System.err);
                    throw failure;
                }
            }
            for (var result : results) {
                var parameters = result.getParams();
                var configuration = new ArrayList<String>();
                configuration.add("mode=" + parameters.getMode());
                for (String name : parameters.getParamsKeys()) {
                    configuration.add(name + "=" + parameters.getParam(name));
                }
                System.out.println("Checked "
                        + parameters.getBenchmark()
                        + " ("
                        + String.join(", ", configuration)
                        + ")");
            }
        } finally {
            Files.delete(log);
        }
    }

    static Options options(List<String> arguments, List<String> benchmarks) {
        boolean check = false;
        var selectors = new ArrayList<String>();
        for (String argument : arguments) {
            if (argument.equals("--check")) {
                if (check) {
                    throw new IllegalArgumentException("--check may only be specified once");
                }
                check = true;
            } else if (argument.startsWith("-")) {
                throw new IllegalArgumentException("Unsupported bench argument: " + argument + "; use 'ja tool jmh' for JMH options");
            } else {
                selectors.add(argument);
            }
        }
        if (benchmarks.isEmpty()) {
            throw new IllegalArgumentException("No benchmarks found");
        }

        var selected = new LinkedHashSet<String>();
        if (selectors.isEmpty()) {
            selected.addAll(benchmarks);
        }
        for (String selector : selectors) {
            var matches = benchmarks.stream()
                                    .filter(benchmark -> benchmark.equals(selector) || className(benchmark).equals(selector))
                                    .toList();
            if (matches.isEmpty()) {
                matches = benchmarks.stream()
                                    .filter(benchmark -> {
                                        String name = className(benchmark);
                                        String shortName = name.substring(name.lastIndexOf('.') + 1);
                                        return shortName.equals(selector) || (shortName + benchmark.substring(name.length())).equals(selector);
                                    })
                                    .toList();
                var classes = matches.stream()
                                     .map(BenchmarkRunner::className)
                                     .distinct()
                                     .sorted()
                                     .toList();
                if (classes.size() > 1) {
                    throw new IllegalArgumentException("Ambiguous benchmark selector: " + selector + "; use a fully qualified name: " + String.join(", ", classes));
                }
            }
            if (matches.isEmpty()) {
                throw new IllegalArgumentException("Unknown benchmark selector: " + selector);
            }
            selected.addAll(matches);
        }

        var options = new OptionsBuilder();
        options.shouldFailOnError(true);
        selected.forEach(benchmark -> options.include("^" + Pattern.quote(benchmark) + "$"));
        if (check) {
            options.forks(1)
                   .warmupForks(0)
                   .warmupIterations(0)
                   .measurementIterations(1)
                   .measurementTime(TimeValue.milliseconds(100));
        }
        return options.build();
    }

    private static String className(String benchmark) {
        return benchmark.substring(0, benchmark.lastIndexOf('.'));
    }
}
