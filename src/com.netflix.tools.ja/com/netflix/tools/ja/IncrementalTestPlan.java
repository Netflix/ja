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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.netflix.tools.ja.TestDiscovery.TestMethod;

/** Recomputes test identities from their last observed execution traces. */
final class IncrementalTestPlan {
    private IncrementalTestPlan() {}

    static Map<String, TestExecution> create(List<TestMethod> tests, ResolvedClassModels classes, TestResultStore results) throws IOException {
        var executions = new LinkedHashMap<String, TestExecution>();
        for (var test : tests) {
            var trace = results.storedTrace(test.selector());
            if (trace.isEmpty()) {
                continue;
            }
            var previous = trace.orElseThrow();
            var execution = classes.observedExecution(test, previous);
            if (execution.isEmpty()) {
                continue;
            }
            var current = execution.orElseThrow();
            executions.put(test.selector(), current);
            if (!current.observedClassHash().equals(previous.observedClassHash())
                    && results.hasSuccessfulResult(current)) {
                results.updateTrace(current);
            }
        }
        return Map.copyOf(executions);
    }
}
