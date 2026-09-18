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
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Lists available module versions and selects versions for requirements and
 * updates.
 */
final class ModuleVersions {
    private ModuleVersions() {}

    static String latestAvailable(ToolRuntime tools, String moduleName, PrintStream err) {
        List<String> versions = list(tools, moduleName, err);
        return SemanticVersion.latestAvailable(versions).orElseThrow(() -> new IllegalArgumentException("Module has no stable SemVer version: " + moduleName));
    }

    static List<String> list(ToolRuntime tools, String moduleName, PrintStream err) {
        var bytes = new ByteArrayOutputStream();
        int result;
        try (var output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            result = tools.run("jig", InputStream.nullInputStream(), output, err, "--list-module-versions",
                    moduleName);
        }
        if (result != 0) {
            throw new ToolExecutionException(result);
        }
        List<String> versions = bytes.toString(StandardCharsets.UTF_8)
                .lines()
                .map(String::strip)
                .filter(version -> !version.isEmpty())
                .toList();
        if (versions.isEmpty()) {
            throw new IllegalArgumentException("Module not found: " + moduleName);
        }
        return versions;
    }
}
