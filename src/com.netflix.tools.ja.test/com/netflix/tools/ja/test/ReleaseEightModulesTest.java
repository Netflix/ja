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
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.classfile.ClassFile;
import java.lang.module.ModuleFinder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import java.util.spi.ToolProvider;
import java.util.zip.ZipFile;

import com.netflix.tools.ja.CommandRunner;
import com.netflix.tools.ja.JaInvocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseEightModulesTest {
    @TempDir
    Path directory;

    @Test
    void compilesAndRunsReleaseEightModules() throws Exception {
        application();

        run(directory, "compile");
        String output = run(directory, "run", "-m", "example.app");

        assertEquals("Hello from release 8\n", output.replace(System.lineSeparator(), "\n"));
    }

    @Test
    void updatesTheVersionedDescriptorWithoutRelocatingIt() throws Exception {
        Path app = application();
        String manifest = Files.readString(app.resolve("META-INF/MANIFEST.MF"));

        run(app, "require", "--static", "org.apiguardian.api@1.1.2");

        assertTrue(Files.readString(app.resolve("META-INF/versions/9/module-info.java"))
                .contains("requires static org.apiguardian.api; // @1.1.2"));
        assertTrue(Files.readString(app.resolve("module-info.hash"))
                .contains("org.apiguardian.api@1.1.2="));
        assertEquals(manifest, Files.readString(app.resolve("META-INF/MANIFEST.MF")));
        assertFalse(Files.exists(app.resolve("module-info.java")));
        assertTrue(run(app, "run").contains("Hello from release 8"));
    }

    @Test
    void runsAModernTestModuleAgainstReleaseEightModules() throws Exception {
        application();
        Path tests = Files.createDirectories(directory.resolve("src/example.tests"));
        Files.writeString(tests.resolve("module-info.java"),
                """
                /** @release 25 */
                open module example.tests {
                    requires example.app;
                    requires org.junit.jupiter; // @6.1.3
                }
                """);
        Files.createDirectories(tests.resolve("test"));
        Files.writeString(tests.resolve("test/MainTest.java"),
                """
                package test;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                public class MainTest {
                    @Test
                    public void greeting() {
                        assertEquals("Hello from release 8", app.Main.message());
                    }
                }
                """);
        int result = ToolProvider.findFirst("jig").orElseThrow().run(System.out, System.err,
                "--module-source-path", directory.resolve("src").toString(), "-m", "example.tests",
                "--update-module-hashes");
        assertEquals(0, result);

        String output = run(directory, "test", "--all");

        assertTrue(output.contains("1 found, 1 successful"), output);
    }

    @Test
    void javacUsesTheReleaseEightClassPath() throws Exception {
        Path app = application();
        Path output = directory.resolve("classes");

        run(app, "tool", "javac", "-d", output.toString(), app.resolve("app/Main.java").toString());

        assertEquals(52, ClassFile.of().parse(output.resolve("app/Main.class")).majorVersion());
    }

    @Test
    void javadocUsesTheReleaseEightClassPathAndSourcePath() throws Exception {
        Path app = application();
        Path output = directory.resolve("documentation");

        run(app, "tool", "javadoc", "-quiet", "-Xdoclint:all,-missing", "-d", output.toString(), "app");

        assertTrue(Files.isRegularFile(output.resolve("app/Main.html")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void assemblyPreservesTheMultiReleaseLayout(boolean jmodLayout) throws Exception {
        Path app = application();
        Files.writeString(app.resolve("META-INF/MANIFEST.MF"),
                """
                Manifest-Version: 1.0
                Multi-Release: true
                Premain-Class: app.Agent
                Implementation-Title: Example Agent

                Name: app/
                Sealed: true

                """);
        Files.writeString(app.resolve("app/Agent.java"),
                """
                package app;
                public final class Agent {
                    public static void premain(String arguments) {}
                }
                """);
        Path source = app;
        if (jmodLayout) {
            Path relocated = app.resolveSibling("staged-app");
            Files.move(app, relocated);
            Files.createDirectory(app);
            source = Files.move(relocated, app.resolve("classes"));
        }
        Path metadata = app.resolve("META-INF/com.netflix.tools.ja/maven/deploy.pom");
        Files.createDirectories(metadata.getParent());
        String pom = "<project><modelVersion>4.0.0</modelVersion><name>Example</name></project>\n";
        Files.writeString(metadata, pom);
        Files.writeString(source.resolve("module-info.pom"), "export intermediary\n");
        Path output = directory.resolve("artifacts");

        run(directory, "assemble", "--module-version", "1.0", output.toString());

        try (var archive = new ZipFile(output.resolve("example.app-javadoc.jar").toFile())) {
            assertNotNull(archive.getEntry("app/Main.html"));
        }
        Path binary = output.resolve("example.app.jar");
        try (var archive = new JarFile(binary.toFile())) {
            var manifest = archive.getManifest();
            assertEquals("app.Agent", manifest.getMainAttributes().getValue("Premain-Class"));
            assertEquals("Example Agent", manifest.getMainAttributes().getValue("Implementation-Title"));
            assertEquals("true", manifest.getAttributes("app/").getValue("Sealed"));
            assertNull(archive.getEntry("module-info.class"));
            assertNull(archive.getEntry("module-info.pom"));
            assertEquals(pom, new String(archive.getInputStream(archive.getEntry("META-INF/com.netflix.tools.ja/maven/deploy.pom"))
                    .readAllBytes(), StandardCharsets.UTF_8));
            assertEquals(52, ClassFile.of().parse(archive.getInputStream(archive.getEntry("app/Main.class"))
                    .readAllBytes()).majorVersion());
            assertEquals(53, ClassFile.of().parse(archive.getInputStream(archive.getEntry("META-INF/versions/9/module-info.class"))
                    .readAllBytes()).majorVersion());
        }
        var descriptor = ModuleFinder.of(binary).find("example.app").orElseThrow().descriptor();
        assertEquals("1.0", descriptor.rawVersion().orElseThrow());
        assertEquals("app.Main", descriptor.mainClass().orElseThrow());
    }

    @Test
    void jmodsUseTheSelectedModuleContents() throws Exception {
        application();
        Path output = directory.resolve("artifacts");

        run(directory, "assemble", "--jmod", "--module-version", "1.0", output.toString());

        try (var archive = new ZipFile(output.resolve("example.app.jmod").toFile())) {
            assertNotNull(archive.getEntry("classes/module-info.class"));
            assertNull(archive.getEntry("classes/META-INF/versions/9/module-info.class"));
            assertEquals(52, ClassFile.of().parse(archive.getInputStream(archive.getEntry("classes/app/Main.class"))
                    .readAllBytes()).majorVersion());
        }
    }

    @Test
    void sourceInspectionUsesBaseSources() throws Exception {
        Path app = application();

        String output = run(app, "describe", "type", "app.Main", "--source", "unit");

        assertTrue(output.contains("dep.Greeting.message()"), output);
        assertTrue(output.contains("Main.java:"), output);
    }

    private Path application() throws Exception {
        Path dependency = module("example.dep", "exports dep;");
        Files.createDirectories(dependency.resolve("dep"));
        Files.writeString(dependency.resolve("dep/Greeting.java"),
                """
                package dep;
                public final class Greeting {
                    public static String message() {
                        return "Hello from release 8";
                    }
                }
                """);
        Path app = module("example.app", "requires example.dep;\n    exports app;");
        Files.writeString(app.resolve("META-INF/versions/9/module-info.java"),
                """
                /**
                 * @release 8
                 * @mainClass app.Main
                 */
                module example.app {
                    requires example.dep;
                    exports app;
                }
                """);
        Files.createDirectories(app.resolve("app"));
        Files.writeString(app.resolve("app/Main.java"),
                """
                package app;
                public final class Main {
                    public static String message() {
                        return dep.Greeting.message();
                    }
                    public static void main(String[] arguments) {
                        System.out.println(message());
                    }
                }
                """);
        return app;
    }

    private Path module(String name, String directives) throws Exception {
        Path module = directory.resolve("src").resolve(name);
        Files.createDirectories(module.resolve("META-INF/versions/9"));
        Files.writeString(module.resolve("META-INF/MANIFEST.MF"), "Manifest-Version: 1.0\nMulti-Release: true\n\n");
        Files.writeString(module.resolve("META-INF/versions/9/module-info.java"),
                "/** @release 8 */\nmodule " + name + " {\n    " + directives + "\n}\n");
        return module;
    }

    private static String run(Path directory, String... arguments) throws Exception {
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        int result = new CommandRunner(ModuleLayer.boot()).run(JaInvocation.parse(directory, arguments),
                InputStream.nullInputStream(), new PrintStream(output), new PrintStream(errors));
        assertEquals(0, result, errors.toString());
        return output.toString();
    }
}
