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

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.spi.ToolProvider;

import com.netflix.tools.ja.ModuleResolver;
import com.netflix.tools.ja.ResolutionOptions;
import com.netflix.tools.ja.ToolExecutionException;
import com.netflix.tools.ja.ToolRuntime;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModuleResolverTest {
    @Test
    void requestsStandardJavaOptions() {
        var runWith = new ArrayList<String>();
        var resolver = new ModuleResolver(ToolRuntime.of(tool((out, arguments) -> {
            runWith.addAll(arguments);
            out.print("--module-path\nmodules\n--add-modules\ncom.example.app\n");
            return 0;
        })));

        List<String> result = resolver.resolve(List.of("--module-source-path", "src", "-m", "com.example.app"), new ResolutionOptions(Set.of("module-path", "add-modules"), true),
                List.of("--enable-feature"), InputStream.nullInputStream(), System.err);

        assertEquals(
                List.of("--module-source-path", "src", "-m", "com.example.app", "--resolve-options", "add-modules,module-path",
                        "--validate-runtime-access", "--no-compile-diagnostics", "--", "--enable-feature"),
                runWith);
        assertEquals(List.of("--module-path", "modules", "--add-modules", "com.example.app"), result);
    }

    @Test
    void canEmitCompilationDiagnostics() {
        var runWith = new ArrayList<String>();
        var resolver = new ModuleResolver(ToolRuntime.of(tool((_, arguments) -> {
            runWith.addAll(arguments);
            return 0;
        })));

        resolver.resolve(List.of("--module-source-path", "src", "-m", "com.example.app"), new ResolutionOptions(Set.of("module-path"), false, true),
                InputStream.nullInputStream(), System.err);

        assertEquals(List.of("--module-source-path", "src", "-m", "com.example.app", "--resolve-options", "module-path"), runWith);
    }

    @Test
    void propagatesResolverFailure() {
        var resolver = new ModuleResolver(ToolRuntime.of(tool((out, arguments) -> 7)));

        assertThrows(
                ToolExecutionException.class,
                () -> resolver.resolve(List.of(), new ResolutionOptions(Set.of("module-path"), false), InputStream.nullInputStream(),
                        System.err));
    }

    private static ToolProvider tool(Operation operation) {
        return new ToolProvider() {
            @Override
            public String name() {
                return "jig";
            }

            @Override
            public int run(PrintWriter out, PrintWriter err, String... arguments) {
                try {
                    return operation.run(out, List.of(arguments));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }

    @FunctionalInterface
    private interface Operation {
        int run(PrintWriter out, List<String> arguments) throws IOException;
    }
}
