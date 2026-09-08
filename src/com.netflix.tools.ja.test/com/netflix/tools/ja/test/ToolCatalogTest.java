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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.netflix.tools.ja.ToolCatalog;
import com.netflix.tools.ja.ToolDefinition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCatalogTest {
    @Test
    void loadsDefinitionsFromSelectedModules() throws Exception {
        ToolCatalog catalog = ToolCatalog.load(ToolCatalogTest.class.getModule().getLayer(),
                Set.of("com.netflix.tools.ja", "com.netflix.tools.launcher.test"));

        List<String> definitions = catalog.definitions().stream()
                .map(ToolDefinition::name)
                .toList();
        assertTrue(
                definitions.containsAll(
                        List.of(
                                "configured-probe",
                                "jar",
                                "javac",
                                "javadoc",
                                "javap",
                                "jdeps",
                                "jlink",
                                "jmh",
                                "jmod",
                                "jnativescan",
                                "jpackage",
                                "jshell",
                                "junit")),
                () -> "Missing selected tool definitions: " + definitions);
        ToolDefinition jmh = catalog.definition("jmh");
        assertEquals(Optional.of("org.openjdk.jmh.core"), jmh.activation());
        assertEquals(Optional.of("com.netflix.tools.jmh"), jmh.module());
        assertEquals(Optional.of("0.17.5"), jmh.version());
        ToolDefinition probe = catalog.definition("configured-probe");
        assertEquals(Optional.empty(), probe.module());
        assertEquals(Set.of("module-path", "module=list"), probe.options());
        assertEquals(
                Set.of("module-path", "upgrade-module-path", "module-source-path", "module=list", "add-modules", "release",
                        "enable-preview", "add-exports"),
                catalog.definition("javadoc").options());
        assertEquals(Set.of("module-path", "upgrade-module-path", "add-modules", "module=roots", "multi-release"),
                catalog.definition("jdeps").options());
        assertEquals(List.of("execute", "--details=summary", "--disable-banner", "--disable-ansi-colors"),
                catalog.definition("junit").defaults());
    }

    @Test
    void selectsNamedToolActivatedByResolvedModule() throws Exception {
        ToolCatalog catalog = new ToolCatalog(List.of(definition("junit", "org.junit.platform.engine"), definition("other", "org.example.engine")));

        ToolDefinition selected = catalog.named("junit", Set.of("org.junit.platform.engine"));

        assertEquals("junit", selected.name());
    }

    @Test
    void namedToolsDoNotCompeteForABuiltInRole() throws Exception {
        ToolCatalog catalog = new ToolCatalog(List.of(definition("junit", "org.junit.platform.engine"), definition("other", "org.example.engine")));
        Set<String> modules = Set.of("org.junit.platform.engine", "org.example.engine");

        assertEquals("junit", catalog.named("junit", modules)
                .name());
        assertEquals("other", catalog.named("other", modules)
                .name());
    }

    @Test
    void toolWithoutActivationIsAlwaysActive() throws Exception {
        ToolCatalog catalog = new ToolCatalog(List.of(definition("jfmt", null)));

        assertEquals("jfmt", catalog.named("jfmt", Set.of())
                .name());
    }

    private static ToolDefinition definition(String name, String activation) throws Exception {
        String properties = "module=com.example."
                + name
                + "@1.0\n"
                + (activation == null ? "" : "activation=" + activation + "\n");
        return ToolDefinition.read(name, new ByteArrayInputStream(properties.getBytes(StandardCharsets.UTF_8)));
    }
}
