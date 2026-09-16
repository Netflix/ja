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
import java.io.File;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.lang.module.ModuleFinder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarFile;
import java.util.spi.ToolProvider;
import java.util.stream.Collectors;

import com.netflix.tools.ja.CommandRunner;
import com.netflix.tools.ja.InstallationDirectories;
import com.netflix.tools.ja.InstalledCommandModule;
import com.netflix.tools.ja.InstalledCommandModule.Generated;
import com.netflix.tools.ja.Installer;
import com.netflix.tools.ja.JaInvocation;
import com.netflix.tools.ja.TemporaryDirectory;
import com.netflix.tools.ja.ToolArguments;
import com.netflix.tools.ja.ToolCatalog;
import com.netflix.tools.ja.ToolDefinition;
import com.netflix.tools.ja.ToolDefinition.Launch;
import com.netflix.tools.ja.ToolExecutionException;
import com.netflix.tools.ja.ToolServices;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandRunnerTest {
    private static final Set<String> COMPILE_OPTIONS = Set.of(
            "add-exports",
            "enable-preview",
            "module-path",
            "module-source-path",
            "module-version",
            "module=list",
            "patch-module",
            "processor-module-path",
            "release",
            "upgrade-module-path");
    private static final Set<String> SOURCE_OPTIONS = Set.of("add-exports", "enable-preview", "module=list", "module-path", "module-source-path", "release");
    private static final Set<String> MODULE_PATH_OPTIONS = Set.of("add-modules", "module-path", "module-source-path", "upgrade-module-path");
    private static final Set<String> RUNTIME_ACCESS_OPTIONS = Set.of(
            "add-exports",
            "add-modules",
            "add-opens",
            "enable-final-field-mutation",
            "enable-native-access",
            "enable-preview",
            "module-path",
            "patch-module",
            "upgrade-module-path");
    private static final Set<String> LAUNCH_OPTIONS = Set.of(
            "add-exports",
            "add-modules",
            "add-opens",
            "enable-final-field-mutation",
            "enable-native-access",
            "enable-preview",
            "module-path",
            "module=main",
            "patch-module",
            "upgrade-module-path");
    private static final Set<String> COMPLETE_LAUNCH_OPTIONS = Set.of(
            "add-exports",
            "add-modules",
            "add-opens",
            "enable-final-field-mutation",
            "enable-native-access",
            "enable-preview",
            "module-path",
            "module=main",
            "upgrade-module-path");
    private static final Set<String> CONFIGURATION_OPTIONS = Set.of("add-modules", "module-path", "upgrade-module-path");

    @TempDir
    Path temporaryDirectory;

    @Test
    void runsAToolProvidedByASourceModule() throws Exception {
        Path module = Files.createDirectories(temporaryDirectory.resolve("src/com.example.tool"));
        Files.writeString(module.resolve("module-info.java"),
                """
                /** @enableNativeAccess com.example.tool */
                module com.example.tool {
                    requires java.compiler;
                    provides java.util.spi.ToolProvider with com.example.Probe;
                }
                """);
        Files.createFile(module.resolve("module-info.hash"));
        Path packageDirectory = Files.createDirectories(module.resolve("com/example"));
        Files.writeString(packageDirectory.resolve("Probe.java"),
                """
                package com.example;

                public final class Probe implements
                        java.util.spi.ToolProvider, javax.tools.OptionChecker {
                    public String name() { return "source-probe"; }
                    public int isSupportedOption(String option) {
                        return option.equals("--module-path") ? 1 : -1;
                    }
                    public int run(java.io.PrintWriter out, java.io.PrintWriter err,
                                   String... arguments) {
                        out.println("native-access="
                                + getClass().getModule().isNativeAccessEnabled());
                        for (String argument : arguments) out.println(argument);
                        return 0;
                    }
                }
                """);
        Path application = Files.createDirectories(temporaryDirectory.resolve("src/com.example.application"));
        Files.writeString(application.resolve("module-info.java"),
                """
                module com.example.application {
                    requires static com.example.tool;
                }
                """);
        Files.createFile(application.resolve("module-info.hash"));
        var commandLine = JaInvocation.parse(application, new String[] {"tool", "source-probe", "explicit"});
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();

        int result = new CommandRunner(ModuleLayer.boot()).run(commandLine, InputStream.nullInputStream(), new PrintStream(output),
                new PrintStream(error));

        assertEquals(0, result, error.toString());
        assertTrue(output.toString()
                         .lines()
                         .toList()
                         .contains("native-access=true"));
        assertTrue(output.toString()
                         .lines()
                         .toList()
                         .contains("--module-path"));
        assertTrue(output.toString()
                         .lines()
                         .toList()
                         .contains("explicit"));
    }

    @Test
    void bundledFormatterDoesNotMaterializeUncompilableSources() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("src/com.example.application"));
        Files.writeString(source.resolve("module-info.java"), "module com.example.application {}\n");
        Files.writeString(source.resolve("module-info.hash"), "");
        Files.writeString(source.resolve("Broken.java"), "not Java\n");
        var commandLine = JaInvocation.parse(source, new String[] {"fmt", "explicit"});

        var jigInvocations = new ArrayList<List<String>>();
        var formatterArguments = new ArrayList<String>();
        var tools = ToolServices.of(tool(
                "jig",
                (output, arguments) -> {
                    jigInvocations.add(List.copyOf(arguments));
                    String options = arguments.get(arguments.indexOf("--resolve-options") + 1);
                    assertEquals("module,module-source-path", options);
                    output.print("--module-source-path\n" + source + "\n--module\ncom.example.application\n");
                    return 0;
                }),
                tool("formatter",
                        (_, arguments) -> {
                            formatterArguments.addAll(arguments);
                            return 0;
                        }));
        var formatter = new ToolDefinition(
                "jfmt",
                Launch.PROVIDER,
                Optional.empty(),
                Optional.empty(),
                "formatter",
                Optional.empty(),
                Set.of("module-source-path", "module"),
                List.of());

        int result = new CommandRunner(ModuleLayer.boot(), tools, new ToolCatalog(List.of(formatter)), () -> null)
                .run(commandLine, InputStream.nullInputStream(), new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, result);
        assertEquals(1, jigInvocations.size());
        assertTrue(formatterArguments.contains("explicit"));
    }

    @Test
    void resolvesMultipleSourceModulesForFormatter() throws Exception {
        Path first = Files.createDirectories(temporaryDirectory.resolve("src/com.example.first"));
        Files.writeString(first.resolve("module-info.java"), "module com.example.first {}\n");
        Path second = Files.createDirectories(temporaryDirectory.resolve("src/com.example.second"));
        Files.writeString(second.resolve("module-info.java"), "module com.example.second {}\n");
        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"fmt", "explicit"});
        var formatterArguments = new ArrayList<String>();
        var tools = ToolServices.of(ToolProvider.findFirst("jig").orElseThrow(),
                tool("formatter",
                        (_, arguments) -> {
                            formatterArguments.addAll(arguments);
                            return 0;
                        }));
        var formatter = new ToolDefinition(
                "jfmt",
                Launch.PROVIDER,
                Optional.empty(),
                Optional.empty(),
                "formatter",
                Optional.empty(),
                Set.of("module-source-path", "module=list"),
                List.of());

        int result = new CommandRunner(ModuleLayer.boot(), tools, new ToolCatalog(List.of(formatter)), () -> null)
                .run(commandLine, InputStream.nullInputStream(), new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, result);
        int module = formatterArguments.indexOf("--module");
        assertEquals("com.example.first,com.example.second", formatterArguments.get(module + 1));
        assertTrue(formatterArguments.contains("explicit"));
    }

    @Test
    void discoversToolsOnTheCurrentModulePath() throws Exception {
        Path sources = temporaryDirectory.resolve("src");
        Path toolModule = Files.createDirectories(sources.resolve("com.example.tool"));
        Files.writeString(toolModule.resolve("module-info.java"),
                """
                module com.example.tool {
                    provides java.util.spi.ToolProvider with com.example.Probe;
                }
                """);
        Path packageDirectory = Files.createDirectories(toolModule.resolve("com/example"));
        Files.writeString(packageDirectory.resolve("Probe.java"),
                """
                package com.example;

                public final class Probe implements java.util.spi.ToolProvider {
                    public String name() { return "source-probe"; }
                    public int run(java.io.PrintWriter out, java.io.PrintWriter err,
                                   String... arguments) {
                        out.println(getClass().getModule().getLayer().configuration()
                                .findModule("com.example.tool").orElseThrow()
                                .reference().location().orElseThrow());
                        for (String argument : arguments) out.println(argument);
                        return 0;
                    }
                }
                """);
        Path application = Files.createDirectories(sources.resolve("com.example.application"));
        Files.writeString(application.resolve("module-info.java"),
                """
                module com.example.application {
                    requires static com.example.tool;
                }
                """);
        var commandLine = JaInvocation.parse(application, new String[] {"tool", "source-probe", "explicit"});

        Path modules = temporaryDirectory.resolve("modules");
        int compilation = ToolProvider.findFirst("javac")
                .orElseThrow()
                .run(System.out, System.err, "--module-source-path", sources.toString(), "-d",
                        modules.toString(), "--module", "com.example.application,com.example.tool", "-proc:none");
        assertEquals(0, compilation);
        Files.writeString(toolModule.resolve("module-info.java"), "not a module descriptor\n");

        var jigInvocations = new ArrayList<List<String>>();
        var tools = ToolServices.of(tool("jig",
                (output, arguments) -> {
                    jigInvocations.add(List.copyOf(arguments));
                    output.print("--module-path\n" + modules + "\n--add-modules\ncom.example.application,com.example.tool\n");
                    return 0;
                }));
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();

        ToolDefinition sourceProbe = ToolDefinition.read("source-probe", new ByteArrayInputStream(
                "activation=com.example.application\nmodule=com.example.tool@2.0\nprovider=source-probe\n"
                        .getBytes(StandardCharsets.UTF_8)));
        int result = new CommandRunner(ModuleLayer.boot(), tools, new ToolCatalog(List.of(sourceProbe)), () -> null)
                .run(commandLine, InputStream.nullInputStream(), new PrintStream(output),
                        new PrintStream(error));

        assertEquals(0, result, error.toString());
        assertEquals(2, jigInvocations.size());
        var discovery = jigInvocations.get(0);
        assertEquals(List.of("com.example.application"), optionValues(discovery, "-m", "--module"));
        assertEquals(optionList(MODULE_PATH_OPTIONS), discovery.get(discovery.indexOf("--resolve-options") + 1));
        assertFalse(discovery.contains("--compile-time"));
        var execution = jigInvocations.get(1);
        assertEquals(List.of("com.example.application", "com.example.tool"), optionValues(execution, "-m", "--module"));
        assertEquals(
                "add-exports,add-modules,add-opens,enable-final-field-mutation,enable-native-access,module-path",
                execution.get(execution.indexOf("--resolve-options") + 1));
        assertTrue(execution.contains("--validate-runtime-access"));
        assertEquals(modules.resolve("com.example.tool").toUri() + "\nexplicit\n", output.toString());
    }

    @Test
    void resolvesAVersionedProviderForAnActivatedSourceModule() throws Exception {
        Path supportSources = temporaryDirectory.resolve("support-sources");
        Path framework = Files.createDirectories(supportSources.resolve("com.example.framework"));
        Files.writeString(framework.resolve("module-info.java"), "module com.example.framework {}\n");
        Path toolModule = Files.createDirectories(supportSources.resolve("com.example.tool"));
        Files.writeString(toolModule.resolve("module-info.java"),
                """
                module com.example.tool {
                    provides java.util.spi.ToolProvider with com.example.Probe;
                }
                """);
        Path toolPackage = Files.createDirectories(toolModule.resolve("com/example"));
        Files.writeString(toolPackage.resolve("Probe.java"),
                """
                package com.example;

                public final class Probe implements java.util.spi.ToolProvider {
                    public String name() { return "probe"; }
                    public int run(java.io.PrintWriter out, java.io.PrintWriter err,
                                   String... arguments) {
                        for (String argument : arguments) out.println(argument);
                        return 0;
                    }
                }
                """);
        Path modules = temporaryDirectory.resolve("modules");
        int supportCompilation = ToolProvider.findFirst("javac")
                .orElseThrow()
                .run(System.out, System.err, "--module-source-path", supportSources.toString(), "-d",
                        modules.toString(), "--module", "com.example.framework,com.example.tool", "-proc:none");
        assertEquals(0, supportCompilation);

        Path projectSources = temporaryDirectory.resolve("project/src");
        Path application = Files.createDirectories(projectSources.resolve("com.example.application"));
        Files.writeString(application.resolve("module-info.java"),
                """
                module com.example.application {
                    requires static com.example.framework;
                }
                """);
        int applicationCompilation = ToolProvider.findFirst("javac")
                .orElseThrow()
                .run(System.out, System.err, "--module-path", modules.toString(), "--module-source-path",
                        projectSources.toString(), "-d", modules.toString(), "--module", "com.example.application", "-proc:none");
        assertEquals(0, applicationCompilation);

        var commandLine = JaInvocation.parse(application, new String[] {"tool", "probe", "explicit"});
        var jigInvocations = new ArrayList<List<String>>();
        var tools = ToolServices.of(tool("jig",
                (output, arguments) -> {
                    jigInvocations.add(List.copyOf(arguments));
                    if (arguments.get(arguments.indexOf("--resolve-options") + 1).equals(optionList(MODULE_PATH_OPTIONS))) {
                        output.print("--module-path\n" + modules + "\n--add-modules\ncom.example.application,com.example.framework\n");
                    } else if (joinedPair(arguments, "--add-requires", "com.example.tool@2.0")) {
                        output.print("--module-path\n" + modules + "\n--add-modules\ncom.example.tool\n");
                    } else {
                        output.print("--module-path\n" + modules + "\n--add-modules\ncom.example.application\n");
                    }
                    return 0;
                }));
        ToolDefinition probe = ToolDefinition.read("probe", new ByteArrayInputStream(
                "activation=com.example.framework\nmodule=com.example.tool@2.0\nprovider=probe\noptions=module-path,add-modules\n"
                        .getBytes(StandardCharsets.UTF_8)));
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();

        int result = new CommandRunner(ModuleLayer.boot(), tools, new ToolCatalog(List.of(probe)), () -> null)
                .run(commandLine, InputStream.nullInputStream(), new PrintStream(output), new PrintStream(error));

        assertEquals(0, result, error.toString());
        assertTrue(jigInvocations.stream()
                .anyMatch(arguments -> joinedPair(arguments, "--add-requires", "com.example.tool@2.0")));
        assertTrue(output.toString().endsWith("explicit\n"));
    }

    @Test
    void bundledToolDoesNotDiscoverRepeatedSourceToolName() throws Exception {
        Path toolModule = Files.createDirectories(temporaryDirectory.resolve("src/com.example.tool"));
        Files.writeString(toolModule.resolve("module-info.java"),
                """
                module com.example.tool {
                    provides java.util.spi.ToolProvider with com.example.Probe;
                }
                """);
        Files.createFile(toolModule.resolve("module-info.hash"));
        Path packageDirectory = Files.createDirectories(toolModule.resolve("com/example"));
        Files.writeString(packageDirectory.resolve("Probe.java"),
                """
                package com.example;

                public final class Probe implements java.util.spi.ToolProvider {
                    public Probe() { throw new AssertionError("provider was instantiated"); }
                    public String name() { return "probe"; }
                    public int run(java.io.PrintWriter out, java.io.PrintWriter err,
                                   String... arguments) {
                        return 0;
                    }
                }
                """);
        Path metadata = Files.createDirectories(toolModule.resolve("META-INF/com.netflix.tools/tools"));
        Files.writeString(metadata.resolve("probe.properties"), "provider=probe\n");
        Path application = Files.createDirectories(temporaryDirectory.resolve("src/com.example.application"));
        Files.writeString(application.resolve("module-info.java"),
                """
                module com.example.application {
                    requires static com.example.tool;
                }
                """);
        Files.createFile(application.resolve("module-info.hash"));
        var commandLine = JaInvocation.parse(application, new String[] {"tool", "probe"});
        var tools = ToolServices.of(tool("jig", (_, _) -> {
            throw new AssertionError("source scope must not be resolved");
        }),
                tool("probe", (_, _) -> 0));

        int result = new CommandRunner(ModuleLayer.boot(), tools, new ToolCatalog(List.of()), () -> null)
                .run(commandLine, InputStream.nullInputStream(), new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, result);
    }

    @Test
    void runsInstalledToolWithoutResolvingModuleScope() throws Exception {
        Path module = Files.createDirectories(temporaryDirectory.resolve("src/com.example.application"));
        Files.writeString(module.resolve("module-info.java"), "module com.example.application {}\n");
        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"tool", "discovered-probe", "explicit"});
        var arguments = new ArrayList<String>();
        var jigRuns = new AtomicInteger();
        ToolServices tools = ToolServices.of(tool("jig",
                (_, _) -> {
                    jigRuns.incrementAndGet();
                    return 0;
                }),
                tool("discovered-probe",
                        (_, supplied) -> {
                            arguments.addAll(supplied);
                            return 0;
                        }));

        int result = new CommandRunner(ModuleLayer.boot(), tools, new ToolCatalog(List.of()), () -> null)
                .run(commandLine, InputStream.nullInputStream(), new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, result);
        assertEquals(0, jigRuns.get());
        assertEquals(List.of("explicit"), arguments);
    }

    @Test
    void bareToolListsToolsOnTheCurrentModulePath() throws Exception {
        Path module = Files.createDirectories(temporaryDirectory.resolve("src/com.example.tool"));
        Files.writeString(module.resolve("module-info.java"),
                """
                module com.example.tool {
                    provides java.util.spi.ToolProvider with com.example.Probe;
                }
                """);
        Files.createFile(module.resolve("module-info.hash"));
        Path packageDirectory = Files.createDirectories(module.resolve("com/example"));
        Files.writeString(packageDirectory.resolve("Probe.java"),
                """
                package com.example;

                public final class Probe implements java.util.spi.ToolProvider {
                    public String name() { return "source-probe"; }
                    public int run(java.io.PrintWriter out, java.io.PrintWriter err,
                                   String... arguments) {
                        return 0;
                    }
                }
                """);
        var commandLine = JaInvocation.parse(module, new String[] {"tool"});
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();

        int result = new CommandRunner(ModuleLayer.boot()).run(commandLine, InputStream.nullInputStream(), new PrintStream(output),
                new PrintStream(error));

        assertEquals(0, result, error.toString());
        assertTrue(output.toString()
                         .lines()
                         .toList()
                         .contains("source-probe"));
    }

    @Test
    void bareToolDoesNotCompileModulesWithoutToolProviders() throws Exception {
        Path provider = Files.createDirectories(temporaryDirectory.resolve("src/com.example.tool"));
        Files.writeString(provider.resolve("module-info.java"),
                """
                module com.example.tool {
                    provides java.util.spi.ToolProvider with com.example.Probe;
                }
                """);
        Path providerPackage = Files.createDirectories(provider.resolve("com/example"));
        Files.writeString(providerPackage.resolve("Probe.java"),
                """
                package com.example;

                public final class Probe implements java.util.spi.ToolProvider {
                    public String name() { return "source-probe"; }
                    public int run(java.io.PrintWriter out, java.io.PrintWriter err,
                                   String... arguments) {
                        return 0;
                    }
                }
                """);
        Path unrelated = Files.createDirectories(temporaryDirectory.resolve("src/com.example.application"));
        Files.writeString(unrelated.resolve("module-info.java"), "module com.example.application {}\n");
        Files.writeString(unrelated.resolve("Broken.java"), "not Java\n");
        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"tool"});
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();

        int result = new CommandRunner(ModuleLayer.boot()).run(commandLine, InputStream.nullInputStream(),
                new PrintStream(output), new PrintStream(error));

        assertEquals(0, result, error.toString());
        assertTrue(output.toString().lines().toList().contains("source-probe"));
    }

    @Test
    void bareToolCompilesAProviderRequiredByTheSelectedModule() throws Exception {
        Path provider = Files.createDirectories(temporaryDirectory.resolve("src/com.example.tool"));
        Files.writeString(provider.resolve("module-info.java"),
                "module com.example.tool { provides java.util.spi.ToolProvider with com.example.Probe; }\n");
        Path providerPackage = Files.createDirectories(provider.resolve("com/example"));
        Files.writeString(providerPackage.resolve("Probe.java"),
                """
                package com.example;

                public final class Probe implements java.util.spi.ToolProvider {
                    public String name() { return "source-probe"; }
                    public int run(java.io.PrintWriter out, java.io.PrintWriter err,
                                   String... arguments) {
                        return 0;
                    }
                }
                """);
        Path application = Files.createDirectories(temporaryDirectory.resolve("src/com.example.application"));
        Files.writeString(application.resolve("module-info.java"),
                "module com.example.application { requires static com.example.tool; }\n");
        Files.writeString(application.resolve("Broken.java"), "not Java\n");
        var commandLine = JaInvocation.parse(application, new String[] {"tool"});
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();

        int result = new CommandRunner(ModuleLayer.boot()).run(commandLine, InputStream.nullInputStream(),
                new PrintStream(output), new PrintStream(error));

        assertEquals(0, result, error.toString());
        assertTrue(output.toString().lines().toList().contains("source-probe"));
    }

    @Test
    void providerToolUsesItsDeclaredModuleContract() throws Exception {
        var source = Files.createDirectories(temporaryDirectory.resolve("src/com.example.app"));
        Files.writeString(source.resolve("module-info.java"), "module com.example.app {}\n");
        var commandLine = JaInvocation.parse(source, new String[] {"tool", "jshell"});
        var jshellArguments = new ArrayList<String>();
        var tools = ToolServices.of(tool("jig",
                (output, arguments) -> {
                    assertEquals("add-modules,enable-preview,module-path", arguments.get(arguments.indexOf("--resolve-options") + 1));
                    output.print("--module-path\nmodules\n--add-modules\ncom.example.app\n");
                    return 0;
                }),
                tool("jshell",
                        (_, arguments) -> {
                            jshellArguments.addAll(arguments);
                            return 0;
                        }));
        var definition = new ToolDefinition(
                "jshell",
                Launch.PROVIDER,
                Optional.empty(),
                Optional.empty(),
                "jshell",
                Optional.of("1"),
                Set.of("module-path", "add-modules", "enable-preview"),
                List.of());

        int result = new CommandRunner(ModuleLayer.boot(), tools, new ToolCatalog(List.of(definition)), () -> null)
                .run(commandLine, InputStream.nullInputStream(), new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, result);
        assertEquals(List.of("--module-path", "modules", "--add-modules", "com.example.app"), jshellArguments);
    }

    @Test
    void enclosingModuleSatisfiesASingleModuleToolContract() throws Exception {
        var foo = Files.createDirectories(temporaryDirectory.resolve("src/com.example.foo"));
        var bar = Files.createDirectories(temporaryDirectory.resolve("src/com.example.bar"));
        Files.writeString(foo.resolve("module-info.java"), "module com.example.foo {}\n");
        Files.writeString(bar.resolve("module-info.java"), "module com.example.bar {}\n");
        var commandLine = JaInvocation.parse(foo, new String[] {"tool", "javap"});
        var jigInvocations = new ArrayList<List<String>>();
        var toolArguments = new ArrayList<String>();
        var tools = ToolServices.of(tool("jig",
                (output, arguments) -> {
                    jigInvocations.add(List.copyOf(arguments));
                    output.print("--module-path\nmodules\n");
                    return 0;
                }),
                tool("javap",
                        (_, arguments) -> {
                            toolArguments.addAll(arguments);
                            return 0;
                        }));
        var definition = new ToolDefinition(
                "javap",
                Launch.PROVIDER,
                Optional.empty(),
                Optional.empty(),
                "javap",
                Optional.of("1"),
                Set.of("module-path", "module=single"),
                List.of());

        var result = new CommandRunner(ModuleLayer.boot(), tools, new ToolCatalog(List.of(definition)), () -> null)
                .run(commandLine, InputStream.nullInputStream(), new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, result);
        assertEquals(1, jigInvocations.size());
        var jigArguments = jigInvocations.getFirst();
        assertEquals("module-path", jigArguments.get(jigArguments.indexOf("--resolve-options") + 1));
        assertEquals(List.of("--module-path", "modules", "--module", "com.example.foo"), toolArguments);
    }

    @Test
    void singleModuleToolReportsAmbiguousWorkingDirectoryBeforeResolution() throws Exception {
        var foo = Files.createDirectories(temporaryDirectory.resolve("src/com.example.foo"));
        var bar = Files.createDirectories(temporaryDirectory.resolve("src/com.example.bar"));
        Files.writeString(foo.resolve("module-info.java"), "module com.example.foo {}\n");
        Files.writeString(bar.resolve("module-info.java"), "module com.example.bar {}\n");
        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"tool", "javap"});
        var jigInvoked = new AtomicBoolean();
        var tools = ToolServices.of(tool("jig",
                (_, _) -> {
                    jigInvoked.set(true);
                    return 0;
                }),
                tool("javap", (_, _) -> 0));
        var definition = new ToolDefinition(
                "javap",
                Launch.PROVIDER,
                Optional.empty(),
                Optional.empty(),
                "javap",
                Optional.of("1"),
                Set.of("module-path", "module=single"),
                List.of());

        var failure = assertThrows(
                IllegalArgumentException.class,
                () -> new CommandRunner(ModuleLayer.boot(), tools, new ToolCatalog(List.of(definition)), () -> null)
                        .run(commandLine, InputStream.nullInputStream(), new PrintStream(new ByteArrayOutputStream()),
                                new PrintStream(new ByteArrayOutputStream())));

        assertEquals(
                "javap requires one selected module; use --module <name> or -C <module-directory>",
                failure.getMessage());
        assertFalse(jigInvoked.get());
    }

    @Test
    void nativeToolModuleQualifierSelectsTheResolutionRootAndPassesThrough() throws Exception {
        var foo = Files.createDirectories(temporaryDirectory.resolve("src/com.example.foo"));
        var bar = Files.createDirectories(temporaryDirectory.resolve("src/com.example.bar"));
        Files.writeString(foo.resolve("module-info.java"), "module com.example.foo {}\n");
        Files.writeString(bar.resolve("module-info.java"), "module com.example.bar {}\n");
        var commandLine = JaInvocation.parse(foo,
                new String[] {"tool", "probe", "--module", "com.example.bar", "Main"});
        var jigInvocations = new ArrayList<List<String>>();
        var toolArguments = new ArrayList<String>();
        var tools = ToolServices.of(tool("jig",
                (output, arguments) -> {
                    jigInvocations.add(List.copyOf(arguments));
                    var options = arguments.get(arguments.indexOf("--resolve-options") + 1);
                    if (!options.equals(optionList(CONFIGURATION_OPTIONS))) {
                        output.print("--module-path\nmodules\n");
                    }
                    return 0;
                }),
                tool("probe",
                        (_, arguments) -> {
                            toolArguments.addAll(arguments);
                            return 0;
                        }));
        var definition = new ToolDefinition(
                "probe",
                Launch.PROVIDER,
                Optional.empty(),
                Optional.empty(),
                "probe",
                Optional.of("1"),
                Set.of("module-path", "module"),
                List.of());

        var result = new CommandRunner(ModuleLayer.boot(), tools, new ToolCatalog(List.of(definition)), () -> null)
                .run(commandLine, InputStream.nullInputStream(), new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, result);
        assertEquals(1, jigInvocations.size());
        var jigArguments = jigInvocations.getLast();
        assertTrue(jigArguments.contains("com.example.bar"), jigArguments.toString());
        assertFalse(jigArguments.contains("com.example.foo"), jigArguments.toString());
        assertEquals("module,module-path", jigArguments.get(jigArguments.indexOf("--resolve-options") + 1));
        assertEquals(List.of("--module", "com.example.bar", "Main"),
                jigArguments.subList(jigArguments.indexOf("--") + 1, jigArguments.size()));
        assertEquals(List.of("--module-path", "modules", "--module", "com.example.bar", "Main"), toolArguments);
    }

    @Test
    void listsObservableModulesWithTheJavaLauncher() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("src/com.example.app"));
        Files.writeString(source.resolve("module-info.java"), "module com.example.app {}\n");
        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"list"});
        var javaArguments = new ArrayList<String>();
        ToolServices tools = ToolServices.of(fakeJig(Set.of("add-modules", "module-path", "upgrade-module-path"),
                        """
                                --module-path
                                modules
                                --upgrade-module-path
                                upgrades
                                --add-modules
                                com.example.app
                                """),
                        tool("jist", (_, _) -> {
                            throw new AssertionError("Jist must not run");
                        }));
        var catalog = new ToolCatalog(
                List.of(
                        new ToolDefinition(
                                "jist",
                                Launch.PROVIDER,
                                Optional.empty(),
                                Optional.empty(),
                                "jist",
                                Optional.of("1"),
                                Set.of(),
                                List.of())));

        int result = new CommandRunner(
                ModuleLayer.boot(),
                tools,
                catalog,
                () -> null,
                (arguments, _, _, _) -> {
                    javaArguments.addAll(arguments);
                    return 0;
                })
                .run(commandLine, InputStream.nullInputStream(), new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, result);
        assertEquals(
                List.of("--module-path", "modules", "--upgrade-module-path", "upgrades", "--add-modules", "com.example.app",
                        "--list-modules"),
                javaArguments);
    }

    @Test
    void rendersTerminalDocumentationWithJist() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("src/com.example.app"));
        Files.writeString(source.resolve("module-info.java"), "module com.example.app {}\n");
        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"doc", "java.lang.String.isEmpty"});
        var jistArguments = new ArrayList<String>();
        ToolServices tools = ToolServices.of(fakeJig(Set.of("compile"),
                        """
                                --module-source-path
                                src
                                --module
                                com.example.app
                                """),
                        tool("jist",
                                (output, arguments) -> {
                                    jistArguments.addAll(arguments);
                                    output.println("String documentation");
                                    return 0;
                                }),
                        tool("jdocserver", (_, _) -> 0));
        var output = new ByteArrayOutputStream();

        int result = new CommandRunner(
                ModuleLayer.boot(),
                tools,
                new ToolCatalog(List.of()),
                () -> null,
                (_, _, _, _) -> 0,
                null,
                (_, _) -> {
                    throw new AssertionError("Browser must not be started");
                })
                .run(commandLine, InputStream.nullInputStream(), new PrintStream(output),
                        new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, result);
        assertEquals("String documentation\n", output.toString());
        assertEquals(
                List.of("--module-source-path", "src", "--module", "com.example.app", "--source", "doc",
                        "--break", "--no-line-number", "java.lang.String.isEmpty"),
                jistArguments);
    }

    @Test
    void rendersDocumentationAcrossMultipleSourceModules() throws Exception {
        Path first = Files.createDirectories(temporaryDirectory.resolve("src/com.example.first"));
        Files.writeString(first.resolve("module-info.java"), "module com.example.first {}\n");
        Path second = Files.createDirectories(temporaryDirectory.resolve("src/com.example.second"));
        Files.writeString(second.resolve("module-info.java"), "module com.example.second {}\n");
        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"doc", "java.lang.String.isEmpty"});
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        var jistArguments = new ArrayList<String>();
        var tools = ToolServices.of(ToolProvider.findFirst("jig").orElseThrow(),
                tool("jist",
                        (toolOutput, arguments) -> {
                            jistArguments.addAll(arguments);
                            toolOutput.println("String.isEmpty documentation");
                            return 0;
                        }),
                tool("jdocserver", (_, _) -> 0));
        var jist = new ToolDefinition(
                "jist",
                Launch.PROVIDER,
                Optional.empty(),
                Optional.empty(),
                "jist",
                Optional.empty(),
                Set.of("module-path", "module-source-path", "module=list", "release", "enable-preview", "add-exports"),
                List.of());

        int result = new CommandRunner(ModuleLayer.boot(), tools, new ToolCatalog(List.of(jist)), () -> null)
                .run(commandLine, InputStream.nullInputStream(), new PrintStream(output),
                        new PrintStream(errors));

        assertEquals(0, result, errors.toString());
        assertEquals("com.example.first,com.example.second", jistArguments.get(jistArguments.indexOf("--module") + 1));
        assertTrue(output.toString().contains("isEmpty"),
                output.toString());
    }

    @Test
    void rendersSourceWithJistSymbolScope() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("src/com.example.app"));
        Files.writeString(source.resolve("module-info.java"), "module com.example.app {}\n");
        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"source", "java.lang.String.isEmpty"});
        var jistArguments = new ArrayList<String>();
        ToolServices tools = ToolServices.of(fakeJig(Set.of("compile"),
                        """
                                --module-source-path
                                src
                                --module
                                com.example.app
                                """),
                        tool("jist",
                                (output, arguments) -> {
                                    jistArguments.addAll(arguments);
                                    output.println("String source");
                                    return 0;
                                }));
        var output = new ByteArrayOutputStream();

        int result = new CommandRunner(
                ModuleLayer.boot(),
                tools,
                new ToolCatalog(List.of()),
                () -> null,
                (_, _, _, _) -> 0)
                .run(commandLine, InputStream.nullInputStream(), new PrintStream(output),
                        new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, result);
        assertEquals("String source\n", output.toString());
        assertEquals(
                List.of("--module-source-path", "src", "--module", "com.example.app", "--source", "symbol",
                        "java.lang.String.isEmpty"),
                jistArguments);
    }

    @Test
    void browsesDocumentationWithHandlerArguments() throws Exception {
        Path first = Files.createDirectories(temporaryDirectory.resolve("src/com.example.first"));
        Files.writeString(first.resolve("module-info.java"), "module com.example.first {}\n");
        Path second = Files.createDirectories(temporaryDirectory.resolve("src/com.example.second"));
        Files.writeString(second.resolve("module-info.java"), "module com.example.second {}\n");
        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"doc", "--browse", "java.lang.String"});
        var browserArguments = new ArrayList<String>();
        var browsedType = new AtomicReference<Optional<String>>();
        ToolServices tools = ToolServices.of(tool("jig",
                (output, arguments) -> {
                    assertEquals(optionList(COMPILE_OPTIONS), arguments.get(arguments.indexOf("--resolve-options") + 1));
                    output
                            .print("""
                                            --module-source-path
                                            src
                                            --module
                                            com.example.first,com.example.second
                                            --module-version
                                            1
                                            """);
                    return 0;
                }),
                tool("jist", (_, _) -> {
                    throw new AssertionError("Jist must not run");
                }),
                tool("jdocserver", (_, _) -> 0));
        var jdocserver = new ToolDefinition(
                "jdocserver",
                Launch.PROVIDER,
                Optional.empty(),
                Optional.empty(),
                "jdocserver",
                Optional.empty(),
                COMPILE_OPTIONS,
                List.of());

        int result = new CommandRunner(
                ModuleLayer.boot(),
                tools,
                new ToolCatalog(List.of(jdocserver)),
                () -> null,
                (_, _, _, _) -> 0,
                null,
                (arguments, type) -> {
                    browserArguments.addAll(arguments);
                    browsedType.set(type);
                    return 0;
                })
                .run(commandLine, InputStream.nullInputStream(), new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, result);
        assertEquals(List.of("--module-source-path", "src", "--module", "com.example.first,com.example.second"), browserArguments);
        assertEquals(Optional.of("java.lang.String"), browsedType.get());
    }

    @Test
    void requireAddsRuntimeAccessAuthorizationsToTheModule() throws Exception {
        Path module = Files.createDirectories(temporaryDirectory.resolve("src/com.example.app"));
        Path descriptor = Files.writeString(module.resolve("module-info.java"),
                """
                        /**
                         * @mainClass com.example.Main
                         */
                        module com.example.app {}
                        """);
        var commandLine = JaInvocation.parse(
                temporaryDirectory,
                new String[] {"require", "--enable-native-access", "org.example.nativebinding,org.example.other", "--enable-final-field-mutation=org.example.model", "--add-exports", "jdk.compiler/com.sun.tools.javac.tree=com.example.app",
                        "--add-opens=java.base/java.lang=com.example.app", "org.example.library@1.2.3"});
        ToolServices tools = ToolServices.of(tool(
                "jig",
                (output, arguments) -> {
                    assertEquals("add-exports,add-opens,enable-final-field-mutation,enable-native-access,module-path," + "module-source-path", arguments.get(arguments.indexOf("--resolve-options") + 1));
                    assertTrue(arguments.contains("--validate-runtime-access"));
                    Path stagedModule = moduleSource(arguments, "com.example.app");
                    String stagedDescriptor = Files.readString(stagedModule.resolve("module-info.java"));
                    assertTrue(stagedDescriptor.contains("@mainClass com.example.Main"));
                    assertTrue(stagedDescriptor.contains("@enableNativeAccess org.example.nativebinding"));
                    assertTrue(stagedDescriptor.contains("@enableNativeAccess org.example.other"));
                    assertTrue(stagedDescriptor.contains("@enableFinalFieldMutation org.example.model"));
                    assertTrue(stagedDescriptor.contains("@addExports jdk.compiler/com.sun.tools.javac.tree=com.example.app"));
                    assertTrue(stagedDescriptor.contains("@addOpens java.base/java.lang=com.example.app"));
                    assertTrue(stagedDescriptor.contains("requires org.example.library; // @1.2.3"));
                    Files.writeString(stagedModule.resolve("module-info.hash"), "updated\n");
                    return 0;
                }));

        int result = new CommandRunner(ModuleLayer.boot(), tools, ToolCatalog.load(ModuleLayer.boot()), () -> null)
                .run(commandLine, InputStream.nullInputStream(), new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, result);
        String updated = Files.readString(descriptor);
        assertTrue(updated.contains("@enableNativeAccess org.example.nativebinding"));
        assertTrue(updated.contains("@enableNativeAccess org.example.other"));
        assertTrue(updated.contains("@enableFinalFieldMutation org.example.model"));
        assertTrue(updated.contains("@addExports jdk.compiler/com.sun.tools.javac.tree=com.example.app"));
        assertTrue(updated.contains("@addOpens java.base/java.lang=com.example.app"));
    }

    @Test
    void requireWarnsWhenTheResolvedDependencyIsAnAutomaticModule() throws Exception {
        Path module = Files.createDirectories(temporaryDirectory.resolve("src/com.example.app"));
        Files.writeString(module.resolve("module-info.java"), "module com.example.app {}\n");
        Path dependency = TestModules.writeAutomaticJar(temporaryDirectory.resolve("org.example.library-1.2.3.jar"));
        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"require", "org.example.library@1.2.3"});
        ToolServices tools = ToolServices.of(tool(
                "jig",
                (output, arguments) -> {
                    Path stagedModule = moduleSource(arguments, "com.example.app");
                    Files.writeString(stagedModule.resolve("module-info.hash"), "updated\n");
                    output.print("--module-path\n" + dependency + "\n");
                    return 0;
                }));
        var errors = new ByteArrayOutputStream();

        int result = new CommandRunner(ModuleLayer.boot(), tools, ToolCatalog.load(ModuleLayer.boot()), () -> null)
                .run(commandLine, InputStream.nullInputStream(), new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(errors));

        assertEquals(0, result);
        assertEquals(
                """
                warning: dependencies resolved as automatic modules:
                  org.example.library
                Automatic modules are a migration aid and do not define explicit module boundaries.
                """,
                errors.toString().replace(System.lineSeparator(), "\n"));
    }

    @Test
    void reliesOnTheModuleGraphForJigAvailability() {
        assertDoesNotThrow(
                () -> new CommandRunner(ModuleLayer.boot(), ToolServices.of(), new ToolCatalog(List.of()),
                        () -> null));
    }

    @Test
    void reportsDependencyUpdatesBeforeAutomaticModuleWarnings() throws Exception {
        assertEquals(
                """
                org.example.library 0.9.0 -> 1.2.0
                warning: dependencies resolved as automatic modules:
                  org.example.library
                Automatic modules are a migration aid and do not define explicit module boundaries.
                """,
                compatibleRequireUpdateOutput().replace(System.lineSeparator(), "\n"));
    }

    private String compatibleRequireUpdateOutput() throws Exception {
        var sourcePath = temporaryDirectory.resolve("src");
        var module = Files.createDirectories(sourcePath.resolve("com.example.app"));
        var descriptor = Files.writeString(module.resolve("module-info.java"),
                """
                        module com.example.app {
                            requires org.example.library; // @0.9.0
                        }
                        """);
        var hash = Files.writeString(module.resolve("module-info.hash"), "existing\n");
        Path automatic = TestModules.writeAutomaticJar(temporaryDirectory.resolve("org.example.library-1.2.0.jar"));
        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"require", "-u"});
        var tools = ToolServices.of(tool(
                "jig",
                (output, arguments) -> {
                    if (arguments.contains("--list-module-versions")) {
                        output.print("0.9.1\n1.2.0\n1.3.0-beta.1\n2.0.0\n");
                        return 0;
                    }
                    assertTrue(arguments.contains("--update-module-hashes"));
                    assertEquals(-1, arguments.indexOf("--verify-module-hashes"));
                    var stagedModule = moduleSource(arguments, "com.example.app");
                    assertTrue(Files.readString(stagedModule.resolve("module-info.java")).contains("requires org.example.library; // @1.2.0"));
                    Files.writeString(stagedModule.resolve("module-info.hash"), "updated\n");
                    output.print("--module-path\n" + automatic + "\n");
                    return 0;
                }));
        var output = new ByteArrayOutputStream();
        var stream = new PrintStream(output);
        var result = new CommandRunner(ModuleLayer.boot(), tools, ToolCatalog.load(ModuleLayer.boot()), () -> null)
                .run(commandLine, InputStream.nullInputStream(), stream, stream);

        assertEquals(0, result);
        assertTrue(Files.readString(descriptor)
                .contains("requires org.example.library; // @1.2.0"));
        assertEquals("updated\n", Files.readString(hash));
        return output.toString();
    }

    @Test
    void patchRequireUpdateKeepsTheCurrentMinorLine() throws Exception {
        var descriptor = runRequireUpdate("2.3.1", "-u=patch", List.of("2.3.2-beta.1", "2.3.9+build.4", "2.4.0", "3.0.0"));

        assertTrue(descriptor.contains("requires org.example.library; // @2.3.9+build.4"));
    }

    @Test
    void majorRequireUpdateSelectsTheLatestStableVersion() throws Exception {
        var descriptor = runRequireUpdate("1.2.3", "--update=major", List.of("1.9.0", "2.4.0", "3.0.0-beta.1", "3.0.0"));

        assertTrue(descriptor.contains("requires org.example.library; // @3.0.0"));
    }

    @Test
    void requireUpdateSelectsTheLatestNonSemanticVersionWhenNoSemanticVersionExists() throws Exception {
        var descriptor = runRequireUpdate("1.37", "--update", List.of("1.35", "1.37", "1.38"));

        assertTrue(descriptor.contains("requires org.example.library; // @1.38"));
    }

    @Test
    void unversionedRequireAddsTheLatestStableSemanticVersion() throws Exception {
        var module = Files.createDirectories(temporaryDirectory.resolve("src/com.example.app"));
        var descriptor = Files.writeString(module.resolve("module-info.java"), "module com.example.app {}\n");
        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"require", "org.example.library"});
        var tools = ToolServices.of(tool("jig", (output, arguments) -> {
            if (arguments.contains("--list-module-versions")) {
                output.print("not-semver\n2.4.0\n3.0.0\n4.0.0-beta.1\n");
            } else {
                var stagedModule = moduleSource(arguments, "com.example.app");
                assertTrue(Files.readString(stagedModule.resolve("module-info.java")).contains("requires org.example.library; // @3.0.0"));
                Files.writeString(stagedModule.resolve("module-info.hash"), "updated\n");
            }
            return 0;
        }));

        int result = new CommandRunner(ModuleLayer.boot(), tools, ToolCatalog.load(ModuleLayer.boot()), () -> null)
                .run(commandLine, InputStream.nullInputStream(), new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, result);
        assertTrue(Files.readString(descriptor)
                .contains("requires org.example.library; // @3.0.0"));
    }

    @Test
    void requireResolvesAPackageUrlToItsModuleName() throws Exception {
        var module = Files.createDirectories(temporaryDirectory.resolve("src/com.example.app"));
        var descriptor = Files.writeString(module.resolve("module-info.java"), "module com.example.app {}\n");
        String packageUrl = "pkg:maven/org.example/example-library@1.2.3?repository_url=https%3A%2F%2Frepo.example";
        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"require", packageUrl});
        var tools = ToolServices.of(tool("jig", (output, arguments) -> {
            if (arguments.contains("--lookup-module")) {
                assertEquals(packageUrl, arguments.get(arguments.indexOf("--lookup-module") + 1));
                output.println("org.example.library");
            } else {
                var stagedModule = moduleSource(arguments, "com.example.app");
                assertTrue(Files.readString(stagedModule.resolve("module-info.java")).contains("requires org.example.library; // @1.2.3"));
                Files.writeString(stagedModule.resolve("module-info.hash"), "updated\n");
            }
            return 0;
        }));

        int result = new CommandRunner(ModuleLayer.boot(), tools, ToolCatalog.load(ModuleLayer.boot()), () -> null)
                .run(commandLine, InputStream.nullInputStream(), new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, result);
        assertTrue(Files.readString(descriptor)
                .contains("requires org.example.library; // @1.2.3"));
    }

    @Test
    void requireUpdateUpdatesEveryModuleOnTheSourcePath() throws Exception {
        var sourcePath = temporaryDirectory.resolve("src");
        var application = Files.createDirectories(sourcePath.resolve("com.example.app"));
        var library = Files.createDirectories(sourcePath.resolve("com.example.lib"));
        var applicationDescriptor = Files.writeString(application.resolve("module-info.java"),
                """
                        module com.example.app {
                            requires org.example.application; // @1.0.0
                        }
                        """);
        var libraryDescriptor = Files.writeString(library.resolve("module-info.java"),
                """
                        module com.example.lib {
                            requires org.example.library; // @2.0.0
                        }
                        """);
        Files.writeString(application.resolve("module-info.hash"), "existing app\n");
        Files.writeString(library.resolve("module-info.hash"), "existing lib\n");
        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"require", "-u"});
        var tools = ToolServices.of(tool(
                "jig",
                (output, arguments) -> {
                    int versions = arguments.indexOf("--list-module-versions");
                    if (versions >= 0) {
                        String module = arguments.get(versions + 1);
                        output.println(module.equals("org.example.application") ? "1.1.0" : "2.1.0");
                        return 0;
                    }
                    var stagedApplication = moduleSource(arguments, "com.example.app");
                    var stagedLibrary = moduleSource(arguments, "com.example.lib");
                    assertTrue(Files.readString(stagedApplication.resolve("module-info.java")).contains("requires org.example.application; // @1.1.0"));
                    assertTrue(Files.readString(stagedLibrary.resolve("module-info.java")).contains("requires org.example.library; // @2.1.0"));
                    Files.writeString(stagedApplication.resolve("module-info.hash"), "updated app\n");
                    Files.writeString(stagedLibrary.resolve("module-info.hash"), "updated lib\n");
                    return 0;
                }));

        int result = new CommandRunner(ModuleLayer.boot(), tools, ToolCatalog.load(ModuleLayer.boot()), () -> null)
                .run(commandLine, InputStream.nullInputStream(), new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, result);
        assertTrue(Files.readString(applicationDescriptor)
                .contains("requires org.example.application; // @1.1.0"));
        assertTrue(Files.readString(libraryDescriptor)
                .contains("requires org.example.library; // @2.1.0"));
    }

    @Test
    void requireUpdateFailsWhenTheModuleIsMissing() throws Exception {
        var module = Files.createDirectories(temporaryDirectory.resolve("src/com.example.app"));
        var descriptor = Files.writeString(module.resolve("module-info.java"),
                """
                        module com.example.app {
                            requires org.example.missing; // @1.0.0
                        }
                        """);
        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"require", "-u"});
        var resolutionRuns = new AtomicInteger();
        var tools = ToolServices.of(tool("jig",
                (output, arguments) -> {
                    if (arguments.contains("--list-module-versions")) {
                        return 0;
                    }
                    resolutionRuns.incrementAndGet();
                    return 0;
                }));
        var engine = new CommandRunner(ModuleLayer.boot(), tools, ToolCatalog.load(ModuleLayer.boot()),
                () -> null);

        var failure = assertThrows(
                IllegalArgumentException.class,
                () -> engine.run(commandLine, InputStream.nullInputStream(), new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream())));

        assertEquals("Module not found: org.example.missing", failure.getMessage());
        assertEquals(0, resolutionRuns.get());
        assertTrue(Files.readString(descriptor)
                .contains("// @1.0.0"));
    }

    @Test
    void requireStagesTheDescriptorAndExistingHashBeforeUpdating() throws Exception {
        Path sourcePath = temporaryDirectory.resolve("src");
        Path module = Files.createDirectories(sourcePath.resolve("com.example.app"));
        Path descriptor = module.resolve("module-info.java");
        Path hash = module.resolve("module-info.hash");
        Files.writeString(descriptor,
                """
                module com.example.app {
                    exports com.example.app;
                }
                """);
        Files.writeString(hash, "existing\n");
        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"require", "--static", "--transitive", "org.example.library@1.2.3"});
        ToolServices tools = ToolServices.of(tool(
                "jig",
                (output, arguments) -> {
                    int option = arguments.indexOf("--module-source-path");
                    String mapping = arguments.get(option + 1);
                    Path stagedModule = Path.of(mapping.substring(mapping.indexOf('=') + 1));
                    assertTrue(!stagedModule.equals(module));
                    assertEquals("existing\n", Files.readString(stagedModule.resolve("module-info.hash")));
                    assertTrue(Files.readString(stagedModule.resolve("module-info.java")).contains("requires static transitive org.example.library; // @1.2.3"));
                    Files.writeString(stagedModule.resolve("module-info.hash"), "updated\n");
                    return 0;
                }));

        int result = new CommandRunner(ModuleLayer.boot(), tools, ToolCatalog.load(ModuleLayer.boot()), () -> null)
                .run(commandLine, InputStream.nullInputStream(), new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, result);
        assertTrue(Files.readString(descriptor)
                .contains("requires static transitive org.example.library; // @1.2.3"));
        assertEquals("updated\n", Files.readString(hash));
    }

    @Test
    void requireLeavesSourceFilesUntouchedWhenResolutionFails() throws Exception {
        Path module = Files.createDirectories(temporaryDirectory.resolve("src/com.example.app"));
        Path descriptor = module.resolve("module-info.java");
        Path hash = module.resolve("module-info.hash");
        Files.writeString(descriptor, "module com.example.app {}\n");
        Files.writeString(hash, "existing\n");
        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"require", "org.example.library@1.2.3"});
        ToolServices tools = ToolServices.of(tool("jig", (output, arguments) -> 1));
        CommandRunner engine = new CommandRunner(ModuleLayer.boot(), tools, ToolCatalog.load(ModuleLayer.boot()),
                () -> null);

        assertThrows(
                ToolExecutionException.class,
                () -> engine.run(commandLine, InputStream.nullInputStream(), new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream())));
        assertEquals("module com.example.app {}\n", Files.readString(descriptor));
        assertEquals("existing\n", Files.readString(hash));
    }

    @Test
    void compileDoesNotLoadTheToolCatalog() throws Exception {
        var source = Files.createDirectories(temporaryDirectory.resolve("src/com.example.app"));
        Files.writeString(source.resolve("module-info.java"), "module com.example.app {}\n");
        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"compile"});
        var catalogLoads = new AtomicInteger();
        var tools = ToolServices.of(tool("jig", (_, _) -> 0));

        int result = new CommandRunner(ModuleLayer.boot(), tools,
                () -> {
                    catalogLoads.incrementAndGet();
                    return ToolCatalog.load(ModuleLayer.boot());
                },
                () -> null)
                .run(commandLine, InputStream.nullInputStream(), new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, result);
        assertEquals(0, catalogLoads.get());
    }

    @Test
    void toolListingLoadsTheToolCatalog() throws Exception {
        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"tool"});
        var catalogLoads = new AtomicInteger();
        var definition = ToolDefinition.read("probe", new ByteArrayInputStream("provider=probe\n".getBytes(StandardCharsets.UTF_8)));
        var output = new ByteArrayOutputStream();

        int result = new CommandRunner(ModuleLayer.boot(), ToolServices.of(),
                () -> {
                    catalogLoads.incrementAndGet();
                    return new ToolCatalog(List.of(definition));
                },
                () -> null)
                .run(commandLine, InputStream.nullInputStream(), new PrintStream(output),
                        new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, result);
        assertEquals(1, catalogLoads.get());
        assertEquals("probe\n", output.toString());
    }

    @Test
    void verboseIsPassedToJig() throws Exception {
        var source = Files.createDirectories(temporaryDirectory.resolve("src/com.example.app"));
        Files.writeString(source.resolve("module-info.java"), "module com.example.app {}\n");
        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"--verbose", "compile"});
        var runWith = new ArrayList<String>();
        var tools = ToolServices.of(tool("jig",
                (output, arguments) -> {
                    runWith.addAll(arguments);
                    return 0;
                }));
        var log = new ByteArrayOutputStream();

        var result = new CommandRunner(ModuleLayer.boot(), tools, new ToolCatalog(List.of()), () -> null)
                .run(commandLine, InputStream.nullInputStream(), new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(log));

        assertEquals(0, result);
        assertTrue(runWith.contains("--verbose"));
        var logLines = log.toString()
                          .lines()
                          .toList();
        assertEquals(1, logLines.size(), log.toString());
        assertTrue(logLines.getFirst().startsWith("[resolve jig "),
                log.toString());
        assertTrue(logLines.getFirst().endsWith("]"),
                log.toString());
    }

    @Test
    void verboseIsPassedOnlyToToolsDeclaringSupport() throws Exception {
        var supportedArguments = new ArrayList<String>();
        var unsupportedArguments = new ArrayList<String>();
        var tools = ToolServices.of(tool("supported",
                (_, arguments) -> {
                    supportedArguments.addAll(arguments);
                    return 0;
                }),
                tool("unsupported",
                        (_, arguments) -> {
                            unsupportedArguments.addAll(arguments);
                            return 0;
                        }));
        var catalog = new ToolCatalog(
                List.of(
                        ToolDefinition.read("supported", new ByteArrayInputStream("provider=supported\nversion=1\noptions=verbose\n".getBytes(StandardCharsets.UTF_8))),
                        new ToolDefinition(
                                "unsupported",
                                Launch.PROVIDER,
                                Optional.empty(),
                                Optional.empty(),
                                "unsupported",
                                Optional.of("1"),
                                Set.of(),
                                List.of())));
        var runner = new CommandRunner(ModuleLayer.boot(), tools, catalog, () -> null);

        assertEquals(
                0,
                runner.run(JaInvocation.parse(temporaryDirectory, new String[] {"--verbose", "tool", "supported"}), InputStream.nullInputStream(),
                        new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream())));
        assertEquals(
                0,
                runner.run(JaInvocation.parse(temporaryDirectory, new String[] {"--verbose", "tool", "unsupported"}), InputStream.nullInputStream(),
                        new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream())));

        assertEquals(List.of("--verbose"), supportedArguments);
        assertEquals(List.of(), unsupportedArguments);
    }

    @Test
    void compileDelegatesMaterializationToJig() throws Exception {
        var source = Files.createDirectories(temporaryDirectory.resolve("src/com.example.app"));
        Files.writeString(source.resolve("module-info.java"), "module com.example.app {}\n");
        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"compile", "--recompile"});
        var workspace = TemporaryDirectory.unmanaged(temporaryDirectory.resolve("shared"));
        var materializedModule = temporaryDirectory.resolve("cas/modules/content-hash");
        var compilerRuns = new AtomicInteger();
        var tools = ToolServices.of(tool(
                "jig",
                (output, arguments) -> {
                    assertFalse(arguments.contains("-d"));
                    assertTrue(arguments.contains("--recompile"));
                    assertFalse(arguments.contains("--no-compile-diagnostics"));
                    assertEquals("module-path,upgrade-module-path", arguments.get(arguments.indexOf("--resolve-options") + 1));
                    output.print("--module-path\n" + materializedModule + "\n");
                    return 0;
                }),
                tool("javac", (output, arguments) -> compilerRuns.incrementAndGet()));

        var result = new CommandRunner(ModuleLayer.boot(), tools, ToolCatalog.load(ModuleLayer.boot()), () -> workspace)
                .run(commandLine, InputStream.nullInputStream(), new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, result);
        assertEquals(0, compilerRuns.get());
    }

    @Test
    void generatePublishesProcessorSourceOutputIntoTheModule() throws Exception {
        var sourcePath = temporaryDirectory.resolve("src");
        var module = Files.createDirectories(sourcePath.resolve("com.example.app"));
        Files.writeString(module.resolve("module-info.java"), "/** @processWith com.example.processor */\nmodule com.example.app {}\n");
        var processorPath = temporaryDirectory.resolve("processor.jar");
        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"generate", "-Astyle=records"});
        var workspace = TemporaryDirectory.unmanaged(temporaryDirectory.resolve("shared"));
        var compilerRuns = new AtomicInteger();
        var tools = ToolServices.of(tool(
                "jig",
                (output, arguments) -> {
                    assertEquals(optionList(COMPILE_OPTIONS), arguments.get(arguments.indexOf("--resolve-options") + 1));
                    assertFalse(arguments.contains("--compile-time"));
                    assertTrue(arguments.contains("--no-compile-diagnostics"));
                    output.print("--module-source-path\ncom.example.app=" + module + "\n--processor-module-path\n" + processorPath + "\n--module\ncom.example.app\n");
                    return 0;
                }),
                tool(
                        "javac",
                        (output, arguments) -> {
                            compilerRuns.incrementAndGet();
                            assertTrue(arguments.contains("-proc:only"));
                            assertFalse(arguments.contains("-proc:none"));
                            assertTrue(arguments.contains("-implicit:none"));
                            assertTrue(arguments.contains("-Astyle=records"));
                            var sourcePathValue = arguments.get(arguments.indexOf("--module-source-path") + 1);
                            assertTrue(sourcePathValue.startsWith("com.example.app=" + module + File.pathSeparator));
                            var generated = Path.of(arguments.get(arguments.indexOf("-s") + 1));
                            assertTrue(sourcePathValue.endsWith(generated.resolve("com.example.app").toString()));
                            var generatedPackage = Files.createDirectories(generated.resolve("com.example.app/com/example/app"));
                            Files.writeString(generatedPackage.resolve("Generated.java"),
                                    """
                                            package com.example.app;
                                            public final class Generated {}
                                            """);
                            var classes = compilationOutput(arguments).resolve("com.example.app");
                            Files.createDirectories(classes);
                            Files.writeString(classes.resolve("processor-resource.txt"), "temporary\n");
                            return 0;
                        }));

        var result = new CommandRunner(ModuleLayer.boot(), tools, ToolCatalog.load(ModuleLayer.boot()), () -> workspace)
                .run(commandLine, InputStream.nullInputStream(), new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, result);
        assertEquals(1, compilerRuns.get());
        assertTrue(Files.isRegularFile(module.resolve("com/example/app/Generated.java")));
        assertFalse(Files.exists(module.resolve("processor-resource.txt")));
    }

    @Test
    void generateDoesNothingWhenTheModuleDeclaresNoProcessor() throws Exception {
        var sourcePath = temporaryDirectory.resolve("src");
        var module = Files.createDirectories(sourcePath.resolve("com.example.app"));
        Files.writeString(module.resolve("module-info.java"), "module com.example.app {}\n");
        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"generate"});
        var workspace = TemporaryDirectory.unmanaged(temporaryDirectory.resolve("shared"));
        var compilerRuns = new AtomicInteger();
        var tools = ToolServices.of(tool("jig",
                (output, arguments) -> {
                    assertEquals(optionList(COMPILE_OPTIONS), arguments.get(arguments.indexOf("--resolve-options") + 1));
                    assertFalse(arguments.contains("--compile-time"));
                    output.print("--module-source-path\ncom.example.app=" + module + "\n--module\ncom.example.app\n");
                    return 0;
                }),
                tool("javac", (output, arguments) -> compilerRuns.incrementAndGet()));

        var result = new CommandRunner(ModuleLayer.boot(), tools, ToolCatalog.load(ModuleLayer.boot()), () -> workspace)
                .run(commandLine, InputStream.nullInputStream(), new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, result);
        assertEquals(0, compilerRuns.get());
    }

    @Test
    void verboseRunLogsExecutionWithAuthoritativeJigArguments() throws Exception {
        Path sourcePath = temporaryDirectory.resolve("src");
        Path module = Files.createDirectories(sourcePath.resolve("com.example.app"));
        Files.writeString(module.resolve("module-info.java"),
                """
                /** @mainClass com.example.Main */
                module com.example.app {}
                """);
        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"--verbose", "run", "one", "two"});
        TemporaryDirectory workspace = TemporaryDirectory.unmanaged(temporaryDirectory.resolve("shared"));
        Path externalModules = temporaryDirectory.resolve("external-modules");
        Path materializedModule = temporaryDirectory.resolve("cas/modules/content-hash");
        var jigRuns = new AtomicInteger();
        var launchedArguments = new ArrayList<String>();
        var standardOutput = new ByteArrayOutputStream();
        var standardError = new ByteArrayOutputStream();
        ToolServices tools = ToolServices.of(tool(
                "jig",
                (output, arguments) -> {
                    jigRuns.incrementAndGet();
                    assertEquals(optionList(LAUNCH_OPTIONS), arguments.get(arguments.indexOf("--resolve-options") + 1));
                    assertTrue(arguments.contains("--validate-runtime-access"));
                    int separator = arguments.indexOf("--");
                    assertEquals(List.of("one", "two"),
                            arguments.subList(separator + 1, arguments.size()));
                    output.print("--module-path\n" + materializedModule + "\n--add-modules\ncom.example.app,com.example.dependency\n--module\ncom.example.app/" + "com.example.Main\n");
                    return 0;
                }));

        int exitCode = new CommandRunner(
                ModuleLayer.boot(),
                tools,
                ToolCatalog.load(ModuleLayer.boot()),
                () -> workspace,
                (arguments, in, out, err) -> {
                    launchedArguments.addAll(arguments);
                    out.print("application output");
                    err.print("application error");
                    return 17;
                })
                .run(commandLine, new ByteArrayInputStream(new byte[0]), new PrintStream(standardOutput),
                        new PrintStream(standardError));

        assertEquals(17, exitCode);
        assertEquals("application output", standardOutput.toString());
        assertTrue(standardError.toString().contains("[execute java "),
                standardError.toString());
        assertTrue(standardError.toString().endsWith("application error"),
                standardError.toString());
        assertEquals(1, jigRuns.get());
        assertEquals(
                List.of("--module-path", materializedModule.toString(), "--add-modules", "com.example.app,com.example.dependency", "--module",
                        "com.example.app/com.example.Main", "one", "two"),
                launchedArguments);
    }

    @Test
    void runUsesTheModuleMaterializedByJig() throws Exception {
        var module = Files.createDirectories(temporaryDirectory.resolve("src/com.example.application"));
        Files.writeString(module.resolve("module-info.java"),
                """
                /** @mainClass com.example.application.Main */
                module com.example.application {}
                """);
        Files.createFile(module.resolve("module-info.hash"));
        var packageDirectory = Files.createDirectories(module.resolve("com/example/application"));
        Files.writeString(packageDirectory.resolve("Main.java"),
                """
                package com.example.application;
                public final class Main {
                    public static void main(String[] arguments) {}
                }
                """);
        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"run"});
        var launchedArguments = new ArrayList<String>();

        var result = new CommandRunner(
                ModuleLayer.boot(),
                ToolServices.load(ModuleLayer.boot()),
                ToolCatalog.load(ModuleLayer.boot()),
                () -> {
                    throw new AssertionError("run must not request a ja compilation directory");
                },
                (arguments, in, out, err) -> {
                    launchedArguments.addAll(arguments);
                    return 0;
                })
                .run(commandLine, InputStream.nullInputStream(), new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, result);
        var modulePath = ToolArguments.modulePath(launchedArguments);
        var reference = ModuleFinder.of(modulePath.toArray(Path[]::new))
                .find("com.example.application")
                .orElseThrow();
        assertTrue(Files.isDirectory(Path.of(reference.location()
                .orElseThrow())));
        assertEquals("com.example.application/com.example.application.Main", launchedArguments.get(launchedArguments.indexOf("--module") + 1));
    }

    @Test
    void runUsesTheDeclaredMainClassForAnExplicitModule() throws Exception {
        var module = Files.createDirectories(temporaryDirectory.resolve("src/com.example.application"));
        Files.writeString(module.resolve("module-info.java"),
                """
                /** @mainClass com.example.application.Main */
                module com.example.application {}
                """);
        Files.createFile(module.resolve("module-info.hash"));
        var packageDirectory = Files.createDirectories(module.resolve("com/example/application"));
        Files.writeString(packageDirectory.resolve("Main.java"),
                """
                package com.example.application;
                public final class Main {
                    public static void main(String[] arguments) {}
                }
                """);
        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"run", "-m", "com.example.application", "argument"});
        var launchedArguments = new ArrayList<String>();

        var result = new CommandRunner(
                ModuleLayer.boot(),
                ToolServices.load(ModuleLayer.boot()),
                ToolCatalog.load(ModuleLayer.boot()),
                () -> {
                    throw new AssertionError("run must not request a ja compilation directory");
                },
                (arguments, in, out, err) -> {
                    launchedArguments.addAll(arguments);
                    return 0;
                })
                .run(commandLine, InputStream.nullInputStream(), new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, result);
        assertEquals("com.example.application/com.example.application.Main", launchedArguments.get(launchedArguments.indexOf("--module") + 1));
        assertEquals("argument", launchedArguments.getLast());
    }

    @Test
    void runAcceptsAnExplicitModuleAndMainClass() throws Exception {
        var module = Files.createDirectories(temporaryDirectory.resolve("src/com.example.application"));
        Files.writeString(module.resolve("module-info.java"), "module com.example.application {}\n");
        Files.createFile(module.resolve("module-info.hash"));
        var packageDirectory = Files.createDirectories(module.resolve("com/example/application"));
        Files.writeString(packageDirectory.resolve("Alternate.java"),
                """
                package com.example.application;
                public final class Alternate {
                    public static void main(String[] arguments) {}
                }
                """);
        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"run", "-m", "com.example.application/com.example.application.Alternate", "argument"});
        var launchedArguments = new ArrayList<String>();

        var result = new CommandRunner(
                ModuleLayer.boot(),
                ToolServices.load(ModuleLayer.boot()),
                ToolCatalog.load(ModuleLayer.boot()),
                () -> {
                    throw new AssertionError("run must not request a ja compilation directory");
                },
                (arguments, in, out, err) -> {
                    launchedArguments.addAll(arguments);
                    return 0;
                })
                .run(commandLine, InputStream.nullInputStream(), new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, result);
        assertEquals("com.example.application/com.example.application.Alternate", launchedArguments.get(launchedArguments.indexOf("--module") + 1));
        assertEquals("argument", launchedArguments.getLast());
    }

    @Test
    void runDoesNotLaunchWhenJigMaterializationFails() throws Exception {
        Path sourcePath = temporaryDirectory.resolve("src");
        Path module = Files.createDirectories(sourcePath.resolve("com.example.app"));
        Files.writeString(module.resolve("module-info.java"),
                """
                /** @mainClass com.example.Main */
                module com.example.app {}
                """);
        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"run"});
        TemporaryDirectory workspace = TemporaryDirectory.unmanaged(temporaryDirectory.resolve("shared"));
        var jigRuns = new AtomicInteger();
        var launched = new AtomicBoolean();
        ToolServices tools = ToolServices.of(tool("jig",
                (output, arguments) -> {
                    jigRuns.incrementAndGet();
                    return 9;
                }));

        var engine = new CommandRunner(
                ModuleLayer.boot(),
                tools,
                ToolCatalog.load(ModuleLayer.boot()),
                () -> workspace,
                (arguments, in, out, err) -> {
                    launched.set(true);
                    return 0;
                });
        assertThrows(
                ToolExecutionException.class,
                () -> engine.run(commandLine, new ByteArrayInputStream(new byte[0]), new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream())));

        assertEquals(1, jigRuns.get());
        assertFalse(launched.get());
    }

    @Test
    void compileFailurePropagatesWithoutManagingOutput() throws Exception {
        Path sourcePath = temporaryDirectory.resolve("src");
        for (String module : List.of("com.example.app", "com.example.library")) {
            Path source = Files.createDirectories(sourcePath.resolve(module));
            Files.writeString(source.resolve("module-info.java"), "module " + module + " {}\n");
        }
        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"compile"});
        TemporaryDirectory workspace = TemporaryDirectory.unmanaged(temporaryDirectory.resolve("shared"));
        ToolServices tools = ToolServices.of(tool("jig",
                (output, arguments) -> {
                    assertFalse(arguments.contains("-d"));
                    return 9;
                }));

        var engine = new CommandRunner(ModuleLayer.boot(), tools, ToolCatalog.load(ModuleLayer.boot()),
                () -> workspace);
        assertThrows(
                ToolExecutionException.class,
                () -> engine.run(commandLine, InputStream.nullInputStream(), new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream())));
    }

    @Test
    void describeLaunchesJavaWithTheModuleMaterializedByJig() throws Exception {
        Files.writeString(temporaryDirectory.resolve("module-info.java"), "module com.example.app {}\n");
        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"describe"});
        TemporaryDirectory workspace = TemporaryDirectory.unmanaged(temporaryDirectory.resolve("shared"));
        var materializedModule = temporaryDirectory.resolve("cas/modules/content-hash");
        var launchedArguments = new ArrayList<String>();
        ToolServices tools = ToolServices.of(tool("jig",
                (output, arguments) -> {
                    assertEquals("describe-module,module-path,upgrade-module-path", arguments.get(arguments.indexOf("--resolve-options") + 1));
                    output.print("--module-path\n" + materializedModule + "\n--describe-module\ncom.example.app\n");
                    return 0;
                }));

        int exitCode = new CommandRunner(
                ModuleLayer.boot(),
                tools,
                ToolCatalog.load(ModuleLayer.boot()),
                () -> workspace,
                (arguments, in, out, err) -> {
                    launchedArguments.addAll(arguments);
                    return 0;
                })
                .run(commandLine, new ByteArrayInputStream(new byte[0]), new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, exitCode);
        assertEquals(List.of("--module-path", materializedModule.toString(), "--describe-module", "com.example.app"), launchedArguments);
    }

    @Test
    void describeModuleQualifierSelectsOneModuleFromASourcePath() throws Exception {
        Path sourcePath = temporaryDirectory.resolve("src");
        for (String module : List.of("com.example.app", "com.example.app.test")) {
            Path source = Files.createDirectories(sourcePath.resolve(module));
            Files.writeString(source.resolve("module-info.java"), "module " + module + " {}\n");
        }
        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"describe", "-m", "com.example.app"});
        TemporaryDirectory workspace = TemporaryDirectory.unmanaged(temporaryDirectory.resolve("shared"));
        var materializedModule = temporaryDirectory.resolve("cas/modules/content-hash");
        var launchedArguments = new ArrayList<String>();
        ToolServices tools = ToolServices.of(tool("jig",
                (output, arguments) -> {
                    assertEquals(
                            List.of("-m", "com.example.app", "--module-source-path", sourcePath.toString(), "--verify-module-hashes",
                                    "--resolve-options", "describe-module,module-path,upgrade-module-path", "--no-compile-diagnostics"),
                            arguments);
                    output.print("--module-path\n" + materializedModule + "\n--describe-module\ncom.example.app\n");
                    return 0;
                }));

        int exitCode = new CommandRunner(
                ModuleLayer.boot(),
                tools,
                ToolCatalog.load(ModuleLayer.boot()),
                () -> workspace,
                (arguments, in, out, err) -> {
                    launchedArguments.addAll(arguments);
                    return 0;
                })
                .run(commandLine, InputStream.nullInputStream(), new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, exitCode);
        assertEquals(List.of("--module-path", materializedModule.toString(), "--describe-module", "com.example.app"), launchedArguments);
    }

    @Test
    void namedJUnitToolDoesNotAcquireTheBuiltinTestArguments() throws Exception {
        Path sourcePath = temporaryDirectory.resolve("src");
        Path app = Files.createDirectories(sourcePath.resolve("com.example.app"));
        Path library = Files.createDirectories(sourcePath.resolve("com.example.library"));
        Files.writeString(app.resolve("module-info.java"), "module com.example.app {}\n");
        Files.writeString(library.resolve("module-info.java"), "module com.example.library {}\n");
        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"tool", "junit"});
        TemporaryDirectory workspace = TemporaryDirectory.unmanaged(temporaryDirectory.resolve("shared"));
        Files.createDirectories(workspace.modules());
        var jigRuns = new AtomicInteger();
        ToolServices tools = ToolServices.of(tool(
                "jig",
                (output, arguments) -> {
                    int option = arguments.indexOf("--resolve-options");
                    assertTrue(option >= 0);
                    var options = arguments.get(option + 1);
                    if (options.equals(optionList(MODULE_PATH_OPTIONS))) {
                        assertFalse(arguments.contains("--compile-time"));
                        return 0;
                    }
                    if (jigRuns.getAndIncrement() == 0) {
                        assertEquals(optionList(RUNTIME_ACCESS_OPTIONS), options);
                        assertTrue(arguments.contains("--validate-runtime-access"));
                        output.print("--module-path\n" + workspace.modules() + "\n--add-modules\ncom.example.app,com.example.library\n");
                    } else {
                        assertEquals(
                                List.of(
                                        "--module-source-path",
                                        sourcePath.toString(),
                                        "--add-modules",
                                        "com.example.app",
                                        "--add-modules",
                                        "com.example.library",
                                        "--add-requires",
                                        "org.junit.platform.console@1",
                                        "--resolve-options",
                                        optionList(LAUNCH_OPTIONS),
                                        "--validate-runtime-access",
                                        "--no-compile-diagnostics"),
                                arguments);
                        output.print("--module-path\ntools\n--module\norg.junit.platform.console/example.Main\n");
                    }
                    return 0;
                }));
        ToolDefinition junit = new ToolDefinition(
                "junit",
                Launch.JAVA,
                Optional.empty(),
                Optional.of("org.junit.platform.console"),
                "junit",
                Optional.of("1"),
                Set.of("module-path", "upgrade-module-path", "patch-module", "add-modules", "enable-preview", "enable-native-access",
                        "enable-final-field-mutation", "add-opens", "add-exports"),
                true,
                List.of("execute"),
                Optional.empty(),
                Optional.empty());
        var launchedArguments = new ArrayList<String>();

        int exitCode = new CommandRunner(
                ModuleLayer.boot(),
                tools,
                new ToolCatalog(List.of(junit)),
                () -> workspace,
                (arguments, in, out, err) -> {
                    launchedArguments.addAll(arguments);
                    return 0;
                })
                .run(commandLine, new ByteArrayInputStream(new byte[0]), new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, exitCode);
        assertEquals(2, jigRuns.get());
        assertEquals(List.of("execute"),
                launchedArguments.subList(launchedArguments.size() - 1, launchedArguments.size()));
    }

    @Test
    void jdepsToolProjectsMultipleAnalysisRoots() throws Exception {
        var application = Files.createDirectories(temporaryDirectory.resolve("src/com.example.app"));
        var library = Files.createDirectories(temporaryDirectory.resolve("src/com.example.library"));
        Files.writeString(application.resolve("module-info.java"), "module com.example.app {}\n");
        Files.writeString(library.resolve("module-info.java"), "module com.example.library {}\n");
        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"tool", "jdeps"});
        TemporaryDirectory workspace = TemporaryDirectory.unmanaged(temporaryDirectory.resolve("shared"));
        Files.createDirectories(workspace.modules());
        var jdepsArguments = new ArrayList<String>();
        ToolServices tools = ToolServices.of(tool(
                "jig",
                (output, arguments) -> {
                    int option = arguments.indexOf("--resolve-options");
                    assertTrue(option >= 0);
                    String capabilities = arguments.get(option + 1);
                    if (capabilities.equals(optionList(CONFIGURATION_OPTIONS))) {
                        assertFalse(arguments.contains("--compile-time"));
                        return 0;
                    }
                    assertEquals("add-modules,module-path,module=roots,multi-release,upgrade-module-path", capabilities);
                    output.print("--module-path\n" + workspace.modules() + "\n--upgrade-module-path\nupgrades\n--multi-release\n25\n--add-modules\ncom.example.app,com.example.library\n");
                    return 0;
                }),
                tool("jdeps",
                        (_, arguments) -> {
                            jdepsArguments.addAll(arguments);
                            return 0;
                        }));

        int exitCode = new CommandRunner(
                ModuleLayer.boot(),
                tools,
                ToolCatalog.load(ModuleLayer.boot()),
                () -> workspace,
                (_, _, _, _) -> {
                    throw new AssertionError("jdeps must run through its provider");
                })
                .run(commandLine, new ByteArrayInputStream(new byte[0]), new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, exitCode);
        assertEquals(
                List.of(
                        "--module-path",
                        workspace.modules().toString(),
                        "--upgrade-module-path",
                        "upgrades",
                        "--multi-release",
                        "25",
                        "--add-modules",
                        "com.example.app,com.example.library"),
                jdepsArguments);
    }

    @Test
    void benchmarkCompilesOnlyRootsThatRequireItsActivationModule() throws Exception {
        Path sources = temporaryDirectory.resolve("src");
        Path benchmark = Files.createDirectories(sources.resolve("com.example.benchmark"));
        Files.writeString(benchmark.resolve("module-info.java"),
                "module com.example.benchmark { requires static com.example.activation; }\n");
        Path unrelated = Files.createDirectories(sources.resolve("com.example.unrelated"));
        Files.writeString(unrelated.resolve("module-info.java"), "module com.example.unrelated {}\n");
        Path activation = Files.createDirectories(temporaryDirectory.resolve("activation"));
        TestModules.writeModuleInfo(activation, "com.example.activation");
        Path compiled = Files.createDirectories(temporaryDirectory.resolve("compiled"));
        TestModules.writeModuleInfo(compiled, "com.example.benchmark");
        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"bench"});
        var resolutions = new ArrayList<List<String>>();
        var tools = ToolServices.of(tool("jig",
                (output, arguments) -> {
                    resolutions.add(List.copyOf(arguments));
                    String options = arguments.get(arguments.indexOf("--resolve-options") + 1);
                    if (options.equals(optionList(MODULE_PATH_OPTIONS))) {
                        output.print("--module-path\n" + activation + "\n"
                                + "--module-source-path\ncom.example.benchmark=" + benchmark + "\n"
                                + "--module-source-path\ncom.example.unrelated=" + unrelated + "\n"
                                + "--add-modules\ncom.example.benchmark,com.example.unrelated\n");
                    } else {
                        output.print("--module-path\n" + compiled + "\n--add-modules\ncom.example.benchmark\n");
                    }
                    return 0;
                }),
                tool("benchmark", (_, _) -> 0));
        var definition = new ToolDefinition(
                "jmh",
                Launch.PROVIDER,
                Optional.of("com.example.activation"),
                Optional.empty(),
                "benchmark",
                Optional.empty(),
                Set.of("module-path", "add-modules"),
                List.of());

        int result = new CommandRunner(ModuleLayer.boot(), tools, new ToolCatalog(List.of(definition)), () -> null)
                .run(commandLine, InputStream.nullInputStream(), new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, result);
        assertEquals(2, resolutions.size());
        var compilation = resolutions.getLast();
        assertEquals(List.of("com.example.benchmark"), optionValues(compilation, "-m", "--module"));
        assertFalse(compilation.contains("com.example.unrelated"), compilation.toString());
    }

    @Test
    void staticRequirementActivatesAToolWithoutJoiningItsRuntimeArguments() throws Exception {
        Files.writeString(temporaryDirectory.resolve("module-info.java"),
                """
                module com.example.app {
                    requires static com.example.formatter;
                }
                """);
        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"fmt", "explicit"});
        var application = Files.createDirectories(temporaryDirectory.resolve("application"));
        TestModules.writeModuleInfo(application, "com.example.app");
        var activation = Files.createDirectories(temporaryDirectory.resolve("com.example.formatter"));
        TestModules.writeModuleInfo(activation, "com.example.formatter");
        var jigRuns = new AtomicInteger();
        var toolArguments = new ArrayList<String>();
        var tools = ToolServices.of(tool(
                "jig",
                (output, arguments) -> {
                    var options = arguments.get(arguments.indexOf("--resolve-options") + 1);
                    if (options.equals(optionList(MODULE_PATH_OPTIONS))) {
                        assertFalse(arguments.contains("--compile-time"));
                        output.print("--module-path\n" + application + File.pathSeparator + activation + "\n--add-modules\ncom.example.app,com.example.formatter\n");
                        return 0;
                    }
                    assertEquals("module-path", options);
                    output.print("--module-path\n" + application + "\n");
                    jigRuns.incrementAndGet();
                    return 0;
                }),
                tool("formatter",
                        (_, arguments) -> {
                            toolArguments.addAll(arguments);
                            return 0;
                        }));
        var formatter = new ToolDefinition(
                "jfmt",
                Launch.PROVIDER,
                Optional.of("com.example.formatter"),
                Optional.empty(),
                "formatter",
                Optional.empty(),
                Set.of("module-path"),
                List.of());

        var result = new CommandRunner(ModuleLayer.boot(), tools, new ToolCatalog(List.of(formatter)), () -> null)
                .run(commandLine, InputStream.nullInputStream(), new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, result);
        assertEquals(1, jigRuns.get());
        assertEquals(List.of("--module-path", application.toString(), "explicit"), toolArguments);
    }

    @Test
    void installResolvesAndInstallsAnExternalApplication() throws Exception {
        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"install", "com.example.foo@1.2.3"});
        Path application = TestModules.writeJar(temporaryDirectory.resolve("com.example.foo-1.2.3.jar"), "com.example.foo");
        Path store = temporaryDirectory.resolve("store");
        Path commands = temporaryDirectory.resolve("commands");
        var jigArguments = new ArrayList<String>();
        ToolServices tools = ToolServices.of(tool("jig",
                (output, arguments) -> {
                    jigArguments.addAll(arguments);
                    output.print("--module-path\n" + application + "\n--module\ncom.example.foo/com.example.Main\n");
                    return 0;
                }),
                tool(
                        "jlink",
                        (output, arguments) -> {
                            Path image = Path.of(arguments.get(arguments.indexOf("--output") + 1));
                            Files.createDirectories(image.resolve("bin"));
                            Files.createDirectories(image.resolve("lib"));
                            Files.writeString(image.resolve("bin/java"), "java");
                            Files.writeString(image.resolve("lib/modules"), "modules");
                            return 0;
                        }));
        var installer = new Installer(
                tools,
                new InstallationDirectories(store, commands),
                false,
                (name, target, arguments, output, in, out, err) -> {
                    Path classes = Files.createDirectories(output.resolve("classes"));
                    Files.writeString(classes.resolve("module-info.class"), "command");
                    return new Generated(InstalledCommandModule.moduleName(target.moduleName()), classes, Optional.of("com.example"));
                },
                destination -> {
                    Files.createDirectories(destination.getParent());
                    Files.writeString(destination, "native");
                },
                (target, arguments, includeStatic) -> Set.of("com.netflix.tools.launcher", "java.base"));

        int result = new CommandRunner(
                ModuleLayer.boot(),
                tools,
                ToolCatalog.load(ModuleLayer.boot()),
                () -> {
                    throw new AssertionError("install should not use a temporary directory");
                },
                (arguments, in, out, err) -> {
                    throw new AssertionError("install should not launch java");
                },
                installer)
                .run(commandLine, new ByteArrayInputStream(new byte[0]), new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, result);
        assertEquals(
                List.of("--add-requires", "com.example.foo@1.2.3", "--resolve-options", optionList(COMPLETE_LAUNCH_OPTIONS), "--validate-runtime-access",
                        "--no-compile-diagnostics"),
                jigArguments);
        assertTrue(Files.isRegularFile(commands.resolve("foo")));
        assertTrue(Files.isRegularFile(commands.resolve("foo.current")));
    }

    @Test
    void installCanMaterializeStaticApplicationRequirements() throws Exception {
        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"install", "--include-static", "com.example.foo@1.2.3"});
        Path application = TestModules.writeJarWithStatic(temporaryDirectory.resolve("com.example.foo-1.2.3.jar"), "com.example.foo", "com.example.optional");
        Path optional = TestModules.writeJar(temporaryDirectory.resolve("com.example.optional-1.0.jar"), "com.example.optional");
        Path store = temporaryDirectory.resolve("store");
        var resolutions = new ArrayList<String>();
        ToolServices tools = ToolServices.of(tool(
                "jig",
                (output, arguments) -> {
                    String options = arguments.get(arguments.indexOf("--resolve-options") + 1);
                    resolutions.add(options);
                    assertFalse(arguments.contains("--compile-time"));
                    output.print("--module-path\n"
                            + application
                            + System.getProperty("path.separator")
                            + optional
                            + "\n--module\ncom.example.foo/com.example.Main\n");
                    return 0;
                }),
                tool("jlink",
                        (output, arguments) -> {
                            Path image = Path.of(arguments.get(arguments.indexOf("--output") + 1));
                            Files.createDirectories(image.resolve("bin"));
                            return 0;
                        }));
        var installer = new Installer(
                tools,
                new InstallationDirectories(store, temporaryDirectory.resolve("commands")),
                false,
                (name, target, arguments, output, in, out, err) -> {
                    Path classes = Files.createDirectories(output.resolve("classes"));
                    Files.writeString(classes.resolve("module-info.class"), "command");
                    return new Generated("com.example.foo.launcher", classes, Optional.of("com.example"));
                },
                destination -> {
                    Files.createDirectories(destination.getParent());
                    Files.writeString(destination, "native");
                },
                (target, arguments, includeStatic) -> Set.of("com.netflix.tools.launcher", "java.base"));

        int result = new CommandRunner(
                ModuleLayer.boot(),
                tools,
                ToolCatalog.load(ModuleLayer.boot()),
                () -> {
                    throw new AssertionError("install should not use temporary output");
                },
                (arguments, in, out, err) -> {
                    throw new AssertionError("install should not launch java");
                },
                installer)
                .run(commandLine, new ByteArrayInputStream(new byte[0]), new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, result);
        assertEquals(List.of(optionList(COMPLETE_LAUNCH_OPTIONS)), resolutions);
        assertEquals(-1L, Files.mismatch(optional, store.resolve("com.example.foo@1.2.3/app/modules/com.example.optional-1.0.jar")));
    }

    @Test
    void installUsesTheSourceApplicationMaterializedByJig() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("src/com.example.zapp"));
        Files.writeString(source.resolve("module-info.java"),
                """
                /** @mainClass com.example.Main */
                module com.example.zapp {
                    requires static org.junit.platform.engine;
                }
                """);
        Path library = Files.createDirectories(temporaryDirectory.resolve("src/com.example.library"));
        Files.writeString(library.resolve("module-info.java"), "module com.example.library {}\n");
        Path engine = TestModules.writeJar(temporaryDirectory.resolve("junit-platform-engine.jar"), "org.junit.platform.engine");
        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"install"});
        TemporaryDirectory workspace = TemporaryDirectory.unmanaged(temporaryDirectory.resolve("shared"));
        Path store = temporaryDirectory.resolve("store");
        Path commands = temporaryDirectory.resolve("commands");
        var installedArguments = new ArrayList<String>();
        var resolutions = new ArrayList<String>();
        ToolServices tools = ToolServices.of(tool(
                "jig",
                (output, arguments) -> {
                    String options = arguments.get(arguments.indexOf("--resolve-options") + 1);
                    resolutions.add(options);
                    Path application = Files.createDirectories(workspace.modules().resolve("com.example.zapp"));
                    TestModules.writeModuleInfo(application, "com.example.zapp", Set.of("org.junit.platform.engine"));
                    Path packageDirectory = Files.createDirectories(application.resolve("com/example"));
                    Files.writeString(packageDirectory.resolve("Main.class"), "application");
                    Files.writeString(packageDirectory.resolve("MainTest.class"), "test");
                    Path testPackage = Files.createDirectories(packageDirectory.resolve("test"));
                    Files.writeString(testPackage.resolve("Fixture.class"), "fixture");
                    Files.writeString(testPackage.resolve("fixture.properties"), "fixture");
                    output.print("--module-path\n" + workspace.modules());
                    if (options.equals(optionList(COMPILE_OPTIONS))) {
                        output.print(File.pathSeparator + engine);
                    }
                    output.print("\n--add-modules\ncom.example.zapp\n");
                    if (options.equals(optionList(COMPLETE_LAUNCH_OPTIONS))) {
                        output.print("--module\ncom.example.zapp/com.example.Main\n");
                    }
                    return 0;
                }),
                tool(
                        "jlink",
                        (output, arguments) -> {
                            Path image = Path.of(arguments.get(arguments.indexOf("--output") + 1));
                            Files.createDirectories(image.resolve("bin"));
                            Files.createDirectories(image.resolve("lib"));
                            Files.writeString(image.resolve("bin/java"), "java");
                            Files.writeString(image.resolve("lib/modules"), "modules");
                            return 0;
                        }));
        var installer = new Installer(
                tools,
                new InstallationDirectories(store, commands),
                false,
                (name, target, arguments, output, in, out, err) -> {
                    installedArguments.addAll(arguments);
                    Path classes = Files.createDirectories(output.resolve("classes"));
                    Files.writeString(classes.resolve("module-info.class"), "command");
                    return new Generated(InstalledCommandModule.moduleName(target.moduleName()), classes, Optional.empty());
                },
                destination -> {
                    Files.createDirectories(destination.getParent());
                    Files.writeString(destination, "native");
                },
                (target, arguments, includeStatic) -> Set.of("com.netflix.tools.launcher", "java.base"));

        int result = new CommandRunner(
                ModuleLayer.boot(),
                tools,
                ToolCatalog.load(ModuleLayer.boot()),
                () -> workspace,
                (arguments, in, out, err) -> {
                    throw new AssertionError("install should not launch java");
                },
                installer)
                .run(commandLine, new ByteArrayInputStream(new byte[0]), new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, result);
        assertEquals(List.of(optionList(COMPLETE_LAUNCH_OPTIONS), optionList(COMPILE_OPTIONS)),
                resolutions);
        assertTrue(installedArguments.contains("com.example.zapp"));
        assertFalse(installedArguments.contains("com.example.library,com.example.zapp"));
        Path installedModule = store.resolve("com.example.zapp/app/modules/com.example.zapp");
        assertEquals("application", Files.readString(installedModule.resolve("com/example/Main.class")));
        assertFalse(Files.exists(installedModule.resolve("com/example/MainTest.class")));
        assertFalse(Files.exists(installedModule.resolve("com/example/test/Fixture.class")));
        assertFalse(Files.exists(installedModule.resolve("com/example/test/fixture.properties")));
        assertTrue(Files.isRegularFile(commands.resolve("zapp")));
        assertTrue(Files.isRegularFile(commands.resolve("zapp.current")));
    }

    private static Path compilationOutput(List<String> arguments) {
        int option = arguments.lastIndexOf("-d");
        if (option < 0 || option + 1 >= arguments.size()) {
            throw new IllegalArgumentException("No compilation output in " + arguments);
        }
        return Path.of(arguments.get(option + 1));
    }

    private String runRequireUpdate(String current, String option, List<String> versions) throws Exception {
        var module = Files.createDirectories(temporaryDirectory.resolve("src/com.example.app"));
        var descriptor = Files.writeString(module.resolve("module-info.java"),
                """
                        module com.example.app {
                            requires org.example.library; // @%s
                        }
                        """
                        .formatted(current));
        Files.writeString(module.resolve("module-info.hash"), "existing\n");
        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"require", option, "org.example.library"});
        var tools = ToolServices.of(tool("jig", (output, arguments) -> {
            if (arguments.contains("--list-module-versions")) {
                versions.forEach(output::println);
            } else {
                var stagedModule = moduleSource(arguments, "com.example.app");
                Files.writeString(stagedModule.resolve("module-info.hash"), "updated\n");
            }
            return 0;
        }));

        var result = new CommandRunner(ModuleLayer.boot(), tools, ToolCatalog.load(ModuleLayer.boot()), () -> null)
                .run(commandLine, InputStream.nullInputStream(), new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, result);
        return Files.readString(descriptor);
    }

    private static Path moduleSource(List<String> arguments, String module) {
        for (int i = 0; i < arguments.size(); i++) {
            if (!arguments.get(i).equals("--module-source-path") || ++i >= arguments.size()) {
                continue;
            }
            var value = arguments.get(i);
            if (value.startsWith(module + "=")) {
                return Path.of(value.substring(module.length() + 1));
            }
        }
        throw new IllegalArgumentException("No source path for " + module);
    }

    private static boolean joinedPair(List<String> arguments, String option, String value) {
        for (int i = 0; i + 1 < arguments.size(); i++) {
            if (arguments.get(i).equals(option) && arguments.get(i + 1).equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> optionValues(List<String> arguments, String shortOption, String longOption) {
        var values = new ArrayList<String>();
        for (int i = 0; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            if ((argument.equals(shortOption) || argument.equals(longOption)) && i + 1 < arguments.size()) {
                values.add(arguments.get(++i));
            } else if (argument.startsWith(longOption + "=")) {
                values.add(argument.substring(longOption.length() + 1));
            }
        }
        return List.copyOf(values);
    }

    private static String optionList(Set<String> options) {
        return options.stream()
                .sorted()
                .collect(Collectors.joining(","));
    }

    private ToolProvider fakeJig(Set<String> options, String argumentFileContents) {
        return tool(
                "jig",
                (output, arguments) -> {
                    int option = arguments.indexOf("--resolve-options");
                    assertTrue(option >= 0);
                    String optionList = options.equals(Set.of("compile")) ? optionList(SOURCE_OPTIONS) : options.stream()
                            .sorted()
                            .collect(Collectors.joining(","));
                    assertEquals(optionList, arguments.get(option + 1));
                    assertFalse(arguments.contains("--compile-time"));
                    output.print(argumentFileContents);
                    return 0;
                });
    }

    private ToolProvider tool(String name, Operation operation) {
        return new ToolProvider() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public int run(PrintWriter out, PrintWriter err, String... arguments) {
                try {
                    return operation.run(out, List.of(arguments));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }

    @FunctionalInterface
    private interface Operation {
        int run(PrintWriter output, List<String> arguments) throws Exception;
    }
}
