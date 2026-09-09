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

package com.netflix.tools.launcher;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.ServiceLoader.Provider;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.spi.ToolProvider;
import javax.tools.Tool;

public final class ToolLauncher {
    static final String AOT_TRAINING_PROPERTY = "com.netflix.tools.launcher.aot.training";

    private ToolLauncher() {}

    public static void main(String[] args) {
        var out = new PrintWriter(System.out, true);
        var err = new PrintWriter(System.err, true);
        if (args.length == 0) {
            err.println("Tool name is missing");
            System.exit(2);
            return;
        }

        String name = args[0];
        String[] toolArguments = Arrays.copyOfRange(args, 1, args.length);
        int result;
        try {
            boolean training = Boolean.getBoolean(AOT_TRAINING_PROPERTY);
            Optional<Tool> compilerTool = findTool(name);
            if (compilerTool.isPresent()) {
                result = runTool(name, toolArguments, System.in, System.out, System.err, training,
                        WarmupPlans::find, () -> findTool(name).orElseThrow());
            } else {
                Supplier<ToolProvider> provider = () -> ToolProvider.findFirst(name).orElseThrow(() -> new LauncherConfigurationException("Tool not found: " + name));
                result = run(name, toolArguments, out, err, training,
                        WarmupPlans::find, provider);
            }
        } catch (LauncherConfigurationException e) {
            err.println(e.getMessage());
            result = 2;
        }
        out.flush();
        err.flush();
        System.exit(result);
    }

    public static int run(
            String name,
            String[] arguments,
            PrintWriter out,
            PrintWriter err,
            boolean training,
            Function<ToolProvider, Optional<String>> warmupArguments,
            Supplier<ToolProvider> providers) {
        ToolProvider provider = providers.get();
        requireName(name, provider);
        if (!training) {
            return provider.run(out, err, arguments);
        }

        Optional<String> warmup = warmupArguments.apply(provider);
        if (warmup.isEmpty()) {
            err.println(name + " does not declare a warmup argument");
            return 2;
        }
        int result = provider.run(out, err, warmup.get());
        if (result != 0) {
            err.println(name + " warmup failed with exit code " + result);
        }
        return result;
    }

    public static int runTool(
            String name,
            String[] arguments,
            InputStream in,
            OutputStream out,
            OutputStream err,
            boolean training,
            Function<Tool, Optional<String>> warmupArguments,
            Supplier<Tool> tools) {
        Tool tool = tools.get();
        requireName(name, tool);
        if (!training) {
            return tool.run(in, out, err, arguments);
        }

        Optional<String> warmup = warmupArguments.apply(tool);
        if (warmup.isEmpty()) {
            new PrintWriter(err, true).println(name + " does not declare a warmup argument");
            return 2;
        }
        int result = tool.run(InputStream.nullInputStream(), out, err, warmup.get());
        if (result != 0) {
            new PrintWriter(err, true).println(name + " warmup failed with exit code " + result);
        }
        return result;
    }

    private static Optional<Tool> findTool(String name) {
        return ServiceLoader.load(Tool.class).stream()
                .map(Provider::get)
                .filter(tool -> tool.name().equals(name))
                .findFirst();
    }

    private static void requireName(String expected, ToolProvider provider) {
        if (!provider.name().equals(expected)) {
            throw new LauncherConfigurationException("Expected tool provider " + expected + ", found " + provider.name());
        }
    }

    private static void requireName(String expected, Tool tool) {
        if (!tool.name().equals(expected)) {
            throw new LauncherConfigurationException("Expected tool " + expected + ", found " + tool.name());
        }
    }
}
