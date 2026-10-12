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

package com.netflix.tools.launcher.test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.ToolProvider;

import com.netflix.tools.launcher.ToolLauncher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolLauncherIntegrationTest {

    @Test
    void delegatesVersionReportingToTheProvider(@TempDir Path directory) throws Exception {
        Path modules = createProbe(directory, directory.resolve("modules"));

        Result result = launch(directory, modules, null, false);

        assertEquals(9, result.exitCode(), result.output());
        assertEquals("provider ran with --version\n", result.output());
    }

    @Test
    void standaloneApplicationWithoutSystemModuleConflictsRunsProvider(@TempDir Path directory) throws Exception {
        Path application = Files.createDirectories(directory.resolve("application"));
        Path modules = createProbe(directory, application.resolve("app/modules"));

        Result result = launch(directory, modules, application, false);

        assertEquals(9, result.exitCode(), result.output());
        assertEquals("provider ran with --version\n", result.output());
    }

    @Test
    void linkedRuntimeCanContainItsApplicationModules(@TempDir Path directory) throws Exception {
        Path modules = createProbe(directory, directory.resolve("modules"));
        Path image = directory.resolve("image");
        Path sourceJavaHome = Path.of(System.getProperty("java.home"));
        String modulePath = String.join(System.getProperty("path.separator"),
                sourceJavaHome.resolve("jmods").toString(), launcherModule().toString(), modules.toString());
        Process link = new ProcessBuilder(executable(sourceJavaHome, "jlink").toString(),
                "--module-path", modulePath, "--add-modules", "com.netflix.tools.launcher,versioned.probe",
                "--output", image.toString()).redirectErrorStream(true).start();
        String output = new String(link.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, link.waitFor(), output);
        Path applicationModules = createProbe(directory, image.resolve("app/modules"));
        Path java = executable(image, "java");

        Result result = launch(java, directory, applicationModules, image, false);

        assertEquals(9, result.exitCode(), result.output());
        assertEquals("provider ran with --version\n", result.output());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsSystemModuleConflictsBeforeProviderExecution(boolean training, @TempDir Path directory) throws Exception {
        Path application = Files.createDirectories(directory.resolve("application"));
        Path modules = createProbe(directory, application.resolve("app/modules"));
        for (String name : List.of("java.xml", "java.logging")) {
            Path descriptor = Files.writeString(directory.resolve("module-info.java"), "module " + name + " {}\n");
            Path conflictingModule = Files.createDirectories(modules.resolve(name));
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                    "-d", conflictingModule.toString(), descriptor.toString()));
        }

        Result result = launch(directory, modules, application, training);

        assertEquals(2, result.exitCode(), result.output());
        assertEquals("Standalone application modules conflict with system modules: java.logging, java.xml; select a plain JDK with JAVA_HOME\n",
                result.output());
    }

    private static Path createProbe(Path directory, Path modules) throws Exception {
        Path source = Files.createDirectories(directory.resolve("src/versioned.probe"));
        Files.writeString(source.resolve("module-info.java"),
                """
                module versioned.probe {
                    provides java.util.spi.ToolProvider with probe.Probe;
                }
                """);
        Path packageDirectory = Files.createDirectories(source.resolve("probe"));
        Files.writeString(packageDirectory.resolve("Probe.java"),
                """
                package probe;
                public final class Probe implements java.util.spi.ToolProvider {
                    public String name() { return "versioned-probe"; }
                    public int run(java.io.PrintWriter out, java.io.PrintWriter err,
                            String... arguments) {
                        out.println("provider ran with " + String.join(" ", arguments));
                        return 9;
                    }
                }
                """);
        Path probeModule = Files.createDirectories(modules.resolve("versioned.probe"));
        int compilation = ToolProvider.getSystemJavaCompiler().run(
                null,
                null,
                null,
                "--module-version",
                "1.2.3",
                "-d",
                probeModule.toString(),
                source.resolve("module-info.java").toString(),
                packageDirectory.resolve("Probe.java").toString());
        assertEquals(0, compilation);
        return modules;
    }

    private static Result launch(Path directory, Path modules, Path applicationHome, boolean training) throws Exception {
        return launch(javaExecutable(), directory, modules, applicationHome, training);
    }

    private static Result launch(Path java, Path directory, Path modules, Path applicationHome, boolean training) throws Exception {
        var arguments = new ArrayList<>(List.of(
                java.toString(),
                "--upgrade-module-path",
                launcherModule().toString(),
                "--patch-module",
                "com.netflix.tools.launcher=" + launcherPatch(directory)));
        if (applicationHome != null) {
            arguments.add("-Dapplication.home=" + applicationHome);
        }
        if (training) {
            arguments.add("-Dcom.netflix.tools.launcher.aot.training=true");
        }
        arguments.addAll(List.of(
                "--module-path",
                modules.toString(),
                "--add-modules",
                "versioned.probe",
                "-m",
                "com.netflix.tools.launcher/com.netflix.tools.launcher.ToolLauncher",
                "versioned-probe",
                "--version"));
        Process process = new ProcessBuilder(arguments)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream()
                .readAllBytes(),
                        StandardCharsets.UTF_8);
        return new Result(process.waitFor(), output);
    }

    private record Result(int exitCode, String output) {}

    private static Path launcherPatch(Path directory) throws Exception {
        URI classLocation = ToolLauncher.class.getResource("ToolLauncher.class").toURI();
        Path source = Path.of(classLocation);
        Path destination = directory.resolve(ToolLauncher.class.getName().replace('.', '/') + ".class");
        Files.createDirectories(destination.getParent());
        Files.copy(source, destination);
        return directory;
    }

    private static Path launcherModule() {
        URI launcherLocation = ModuleLayer.boot()
                .configuration()
                .findModule("com.netflix.tools.launcher")
                .orElseThrow()
                .reference()
                .location()
                .orElseThrow();
        return Path.of(launcherLocation);
    }

    private static Path javaExecutable() {
        return executable(Path.of(System.getProperty("java.home")), "java");
    }

    private static Path executable(Path javaHome, String name) {
        return javaHome.resolve("bin").resolve(System.getProperty("os.name").startsWith("Windows") ? name + ".exe" : name);
    }
}
