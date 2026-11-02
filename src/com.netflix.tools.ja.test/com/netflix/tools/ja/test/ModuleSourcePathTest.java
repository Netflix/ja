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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.netflix.tools.ja.JaInvocation;
import com.netflix.tools.ja.ModuleSourcePath;
import com.netflix.tools.ja.ModuleSourcePath.Directory;
import com.netflix.tools.ja.ModuleSourcePath.ModuleMapping;
import com.netflix.tools.ja.ToolRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleSourcePathTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void discoversStandaloneModuleAsAModuleSpecificEntry() throws IOException {
        Path module = module(temporaryDirectory.resolve("standalone"), "com.example.app");
        Path workingDirectory = Files.createDirectories(module.resolve("com/example/app"));

        ModuleSourcePath sourcePath = ModuleSourcePath.discover(workingDirectory).orElseThrow();

        assertEquals(List.of("com.example.app=" + module), sourcePath.arguments());
        assertInstanceOf(ModuleMapping.class, sourcePath.entries()
                .getFirst());
        assertEquals(List.of("com.example.app"), sourcePath.defaultRoots());
    }

    @Test
    void discoversAMultiReleaseModuleFromItsBaseAndDescriptorSection() throws IOException {
        Path root = multiReleaseModule(temporaryDirectory.resolve("standalone"), "com.example.app");
        Path packageDirectory = Files.createDirectories(root.resolve("com/example/app"));
        for (Path start : List.of(root, packageDirectory, root.resolve("META-INF/versions/9"),
                root.resolve("META-INF/versions/9/module-info.java"))) {
            var sourcePath = ModuleSourcePath.discover(start).orElseThrow();

            assertEquals(List.of("com.example.app=" + root), sourcePath.arguments(), start.toString());
            assertEquals(root, sourcePath.modules().get("com.example.app"), start.toString());
            assertEquals(List.of("com.example.app"), sourcePath.defaultRoots(), start.toString());
        }
    }

    @Test
    void discoversMultiReleaseModulesAlongsideConventionalAndJmodLayouts() throws IOException {
        Path sourceRoot = temporaryDirectory.resolve("multi/src");
        Path app = multiReleaseModule(sourceRoot.resolve("com.example.app"), "com.example.app");
        Path classes = multiReleaseModule(sourceRoot.resolve("com.example.jmod/classes"), "com.example.jmod");
        module(sourceRoot.resolve("com.example.library"), "com.example.library");

        for (Path start : List.of(sourceRoot.getParent(), sourceRoot)) {
            var sourcePath = ModuleSourcePath.discover(start).orElseThrow();
            assertEquals(List.of("com.example.app", "com.example.jmod", "com.example.library"), sourcePath.defaultRoots());
            assertEquals(app, sourcePath.modules().get("com.example.app"));
            assertEquals(classes, sourcePath.modules().get("com.example.jmod"));
            assertEquals(List.of("com.example.app=" + app, "com.example.jmod=" + classes,
                    "com.example.library=" + sourceRoot.resolve("com.example.library")), sourcePath.arguments());
        }
        for (Path start : List.of(app, app.resolve("META-INF/versions/9"))) {
            assertEquals(List.of("com.example.app"), ModuleSourcePath.discover(start).orElseThrow().defaultRoots());
        }
        for (Path start : List.of(classes.getParent(), classes, classes.resolve("META-INF/versions/9"))) {
            assertEquals(List.of("com.example.jmod"), ModuleSourcePath.discover(start).orElseThrow().defaultRoots());
        }
    }

    @Test
    void compileDefersMultiReleaseDescriptorParsingToTheResolver() throws IOException {
        Path root = multiReleaseModule(temporaryDirectory.resolve("multi/src/com.example.app"), "com.example.app");
        Files.writeString(root.resolve("META-INF/versions/9/module-info.java"), "not yet a valid descriptor\n");

        var invocation = JaInvocation.parse(temporaryDirectory.resolve("multi"), new String[] {"compile"})
                .resolveProjects(ToolRuntime.of(), InputStream.nullInputStream(), System.err);

        assertEquals(List.of("com.example.app"), invocation.rootModules());
    }

    @Test
    void rejectsMultiReleaseDirectoryAndDeclaredModuleNameMismatch() throws IOException {
        Path root = multiReleaseModule(temporaryDirectory.resolve("multi/src/wrong.name"), "com.example.app");

        var failure = assertThrows(IllegalArgumentException.class,
                () -> ModuleSourcePath.discover(root.getParent()));

        assertTrue(failure.getMessage().contains("wrong.name"));
        assertTrue(failure.getMessage().contains("com.example.app"));
    }

    @Test
    void identifiesTheEnclosingModuleAndKeepsSiblingModulesObservable() throws IOException {
        Path sourceRoot = temporaryDirectory.resolve("multi/src");
        module(sourceRoot.resolve("com.example.app"), "com.example.app");
        Path core = module(sourceRoot.resolve("com.example.core"), "com.example.core");
        Path workingDirectory = Files.createDirectories(core.resolve("com/example/core"));

        ModuleSourcePath sourcePath = ModuleSourcePath.discover(workingDirectory).orElseThrow();

        assertEquals(List.of(sourceRoot.toString()), sourcePath.arguments());
        assertInstanceOf(Directory.class, sourcePath.entries()
                .getFirst());
        assertEquals(List.of("com.example.core"), sourcePath.defaultRoots());
        assertEquals(List.of("com.example.app", "com.example.core"), sourcePath.moduleNames());
    }

    @Test
    void discoversAJmodLayoutModule() throws IOException {
        Path sourceRoot = temporaryDirectory.resolve("multi/src");
        Path module = sourceRoot.resolve("com.example.jmod");
        Path classes = module(module.resolve("classes"), "com.example.jmod");
        Path workingDirectory = Files.createDirectories(classes.resolve("com/example"));

        ModuleSourcePath sourcePath = ModuleSourcePath.discover(workingDirectory).orElseThrow();

        assertEquals(List.of("com.example.jmod=" + classes), sourcePath.arguments());
        assertEquals(List.of("com.example.jmod"), sourcePath.defaultRoots());
        assertEquals(classes, sourcePath.modules()
                .get("com.example.jmod"));
    }

    @Test
    void identifiesAJmodLayoutModuleFromItsModuleDirectory() throws IOException {
        Path sourceRoot = temporaryDirectory.resolve("multi/src");
        Path module = sourceRoot.resolve("com.example.jmod");
        Path classes = module(module.resolve("classes"), "com.example.jmod");
        module(sourceRoot.resolve("com.example.sibling"), "com.example.sibling");

        ModuleSourcePath sourcePath = ModuleSourcePath.discover(module).orElseThrow();

        assertEquals(List.of("com.example.jmod"), sourcePath.defaultRoots());
        assertEquals(List.of("com.example.jmod", "com.example.sibling"), sourcePath.moduleNames());
        assertEquals(classes, sourcePath.modules()
                .get("com.example.jmod"));
    }

    @Test
    void selectsAllModulesWhenRunFromTheSourcePathRoot() throws IOException {
        Path sourceRoot = temporaryDirectory.resolve("multi/src");
        module(sourceRoot.resolve("com.example.zeta"), "com.example.zeta");
        module(sourceRoot.resolve("com.example.alpha"), "com.example.alpha");

        ModuleSourcePath sourcePath = ModuleSourcePath.discover(sourceRoot.getParent()).orElseThrow();

        assertEquals(List.of("com.example.alpha", "com.example.zeta"), sourcePath.defaultRoots());
    }

    @Test
    void selectsAllModulesWhenRunFromSrcItself() throws IOException {
        Path sourceRoot = temporaryDirectory.resolve("multi/src");
        module(sourceRoot.resolve("com.example.zeta"), "com.example.zeta");
        module(sourceRoot.resolve("com.example.alpha"), "com.example.alpha");

        ModuleSourcePath sourcePath = ModuleSourcePath.discover(sourceRoot).orElseThrow();

        assertEquals(List.of("com.example.alpha", "com.example.zeta"), sourcePath.defaultRoots());
    }

    @Test
    void discoversTheEnclosingSourcePathFromItsNonModuleDescendant() throws IOException {
        Path sourceRoot = temporaryDirectory.resolve("multi/src");
        module(sourceRoot.resolve("com.example.app"), "com.example.app");
        module(sourceRoot.resolve("com.example.core"), "com.example.core");
        Path workingDirectory = Files.createDirectories(sourceRoot.resolve("scratch/generated"));

        ModuleSourcePath sourcePath = ModuleSourcePath.discover(workingDirectory).orElseThrow();

        assertEquals(List.of("com.example.app", "com.example.core"), sourcePath.defaultRoots());
        assertTrue(sourcePath.enclosingModule()
                             .isEmpty());
    }

    @Test
    void doesNotDiscoverSourcePathFromNonModuleDescendant() throws IOException {
        Path root = temporaryDirectory.resolve("multi");
        module(root.resolve("src/com.example.app"), "com.example.app");
        Path workingDirectory = Files.createDirectories(root.resolve("docs/design"));

        assertTrue(ModuleSourcePath.discover(workingDirectory)
                .isEmpty());
    }

    @Test
    void nearestModuleSourcePathWins() throws IOException {
        Path outer = temporaryDirectory.resolve("outer");
        module(outer.resolve("src/com.example.outer"), "com.example.outer");
        Path inner = outer.resolve("nested");
        module(inner.resolve("src/com.example.inner"), "com.example.inner");

        ModuleSourcePath sourcePath = ModuleSourcePath.discover(inner).orElseThrow();

        assertEquals(List.of("com.example.inner"), sourcePath.defaultRoots());
    }

    @Test
    void compileDefersDescriptorParsingToTheResolver() throws IOException {
        Path sourceRoot = temporaryDirectory.resolve("multi/src");
        Path module = Files.createDirectories(sourceRoot.resolve("com.example.app"));
        Files.writeString(module.resolve("module-info.java"), "not yet a valid descriptor\n");

        JaInvocation commandLine = JaInvocation.parse(sourceRoot.getParent(), new String[] {"compile"}).resolveProjects(ToolRuntime.of(), InputStream.nullInputStream(), System.err);

        assertEquals(List.of("com.example.app"), commandLine.rootModules());
        assertEquals(List.of(sourceRoot.toString()),
                commandLine.moduleSourcePath()
                           .orElseThrow()
                           .arguments());
    }

    @Test
    void rejectsDirectoryAndDeclaredModuleNameMismatch() throws IOException {
        Path sourceRoot = temporaryDirectory.resolve("multi/src");
        module(sourceRoot.resolve("wrong.name"), "com.example.app");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> ModuleSourcePath.discover(sourceRoot.getParent()));

        assertTrue(exception.getMessage()
                            .contains("wrong.name"));
        assertTrue(exception.getMessage()
                            .contains("com.example.app"));
    }

    @Test
    void noModuleSourcePathMeansNoDiscovery() throws IOException {
        Path workingDirectory = Files.createDirectories(temporaryDirectory.resolve("empty/nested"));

        assertTrue(ModuleSourcePath.discover(workingDirectory)
                .isEmpty());
    }

    private static Path multiReleaseModule(Path directory, String name) throws IOException {
        Path section = Files.createDirectories(directory.resolve("META-INF/versions/9"));
        Files.writeString(section.resolve("module-info.java"), "/** @release 8 */ module " + name + " {}\n");
        Files.writeString(directory.resolve("META-INF/MANIFEST.MF"), "Manifest-Version: 1.0\nMulti-Release: true\n\n");
        return directory.toAbsolutePath().normalize();
    }

    private static Path module(Path directory, String name) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("module-info.java"), "module " + name + " {}\n");
        return directory.toAbsolutePath().normalize();
    }
}
