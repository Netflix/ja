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

package com.netflix.tools.launcher.test;

import java.util.List;
import java.util.stream.Stream;

import com.netflix.tools.launcher.JdkCompilationOptions;
import com.netflix.tools.launcher.JdkModuleOptions;
import com.netflix.tools.launcher.ModuleRuntimeAccessArguments;
import com.netflix.tools.launcher.ToolOption;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JdkOptionGroupsTest {
    @Test
    void exposesModuleCompilationAndRuntimeAccessGroups() {
        var runtime = Stream.concat(
                JdkModuleOptions.runtimePaths().options().stream(),
                ModuleRuntimeAccessArguments.all().options().stream())
                .toList();
        var compilation = JdkCompilationOptions.paths().options();

        assertEquals(1, supported(runtime, "--module-path"));
        assertEquals(1, supported(runtime, "-p"));
        assertEquals(1, supported(runtime, "--patch-module"));
        assertEquals(1, supported(runtime, "--add-opens"));
        assertEquals(1, supported(compilation, "--module-source-path"));
        assertEquals(0, JdkCompilationOptions.enablePreview()
                .argumentCount());
    }

    private static int supported(List<ToolOption> options, String name) {
        return options.stream()
                .filter(option -> option.names().contains(name))
                .findFirst()
                .map(ToolOption::argumentCount)
                .orElse(-1);
    }
}
