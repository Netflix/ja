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
import java.lang.module.ModuleDescriptor.Version;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.netflix.tools.ja.ToolDefinition;
import com.netflix.tools.ja.ToolDefinition.Launch;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolDefinitionTest {
    @Test
    void readsProviderTool() throws Exception {
        ToolDefinition definition = definition("jist",
                """
                        module=com.netflix.tools.jist@1.0
                        options=module-path,module-source-path,module=list
                        """);

        assertEquals(Launch.PROVIDER, definition.launch());
        assertEquals(Optional.of("com.netflix.tools.jist"), definition.module());
        assertEquals(Optional.of("1.0"), definition.version());
        assertEquals(Set.of("module-path", "module-source-path", "module=list"), definition.options());
        assertEquals(-1, definition.isSupportedOption("--verbose"));
    }

    @Test
    void readsActivatedToolDefinition() throws Exception {
        ToolDefinition definition = definition("junit",
                """
                        launch=java
                        activation=org.junit.platform.engine
                        module=org.junit.platform.console
                        defaults=execute --disable-banner "--reports-dir=test reports"
                        options=module-path,add-modules,enable-preview,add-opens,verbose
                        class-suffix=Test
                        package-suffix=test
                        """);

        assertEquals("junit", definition.name());
        assertEquals(Launch.JAVA, definition.launch());
        assertEquals(Optional.of("org.junit.platform.engine"), definition.activation());
        assertEquals(Optional.of("org.junit.platform.console"), definition.module());
        assertEquals(List.of("execute", "--disable-banner", "--reports-dir=test reports"), definition.defaults());
        assertEquals("junit", definition.provider());
        assertEquals(
                Set.of("module-path", "add-modules", "enable-preview", "add-opens", "verbose"),
                definition.options());
        assertEquals(Optional.of("Test"), definition.classSuffix());
        assertEquals(Optional.of("test"), definition.packageSuffix());
        assertEquals(1, definition.isSupportedOption("--module-path"));
        assertEquals(0, definition.isSupportedOption("--enable-preview"));
        assertEquals(0, definition.isSupportedOption("--verbose"));
        assertEquals(-1, definition.isSupportedOption("--release"));
        assertEquals("6.1.2", definition.resolveVersion(Optional.of(Version.parse("6.1.2"))));
    }

    @Test
    void moduleVersionOverridesActivationVersion() throws Exception {
        ToolDefinition definition = definition("formatter",
                """
                        activation=java.compiler
                        module=com.example.formatter@2.0.0
                        """);

        assertEquals(Optional.of("com.example.formatter"), definition.module());
        assertEquals(Optional.of("2.0.0"), definition.version());
        assertEquals("2.0.0", definition.resolveVersion(Optional.of(Version.parse("25"))));
    }

    @Test
    void rejectsConflictingToolVersions() {
        var failure = assertThrows(IllegalArgumentException.class,
                () ->
                        definition("formatter",
                                """
                        module=com.example.formatter@2.0.0
                        version=2.1.0
                        """));

        assertEquals("Tool version is specified in both module and version", failure.getMessage());
    }

    @Test
    void alwaysActiveToolRequiresExplicitVersion() throws Exception {
        ToolDefinition definition = definition("formatter",
                """
                        module=com.example.formatter
                        """);

        assertThrows(IllegalStateException.class, () -> definition.resolveVersion(Optional.empty()));
    }

    @Test
    void rejectsUnknownLaunchStyle() {
        var failure = assertThrows(IllegalArgumentException.class, () -> definition("broken", "launch=process\n"));

        assertEquals("launch must be provider or java", failure.getMessage());
    }

    @Test
    void readsExplicitSingleAndRootModuleForms() throws Exception {
        assertEquals(Set.of("module=single"), definition("single", "options=module=single\n").options());
        assertEquals(Set.of("module=roots", "add-modules"), definition("roots", "options=module=roots,add-modules\n").options());
    }

    @Test
    void rejectsOptionSpellingsRatherThanNormalizedKeys() {
        assertThrows(IllegalArgumentException.class, () -> definition("broken", "options=--module-path\n"));
    }

    @Test
    void rejectsRootModuleFormWithoutAddModules() {
        var exception = assertThrows(IllegalArgumentException.class, () -> definition("broken", "options=module=roots\n"));
        assertEquals("module=roots requires add-modules", exception.getMessage());
    }

    @Test
    void rejectsConflictingModuleForms() {
        assertThrows(IllegalArgumentException.class, () -> definition("broken", "options=module,module=list\n"));
    }

    @Test
    void packageSuffixIsASinglePackagePart() {
        assertThrows(IllegalArgumentException.class, () -> definition("broken", "package-suffix=.test\n"));
        assertThrows(IllegalArgumentException.class, () -> definition("broken", "package-suffix=integration.test\n"));
    }

    @Test
    void installedToolDoesNotRequireAModule() throws Exception {
        ToolDefinition definition = definition("jar", "provider=jar\n");

        assertEquals(Optional.empty(), definition.module());
    }

    private static ToolDefinition definition(String name, String properties) throws Exception {
        return ToolDefinition.read(name, new ByteArrayInputStream(properties.getBytes(StandardCharsets.UTF_8)));
    }
}
