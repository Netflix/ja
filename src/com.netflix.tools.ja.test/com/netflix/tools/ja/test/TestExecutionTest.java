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
import java.util.Set;

import com.netflix.module.ModuleHash;
import com.netflix.module.ModuleHash.Type;
import com.netflix.tools.ja.ResolvedClassModels.ModuleState;
import com.netflix.tools.ja.TestExecution;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestExecutionTest {
    private static final String A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Test
    void identityIncludesObservedCodeAndOpaqueModuleState() {
        var moduleA = module("example.module", A, A);
        var moduleB = module("example.module", B, A);
        var first = execution("code-a", moduleA);

        assertNotEquals(first.identity(), execution("code-b", moduleA).identity());
        assertNotEquals(first.identity(), execution("code-a", moduleB).identity());
        assertNotEquals(first.identity(), execution("code-a", module("example.module", A, B)).identity());
    }

    @Test
    void identityIncludesTheRuntimeImage() {
        var module = module("example.module", A, A);
        var first = new TestExecution("example.Test#test()", "code", List.of(module), A,
                Set.of());
        var second = new TestExecution("example.Test#test()", "code", List.of(module), B,
                Set.of());

        assertNotEquals(first.identity(), second.identity());
    }

    @Test
    void observedClassHashIsOnlyAValidationShortcut() {
        var module = module("example.module", A, A);
        var first = new TestExecution("example.Test#test()", "code", A, List.of(module), A,
                Set.of());
        var second = new TestExecution("example.Test#test()", "code", B, List.of(module), A,
                Set.of());

        assertEquals(first.identity(), second.identity());
    }

    @Test
    void requiresARuntimeImageIdentity() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TestExecution("example.Test#test()", "code", List.of(), "",
                        Set.of()));
    }

    @Test
    void identityIsIndependentOfModuleTraversalOrder() {
        var first = new TestExecution(
                "example.Test#test()",
                "code",
                List.of(module("module.a", A, A), module("module.b", B, B)),
                A,
                Set.of());
        var second = new TestExecution(
                "example.Test#test()",
                "code",
                List.of(module("module.b", B, B), module("module.a", A, A)),
                A,
                Set.of());

        assertEquals(first.identity(), second.identity());
    }

    private static TestExecution execution(String code, ModuleState module) {
        return new TestExecution("example.Test#test()", code, List.of(module), A,
                Set.of());
    }

    private static ModuleState module(String name, String module, String patch) {
        return new ModuleState(name, new ModuleHash(Type.MODULE, "sha256", module), List.of(new ModuleHash(Type.PATCH, "sha256", patch)));
    }
}
