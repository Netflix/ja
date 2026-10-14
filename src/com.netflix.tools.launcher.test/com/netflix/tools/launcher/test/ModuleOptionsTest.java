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

import java.util.Set;

import com.netflix.tools.launcher.ModuleOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModuleOptionsTest {
    @Test
    void checksExactDeclaredOptions() {
        var checker = ModuleOptions.checker(Set.of("module-path", "patch-module", "verbose"));

        assertEquals(1, checker.isSupportedOption("--module-path"));
        assertEquals(1, checker.isSupportedOption("-p"));
        assertEquals(1, checker.isSupportedOption("--patch-module"));
        assertEquals(0, checker.isSupportedOption("--verbose"));
        assertEquals(-1, checker.isSupportedOption("--add-modules"));
    }

    @Test
    void moduleFormsNormalizeToTheModuleOption() {
        assertEquals(1, ModuleOptions.checker(Set.of("module=main"))
                .isSupportedOption("--module"));
    }

    @Test
    void separatesToolOptionsFromResolutionOptions() {
        assertEquals(Set.of("module=list", "release"), ModuleOptions.resolutionOptions(Set.of("module=list", "release", "verbose")));
    }

    @Test
    void checksClasspathAndCompilerArgumentsWithTheirStandardSpellings() {
        var checker = ModuleOptions.checker(Set.of("class-path", "processor-path", "source", "target", "encoding", "system", "add-reads"));
        for (String spelling : Set.of("--class-path", "-classpath", "-cp", "--processor-path", "-processorpath",
                "--source", "-source", "--target", "-target", "-encoding", "--system", "--add-reads")) {
            assertEquals(1, checker.isSupportedOption(spelling), spelling);
        }
    }

    @Test
    void discoversAllStandardArgumentsAcceptedByATool() {
        var options = Set.of("class-path", "processor-path", "source", "target", "encoding", "system", "add-reads", "d");
        assertEquals(options, ModuleOptions.supportedBy(ModuleOptions.checker(options)));
    }

    @Test
    void compilationOutputUsesItsShortSpelling() {
        assertEquals(1, ModuleOptions.checker(Set.of("d"))
                .isSupportedOption("-d"));
        assertEquals(-1, ModuleOptions.checker(Set.of("d"))
                .isSupportedOption("--d"));
    }
}
