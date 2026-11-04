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

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.openjdk.jmh.runner.options.TimeValue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkRunnerTest {
    private static final List<String> BENCHMARKS = List.of("com.example.ExampleBenchmark.first", "com.example.ExampleBenchmark.second", "com.other.OtherBenchmark.first");

    @Test
    void normalRunsLeaveMeasurementConfigurationToJmhAndAnnotations() {
        var options = BenchmarkRunner.options(List.of(), BENCHMARKS);

        assertTrue(options.shouldFailOnError().get());
        assertFalse(options.getForkCount().hasValue());
        assertFalse(options.getWarmupForkCount().hasValue());
        assertFalse(options.getWarmupIterations().hasValue());
        assertFalse(options.getWarmupTime().hasValue());
        assertFalse(options.getMeasurementIterations().hasValue());
        assertFalse(options.getMeasurementTime().hasValue());
        assertEquals(BENCHMARKS, matches(options.getIncludes()));
    }

    @Test
    void checkRunsUseSmokeTestSettings() {
        var options = BenchmarkRunner.options(List.of("--check"), BENCHMARKS);

        assertTrue(options.shouldFailOnError().get());
        assertEquals(1, options.getForkCount().get());
        assertEquals(0, options.getWarmupForkCount().get());
        assertEquals(0, options.getWarmupIterations().get());
        assertEquals(1, options.getMeasurementIterations().get());
        assertEquals(TimeValue.milliseconds(100),
                options.getMeasurementTime().get());
    }

    @Test
    void classesSelectOnlyTheirDiscoveredMethods() {
        var options = BenchmarkRunner.options(List.of("com.example.ExampleBenchmark"), BENCHMARKS);

        assertEquals(BENCHMARKS.subList(0, 2), matches(options.getIncludes()));
        assertFalse(options.getIncludes()
                           .stream()
                           .anyMatch(pattern -> Pattern.matches(pattern, "com.example.ExampleBenchmarkExtra.first")));
    }

    @Test
    void methodsSelectOnlyTheDiscoveredMethod() {
        var options = BenchmarkRunner.options(List.of("ExampleBenchmark.first"), BENCHMARKS);

        assertEquals(List.of(BENCHMARKS.getFirst()), matches(options.getIncludes()));
    }

    @Test
    void overlappingSelectionsAreDeduplicated() {
        var options = BenchmarkRunner.options(List.of("ExampleBenchmark", BENCHMARKS.getFirst(), "ExampleBenchmark"), BENCHMARKS);

        assertEquals(2, options.getIncludes().size());
        assertEquals(BENCHMARKS.subList(0, 2), matches(options.getIncludes()));
    }

    @Test
    void everySelectorMustMatch() {
        var failure = assertThrows(IllegalArgumentException.class, () -> BenchmarkRunner.options(List.of(BENCHMARKS.getFirst(), "MissingBenchmark"), BENCHMARKS));

        assertEquals("Unknown benchmark selector: MissingBenchmark", failure.getMessage());
    }

    @Test
    void ambiguousShortNamesRequireQualification() {
        var benchmarks = List.of("com.first.ExampleBenchmark.first", "com.second.ExampleBenchmark.first");
        var failure = assertThrows(IllegalArgumentException.class, () -> BenchmarkRunner.options(List.of("ExampleBenchmark.first"), benchmarks));

        assertTrue(failure.getMessage().contains("Ambiguous benchmark selector"));
        assertTrue(failure.getMessage().contains("com.first.ExampleBenchmark"));
        assertTrue(failure.getMessage().contains("com.second.ExampleBenchmark"));
        assertEquals(1,
                BenchmarkRunner.options(List.of(benchmarks.getFirst()), benchmarks)
                        .getIncludes()
                        .size());
    }

    @Test
    void rejectsRegexSelectorsAndJmhOptions() {
        assertThrows(IllegalArgumentException.class, () -> BenchmarkRunner.options(List.of(".*Benchmark"), BENCHMARKS));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkRunner.options(List.of("-wi", "3"), BENCHMARKS));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkRunner.options(List.of("--check", "--check"), BENCHMARKS));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkRunner.options(List.of("--quick"), BENCHMARKS));
    }

    @Test
    void emptyDiscoveryFailsRatherThanRunningEverything() {
        assertThrows(IllegalArgumentException.class,
                () -> BenchmarkRunner.options(List.of(), List.of()));
    }

    private static List<String> matches(List<String> patterns) {
        return BENCHMARKS.stream()
                         .filter(benchmark -> patterns.stream().anyMatch(pattern -> Pattern.matches(pattern, benchmark)))
                         .toList();
    }
}
