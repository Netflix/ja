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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.spi.ToolProvider;

import com.netflix.tools.ja.ApplicationTarget;
import com.netflix.tools.ja.ToolRuntime;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApplicationTargetTest {
    @Test
    void parsesCanonicalModuleRequirement() {
        ApplicationTarget target = ApplicationTarget.resolve("com.example.foo@1.2.3", ToolRuntime.of(), new ByteArrayInputStream(new byte[0]),
                new PrintStream(new ByteArrayOutputStream()));

        assertEquals("com.example.foo", target.moduleName());
        assertEquals(Optional.of("1.2.3"), target.version());
        assertEquals(List.of("--add-requires", "com.example.foo@1.2.3"), target.resolutionArguments());
    }

    @Test
    void unversionedModuleSelectsTheLatestStableSemanticVersion() {
        var request = new AtomicReference<List<String>>();
        ToolProvider jig = new ToolProvider() {
            @Override
            public String name() {
                return "jig";
            }

            @Override
            public int run(PrintWriter out, PrintWriter err, String... args) {
                request.set(List.of(args));
                out.print("not-semver\n2.4.0\n3.0.0\n4.0.0-rc.1\n");
                return 0;
            }
        };

        ApplicationTarget target = ApplicationTarget.resolve("com.example.foo", ToolRuntime.of(jig), new ByteArrayInputStream(new byte[0]),
                new PrintStream(new ByteArrayOutputStream()));

        assertEquals(List.of("--list-module-versions", "com.example.foo"), request.get());
        assertEquals("com.example.foo", target.moduleName());
        assertEquals(Optional.of("3.0.0"), target.version());
        assertEquals(List.of("--add-requires", "com.example.foo@3.0.0"), target.resolutionArguments());
    }

    @Test
    void unversionedModuleSelectsTheLatestNonSemanticVersionWhenNoSemanticVersionExists() {
        ToolProvider jig = new ToolProvider() {
            @Override
            public String name() {
                return "jig";
            }

            @Override
            public int run(PrintWriter out, PrintWriter err, String... args) {
                out.print("1.35\n1.37\n1.38\n");
                return 0;
            }
        };

        ApplicationTarget target = ApplicationTarget.resolve("com.example.foo", ToolRuntime.of(jig), new ByteArrayInputStream(new byte[0]),
                new PrintStream(new ByteArrayOutputStream()));

        assertEquals(Optional.of("1.38"), target.version());
    }

    @Test
    void looksUpPackageUrlThroughJig() {
        var request = new AtomicReference<List<String>>();
        ToolProvider jig = new ToolProvider() {
            @Override
            public String name() {
                return "jig";
            }

            @Override
            public int run(PrintWriter out, PrintWriter err, String... args) {
                request.set(List.of(args));
                out.println("com.example.foo");
                return 0;
            }
        };
        var errors = new ByteArrayOutputStream();

        ApplicationTarget target = ApplicationTarget.resolve("pkg:maven/com.example/example-foo@1.2.3?repository_url=https%3A%2F%2Frepo.example",
                ToolRuntime.of(jig), new ByteArrayInputStream(new byte[0]), new PrintStream(errors, true, StandardCharsets.UTF_8));

        assertEquals(
                List.of("--lookup-module", "pkg:maven/com.example/example-foo@1.2.3?repository_url=https%3A%2F%2Frepo.example"),
                request.get());
        assertEquals("com.example.foo", target.moduleName());
        assertEquals(Optional.of("1.2.3"), target.version());
        assertEquals(List.of("--add-requires", "com.example.foo@1.2.3"), target.resolutionArguments());
    }

    @Test
    void unversionedPackageUrlSelectsTheLatestStableSemanticVersion() {
        var requests = new ArrayList<List<String>>();
        ToolProvider jig = new ToolProvider() {
            @Override
            public String name() {
                return "jig";
            }

            @Override
            public int run(PrintWriter out, PrintWriter err, String... args) {
                requests.add(List.of(args));
                if (args[0].equals("--lookup-module")) {
                    out.println("com.example.foo");
                } else {
                    out.print("not-semver\n2.4.0\n3.0.0\n4.0.0-rc.1\n");
                }
                return 0;
            }
        };
        String packageUrl = "pkg:maven/com.example/example-foo?repository_url=https%3A%2F%2Frepo.example";

        ApplicationTarget target = ApplicationTarget.resolve(packageUrl, ToolRuntime.of(jig), new ByteArrayInputStream(new byte[0]),
                new PrintStream(new ByteArrayOutputStream()));

        assertEquals(List.of(List.of("--lookup-module", packageUrl), List.of("--list-module-versions", "com.example.foo")),
                requests);
        assertEquals("com.example.foo", target.moduleName());
        assertEquals(Optional.of("3.0.0"), target.version());
        assertEquals(List.of("--add-requires", "com.example.foo@3.0.0"), target.resolutionArguments());
    }
}
