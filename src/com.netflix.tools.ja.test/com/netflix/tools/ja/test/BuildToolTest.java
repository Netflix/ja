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
import java.io.File;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.spi.ToolProvider;

import com.netflix.tools.ja.CommandRunner;
import com.netflix.tools.ja.JaInvocation;
import com.netflix.tools.ja.ToolCatalog;
import com.netflix.tools.ja.ToolDefinition;
import com.netflix.tools.ja.ToolDefinition.Launch;
import com.netflix.tools.ja.ToolExecutionException;
import com.netflix.tools.ja.ToolRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildToolTest {
    @TempDir
    Path directory;

    @Test
    void mavenToolUsesTheSelectedScopeAndLeavesUserArgumentsAlone() throws Exception {
        var fixture = fixture("maven", Set.of("class-path", "source-path"));
        assertEquals(0, fixture.run("-p", "example:app/test", "tool", "capture", "--flag", "user"));
        assertEquals(List.of("--class-path", "dependencies", "--source-path", "sources", "--flag", "user"), fixture.received);
        var query = fixture.resolution();
        assertEquals("example:app", value(query, "--project"));
        assertEquals("test", value(query, "--scope"));
        assertEquals("class-path,source-path", value(query, "--resolve-options"));
        assertFalse(query.contains("--no-compile-diagnostics"));
        assertFalse(query.contains("--validate-runtime-access"));
        assertFalse(query.contains("--"));
    }

    @Test
    void gradleToolDefaultsToMainCompileEvenInTestSources() throws Exception {
        var fixture = fixture("gradle", Set.of("class-path", "source-path"));
        fixture.cwd = Files.createDirectories(directory.resolve("src/test/java"));
        assertEquals(0, fixture.run("tool", "capture"));
        assertEquals("main", value(fixture.resolution(), "--source-set"));
        assertEquals("compile", value(fixture.resolution(), "--classpath"));
    }

    @Test
    void gradleToolAcceptsSourceSetAndClasspathQualifications() throws Exception {
        var fixture = fixture("gradle", Set.of("source-path"));
        assertEquals(0, fixture.run("-p", ":app/test/runtime", "tool", "capture"));
        assertEquals("test", value(fixture.resolution(), "--source-set"));
        assertEquals("runtime", value(fixture.resolution(), "--classpath"));
    }

    @Test
    void toolsWithoutProjectOptionsDoNotResolveOrPrepareProjectOutputs() throws Exception {
        var fixture = fixture("maven", Set.of());
        assertEquals(0, fixture.run("-p", "example:app/test", "tool", "capture", "user"));
        assertEquals(List.of("user"), fixture.received);
        assertEquals(1, fixture.queries.size());
    }

    @Test
    void buildResolutionFailureDoesNotRunTheTool() throws Exception {
        var fixture = fixture("maven", Set.of("source-path"));
        fixture.status = 7;
        var failure = assertThrows(ToolExecutionException.class, () -> fixture.run("tool", "capture"));
        assertTrue(fixture.received.isEmpty());
        assertNotNull(failure);
    }

    @Test
    void singleModuleToolsReceiveTheNativeModuleNameNotTheProjectSelector() throws Exception {
        var fixture = fixture("maven", Set.of("module=single", "source-path"));
        assertEquals(0, fixture.run("-p", "example:app", "tool", "capture"));
        assertEquals(List.of("--source-path", "sources", "--module", "example.app"), fixture.received);
    }

    @Test
    void multipleBuildContextsFailBeforeResolvingRatherThanGuessingHowToCombineThem() throws Exception {
        var fixture = fixture("maven", Set.of("source-path"));
        var failure = assertThrows(IllegalArgumentException.class,
                () -> fixture.run("-p", "example:app/compile", "-p", "example:app/test", "tool", "capture"));
        assertTrue(failure.getMessage().contains("one project context"));
        assertTrue(fixture.received.isEmpty());
        assertEquals(1, fixture.queries.size());
    }

    @Test
    void forwardsTheCompleteDeclaredRequestToBothBuildResolvers() throws Exception {
        var options = Set.of("class-path", "processor-path", "source", "target", "encoding", "system", "d",
                "module-path", "upgrade-module-path", "release", "enable-preview", "add-exports");
        for (String type : List.of("maven", "gradle")) {
            Files.deleteIfExists(directory.resolve("pom.xml"));
            Files.deleteIfExists(directory.resolve("settings.gradle"));
            var fixture = fixture(type, options);
            assertEquals(0, fixture.run("tool", "capture"));
            assertEquals(options, Set.of(value(fixture.resolution(), "--resolve-options").split(",")));
            assertEquals(List.of("--class-path", "dependencies", "--processor-path", "processors",
                    "-source", "17", "-target", "17", "-encoding", "UTF-8", "--system", "jdk", "-d", "output"), fixture.received);
        }
    }

    @Test
    void testRemainsAModuleCommand() throws Exception {
        var fixture = fixture("maven", Set.of());
        var failure = assertThrows(IllegalArgumentException.class, () -> fixture.run("-p", "example:app/test", "test"));
        assertTrue(failure.getMessage().contains("test requires a module project"));
    }

    @Test
    void javaMainToolsKeepTheProjectClasspathBeforeTheirOwnEntryPoint() throws Exception {
        for (String type : List.of("maven", "gradle")) {
            Files.deleteIfExists(directory.resolve("pom.xml"));
            Files.deleteIfExists(directory.resolve("settings.gradle"));
            var fixture = fixture(type, Set.of("class-path", "module-path", "module=list"));
            fixture.definition = new ToolDefinition("capture", Launch.JAVA, Optional.empty(), Optional.of("example.tool"),
                    "capture", Optional.of("1.0"), fixture.definition.options(), List.of("execute"));
            fixture.modulePath = "project modules";
            fixture.launcherArguments = List.of("--module-path", "tool modules", "--class-path", "tool dependencies",
                    "--module", "example.tool/Main");
            assertEquals(0, fixture.run("tool", "capture", "user"));
            assertEquals(List.of("--add-modules", "example.app", "--class-path",
                    "dependencies" + File.pathSeparator + "tool dependencies", "--module-path",
                    "project modules" + File.pathSeparator + "tool modules", "--module", "example.tool/Main",
                    "execute", "user"), fixture.received);
            assertTrue(fixture.queries.stream().anyMatch(query -> query.contains("example.tool@1.0")));
        }
    }

    @Test
    void javaMainToolsResolveRuntimePathsWithoutManifestOptions() throws Exception {
        var fixture = fixture("maven", Set.of());
        fixture.definition = new ToolDefinition("capture", Launch.JAVA, Optional.empty(), Optional.of("example.tool"),
                "capture", Optional.of("1.0"), Set.of(), List.of());
        fixture.launcherArguments = List.of("--module", "example.tool/Main");
        assertEquals(0, fixture.run("tool", "capture"));
        assertEquals(List.of("--class-path", "dependencies", "--module", "example.tool/Main"), fixture.received);
        assertTrue(Set.of(value(fixture.resolution(), "--resolve-options").split(",")).contains("class-path"));
    }

    @Test
    void aClasspathDependencyCanActivateAToolAndSupplyItsVersion() throws Exception {
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", "example.framework");
        var dependency = directory.resolve("framework-2.4.2.jar");
        try (var _ = new JarOutputStream(Files.newOutputStream(dependency), manifest)) {}
        var unnamed = directory.resolve("unnamed.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(unnamed))) {
            output.putNextEntry(new JarEntry("Example.class"));
            output.write(ClassFile.of().build(ClassDesc.of("Example"), _ -> {}));
        }
        for (String type : List.of("maven", "gradle")) {
            Files.deleteIfExists(directory.resolve("pom.xml"));
            Files.deleteIfExists(directory.resolve("settings.gradle"));
            var fixture = fixture(type, Set.of("class-path"));
            fixture.classPath = dependency + File.pathSeparator + unnamed;
            fixture.definition = new ToolDefinition("capture", Launch.JAVA, Optional.of("example.framework"),
                    Optional.of("example.tool"), "capture", Optional.empty(), Set.of("class-path"), List.of());
            fixture.launcherArguments = List.of("--module", "example.tool/Main");
            assertEquals(0, fixture.run("tool", "capture"));
            assertTrue(fixture.queries.stream().anyMatch(query -> query.contains("example.tool@2.4.2")));
            assertEquals(List.of("--class-path", fixture.classPath, "--module", "example.tool/Main"), fixture.received);
        }
    }

    @Test
    void formattingUsesTheSelectedBuildContext() throws Exception {
        var fixture = fixture("gradle", Set.of("class-path", "source-path"));
        fixture.definition = new ToolDefinition("jfmt", Launch.PROVIDER, Optional.empty(), Optional.empty(), "capture",
                Optional.empty(), Set.of("class-path", "source-path"), List.of());
        assertEquals(0, fixture.run("-p", ":app/test/runtime", "fmt"));
        assertEquals(List.of("--class-path", "dependencies", "--source-path", "sources"), fixture.received);
        assertEquals("test", value(fixture.resolution(), "--source-set"));
        assertEquals("runtime", value(fixture.resolution(), "--classpath"));
    }

    @Test
    void sourceAndTerminalDocumentationUseTheSelectedBuildContext() throws Exception {
        var fixture = fixture("maven", Set.of("class-path", "source-path"));
        fixture.definition = new ToolDefinition("jist", Launch.PROVIDER, Optional.empty(), Optional.empty(), "jist",
                Optional.empty(), Set.of("class-path", "source-path"), List.of());
        fixture.additionalTools = List.of(provider("jdocserver", (_, _) -> 0));
        assertEquals(0, fixture.run("-p", "example:app/test", "source", "example.Example"));
        assertEquals(List.of("--class-path", "dependencies", "--source-path", "sources", "--source", "symbol", "example.Example"),
                fixture.received);
        assertEquals("test", value(fixture.resolution(), "--scope"));
        fixture.received.clear();
        fixture.queries.clear();
        assertEquals(0, fixture.run("-p", "example:app/runtime", "doc", "example.Example"));
        assertEquals(List.of("--class-path", "dependencies", "--source-path", "sources", "--source", "doc", "--break",
                "--no-line-number", "example.Example"), fixture.received);
        assertEquals("runtime", value(fixture.resolution(), "--scope"));
    }

    @Test
    void codeInsightUsesTheSelectedMavenAndGradleContexts() throws Exception {
        for (var type : List.of("maven", "gradle")) {
            Files.deleteIfExists(directory.resolve("pom.xml"));
            Files.deleteIfExists(directory.resolve("settings.gradle"));
            var fixture = fixture(type, Set.of("class-path", "source-path"));
            fixture.definition = new ToolDefinition("jist", Launch.PROVIDER, Optional.empty(), Optional.empty(), "jist",
                    Optional.empty(), Set.of("class-path", "source-path"), List.of());
            var selector = type.equals("maven") ? "example:app/test" : ":app/test/runtime";
            for (var query : List.of(
                    List.of("describe", "module", "java.base"), List.of("describe", "type", "example.Example"),
                    List.of("describe", "member", "example.Example.run"), List.of("describe", "file", "Example.java"),
                    List.of("find", "usages", "example.Example"), List.of("find", "callers", "example.Example.run"),
                    List.of("find", "impl", "example.Example"), List.of("find", "impl", "example.Example.run"))) {
                fixture.received.clear();
                fixture.queries.clear();
                fixture.toolQueries.clear();
                var arguments = new ArrayList<>(List.of("-p", selector));
                arguments.addAll(query);
                assertEquals(0, fixture.run(arguments.toArray(String[]::new)), query.toString());
                assertEquals(List.of("--class-path", "dependencies", "--source-path", "sources"), fixture.received.subList(0, 4));
                assertEquals(query.get(1).equals("file") ? directory.resolve("Example.java").toString() : query.getLast(), fixture.received.getLast());
                assertEquals("test", value(fixture.resolution(), type.equals("maven") ? "--scope" : "--source-set"));
                if (type.equals("gradle")) assertEquals("runtime", value(fixture.resolution(), "--classpath"));
                if (query.get(1).equals("impl")) {
                    assertEquals(2, fixture.toolQueries.size());
                    assertEquals(fixture.received.subList(0, 4), fixture.toolQueries.getFirst().subList(0, 4));
                    assertEquals(query.getLast().equals("example.Example") ? "--subtypes" : "--overrides", fixture.received.get(4));
                    assertEquals(1, fixture.queries.stream().filter(argumentsForJig -> argumentsForJig.contains("--resolve-options")).count());
                }
            }
        }
    }

    @Test
    void verboseSourceQueriesPreserveBuildSubcommands() throws Exception {
        for (var type : List.of("maven", "gradle")) {
            Files.deleteIfExists(directory.resolve("pom.xml"));
            Files.deleteIfExists(directory.resolve("settings.gradle"));
            var fixture = fixture(type, Set.of("class-path", "source-path"));
            fixture.definition = new ToolDefinition("jist", Launch.PROVIDER, Optional.empty(), Optional.empty(), "jist",
                    Optional.empty(), Set.of("class-path", "source-path"), List.of());
            fixture.additionalTools = List.of(provider("jdocserver", (_, _) -> 0));

            assertEquals(0, fixture.run("--verbose", "-p", type.equals("maven") ? "example:app" : ":app", "source", "java.lang.String"));

            assertEquals(2, fixture.queries.size(), fixture.queries.toString());
            for (var query : fixture.queries) {
                assertEquals(type, query.getFirst(), query.toString());
                assertEquals("--verbose", query.get(1), query.toString());
            }
            assertEquals(List.of("--class-path", "dependencies", "--source-path", "sources", "--source", "symbol", "java.lang.String"),
                    fixture.received);
        }
    }

    @Test
    void documentationBrowsingPassesTheContextToTheDocumentationTool() throws Exception {
        var fixture = fixture("gradle", Set.of("class-path", "source-path"));
        fixture.definition = new ToolDefinition("jdocserver", Launch.PROVIDER, Optional.empty(), Optional.empty(), "jdocserver",
                Optional.empty(), Set.of("class-path", "source-path"), List.of());
        fixture.additionalTools = List.of(provider("jist", (_, _) -> 0));
        assertEquals(0, fixture.run("doc", "--browse", "example.Example"));
        assertEquals(List.of("--class-path", "dependencies", "--source-path", "sources", "--browse=example.Example"), fixture.received);
    }

    private Fixture fixture(String type, Set<String> options) throws Exception {
        Files.writeString(directory.resolve(type.equals("maven") ? "pom.xml" : "settings.gradle"), "");
        return new Fixture(type, options);
    }

    private final class Fixture {
        final String type;
        final List<List<String>> queries = new ArrayList<>();
        final List<List<String>> toolQueries = new ArrayList<>();
        final List<String> received = new ArrayList<>();
        ToolDefinition definition;
        Path cwd = directory;
        String classPath = "dependencies";
        String modulePath;
        List<String> launcherArguments = List.of();
        List<ToolProvider> additionalTools = List.of();
        int status;

        Fixture(String type, Set<String> options) {
            this.type = type;
            definition = new ToolDefinition("capture", Launch.PROVIDER, Optional.empty(), Optional.empty(), "capture",
                    Optional.empty(), options, List.of());
        }

        int run(String... arguments) throws Exception {
            var providers = new ArrayList<ToolProvider>();
            providers.add(provider("jig", (out, args) -> {
                queries.add(args);
                if (args.contains("--format")) {
                    if (type.equals("maven")) {
                        for (String scope : List.of("compile", "runtime", "test")) {
                            out.println(directory.toRealPath() + "\t" + "example:app\t" + scope);
                        }
                    } else {
                        for (String sourceSet : List.of("main", "test")) {
                            for (String classpath : List.of("compile", "runtime")) {
                                out.println(directory.toRealPath() + "\t:app\t" + sourceSet + "\t" + classpath);
                            }
                        }
                    }
                    return 0;
                }
                if (!args.getFirst().equals(type)) {
                    for (var argument : launcherArguments) {
                        out.println("\"" + argument.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
                    }
                    return 0;
                }
                var options = Set.of(value(args, "--resolve-options").split(","));
                if (options.contains("module=list")) {
                    out.println("--module example.app");
                }
                if (options.contains("class-path")) {
                    out.println("--class-path \"" + classPath + "\"");
                }
                if (options.contains("module-path") && modulePath != null) {
                    out.println("--module-path \"" + modulePath + "\"");
                }
                if (options.contains("source-path")) {
                    out.println("--source-path sources");
                }
                for (var option : List.of(List.of("processor-path", "--processor-path", "processors"),
                        List.of("source", "-source", "17"), List.of("target", "-target", "17"),
                        List.of("encoding", "-encoding", "UTF-8"), List.of("system", "--system", "jdk"),
                        List.of("d", "-d", "output"))) {
                    if (options.contains(option.getFirst())) {
                        out.println(option.get(1) + " " + option.get(2));
                    }
                }
                return status;
            }));
            providers.add(provider(definition.provider(), (out, args) -> {
                toolQueries.add(args);
                if (definition.provider().equals("jist") && args.contains("-l")) {
                    out.println("class example.Example");
                } else {
                    received.addAll(args);
                }
                return 0;
            }));
            providers.addAll(additionalTools);
            var runtime = ToolRuntime.of(providers.toArray(ToolProvider[]::new));
            return new CommandRunner(ModuleLayer.boot(), runtime, new ToolCatalog(List.of(definition)), () -> null,
                    (args, _, _, _) -> {
                        received.addAll(args);
                        return 0;
                    })
                    .run(JaInvocation.parse(cwd, arguments), InputStream.nullInputStream(),
                            new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));
        }

        List<String> resolution() {
            return queries.stream().filter(query -> query.contains("--resolve-options")).findFirst().orElseThrow();
        }
    }

    private static String value(List<String> arguments, String option) {
        return arguments.get(arguments.indexOf(option) + 1);
    }

    private interface Query {
        int run(PrintWriter out, List<String> arguments) throws Exception;
    }

    private static ToolProvider provider(String name, Query query) {
        return new ToolProvider() {
            public String name() { return name; }
            public int run(PrintWriter out, PrintWriter err, String... arguments) {
                try {
                    return query.run(out, List.of(arguments));
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            }
        };
    }
}
