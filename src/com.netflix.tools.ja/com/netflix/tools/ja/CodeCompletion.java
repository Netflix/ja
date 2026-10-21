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
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.netflix.tools.cli.CommandLine.Completion;

final class CodeCompletion {
    private CodeCompletion() {}

    static List<Completion> symbols(ToolRuntime tools, Path directory, List<String> globals,
            List<String> query, String current) {
        var arguments = new ArrayList<>(globals);
        arguments.addAll(List.of("tool", "jist", "__complete", "-C", directory.toString()));
        arguments.addAll(query);
        arguments.add(current);
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();
        try {
            var invocation = JaInvocation.parse(directory, arguments.toArray(String[]::new));
            var runner = new CommandRunner(Ja.class.getModule().getLayer(), tools, new ToolCatalog(List.of()), TemporaryDirectory::create);
            int result = runner.run(invocation, InputStream.nullInputStream(), new PrintStream(output), new PrintStream(error));
            return result == 0 ? ToolRuntime.completionProtocol(output.toString(StandardCharsets.UTF_8)) : List.of();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot complete Java subjects", e);
        }
    }

    static List<Completion> paths(Path directory, String current) {
        var value = Path.of(current);
        var parent = current.endsWith("/") || current.endsWith(File.separator) ? value : value.getParent();
        var base = parent == null ? Path.of("") : parent;
        var root = directory.resolve(base);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var files = Files.list(root)) {
            return files.filter(path -> Files.isDirectory(path) || path.toString().endsWith(".java") || path.toString().endsWith(".class"))
                    .map(path -> new Completion(base.resolve(path.getFileName()) + (Files.isDirectory(path) ? File.separator : ""),
                            Files.isDirectory(path) ? "Directory" : "Java file"))
                    .filter(completion -> completion.value().startsWith(current))
                    .sorted(Comparator.comparing(Completion::value)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot complete Java files", e);
        }
    }
}
