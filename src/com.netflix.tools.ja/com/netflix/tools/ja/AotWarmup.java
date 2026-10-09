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
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;

/** Warms Ja's startup paths without invoking delegated tool warmups. */
final class AotWarmup {
    private AotWarmup() {}

    static int run(PrintStream err) throws IOException {
        var layer = AotWarmup.class.getModule().getLayer();
        var tools = ToolRuntime.load(layer);
        var catalog = ToolCatalog.load(layer).withDiscovered(tools);
        try (var work = TemporaryDirectory.create();
             var out = new PrintStream(OutputStream.nullOutputStream())) {
            int result = invoke(work, out, err, "--help");
            if (result != 0) {
                return result;
            }

            for (var command : Ja.commandLine().commands()) {
                result = invoke(work, out, err, command.name(), "--help");
                if (result != 0) {
                    return result;
                }
            }

            result = invoke(work, out, err, "__complete", "co");
            if (result != 0) {
                return result;
            }
            result = invoke(work, out, err, "__complete", "compile", "--r");
            if (result != 0) {
                return result;
            }

            result = invoke(work, out, err, "init", "--main-class", "com.netflix.tools.ja.warmup.Main",
                    "com.netflix.tools.ja.warmup");
            if (result != 0) {
                return result;
            }
            var main = work.root().resolve("com/netflix/tools/ja/warmup/Main.java");
            Files.createDirectories(main.getParent());
            Files.writeString(main,
                    """
                    package com.netflix.tools.ja.warmup;

                    public final class Main {
                        public static void main(String[] args) {}
                    }
                    """);
            if (available(BuiltinCommand.FMT, tools, catalog)) {
                result = invoke(work, out, err, "fmt");
                if (result != 0) {
                    return result;
                }
            }
            result = invoke(work, out, err, "compile");
            if (result != 0) {
                return result;
            }
            result = invoke(work, out, err, "compile");
            if (result != 0) {
                return result;
            }
            if (available(BuiltinCommand.SOURCE, tools, catalog)) {
                result = invoke(work, out, err, "source", "java.lang.String.isEmpty");
                if (result != 0) {
                    return result;
                }
            }
            if (available(BuiltinCommand.DOC, tools, catalog)) {
                result = invoke(work, out, err, "doc", "java.lang.String.isEmpty");
                if (result != 0) {
                    return result;
                }
            }
            result = invoke(
                    work,
                    out,
                    err,
                    "tool",
                    "jar",
                    "--create",
                    "--file",
                    work.root()
                        .resolve("warmup.jar")
                        .toString(),
                    "-C",
                    work.root().toString(),
                    "module-info.java",
                    "-C",
                    work.root().toString(),
                    "com");
            if (result != 0) {
                return result;
            }
            result = invoke(work, out, err, "tool");
            if (result != 0) {
                return result;
            }

            loadModuleClasses();
            return 0;
        }
    }

    private static boolean available(BuiltinCommand command, ToolRuntime tools, ToolCatalog catalog) {
        return CommandAvailability.missingTools(command, tools, catalog).isEmpty();
    }

    private static int invoke(TemporaryDirectory work, PrintStream out, PrintStream err,
            String... arguments)
            throws IOException {
        return Ja.run(InputStream.nullInputStream(), out, err, work.root(),
                arguments);
    }

    private static void loadModuleClasses() throws IOException {
        var module = AotWarmup.class.getModule();
        var reference = module.getLayer()
                              .configuration()
                              .findModule(module.getName())
                              .orElseThrow()
                              .reference();
        try (var reader = reference.open();
             var resources = reader.list()) {
            for (var resource : resources.filter(name -> isClass(module, name))
                    .sorted()
                    .toList()) {
                var className = resource.substring(0, resource.length() - ".class".length()).replace('/', '.');
                try {
                    Class.forName(className, false, module.getClassLoader());
                } catch (ClassNotFoundException failure) {
                    throw new IllegalStateException("Cannot load module class " + className, failure);
                }
            }
        }
    }

    private static boolean isClass(Module module, String resource) {
        if (!resource.endsWith(".class")) {
            return false;
        }
        int separator = resource.lastIndexOf('/');
        return separator > 0 && module.getPackages().contains(resource.substring(0, separator)
                .replace('/', '.'));
    }
}
