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

import java.util.LinkedHashSet;
import java.util.Set;

import com.netflix.tools.ja.ModuleResolver.Projection;

/** Standard jig output projections for the Java operations coordinated by ja. */
final class ToolProjections {
    static final Set<String> JAVAC_OPTIONS = Set.of(
            "module-path",
            "processor-module-path",
            "upgrade-module-path",
            "module-source-path",
            "module=list",
            "module-version",
            "patch-module",
            "release",
            "enable-preview",
            "add-exports");

    static final Set<String> RUNTIME_OPTIONS = Set.of("module-path", "upgrade-module-path", "patch-module", "add-modules");

    // Without patch-module, jig emits complete exploded source modules.
    static final Set<String> COMPLETE_RUNTIME_OPTIONS = Set.of("module-path", "upgrade-module-path", "add-modules");

    static final Set<String> ACCESS_OPTIONS = Set.of("enable-native-access", "enable-final-field-mutation", "add-opens", "add-exports");

    static final Projection JAVAC = new Projection(JAVAC_OPTIONS, true, false);

    static final Projection CONFIGURATION = new Projection(Set.of("module-path", "upgrade-module-path", "add-modules"), true, false);

    static final Projection RUNTIME = new Projection(RUNTIME_OPTIONS, false, false);

    static final Projection RUNTIME_WITH_ACCESS = runtimeWithAccess(false);

    static final Projection COMPLETE_RUNTIME_WITH_ACCESS = new Projection(union(COMPLETE_RUNTIME_OPTIONS, ACCESS_OPTIONS, Set.of("enable-preview")), false, true);

    static final Projection JAVA = java(RUNTIME_OPTIONS);

    static final Projection COMPLETE_JAVA = java(COMPLETE_RUNTIME_OPTIONS);

    private ToolProjections() {}

    static Projection runtimeWithAccess(boolean compileTime) {
        return new Projection(union(RUNTIME_OPTIONS, ACCESS_OPTIONS, Set.of("enable-preview")), compileTime, true);
    }

    static Projection sourceList(boolean compileTime) {
        return source(compileTime, "module=list");
    }

    private static Projection java(Set<String> runtimeOptions) {
        return new Projection(union(runtimeOptions, ACCESS_OPTIONS, Set.of("module=main", "enable-preview")),
                false, true);
    }

    private static Projection source(boolean compileTime, String moduleOption) {
        return new Projection(
                Set.of("module-path", "upgrade-module-path", "module-source-path", moduleOption, "release", "enable-preview", "add-exports"),
                compileTime,
                false);
    }

    @SafeVarargs
    private static Set<String> union(Set<String>... sets) {
        var result = new LinkedHashSet<String>();
        for (var set : sets) {
            result.addAll(set);
        }
        return Set.copyOf(result);
    }
}
