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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.netflix.tools.ja.CommandRunner;
import com.netflix.tools.ja.JaInvocation;
import com.netflix.tools.ja.JaTool;
import com.netflix.tools.ja.ToolExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenExportTest {

    @Test
    void exportsSourceModulesAsAMavenBuild(@TempDir Path directory) throws Exception {
        sourceModule(directory, "com.example.application",
                """
                module com.example.application {
                    requires com.example.library;
                }
                """);
        sourceModule(directory, "com.example.library",
                """
                module com.example.library {}
                """);

        Result result = runJa(directory, "maven", "export");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(Files.isRegularFile(directory.resolve("pom.xml")));
        assertTrue(Files.isRegularFile(directory.resolve("src/com.example.application/module-info.pom")));
        assertTrue(Files.isRegularFile(directory.resolve("src/com.example.library/module-info.pom")));
        assertTrue(Files.readString(directory.resolve(".mvn/maven.config"))
                .startsWith("-Dmaven.repo.local.tail="));
    }

    @Test
    void rejectsDestinationDirectory(@TempDir Path directory) throws Exception {
        sourceModule(directory, "com.example.application",
                """
                module com.example.application {}
                """);

        Result result = runJa(directory, "maven", "export", ".");

        assertEquals(2, result.exitCode());
        assertEquals("ja: Unexpected argument: .\n", result.error());
    }

    @Test
    void rejectsUnknownOperations(@TempDir Path directory) throws Exception {
        sourceModule(directory, "com.example.application",
                """
                module com.example.application {}
                """);

        Result result = run(directory, "maven", "publish", ".");

        assertEquals(2, result.exitCode());
        assertTrue(result.error().contains("Unknown maven operation: publish"),
                result.error());
    }

    private static void sourceModule(Path directory, String name, String descriptor) throws Exception {
        Path source = Files.createDirectories(directory.resolve("src")
                .resolve(name));
        Files.writeString(source.resolve("module-info.java"), descriptor);
        Files.writeString(source.resolve("module-info.hash"), "");
    }

    private static Result runJa(Path workingDirectory, String... arguments) {
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        var invocation = new String[arguments.length + 2];
        invocation[0] = "-C";
        invocation[1] = workingDirectory.toString();
        System.arraycopy(arguments, 0, invocation, 2, arguments.length);
        int exitCode = new JaTool().run(new ByteArrayInputStream(new byte[0]), output, errors, invocation);
        return new Result(exitCode, output.toString(StandardCharsets.UTF_8), errors.toString(StandardCharsets.UTF_8));
    }

    private static Result run(Path workingDirectory, String... arguments) throws Exception {
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        int exitCode;
        try (var out = new PrintStream(output);
             var err = new PrintStream(errors)) {
            var commandLine = JaInvocation.parse(workingDirectory, arguments);
            exitCode = new CommandRunner(ModuleLayer.boot()).run(commandLine, new ByteArrayInputStream(new byte[0]), out, err);
        } catch (ToolExecutionException e) {
            exitCode = 1;
        } catch (IllegalArgumentException | IllegalStateException e) {
            errors.writeBytes(("ja: " + e.getMessage() + "\n").getBytes(StandardCharsets.UTF_8));
            exitCode = 2;
        }
        return new Result(exitCode, output.toString(StandardCharsets.UTF_8), errors.toString(StandardCharsets.UTF_8));
    }

    private record Result(int exitCode, String output, String error) {}
}
