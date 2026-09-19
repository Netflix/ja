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
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.spi.ToolProvider;

import com.netflix.tools.ja.CommandRunner;
import com.netflix.tools.ja.JaInvocation;
import com.netflix.tools.ja.ToolCatalog;
import com.netflix.tools.ja.ToolExecutionException;
import com.netflix.tools.ja.ToolRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenDeploymentTest {
    @Test
    void deploysAssembledArtifactsToMavenCentral(@TempDir Path directory) throws Exception {
        sourceModule(directory, "com.example.library");
        var deployment = new ArrayList<String>();
        ToolRuntime tools = tools(directory, deployment, true);
        var commandLine = JaInvocation.parse(
                directory,
                new String[] {"maven", "deploy-central", "--module-version", "1.0", "--name", "Example 1.0",
                        "--manual"});

        int result = run(commandLine, tools);

        assertEquals(0, result);
        assertEquals("maven", deployment.get(0));
        assertEquals("deploy-central", deployment.get(1));
        assertEquals("1.0", value(deployment, "--module-version"));
        assertEquals("Example 1.0", value(deployment, "--name"));
        assertTrue(deployment.contains("--manual"));
        Path artifacts = Path.of(deployment.getLast());
        assertFalse(Files.exists(artifacts));
    }

    @Test
    void preservesTheCallerSelectedModuleScopeDuringPreparation(@TempDir Path directory) throws Exception {
        sourceModule(directory, "com.example.application");
        sourceModule(directory, "com.example.library");

        assertEquals(List.of("com.example.application", "com.example.library"), roots(firstResolution(directory)));
        assertEquals(List.of("com.example.application"), roots(firstResolution(directory.resolve("src/com.example.application"))));
    }

    @Test
    void deploysUsingModuleDeploymentMetadata(@TempDir Path directory) throws Exception {
        sourceModule(directory, "com.example.library");
        Path repository = directory.resolve("repository");
        var deployment = new ArrayList<String>();
        ToolRuntime tools = tools(directory, deployment, true);
        var commandLine = JaInvocation.parse(
                directory,
                new String[] {"maven", "deploy", "--module-version", "1.0", "--repository",
                        repository.toString(), "--sign"});

        int result = run(commandLine, tools);

        assertEquals(0, result);
        assertEquals(List.of("maven", "deploy"), deployment.subList(0, 2));
        assertEquals("1.0", value(deployment, "--module-version"));
        assertEquals(repository.toString(), value(deployment, "--repository"));
        assertTrue(deployment.contains("--sign"));
    }

    @Test
    void deploymentRequiresARepository(@TempDir Path directory) throws Exception {
        sourceModule(directory, "com.example.library");
        var commandLine = JaInvocation.parse(directory, new String[] {"maven", "deploy", "--module-version", "1.0"});

        var failure = assertThrows(IllegalArgumentException.class, () -> run(commandLine, tools(directory, new ArrayList<>(), true)));

        assertEquals("maven deploy requires --repository", failure.getMessage());
    }

    @Test
    void installsWithoutPublicationMetadata(@TempDir Path directory) throws Exception {
        sourceModule(directory, "com.example.library");
        Files.delete(directory.resolve("src/com.example.library/META-INF/com.netflix.tools.ja/maven/deploy.pom"));
        var deployment = new ArrayList<String>();
        ToolRuntime tools = tools(directory, deployment, false);
        var commandLine = JaInvocation.parse(directory, new String[] {"maven", "install", "--module-version", "1.0"});

        int result = run(commandLine, tools);

        assertEquals(0, result);
        assertEquals(List.of("maven", "install"), deployment.subList(0, 2));
        assertEquals("1.0", value(deployment, "--module-version"));
    }

    private static ToolRuntime tools(Path directory, List<String> deployment, boolean expectMetadata) throws Exception {
        String moduleName = "com.example.library";
        Path runtimeModule = Files.createDirectories(directory.resolve("runtime")
                .resolve(moduleName));
        TestModules.writeModuleInfo(runtimeModule, moduleName);
        ToolProvider jig = tool("jig",
                arguments -> {
                    if (!arguments.isEmpty() && arguments.getFirst().equals("maven")) {
                        Path artifacts = Path.of(arguments.getLast());
                        assertTrue(Files.isRegularFile(artifacts.resolve(moduleName + ".jar")));
                        assertTrue(Files.isRegularFile(artifacts.resolve(moduleName + "-sources.jar")));
                        assertTrue(Files.isRegularFile(artifacts.resolve(moduleName + "-javadoc.jar")));
                        assertEquals(expectMetadata, Files.isRegularFile(artifacts.resolve(moduleName + ".pom")));
                        deployment.addAll(arguments);
                        return 0;
                    }
                    int write = arguments.indexOf("--write-argfile");
                    if (write >= 0) {
                        String options = arguments.get(arguments.indexOf("--resolve-options") + 1);
                        String content;
                        if (options.equals("main-class,module-version")) {
                            content = "--module-version\n1.0\n";
                        } else if (options.contains("module-source-path")) {
                            content = "";
                        } else {
                            content = "--module-path\n" + runtimeModule + "\n";
                        }
                        Files.writeString(Path.of(arguments.get(write + 1)), content);
                    }
                    return 0;
                });
        return ToolRuntime.of(jig, ToolProvider.findFirst("jar").orElseThrow(),
                tool("javadoc", arguments -> 0));
    }

    private static int run(JaInvocation commandLine, ToolRuntime tools) throws Exception {
        return new CommandRunner(ModuleLayer.boot(), tools, ToolCatalog.load(ModuleLayer.boot()), () -> null)
                .run(commandLine, InputStream.nullInputStream(), new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream()));
    }

    private static List<String> firstResolution(Path workingDirectory) throws Exception {
        var invocation = new AtomicReference<List<String>>();
        ToolRuntime tools = ToolRuntime.of(tool("jig", arguments -> {
            invocation.set(List.copyOf(arguments));
            return 1;
        }),
                ToolProvider.findFirst("jar").orElseThrow(), tool("javadoc", arguments -> 0));
        var commandLine = JaInvocation.parse(workingDirectory, new String[] {"maven", "install", "--module-version", "1.0"});
        assertThrows(ToolExecutionException.class, () -> run(commandLine, tools));
        assertFalse(invocation.get()
                              .contains("--validate-runtime-access"));
        return invocation.get();
    }

    private static List<String> roots(List<String> arguments) {
        var roots = new ArrayList<String>();
        for (int index = 0; index < arguments.size(); index++) {
            String argument = arguments.get(index);
            if (argument.equals("-m") || argument.equals("--module")) {
                roots.add(arguments.get(++index));
            }
        }
        return List.copyOf(roots);
    }

    private static String value(List<String> arguments, String option) {
        return arguments.get(arguments.indexOf(option) + 1);
    }

    private static void sourceModule(Path directory, String module) throws Exception {
        Path source = Files.createDirectories(directory.resolve("src")
                .resolve(module));
        Files.writeString(source.resolve("module-info.java"), "module " + module + " {}\n");
        Files.writeString(source.resolve("module-info.hash"), "");
        Path deploymentPom = source.resolve("META-INF/com.netflix.tools.ja/maven/deploy.pom");
        Files.createDirectories(deploymentPom.getParent());
        Files.writeString(deploymentPom,
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <name>Example library</name>
                </project>
                """);
    }

    private static ToolProvider tool(String name, Operation operation) {
        return new ToolProvider() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public int run(PrintWriter out, PrintWriter err, String... arguments) {
                try {
                    return operation.run(List.of(arguments));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }

    @FunctionalInterface
    private interface Operation {
        int run(List<String> arguments) throws Exception;
    }
}
