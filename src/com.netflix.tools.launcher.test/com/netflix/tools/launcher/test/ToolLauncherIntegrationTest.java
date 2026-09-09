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
import javax.tools.ToolProvider;

import com.netflix.tools.launcher.ToolLauncher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolLauncherIntegrationTest {

    @Test
    void delegatesVersionReportingToTheProvider(@TempDir Path directory) throws Exception {
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
        Path modules = Files.createDirectories(directory.resolve("modules"));
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

        Process process = new ProcessBuilder(
                javaExecutable().toString(),
                "--upgrade-module-path",
                launcherModule().toString(),
                "--patch-module",
                "com.netflix.tools.launcher=" + launcherClasses(),
                "--module-path",
                modules.toString(),
                "--add-modules",
                "versioned.probe",
                "-m",
                "com.netflix.tools.launcher/com.netflix.tools.launcher.ToolLauncher",
                "versioned-probe",
                "--version")
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream()
                .readAllBytes(),
                        StandardCharsets.UTF_8);
        int result = process.waitFor();

        assertEquals(9, result, output);
        assertEquals("provider ran with --version\n", output);
    }

    private static Path launcherClasses() throws Exception {
        URI classLocation = ToolLauncher.class.getResource("ToolLauncher.class").toURI();
        Path root = Path.of(classLocation);
        for (int i = 0; i < ToolLauncher.class.getName().split("\\.").length; i++) {
            root = root.getParent();
        }
        return root;
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
        return Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
    }
}
