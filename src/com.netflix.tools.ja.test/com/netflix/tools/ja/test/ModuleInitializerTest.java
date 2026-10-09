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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.netflix.tools.ja.CommandRunner;
import com.netflix.tools.ja.JaInvocation;
import com.netflix.tools.ja.ModuleSourcePath;
import com.netflix.tools.ja.ToolCatalog;
import com.netflix.tools.ja.ToolRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleInitializerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void initializesTheCurrentDirectory() throws Exception {
        Path existingSource = Files.writeString(temporaryDirectory.resolve("Main.java"), "void main() {}\n");

        assertEquals(0, initialize(temporaryDirectory, "com.example.application"));

        assertEquals("module com.example.application {\n}\n", Files.readString(temporaryDirectory.resolve("module-info.java")));
        assertTrue(Files.exists(existingSource));
    }

    @Test
    void initializesWithStandardModuleOptions() throws Exception {
        assertEquals(
                0,
                initialize(
                        temporaryDirectory,
                        "--release",
                        "25",
                        "--main-class=com.example.application.Main",
                        "--enable-preview",
                        "--enable-native-access=com.example.application,com.example.nativebinding",
                        "--enable-final-field-mutation",
                        "com.example.model",
                        "--add-opens",
                        "java.base/java.lang=com.example.application,com.example.friend",
                        "--add-exports=jdk.compiler/com.sun.tools.javac.tree=com.example.processor",
                        "com.example.application"));

        assertEquals(
                """
                /**
                 * @release 25
                 * @mainClass com.example.application.Main
                 * @enablePreview
                 * @enableNativeAccess com.example.application
                 * @enableNativeAccess com.example.nativebinding
                 * @enableFinalFieldMutation com.example.model
                 * @addExports jdk.compiler/com.sun.tools.javac.tree=com.example.processor
                 * @addOpens java.base/java.lang=com.example.application
                 * @addOpens java.base/java.lang=com.example.friend
                 */
                module com.example.application {
                }
                """,
                Files.readString(temporaryDirectory.resolve("module-info.java")));
    }

    @Test
    void initializesAModuleOnTheSourcePathFromItsParent() throws Exception {
        Path sourcePath = Files.createDirectory(temporaryDirectory.resolve("src"));
        Path library = Files.createDirectory(sourcePath.resolve("com.example.library"));
        Files.writeString(library.resolve("module-info.java"), "module com.example.library {}\n");

        assertEquals(0, initialize(temporaryDirectory, "com.example.application"));

        assertEquals("module com.example.application {\n}\n", Files.readString(sourcePath.resolve("com.example.application/module-info.java")));
        assertFalse(Files.exists(temporaryDirectory.resolve("module-info.java")));
        assertEquals(List.of("com.example.application", "com.example.library"),
                ModuleSourcePath.discover(temporaryDirectory)
                        .orElseThrow()
                        .moduleNames());
    }

    @Test
    void initializesTheFirstModuleOnAnEmptySourcePath() throws Exception {
        Path sourcePath = Files.createDirectory(temporaryDirectory.resolve("src"));

        assertEquals(0, initialize(temporaryDirectory, "com.example.application"));

        assertEquals("module com.example.application {\n}\n", Files.readString(sourcePath.resolve("com.example.application/module-info.java")));
        assertFalse(Files.exists(temporaryDirectory.resolve("module-info.java")));
    }

    @Test
    void initializesAModuleWhenTheCurrentDirectoryIsTheSourcePath() throws Exception {
        Path sourcePath = Files.createDirectory(temporaryDirectory.resolve("src"));

        assertEquals(0, initialize(sourcePath, "com.example.application"));

        assertEquals("module com.example.application {\n}\n", Files.readString(sourcePath.resolve("com.example.application/module-info.java")));
    }

    @Test
    void initializesASiblingFromInsideAnExistingSourceModule() throws Exception {
        Path sourcePath = Files.createDirectories(temporaryDirectory.resolve("src"));
        Path library = Files.createDirectories(sourcePath.resolve("com.example.library"));
        Files.writeString(library.resolve("module-info.java"), "module com.example.library {}\n");
        Path packageDirectory = Files.createDirectories(library.resolve("com/example/library"));

        assertEquals(0, initialize(packageDirectory, "com.example.application"));

        assertEquals("module com.example.application {\n}\n", Files.readString(sourcePath.resolve("com.example.application/module-info.java")));
        assertFalse(Files.exists(packageDirectory.resolve("module-info.java")));
    }

    @Test
    void doesNotTreatAConventionalNonModularSrcDirectoryAsAModuleSourcePath() throws Exception {
        Files.createDirectories(temporaryDirectory.resolve("src/main/java"));

        assertEquals(0, initialize(temporaryDirectory, "com.example.application"));

        assertEquals("module com.example.application {\n}\n", Files.readString(temporaryDirectory.resolve("module-info.java")));
        assertFalse(Files.exists(temporaryDirectory.resolve("src/com.example.application/module-info.java")));
    }

    @Test
    void rejectsInitializationInsideAnExistingModule() throws Exception {
        Files.writeString(temporaryDirectory.resolve("module-info.java"), "module com.example.parent {}\n");
        Path nested = Files.createDirectories(temporaryDirectory.resolve("nested/source"));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> initialize(nested, "com.example.child"));

        assertTrue(failure.getMessage()
                          .contains(temporaryDirectory.resolve("module-info.java")
                                  .toString()));
        assertFalse(Files.exists(nested.resolve("module-info.java")));
    }

    @Test
    void refusesToReplaceAnExistingDescriptor() throws Exception {
        Path descriptor = Files.writeString(temporaryDirectory.resolve("module-info.java"), "module com.example.existing {}\n");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> initialize(temporaryDirectory, "com.example.replacement"));

        assertTrue(failure.getMessage()
                          .contains(descriptor.toString()));
        assertEquals("module com.example.existing {}\n", Files.readString(descriptor));
    }

    private static int initialize(Path workingDirectory, String... arguments) throws IOException {
        var commandLineArguments = new String[arguments.length + 1];
        commandLineArguments[0] = "init";
        System.arraycopy(arguments, 0, commandLineArguments, 1, arguments.length);
        var commandLine = JaInvocation.parse(workingDirectory, commandLineArguments);
        return new CommandRunner(ModuleLayer.boot(), ToolRuntime.of(), new ToolCatalog(List.of()),
                () -> {
                    throw new AssertionError("init must not create temporary compilation output");
                })
                .run(commandLine, InputStream.nullInputStream(), new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream()));
    }
}
