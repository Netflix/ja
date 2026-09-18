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
import java.io.InputStream;
import java.lang.ModuleLayer.Controller;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import javax.tools.Tool;

import com.netflix.tools.ja.InstalledCommandModule;
import com.netflix.tools.ja.InstalledCommandModule.Generated;
import com.netflix.tools.ja.ToolRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstalledCommandModuleTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void adaptsAModuleMainClassToAStdinCapableTool() throws Exception {
        Path targetSource = Files.createDirectories(temporaryDirectory.resolve("target-source"));
        Path mainPackage = Files.createDirectories(targetSource.resolve("example"));
        Files.writeString(targetSource.resolve("module-info.java"), "module com.example.application {}\n");
        Files.writeString(mainPackage.resolve("Main.java"),
                """
                package example;

                public final class Main {
                    public static void main(String[] arguments) throws Exception {
                        System.out.print(arguments[0] + ":" + new String(System.in.readAllBytes()));
                    }
                }
                """);
        Path targetClasses = Files.createDirectories(temporaryDirectory.resolve("target-classes"));
        ToolRuntime tools = ToolRuntime.load(ModuleLayer.boot());
        assertEquals(
                0,
                tools.run(
                        "javac",
                        InputStream.nullInputStream(),
                        System.out,
                        System.err,
                        "-d",
                        targetClasses.toString(),
                        targetSource.resolve("module-info.java").toString(),
                        mainPackage.resolve("Main.java").toString()));

        Generated generated = InstalledCommandModule.generate(
                "application",
                "com.example.application",
                "example.Main",
                Optional.of("1.2.3"),
                List.of(targetClasses),
                temporaryDirectory.resolve("command"),
                tools,
                InputStream.nullInputStream(),
                System.out,
                System.err);

        ModuleFinder finder = ModuleFinder.of(targetClasses, generated.classes());
        Configuration configuration = Configuration.resolveAndBind(finder, List.of(ModuleLayer.boot().configuration()),
                ModuleFinder.of(), Set.of("com.example.application.launcher"));
        Controller controller = ModuleLayer.defineModulesWithOneLoader(configuration, List.of(ModuleLayer.boot()), ClassLoader.getSystemClassLoader());
        ModuleLayer layer = controller.layer();
        Module target = layer.findModule("com.example.application").orElseThrow();
        Module command = layer.findModule("com.example.application.launcher").orElseThrow();
        controller.addOpens(target, "example", command);

        Tool tool = ServiceLoader.load(layer, Tool.class).stream()
                .filter(provider -> provider.type()
                        .getModule()
                        .equals(command))
                .findFirst()
                .orElseThrow()
                .get();
        var output = new ByteArrayOutputStream();
        int result = tool.run(new ByteArrayInputStream("input".getBytes()),
                output, new ByteArrayOutputStream(), "argument");

        assertEquals(0, result);
        assertEquals("application", tool.name());
        assertEquals("argument:input", output.toString());
        assertTrue(command.getDescriptor().requires().stream()
                .noneMatch(requirement -> requirement.name().equals("java.compiler")));
        assertEquals("1.2.3",
                command.getDescriptor()
                       .rawVersion()
                       .orElseThrow());
        assertEquals("example", generated.mainPackage()
                .orElseThrow());
    }
}
