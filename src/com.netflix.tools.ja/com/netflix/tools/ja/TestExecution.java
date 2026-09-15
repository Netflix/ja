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

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Observed code, module inputs, and runtime image for one test method. */
public record TestExecution(String selector,
                            String codeHash,
                            String observedClassHash,
                            List<ResolvedClassModels.ModuleState> moduleStates,
                            String runtimeImageHash,
                            Set<ExecutionTrace.Event> trace) {
    public TestExecution(String selector, String codeHash,
            List<ResolvedClassModels.ModuleState> moduleStates, String runtimeImageHash,
            Set<ExecutionTrace.Event> trace) {
        this(selector, codeHash, "", moduleStates, runtimeImageHash, trace);
    }

    public TestExecution {
        Objects.requireNonNull(selector, "selector");
        Objects.requireNonNull(codeHash, "codeHash");
        Objects.requireNonNull(observedClassHash, "observedClassHash");
        Objects.requireNonNull(runtimeImageHash, "runtimeImageHash");
        if (runtimeImageHash.isEmpty()) {
            throw new IllegalArgumentException("runtimeImageHash is empty");
        }
        moduleStates = moduleStates.stream()
                .sorted(Comparator.comparing(ResolvedClassModels.ModuleState::moduleName))
                .toList();
        trace = Set.copyOf(trace);
    }

    public String identity() {
        var digest = new Sha256()
                .add(selector)
                .add(codeHash)
                .add(runtimeImageHash)
                .add(moduleStates.size());
        for (var module : moduleStates) {
            digest.add(module.moduleName())
                    .add(module.moduleHash().toString())
                    .add(module.patchHashes().size());
            for (var patch : module.patchHashes()) {
                digest.add(patch.toString());
            }
        }
        return digest.hex();
    }
}
