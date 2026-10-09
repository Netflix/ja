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

package com.netflix.tools.ja.test;

import java.util.List;

import com.netflix.tools.cli.CommandLine.Completion;
import com.netflix.tools.cli.CommandLine.CompletionRequest;
import com.netflix.tools.cli.CommandLine.ToolInvocation;
import com.netflix.tools.ja.BuiltinCommand;
import com.netflix.tools.ja.Ja;
import com.netflix.tools.ja.SelectorCompletion;
import com.netflix.tools.ja.ToolRuntime;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectorCompletionTest {
    @Test
    void wiresJUnitDiscoveryIntoTestCompletion() {
        var completions = Ja.complete(new CompletionRequest(ToolInvocation.of("test"), "SelectorCompletion"), ToolRuntime.of()).stream()
                .map(Completion::value)
                .toList();

        assertTrue(completions.contains("com.netflix.tools.ja.test.SelectorCompletionTest"));
        assertTrue(completions.contains("com.netflix.tools.ja.test.SelectorCompletionTest.readsClassesAndMethodsFromJUnitDiscovery"));
        assertTrue(Ja.complete(
                        new CompletionRequest(ToolInvocation.of("test", "--tag"), "SelectorCompletion"),
                        ToolRuntime.of())
                .isEmpty());
    }

    @Test
    void readsClassesAndMethodsFromJUnitDiscovery() {
        var output =
                """
                JUnit Jupiter ([engine:junit-jupiter])
                ExampleTest ([engine:junit-jupiter]/[class:com.example.ExampleTest])
                works() ([engine:junit-jupiter]/[class:com.example.ExampleTest]/[method:works()])
                parameterized(String) ([engine:junit-jupiter]/[class:com.example.ExampleTest]/[test-template:parameterized(java.lang.String)])
                """;

        assertEquals(List.of("com.example.ExampleTest", "com.example.ExampleTest.parameterized", "com.example.ExampleTest.works"),
                SelectorCompletion.candidates(BuiltinCommand.TEST, output, "Example").stream()
                        .map(Completion::value)
                        .toList());
    }

    @Test
    void readsClassesAndMethodsFromJmhDiscovery() {
        var output =
                """
                Processing 1 classes from /tmp/bytecode with "reflection" generator
                Benchmarks:
                com.example.ExampleBenchmark.first
                com.example.ExampleBenchmark.second
                """;

        assertEquals(List.of("com.example.ExampleBenchmark", "com.example.ExampleBenchmark.first", "com.example.ExampleBenchmark.second"),
                SelectorCompletion.candidates(BuiltinCommand.BENCH, output, "Example").stream()
                        .map(Completion::value)
                        .toList());
    }
}
