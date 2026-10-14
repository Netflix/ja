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

import java.io.File;
import java.util.List;
import javax.tools.OptionChecker;

import com.netflix.tools.ja.ToolArguments;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolArgumentsTest {
    @Test
    void launcherPathsAreJoinedAcrossAliasesAndKeepEmptyEntries() {
        var separator = File.pathSeparator;
        assertEquals(List.of("--class-path", "first" + separator + separator + "last", "--upgrade-module-path",
                        "upgrade" + separator + "other", "--module", "tool/Main", "-cp", "user value"),
                ToolArguments.composeLauncher(List.of("-cp", "first" + separator, "--upgrade-module-path=upgrade"),
                        List.of("--class-path=first" + separator + "last", "--upgrade-module-path", "other",
                                "--module", "tool/Main", "-cp", "user value")));
    }

    @Test
    void launcherPatchesAreJoinedOnlyForTheSameModule() {
        assertEquals(List.of("--patch-module", "one=project" + File.pathSeparator + "tool", "--patch-module",
                        "two=other", "--enable-preview", "--module", "tool/Main"),
                ToolArguments.composeLauncher(List.of("--patch-module", "one=project"),
                        List.of("--patch-module=one=tool", "--patch-module", "two=other", "--enable-preview",
                                "--module", "tool/Main")));
    }

    @Test
    void selectsArgumentsSupportedByAConsumer() {
        OptionChecker source = option -> switch (option) {
            case "--module-path", "--module-source-path" -> 1;
            case "--enable-preview", "-proc:none" -> 0;
            default -> -1;
        };
        OptionChecker consumer = option -> switch (option) {
            case "--module-path" -> 1;
            case "--enable-preview" -> 0;
            default -> -1;
        };

        assertEquals(
                List.of("--module-path", "modules", "--enable-preview"),
                ToolArguments.select(List.of("--module-path", "modules", "--module-source-path", "src", "-proc:none", "--enable-preview"), source, consumer));
    }
}
