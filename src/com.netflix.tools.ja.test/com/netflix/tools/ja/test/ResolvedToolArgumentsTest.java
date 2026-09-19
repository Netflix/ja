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

import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import com.netflix.tools.ja.ResolvedToolArguments;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResolvedToolArgumentsTest {
    @Test
    void usesTheEffectiveGraphRatherThanEveryObservableModule(@TempDir Path directory) throws Exception {
        TestModules.writeJar(directory.resolve("com.example.app.jar"), "com.example.app", "com.example.library");
        TestModules.writeJar(directory.resolve("com.example.library.jar"), "com.example.library");
        TestModules.writeJar(directory.resolve("com.example.unrelated.jar"), "com.example.unrelated");

        var resolved = ResolvedToolArguments.resolve(List.of("--enable-preview"), List.of("--module-path", directory.toString(), "--add-modules", "com.example.app"),
                ModuleLayer.boot());

        assertEquals(List.of("--enable-preview"), resolved.arguments());
        assertEquals(Set.of("com.example.app", "com.example.library"), resolved.modules());
        assertFalse(resolved.modules()
                            .contains("com.example.unrelated"));
    }

    @Test
    void usesParentModulesInsteadOfModulePathReplacements(@TempDir Path directory) throws Exception {
        Path parentModules = Files.createDirectories(directory.resolve("parent"));
        TestModules.writeJar(parentModules.resolve("com.example.duplicate.jar"), "com.example.duplicate", "java.logging");
        TestModules.writeJarWithTransitive(parentModules.resolve("com.example.bridge.jar"), "com.example.bridge", "com.example.duplicate");
        Configuration parentConfiguration = Configuration.resolve(
                ModuleFinder.of(parentModules),
                List.of(ModuleLayer.boot().configuration()),
                ModuleFinder.of(),
                Set.of("com.example.bridge", "com.example.duplicate"));
        ModuleLayer parent = ModuleLayer.defineModulesWithOneLoader(parentConfiguration, List.of(ModuleLayer.boot()), ClassLoader.getSystemClassLoader()).layer();

        Path childModules = Files.createDirectories(directory.resolve("child"));
        TestModules.writeJar(childModules.resolve("com.example.duplicate.jar"), "com.example.duplicate");
        TestModules.writeJar(childModules.resolve("com.example.app.jar"), "com.example.app", "com.example.bridge", "com.example.duplicate");

        var resolved = ResolvedToolArguments.resolve(List.of(), List.of("--module-path", childModules.toString(), "--add-modules", "com.example.app"), parent);

        assertTrue(resolved.modules()
                           .containsAll(Set.of("com.example.app", "com.example.duplicate")));
        var duplicate = resolved.moduleDescriptors().stream()
                .filter(descriptor -> descriptor.name().equals("com.example.duplicate"))
                .findFirst()
                .orElseThrow();
        assertTrue(duplicate.requires().stream()
                .anyMatch(require -> require.name().equals("java.logging")), duplicate.toString());
    }
}
