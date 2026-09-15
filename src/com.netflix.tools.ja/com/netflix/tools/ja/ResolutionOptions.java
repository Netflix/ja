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

/** Standard Java options requested from jig, together with resolution behavior. */
public record ResolutionOptions(Set<String> options, boolean compileTime, boolean validateRuntimeAccess,
        boolean emitCompileDiagnostics) {
    public static final ResolutionOptions EMPTY = new ResolutionOptions(Set.of(), false, false);

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

    static final ResolutionOptions JAVAC = new ResolutionOptions(JAVAC_OPTIONS, true, false);

    static final ResolutionOptions MODULE_PATHS = new ResolutionOptions(Set.of("module-path", "module-source-path", "upgrade-module-path", "add-modules"), true, false);

    static final ResolutionOptions CONFIGURATION = new ResolutionOptions(Set.of("module-path", "upgrade-module-path", "add-modules"), true, false);

    static final ResolutionOptions RUNTIME = new ResolutionOptions(RUNTIME_OPTIONS, false, false);

    static final ResolutionOptions RUNTIME_WITH_ACCESS = runtimeWithAccess(false);

    static final ResolutionOptions COMPLETE_RUNTIME_WITH_ACCESS = new ResolutionOptions(union(COMPLETE_RUNTIME_OPTIONS, ACCESS_OPTIONS, Set.of("enable-preview")), false, true);

    static final ResolutionOptions JAVA = java(RUNTIME_OPTIONS);

    static final ResolutionOptions COMPLETE_JAVA = java(COMPLETE_RUNTIME_OPTIONS);

    public ResolutionOptions(Set<String> options, boolean compileTime, boolean validateRuntimeAccess) {
        this(options, compileTime, validateRuntimeAccess, false);
    }

    public ResolutionOptions {
        options = Set.copyOf(options);
    }

    boolean active() {
        return !options.isEmpty() || compileTime || validateRuntimeAccess;
    }

    ResolutionOptions withCompileTime(boolean value) {
        return value == compileTime ? this : new ResolutionOptions(options, value, validateRuntimeAccess, emitCompileDiagnostics);
    }

    ResolutionOptions withCompileDiagnostics() {
        return emitCompileDiagnostics ? this : new ResolutionOptions(options, compileTime, validateRuntimeAccess, true);
    }

    static ResolutionOptions runtimeWithAccess(boolean compileTime) {
        return new ResolutionOptions(union(RUNTIME_OPTIONS, ACCESS_OPTIONS, Set.of("enable-preview")), compileTime, true);
    }

    static ResolutionOptions sourceList(boolean compileTime) {
        return source(compileTime, "module=list");
    }

    private static ResolutionOptions java(Set<String> runtimeOptions) {
        return new ResolutionOptions(union(runtimeOptions, ACCESS_OPTIONS, Set.of("module=main", "enable-preview")),
                false, true);
    }

    private static ResolutionOptions source(boolean compileTime, String moduleOption) {
        return new ResolutionOptions(
                Set.of("module-path", "module-source-path", moduleOption, "release", "enable-preview", "add-exports"),
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
