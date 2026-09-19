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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;
import javax.lang.model.SourceVersion;

import com.netflix.tools.cli.CommandLine.Completion;

/** Completes test and benchmark selectors reported by their underlying tools. */
public final class SelectorCompletion {
    static final String REQUEST = "--ja-list-selectors";

    private static final Pattern JUNIT_CLASS = Pattern.compile("\\[class:([^]]+)]");
    private static final Pattern JUNIT_METHOD = Pattern.compile("\\[(?:method|test-template|test-factory):([^]()]+)(?:\\([^]]*\\))?]");

    private SelectorCompletion() {}

    static List<Completion> complete(BuiltinCommand command, Path workingDirectory, String current) {
        try {
            var output = new ByteArrayOutputStream();
            var errors = new ByteArrayOutputStream();
            var invocation = JaInvocation.parse(workingDirectory, new String[] {command.commandName(), REQUEST});
            int result = new CommandRunner(Ja.class
                    .getModule()
                    .getLayer())
                    .run(invocation, InputStream.nullInputStream(), new PrintStream(output, true, StandardCharsets.UTF_8),
                            new PrintStream(errors, true, StandardCharsets.UTF_8));
            if (result != 0) {
                return List.of();
            }
            return candidates(command, output.toString(StandardCharsets.UTF_8), current);
        } catch (RuntimeException | IOException ignored) {
            return List.of();
        }
    }

    public static List<Completion> candidates(BuiltinCommand command, String output, String current) {
        var candidates = switch (command) {
            case TEST -> junitCandidates(output);
            case BENCH -> jmhCandidates(output);
            default -> throw new IllegalArgumentException("Selector completion is unsupported for " + command.commandName());
        };
        return candidates.stream()
                .filter(candidate -> matches(candidate, current))
                .map(candidate -> new Completion(candidate.value(), candidate.description()))
                .sorted(Comparator.comparing(Completion::value))
                .toList();
    }

    private static List<Candidate> junitCandidates(String output) {
        var candidates = new LinkedHashMap<String, Candidate>();
        for (String line : output.lines().toList()) {
            var classMatcher = JUNIT_CLASS.matcher(line);
            if (!classMatcher.find()) {
                continue;
            }
            String className = classMatcher.group(1);
            candidates.putIfAbsent(className, Candidate.classCandidate(className, "Test class"));
            var methodMatcher = JUNIT_METHOD.matcher(line);
            if (methodMatcher.find()) {
                String method = className + "." + methodMatcher.group(1);
                candidates.putIfAbsent(method, Candidate.methodCandidate(method, className, "Test method"));
            }
        }
        return List.copyOf(candidates.values());
    }

    private static List<Candidate> jmhCandidates(String output) {
        var candidates = new LinkedHashMap<String, Candidate>();
        for (String line : output.lines().toList()) {
            String benchmark = line.strip();
            int methodSeparator = benchmark.lastIndexOf('.');
            if (methodSeparator <= 0 || !SourceVersion.isName(benchmark)) {
                continue;
            }
            String className = benchmark.substring(0, methodSeparator);
            candidates.putIfAbsent(className, Candidate.classCandidate(className, "Benchmark class"));
            candidates.putIfAbsent(benchmark, Candidate.methodCandidate(benchmark, className, "Benchmark method"));
        }
        return List.copyOf(candidates.values());
    }

    private static boolean matches(Candidate candidate, String current) {
        if (current.isEmpty() || candidate.value().startsWith(current)) {
            return true;
        }
        return candidate.shortValue().startsWith(current);
    }

    private record Candidate(String value, String shortValue, String description) {
        private static Candidate classCandidate(String value, String description) {
            return new Candidate(value, simpleName(value), description);
        }

        private static Candidate methodCandidate(String value, String className, String description) {
            return new Candidate(value, simpleName(className) + value.substring(className.length()), description);
        }

        private static String simpleName(String name) {
            int separator = name.lastIndexOf('.');
            return separator < 0 ? name : name.substring(separator + 1);
        }
    }
}
