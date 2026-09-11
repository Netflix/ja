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
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.lang.classfile.Annotation;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleParameterAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.spi.ToolProvider;
import javax.tools.OptionChecker;

import com.netflix.module.ModuleRuntimeAccess;
import com.netflix.module.ModuleRuntimeAccessOptions;
import com.netflix.tools.ja.BuiltinCommand;
import com.netflix.tools.ja.Command.Builtin;
import com.netflix.tools.ja.Command.Tool;
import com.netflix.tools.ja.JUnitTestSummary;
import com.netflix.tools.ja.JaInvocation;
import com.netflix.tools.ja.ResolvedToolArguments;
import com.netflix.tools.ja.RuntimeImageHash;
import com.netflix.tools.ja.TestResultStore;
import com.netflix.tools.ja.ToolCatalog;
import com.netflix.tools.ja.ToolDefinition;
import com.netflix.tools.ja.ToolDefinition.Launch;
import com.netflix.tools.ja.ToolRunner;
import com.netflix.tools.ja.ToolServices;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRunnerTest {
    private record IncrementalTestState(TestResultStore results, RuntimeImageHash runtimeImage) {}

    private record IncrementalTestFixture(
            String moduleName,
            Path directory,
            Path module,
            Path classFile,
            Path resource,
            String property,
            MethodTypeDesc testMethod,
            ToolRunner runner,
            JaInvocation commandLine,
            ResolvedToolArguments resolved)
            implements AutoCloseable {
        private static IncrementalTestFixture create(Path directory, String property) throws IOException {
            return create(directory, property, MethodTypeDesc.of(ClassDesc.ofDescriptor("V")));
        }

        private static IncrementalTestFixture create(Path directory, String property, MethodTypeDesc testMethod) throws IOException {
            var moduleName = "example.incremental.tests";
            var modules = Files.createDirectories(directory.resolve("modules"));
            var module = Files.createDirectories(modules.resolve(moduleName));
            TestModules.writeModuleInfo(module, moduleName, "org.junit.jupiter.api");
            var classFile = module.resolve("example/IncrementalTest.class");
            Files.createDirectories(classFile.getParent());
            writeIncrementalTest(classFile, ClassDesc.of("example.IncrementalTest"), testMethod, property, false,
                    false);
            var resource = module.resolve("example/input.txt");
            Files.writeString(resource, "one");
            return new IncrementalTestFixture(
                    moduleName,
                    directory,
                    module,
                    classFile,
                    resource,
                    property,
                    testMethod,
                    incrementalTestRunner(directory),
                    incrementalTestCommand(moduleName),
                    incrementalTestArguments(modules, moduleName));
        }

        private void prime() throws IOException {
            expectExecuted("initial");
        }

        private void expectExecuted(String marker) throws IOException {
            System.setProperty(property, marker);
            assertEquals(0, run(System.out));
            assertEquals("executed", System.getProperty(property));
        }

        private void expectCached(String marker) throws IOException {
            System.setProperty(property, marker);
            var output = new ByteArrayOutputStream();
            assertEquals(0, run(new PrintStream(output)));
            assertEquals(marker, System.getProperty(property));
            assertTrue(output.toString().contains("Tests:      1 found, 1 cached"),
                    output.toString());
        }

        private int run(PrintStream out) throws IOException {
            return runner.run(commandLine, List.of(), resolved, InputStream.nullInputStream(),
                    out, System.err);
        }

        private void writeTest(boolean changedHelper, boolean changedUnrelated) throws IOException {
            writeIncrementalTest(classFile, ClassDesc.of("example.IncrementalTest"), testMethod, property, changedHelper,
                    changedUnrelated);
        }

        @Override
        public void close() {
            System.clearProperty(property);
        }
    }

    @Test
    void passesResolvedArgumentsAndDefaultsToAToolProvider() throws Exception {
        var runWith = new ArrayList<String>();
        var tools = ToolServices.of(tool("jshell", runWith));
        var definition = new ToolDefinition(
                "jshell",
                Launch.PROVIDER,
                Optional.empty(),
                Optional.empty(),
                "jshell",
                Optional.of("1"),
                Set.of("module-path", "add-modules"),
                List.of("--default"));
        var runner = new ToolRunner(testLayer(), tools, new ToolCatalog(List.of(definition)),
                (arguments, in, out, err) -> {
                    throw new AssertionError("Java must not be launched");
                });
        var resolved = new ResolvedToolArguments(
                List.of("--module-path", "modules", "--add-modules", "com.example.app"),
                Set.of(ModuleDescriptor.newModule("com.example.app").build()),
                Map.of());

        int result = runner.run(toolCommandLine("jshell", List.of("explicit")), List.of(), resolved,
                InputStream.nullInputStream(), System.out, System.err);

        assertEquals(0, result);
        assertEquals(List.of("--module-path", "modules", "--add-modules", "com.example.app", "--default", "explicit"), runWith);
    }

    @Test
    void launchesADirectProviderWithItsDeclaredRuntimeAccess(@TempDir Path directory) throws Exception {
        var moduleName = "com.example.runtime.tool";
        var sources = directory.resolve("src");
        var source = Files.createDirectories(sources.resolve(moduleName));
        Files.writeString(source.resolve("module-info.java"),
                """
                module com.example.runtime.tool {
                    provides java.util.spi.ToolProvider with com.example.Probe;
                }
                """);
        var packageDirectory = Files.createDirectories(source.resolve("com/example"));
        Files.writeString(packageDirectory.resolve("Probe.java"),
                """
                package com.example;
                public final class Probe implements java.util.spi.ToolProvider {
                    public String name() { return "runtime-probe"; }
                    public int run(java.io.PrintWriter out, java.io.PrintWriter err,
                                   String... arguments) {
                        throw new AssertionError("provider must run in a Java process");
                    }
                }
                """);
        var modules = directory.resolve("modules");
        assertEquals(
                0,
                ToolProvider.findFirst("javac")
                        .orElseThrow()
                        .run(System.out, System.err, "--module-source-path", sources.toString(), "-d",
                                modules.toString(), "--module", moduleName, "-proc:none"));
        var moduleInfo = modules.resolve(moduleName).resolve("module-info.class");
        var access = ModuleRuntimeAccessOptions.newBuilder()
                .addExports("jdk.compiler", "com.sun.tools.javac.api", moduleName)
                .build();
        Files.write(moduleInfo, ModuleRuntimeAccess.write(Files.readAllBytes(moduleInfo), access));

        var finder = ModuleFinder.of(modules);
        var configuration = Configuration.resolve(finder, List.of(testLayer().configuration()), ModuleFinder.of(),
                Set.of(moduleName));
        var controller = ModuleLayer.defineModulesWithOneLoader(configuration, List.of(testLayer()), ClassLoader.getSystemClassLoader());
        var provider = ServiceLoader.load(controller.layer(), ToolProvider.class).stream()
                .filter(candidate -> candidate.type()
                        .getModule()
                        .getName()
                        .equals(moduleName))
                .map(ServiceLoader.Provider::get)
                .findFirst()
                .orElseThrow();
        var jigArguments = new ArrayList<String>();
        var jig = new ToolProvider() {
            @Override
            public String name() {
                return "jig";
            }

            @Override
            public int run(PrintWriter out, PrintWriter err, String... arguments) {
                jigArguments.addAll(List.of(arguments));
                out.println("--add-exports");
                out.println("jdk.compiler/com.sun.tools.javac.api=" + moduleName);
                out.println("--add-modules");
                out.println(moduleName);
                return 0;
            }
        };
        var tools = ToolServices.of(jig, provider);
        var definition = new ToolDefinition(
                "runtime-probe",
                Launch.PROVIDER,
                Optional.empty(),
                Optional.of(moduleName),
                "runtime-probe",
                Optional.of("1"),
                Set.of(),
                List.of());
        var launchedWith = new ArrayList<String>();
        var runner = new ToolRunner(
                controller.layer(),
                tools,
                new ToolCatalog(List.of(definition)),
                (arguments, in, out, err) -> {
                    launchedWith.addAll(arguments);
                    return 23;
                });

        int result = runner.run(
                toolCommandLine("runtime-probe", List.of("explicit")),
                List.of(),
                new ResolvedToolArguments(List.of(), Set.of(), Map.of()),
                InputStream.nullInputStream(),
                System.out,
                System.err);

        assertEquals(23, result);
        assertTrue(joinedPair(jigArguments, "--add-modules", moduleName));
        assertTrue(jigArguments.contains("--validate-runtime-access"));
        assertEquals(
                List.of("--add-exports", "jdk.compiler/com.sun.tools.javac.api=" + moduleName, "--add-modules", moduleName, "--module",
                        "com.netflix.tools.launcher/com.netflix.tools.launcher.ToolLauncher", "runtime-probe", "explicit"),
                launchedWith);
    }

    @Test
    void combinesTheProviderOptionCheckerWithTheDeclaredContract() throws Exception {
        var runWith = new ArrayList<String>();
        var tools = ToolServices.of(checkingTool("probe", runWith,
                option -> option.equals("--enable-preview") || option.equals("--verbose")
                        ? 0
                        : -1));
        var definition = new ToolDefinition(
                "probe",
                Launch.PROVIDER,
                Optional.empty(),
                Optional.empty(),
                "probe",
                Optional.of("1"),
                Set.of("module-path", "add-modules", "enable-preview"),
                List.of());
        var runner = new ToolRunner(testLayer(), tools, new ToolCatalog(List.of(definition)),
                (arguments, in, out, err) -> 0);
        var resolved = new ResolvedToolArguments(
                List.of("--module-path", "modules", "--add-modules", "com.example.app", "--enable-preview"),
                Set.of(ModuleDescriptor.newModule("com.example.app").build()),
                Map.of());

        int result = runner.run(verboseToolCommandLine("probe", List.of()), List.of(), resolved,
                InputStream.nullInputStream(), System.out, System.err);

        assertEquals(0, result);
        assertEquals(List.of("--module-path", "modules", "--add-modules", "com.example.app", "--enable-preview", "--verbose"), runWith);
    }

    @Test
    void passesVerboseToAJavaStyleTool() throws Exception {
        var tools = ToolServices.of(tool("jig", new ArrayList<>()));
        var definition = ToolDefinition.read("source-test", new ByteArrayInputStream("launch=java\nmodule=com.example.test\nversion=1\noptions=verbose\n".getBytes(StandardCharsets.UTF_8)));
        var launchedWith = new ArrayList<String>();
        var runner = new ToolRunner(
                testLayer(),
                tools,
                new ToolCatalog(List.of(definition)),
                (arguments, in, out, err) -> {
                    launchedWith.addAll(arguments);
                    return 0;
                });
        var commandLine = new JaInvocation(
                Path.of("").toAbsolutePath(),
                true,
                new Tool("source-test"),
                Optional.empty(),
                List.of(),
                List.of(),
                List.of());

        var result = runner.run(
                commandLine,
                List.of(),
                new ResolvedToolArguments(List.of(), Set.of(), Map.of()),
                InputStream.nullInputStream(),
                System.out,
                System.err);

        assertEquals(0, result);
        assertTrue(launchedWith.contains("--verbose"));
    }

    @Test
    void resolvesTheJUnitLauncherFromTheActivatedEngineVersion(@TempDir Path directory) throws Exception {
        var moduleName = "example.launcher.tests";
        var modules = Files.createDirectories(directory.resolve("modules"));
        var module = Files.createDirectories(modules.resolve(moduleName));
        TestModules.writeModuleInfo(module, moduleName, "org.junit.jupiter.api");
        var property = getClass().getName() + ".launcher";
        var classFile = module.resolve("example/IncrementalTest.class");
        Files.createDirectories(classFile.getParent());
        writeIncrementalTest(classFile, ClassDesc.of("example.IncrementalTest"), MethodTypeDesc.of(ClassDesc.ofDescriptor("V")), property,
                false, false);
        var resolvedWith = new ArrayList<String>();
        var launchedWith = new ArrayList<String>();
        var definition = new ToolDefinition(
                "junit",
                Launch.JAVA,
                Optional.of("org.junit.platform.engine"),
                Optional.of("org.junit.platform.console"),
                "junit",
                Optional.empty(),
                Set.of("module-path", "add-modules"),
                List.of("execute"));
        var testState = incrementalTestState(directory);
        var runner = new ToolRunner(
                testLayer(),
                ToolServices.of(tool("jig", resolvedWith)),
                new ToolCatalog(List.of(definition)),
                (arguments, in, out, err) -> {
                    launchedWith.addAll(arguments);
                    return 0;
                },
                testState.results(),
                testState.runtimeImage());
        var engine = ModuleDescriptor.newModule("org.junit.platform.engine")
                .version("6.1.3")
                .build();
        var root = ModuleFinder.of(modules)
                .find(moduleName)
                .orElseThrow()
                .descriptor();
        var commandLine = incrementalTestCommand(moduleName);

        try {
            assertEquals(
                    0,
                    runner.run(
                            commandLine,
                            List.of(),
                            new ResolvedToolArguments(List.of("--module-path", modules.toString()), Set.of(engine, root), Map.of()),
                            InputStream.nullInputStream(),
                            System.out,
                            System.err));

            assertTrue(joinedPair(resolvedWith, "--add-requires", "org.junit.platform.console@6.1.3"));
            assertTrue(launchedWith.isEmpty());
            assertEquals("executed", System.getProperty(property));
        } finally {
            System.clearProperty(property);
        }
    }

    @Test
    void resolvesTheJUnitLauncherOnceWhenIncrementalExecutionFallsBack() throws Exception {
        var resolvedWith = new ArrayList<String>();
        var definition = new ToolDefinition(
                "junit",
                Launch.JAVA,
                Optional.of("org.junit.platform.engine"),
                Optional.of("org.junit.platform.console"),
                "junit",
                Optional.empty(),
                Set.of("module-path", "add-modules"),
                List.of("execute"));
        var runner = new ToolRunner(testLayer(), ToolServices.of(tool("jig", resolvedWith)), new ToolCatalog(List.of(definition)),
                (arguments, in, out, err) -> 0);
        var engine = ModuleDescriptor.newModule("org.junit.platform.engine")
                .version("6.1.3")
                .build();
        var commandLine = new JaInvocation(
                Path.of("").toAbsolutePath(),
                new Builtin(BuiltinCommand.TEST),
                Optional.empty(),
                List.of(),
                List.of(),
                List.of("--tag", "none"));

        assertEquals(
                0,
                runner.run(
                        commandLine,
                        List.of(),
                        new ResolvedToolArguments(List.of(), Set.of(engine), Map.of()),
                        InputStream.nullInputStream(),
                        System.out,
                        System.err));

        assertEquals(1, joinedPairCount(resolvedWith, "--add-requires", "org.junit.platform.console@6.1.3"));
    }

    @Test
    void passesResolvedJUnitRuntimeArgumentsToTestExecution() throws Exception {
        var resolvedWith = new ArrayList<String>();
        var launchedWith = new ArrayList<String>();
        var definition = new ToolDefinition(
                "junit",
                Launch.JAVA,
                Optional.of("org.junit.platform.engine"),
                Optional.of("org.junit.platform.console"),
                "junit",
                Optional.empty(),
                Set.of("module-path", "add-modules"),
                List.of("execute"));
        var runner = new ToolRunner(
                testLayer(),
                ToolServices.of(tool("jig", resolvedWith, List.of("--enable-preview"))),
                new ToolCatalog(List.of(definition)),
                (arguments, in, out, err) -> {
                    launchedWith.addAll(arguments);
                    return 0;
                });
        var engine = ModuleDescriptor.newModule("org.junit.platform.engine")
                .version("6.1.3")
                .build();
        var commandLine = new JaInvocation(
                Path.of("").toAbsolutePath(),
                new Builtin(BuiltinCommand.TEST),
                Optional.empty(),
                List.of(),
                List.of(),
                List.of());

        assertEquals(
                0,
                runner.run(
                        commandLine,
                        List.of(),
                        new ResolvedToolArguments(List.of(), Set.of(engine), Map.of()),
                        InputStream.nullInputStream(),
                        System.out,
                        System.err));

        assertTrue(launchedWith.contains("--enable-preview"));
        assertEquals(1, joinedPairCount(resolvedWith, "--add-requires", "org.junit.platform.console@6.1.3"));
    }

    @Test
    void warnsWhenJavaBaseRuntimeAccessPreventsTestCaching(@TempDir Path directory) throws Exception {
        var moduleName = "example.runtime.access.tests";
        var modules = Files.createDirectories(directory.resolve("modules"));
        var module = Files.createDirectories(modules.resolve(moduleName));
        TestModules.writeModuleInfo(module, moduleName, "org.junit.jupiter.api");
        var property = getClass().getName() + ".runtimeAccess";
        var classFile = module.resolve("example/RuntimeAccessTest.class");
        Files.createDirectories(classFile.getParent());
        writeIncrementalTest(classFile, ClassDesc.of("example.RuntimeAccessTest"), MethodTypeDesc.of(ClassDesc.ofDescriptor("V")), property,
                false, false);
        var runtimeAccess = "java.base/java.lang=" + moduleName;
        var resolvedWith = new ArrayList<String>();
        var launchedWith = new ArrayList<String>();
        var requireRuntimeAccess = new AtomicBoolean();
        var jig = new ToolProvider() {
            @Override
            public String name() {
                return "jig";
            }

            @Override
            public int run(PrintWriter out, PrintWriter err, String... arguments) {
                resolvedWith.addAll(List.of(arguments));
                if (requireRuntimeAccess.get()) {
                    out.println("--add-opens");
                    out.println(runtimeAccess);
                }
                return 0;
            }
        };
        var definition = new ToolDefinition(
                "junit",
                Launch.JAVA,
                Optional.of("org.junit.platform.engine"),
                Optional.of("org.junit.platform.console"),
                "junit",
                Optional.empty(),
                Set.of("module-path", "add-modules"),
                List.of("execute"));
        var testState = incrementalTestState(directory);
        var runner = new ToolRunner(
                testLayer(),
                ToolServices.of(jig),
                new ToolCatalog(List.of(definition)),
                (arguments, in, out, err) -> {
                    launchedWith.addAll(arguments);
                    return 0;
                },
                testState.results(),
                testState.runtimeImage());
        var engine = ModuleDescriptor.newModule("org.junit.platform.engine")
                .version("6.1.3")
                .build();
        var root = ModuleFinder.of(modules)
                .find(moduleName)
                .orElseThrow()
                .descriptor();
        var commandLine = incrementalTestCommand(moduleName);
        var resolved = new ResolvedToolArguments(List.of("--module-path", modules.toString()),
                Set.of(engine, root), Map.of());

        try {
            assertEquals(
                    0,
                    runner.run(commandLine, List.of(), resolved, InputStream.nullInputStream(),
                            System.out, System.err));
            assertEquals("executed", System.getProperty(property));

            requireRuntimeAccess.set(true);
            var error = new ByteArrayOutputStream();
            assertEquals(
                    0,
                    runner.run(commandLine, List.of(), resolved, InputStream.nullInputStream(),
                            System.out, new PrintStream(error)));
            assertEquals(
                    "ja: warning: test caching is unavailable because the required access from java.base can only be applied in a separate JVM; running all tests"
                            + System.lineSeparator(),
                    error.toString());
        } finally {
            System.clearProperty(property);
        }

        assertTrue(joinedPair(launchedWith, "--add-opens", runtimeAccess));
        assertFalse(launchedWith.contains("--exclude-methodname"));
        assertEquals(2, joinedPairCount(resolvedWith, "--add-requires", "org.junit.platform.console@6.1.3"));
    }

    @Test
    void cachesTestsWhenRuntimeAccessRequiresAJdkModuleInTheTestLayer(@TempDir Path directory) throws Exception {
        var moduleName = "example.runtime.access.tests";
        var modules = Files.createDirectories(directory.resolve("modules"));
        var module = Files.createDirectories(modules.resolve(moduleName));
        TestModules.writeModuleInfo(module, moduleName, "org.junit.jupiter.api");
        var property = getClass().getName() + ".jdkRuntimeAccess";
        var classFile = module.resolve("example/RuntimeAccessTest.class");
        Files.createDirectories(classFile.getParent());
        writeIncrementalTest(classFile, ClassDesc.of("example.RuntimeAccessTest"), MethodTypeDesc.of(ClassDesc.ofDescriptor("V")), property,
                false, false);
        var runtimeAccess = "jdk.javadoc/jdk.javadoc.internal.tool=" + moduleName;
        var resolvedWith = new ArrayList<String>();
        var launchedWith = new ArrayList<String>();
        var jig = tool("jig", resolvedWith, List.of("--add-exports", runtimeAccess));
        var definition = new ToolDefinition(
                "junit",
                Launch.JAVA,
                Optional.of("org.junit.platform.engine"),
                Optional.of("org.junit.platform.console"),
                "junit",
                Optional.empty(),
                Set.of("module-path", "add-modules"),
                List.of("execute"));
        var testState = incrementalTestState(directory);
        var runner = new ToolRunner(
                testLayer(),
                ToolServices.of(jig),
                new ToolCatalog(List.of(definition)),
                (arguments, in, out, err) -> {
                    launchedWith.addAll(arguments);
                    return 0;
                },
                testState.results(),
                testState.runtimeImage());
        var engine = ModuleDescriptor.newModule("org.junit.platform.engine")
                .version("6.1.3")
                .build();
        var root = ModuleFinder.of(modules)
                .find(moduleName)
                .orElseThrow()
                .descriptor();
        var commandLine = incrementalTestCommand(moduleName);
        var resolved = new ResolvedToolArguments(List.of("--module-path", modules.toString()),
                Set.of(engine, root), Map.of());

        try {
            assertEquals(
                    0,
                    runner.run(commandLine, List.of(), resolved, InputStream.nullInputStream(),
                            System.out, System.err));
            assertEquals("executed", System.getProperty(property));

            System.setProperty(property, "cached");
            var output = new ByteArrayOutputStream();
            assertEquals(
                    0,
                    runner.run(commandLine, List.of(), resolved, InputStream.nullInputStream(),
                            new PrintStream(output), System.err));
            assertEquals("cached", System.getProperty(property));
            assertTrue(output.toString().contains("Tests:      1 found, 1 cached"));
        } finally {
            System.clearProperty(property);
        }

        assertTrue(launchedWith.isEmpty());
        assertEquals(2, joinedPairCount(resolvedWith, "--add-requires", "org.junit.platform.console@6.1.3"));
    }

    @Test
    void cachesTestsFromTheirObservedDirectoryModuleCode(@TempDir Path directory) throws Exception {
        try (var fixture = IncrementalTestFixture.create(directory, getClass().getName() + ".observed")) {
            fixture.prime();
            fixture.expectCached("cached");

            fixture.writeTest(false, true);
            fixture.expectCached("unrelated");
        }
    }

    @Test
    void cachesTestsWithInjectedMethodParameters(@TempDir Path directory) throws Exception {
        try (var fixture = IncrementalTestFixture.create(directory, getClass().getName() + ".parameter",
                MethodTypeDesc.of(ClassDesc.ofDescriptor("V"), ClassDesc.of("java.nio.file.Path")))) {
            fixture.prime();
            fixture.expectCached("cached");
        }
    }

    @Test
    void hashesObservedMethodsWithSourceClassHierarchy(@TempDir Path directory) throws Exception {
        var moduleName = "example.hierarchy.tests";
        var modules = Files.createDirectories(directory.resolve("modules"));
        var module = Files.createDirectories(modules.resolve(moduleName));
        TestModules.writeModuleInfo(module, moduleName, "org.junit.jupiter.api");
        var testType = ClassDesc.of("example.HierarchyTest");
        var peerType = ClassDesc.of("example.Peer");
        var voidMethod = MethodTypeDesc.of(ClassDesc.ofDescriptor("V"));
        var property = getClass().getName() + ".hierarchy";
        var hierarchy = ClassHierarchyResolver.of(Set.of(), Map.of(peerType, ClassDesc.of("java.lang.Object")))
                .orElse(ClassHierarchyResolver.defaultResolver());
        var classFile = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(hierarchy));
        var peer = classFile.build(peerType,
                builder -> builder.withMethodBody(
                        "<init>",
                        voidMethod,
                        ClassFile.ACC_PUBLIC,
                        code -> code.aload(0)
                                    .invokespecial(ClassDesc.of("java.lang.Object"), "<init>", voidMethod)
                                    .return_()));
        var test = classFile.build(testType,
                builder -> {
                    builder.withMethodBody(
                            "<init>",
                            voidMethod,
                            ClassFile.ACC_PUBLIC,
                            code -> code.aload(0)
                                        .invokespecial(ClassDesc.of("java.lang.Object"), "<init>", voidMethod)
                                        .return_());
                    builder.withMethod("test", voidMethod, ClassFile.ACC_PUBLIC,
                            method -> {
                                method.with(RuntimeVisibleAnnotationsAttribute.of(Annotation.of(ClassDesc.of("org.junit.jupiter.api.Test"))));
                                method.withCode(code -> {
                                    var other = code.newLabel();
                                    var merged = code.newLabel();
                                    code.iconst_1()
                                        .ifeq(other)
                                        .new_(peerType)
                                        .dup()
                                        .invokespecial(peerType, "<init>", voidMethod)
                                        .goto_(merged)
                                        .labelBinding(other)
                                        .ldc("value")
                                        .labelBinding(merged)
                                        .pop()
                                        .ldc(property)
                                        .ldc("executed")
                                        .invokestatic(ClassDesc.of("java.lang.System"), "setProperty",
                                                MethodTypeDesc.of(ClassDesc.of("java.lang.String"),
                                                        ClassDesc.of("java.lang.String"), ClassDesc.of("java.lang.String")))
                                        .pop()
                                        .return_();
                                });
                            });
                });
        var packageDirectory = Files.createDirectories(module.resolve("example"));
        Files.write(packageDirectory.resolve("Peer.class"), peer);
        Files.write(packageDirectory.resolve("HierarchyTest.class"), test);
        var runner = incrementalTestRunner(directory);

        try {
            assertEquals(0,
                    runner.run(incrementalTestCommand(moduleName), List.of(),
                            incrementalTestArguments(modules, moduleName), InputStream.nullInputStream(),
                            System.out, System.err));
            assertEquals("executed", System.getProperty(property));
        } finally {
            System.clearProperty(property);
        }
    }

    @Test
    void invalidatesAChangedDirectoryModuleResource(@TempDir Path directory) throws Exception {
        try (var fixture = IncrementalTestFixture.create(directory, getClass().getName() + ".resource")) {
            fixture.prime();
            Files.writeString(fixture.resource(), "two");
            fixture.expectExecuted("resource");
        }
    }

    @Test
    void invalidatesAChangedDirectoryModuleDescriptor(@TempDir Path directory) throws Exception {
        try (var fixture = IncrementalTestFixture.create(directory, getClass().getName() + ".descriptor")) {
            fixture.prime();
            TestModules.writeModuleInfo(fixture.module(), fixture.moduleName(), "org.junit.jupiter.api", "java.logging");
            fixture.expectExecuted("descriptor");
        }
    }

    @Test
    void invalidatesAChangedRuntimeImage(@TempDir Path directory) throws Exception {
        try (var fixture = IncrementalTestFixture.create(directory, getClass().getName() + ".runtime")) {
            fixture.prime();
            Files.writeString(fixture.directory()
                    .resolve("runtime/lib/modules"),
                    "changed runtime");
            fixture.expectExecuted("runtime");
        }
    }

    @Test
    void invalidatesChangedObservedHelperCode(@TempDir Path directory) throws Exception {
        try (var fixture = IncrementalTestFixture.create(directory, getClass().getName() + ".helper")) {
            fixture.prime();
            fixture.writeTest(true, false);
            fixture.expectExecuted("helper");
        }
    }

    @Test
    void invalidatesObservedDispatchWhenAReceiverAddsAnOverride(@TempDir Path directory) throws Exception {
        var moduleName = "example.dispatch.tests";
        var modules = Files.createDirectories(directory.resolve("modules"));
        var module = Files.createDirectories(modules.resolve(moduleName));
        TestModules.writeModuleInfo(module, moduleName, "org.junit.jupiter.api");
        var property = getClass().getName() + ".dispatch";
        writeDispatchTest(module, property, false);
        var runner = incrementalTestRunner(directory);
        var commandLine = incrementalTestCommand(moduleName);
        var resolved = incrementalTestArguments(modules, moduleName);

        try {
            assertEquals(
                    0,
                    runner.run(commandLine, List.of(), resolved, InputStream.nullInputStream(),
                            System.out, System.err));
            assertEquals("base", System.getProperty(property));

            System.setProperty(property, "cached");
            assertEquals(
                    0,
                    runner.run(commandLine, List.of(), resolved, InputStream.nullInputStream(),
                            System.out, System.err));
            assertEquals("cached", System.getProperty(property));

            writeDispatchTest(module, property, true);
            System.setProperty(property, "stale");
            assertEquals(
                    0,
                    runner.run(commandLine, List.of(), resolved, InputStream.nullInputStream(),
                            System.out, System.err));
            assertEquals("derived", System.getProperty(property));
        } finally {
            System.clearProperty(property);
        }
    }

    @Test
    void doesNotCacheTestsInPackagedRoots(@TempDir Path directory) throws Exception {
        var moduleName = "example.packaged.tests";
        var exploded = Files.createDirectories(directory.resolve("exploded"));
        TestModules.writeModuleInfo(exploded, moduleName, "org.junit.jupiter.api");
        var type = ClassDesc.of("example.PackagedTest");
        var voidMethod = MethodTypeDesc.of(ClassDesc.ofDescriptor("V"));
        var property = getClass().getName() + ".packaged";
        var classFile = exploded.resolve("example/PackagedTest.class");
        Files.createDirectories(classFile.getParent());
        writeIncrementalTest(classFile, type, voidMethod, property, false, false);
        var jar = directory.resolve(moduleName + ".jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar));
             var files = Files.walk(exploded)) {
            for (var file : files.filter(Files::isRegularFile)
                                 .sorted()
                                 .toList()) {
                output.putNextEntry(new JarEntry(exploded.relativize(file)
                        .toString()
                        .replace('\\', '/')));
                Files.copy(file, output);
                output.closeEntry();
            }
        }
        var definition = new ToolDefinition(
                "junit",
                Launch.PROVIDER,
                Optional.empty(),
                Optional.of("org.junit.platform.console"),
                "junit",
                Optional.of("6.1.3"),
                Set.of("module-path", "add-modules", "patch-module"),
                List.of("execute", "--details=summary", "--disable-banner", "--disable-ansi-colors"));
        var testState = incrementalTestState(directory);
        var runner = new ToolRunner(
                testLayer(),
                ToolServices.of(),
                new ToolCatalog(List.of(definition)),
                (arguments, in, out, err) -> {
                    throw new AssertionError("Java must not be launched");
                },
                testState.results(),
                testState.runtimeImage());
        var commandLine = new JaInvocation(
                Path.of("").toAbsolutePath(),
                new Builtin(BuiltinCommand.TEST),
                Optional.empty(),
                List.of(moduleName),
                List.of(),
                List.of());
        var resolved = new ResolvedToolArguments(
                List.of("--module-path", jar.toString()),
                Set.of(ModuleFinder.of(jar)
                        .find(moduleName)
                        .orElseThrow()
                        .descriptor()),
                Map.of());

        try {
            assertEquals(
                    0,
                    runner.run(commandLine, List.of(), resolved, InputStream.nullInputStream(),
                            System.out, System.err));
            assertEquals("executed", System.getProperty(property));

            System.setProperty(property, "again");
            assertEquals(
                    0,
                    runner.run(commandLine, List.of(), resolved, InputStream.nullInputStream(),
                            System.out, System.err));
            assertEquals("executed", System.getProperty(property));
        } finally {
            System.clearProperty(property);
        }
    }

    private static boolean joinedPair(List<String> arguments, String option, String value) {
        return joinedPairCount(arguments, option, value) > 0;
    }

    private static int joinedPairCount(List<String> arguments, String option, String value) {
        int count = 0;
        for (int i = 0; i + 1 < arguments.size(); i++) {
            if (arguments.get(i).equals(option) && arguments.get(i + 1).equals(value)) {
                count++;
            }
        }
        return count;
    }

    private static ModuleLayer testLayer() {
        return ToolRunnerTest.class.getModule().getLayer();
    }

    private static IncrementalTestState incrementalTestState(Path directory) throws IOException {
        var runtime = Files.createDirectories(directory.resolve("runtime"));
        var release = runtime.resolve("release");
        if (!Files.exists(release)) {
            Files.writeString(release, "JAVA_VERSION=\"test\"\n");
        }
        var library = Files.createDirectories(runtime.resolve("lib"));
        var modules = library.resolve("modules");
        if (!Files.exists(modules)) {
            Files.writeString(modules, "runtime");
        }
        var resultRoot = directory.resolve("results");
        return new IncrementalTestState(new TestResultStore(resultRoot), new RuntimeImageHash(runtime, resultRoot.resolve("runtime-images")));
    }

    private static ToolRunner incrementalTestRunner(Path directory) throws IOException {
        var definition = new ToolDefinition(
                "junit",
                Launch.PROVIDER,
                Optional.empty(),
                Optional.of("org.junit.platform.console"),
                "junit",
                Optional.of("6.1.3"),
                Set.of("module-path", "add-modules", "patch-module"),
                List.of("execute", "--details=summary", "--disable-banner", "--disable-ansi-colors"));
        var testState = incrementalTestState(directory);
        return new ToolRunner(
                testLayer(),
                ToolServices.of(),
                new ToolCatalog(List.of(definition)),
                (arguments, in, out, err) -> {
                    throw new AssertionError("Java must not be launched");
                },
                testState.results(),
                testState.runtimeImage());
    }

    private static JaInvocation incrementalTestCommand(String moduleName) {
        return new JaInvocation(
                Path.of("").toAbsolutePath(),
                new Builtin(BuiltinCommand.TEST),
                Optional.empty(),
                List.of(moduleName),
                List.of(),
                List.of());
    }

    private static ResolvedToolArguments incrementalTestArguments(Path modules, String moduleName) {
        return new ResolvedToolArguments(
                List.of("--module-path", modules.toString()),
                Set.of(ModuleFinder.of(modules)
                        .find(moduleName)
                        .orElseThrow()
                        .descriptor()),
                Map.of());
    }

    private static void writeDispatchTest(Path module, String property, boolean override) throws IOException {
        var voidMethod = MethodTypeDesc.of(ClassDesc.ofDescriptor("V"));
        var baseType = ClassDesc.of("example.DispatchBase");
        var derivedType = ClassDesc.of("example.DispatchDerived");
        var testType = ClassDesc.of("example.DispatchTest");
        var stringType = ClassDesc.of("java.lang.String");
        var setProperty = MethodTypeDesc.of(stringType, stringType, stringType);
        var base = ClassFile.of().build(baseType,
                builder -> {
                    builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
                    builder.withMethodBody(
                            "<init>",
                            voidMethod,
                            ClassFile.ACC_PUBLIC,
                            code -> code.aload(0)
                                        .invokespecial(ClassDesc.of("java.lang.Object"), "<init>", voidMethod)
                                        .return_());
                    builder.withMethodBody(
                            "action",
                            voidMethod,
                            ClassFile.ACC_PUBLIC,
                            code -> code.ldc(property)
                                        .ldc("base")
                                        .invokestatic(ClassDesc.of("java.lang.System"), "setProperty", setProperty)
                                        .pop()
                                        .return_());
                });
        var derived = ClassFile.of().build(derivedType,
                builder -> {
                    builder.withSuperclass(baseType);
                    builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
                    builder.withMethodBody(
                            "<init>",
                            voidMethod,
                            ClassFile.ACC_PUBLIC,
                            code -> code.aload(0)
                                        .invokespecial(baseType, "<init>", voidMethod)
                                        .return_());
                    if (override) {
                        builder.withMethodBody(
                                "action",
                                voidMethod,
                                ClassFile.ACC_PUBLIC,
                                code -> code.ldc(property)
                                            .ldc("derived")
                                            .invokestatic(ClassDesc.of("java.lang.System"), "setProperty", setProperty)
                                            .pop()
                                            .return_());
                    }
                });
        var test = ClassFile.of().build(testType,
                builder -> {
                    builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
                    builder.withMethodBody(
                            "<init>",
                            voidMethod,
                            ClassFile.ACC_PUBLIC,
                            code -> code.aload(0)
                                        .invokespecial(ClassDesc.of("java.lang.Object"), "<init>", voidMethod)
                                        .return_());
                    builder.withMethod("test", voidMethod, ClassFile.ACC_PUBLIC,
                            method -> {
                                method.with(RuntimeVisibleAnnotationsAttribute.of(Annotation.of(ClassDesc.of("org.junit.jupiter.api.Test"))));
                                method.withCode(
                                        code -> code.new_(derivedType)
                                                    .dup()
                                                    .invokespecial(derivedType, "<init>", voidMethod)
                                                    .invokevirtual(baseType, "action", voidMethod)
                                                    .return_());
                            });
                });
        var packageDirectory = Files.createDirectories(module.resolve("example"));
        Files.write(packageDirectory.resolve("DispatchBase.class"), base);
        Files.write(packageDirectory.resolve("DispatchDerived.class"), derived);
        Files.write(packageDirectory.resolve("DispatchTest.class"), test);
    }

    private static void writeIncrementalTest(Path path, ClassDesc type, MethodTypeDesc testMethod,
            String property, boolean changedHelper, boolean changedUnrelated)
            throws IOException {
        var voidMethod = MethodTypeDesc.of(ClassDesc.ofDescriptor("V"));
        Files.write(path,
                ClassFile.of().build(
                        type,
                        builder -> {
                            builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
                            builder.withMethodBody(
                                    "<init>",
                                    voidMethod,
                                    ClassFile.ACC_PUBLIC,
                                    code -> code.aload(0)
                                                .invokespecial(ClassDesc.of("java.lang.Object"), "<init>", voidMethod)
                                                .return_());
                            builder.withMethodBody("helper", voidMethod, ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC,
                                    code -> {
                                        if (changedHelper) {
                                            code.iconst_0().pop();
                                        }
                                        code.return_();
                                    });
                            builder.withMethodBody("unrelated", voidMethod, ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC,
                                    code -> {
                                        if (changedUnrelated) {
                                            code.iconst_0().pop();
                                        }
                                        code.return_();
                                    });
                            builder.withMethod("test", testMethod, ClassFile.ACC_PUBLIC,
                                    method -> {
                                        method.with(RuntimeVisibleAnnotationsAttribute.of(Annotation.of(ClassDesc.of("org.junit.jupiter.api.Test"))));
                                        if (testMethod.parameterCount() > 0) {
                                            method.with(RuntimeVisibleParameterAnnotationsAttribute.of(List.of(List.of(Annotation.of(ClassDesc.of("org.junit.jupiter.api.io.TempDir"))))));
                                        }
                                        method.withCode(
                                                code -> code.invokestatic(type, "helper", voidMethod)
                                                            .ldc(property)
                                                            .ldc("executed")
                                                            .invokestatic(ClassDesc.of("java.lang.System"), "setProperty",
                                                                    MethodTypeDesc.of(ClassDesc.of("java.lang.String"), ClassDesc.of("java.lang.String"), ClassDesc.of("java.lang.String")))
                                                            .pop()
                                                            .return_());
                                    });
                        }));
    }

    @Test
    void rejectsArgumentsOutsideTheOwnedTestInterface() throws Exception {
        var runWith = new ArrayList<String>();
        var tools = ToolServices.of(tool("junit", runWith));
        var definition = new ToolDefinition(
                "junit",
                Launch.PROVIDER,
                Optional.empty(),
                Optional.empty(),
                "junit",
                Optional.of("1"),
                Set.of("module-path", "add-modules"),
                List.of("--default"));
        var runner = new ToolRunner(testLayer(), tools, new ToolCatalog(List.of(definition)),
                (arguments, in, out, err) -> {
                    throw new AssertionError("Java must not be launched");
                });
        var commandLine = new JaInvocation(
                Path.of("").toAbsolutePath(),
                new Builtin(BuiltinCommand.TEST),
                Optional.empty(),
                List.of("com.example.app"),
                List.of(),
                List.of("--select-method", "com.example.AppTest#example()"));
        var resolved = new ResolvedToolArguments(
                List.of("--module-path", "modules"),
                Set.of(ModuleDescriptor.newModule("com.example.app").build()),
                Map.of());

        var unsupported = assertThrows(
                IllegalArgumentException.class,
                () -> runner.run(commandLine, List.of(), resolved, InputStream.nullInputStream(),
                        System.out, System.err));
        assertEquals("Unsupported test argument: --select-method", unsupported.getMessage());
        assertEquals(List.of(), runWith);

        var runAll = new JaInvocation(
                Path.of("").toAbsolutePath(),
                new Builtin(BuiltinCommand.TEST),
                Optional.empty(),
                List.of("com.example.app"),
                List.of(),
                List.of("--all", "--select-method", "com.example.AppTest#example()"));
        var failure = assertThrows(
                IllegalArgumentException.class,
                () -> runner.run(runAll, List.of(), resolved, InputStream.nullInputStream(),
                        System.out, System.err));
        assertEquals("Unsupported test argument: --select-method", failure.getMessage());
    }

    @Test
    void explicitTagFilterSelectsRootModulesAndBypassesCachePlanning() throws Exception {
        var runWith = new ArrayList<String>();
        var tools = ToolServices.of(tool("junit", runWith));
        var definition = new ToolDefinition(
                "junit",
                Launch.PROVIDER,
                Optional.empty(),
                Optional.empty(),
                "junit",
                Optional.of("1"),
                Set.of("module-path", "add-modules"),
                List.of("--default"));
        var runner = new ToolRunner(testLayer(), tools, new ToolCatalog(List.of(definition)),
                (arguments, in, out, err) -> {
                    throw new AssertionError("Java must not be launched");
                });
        var commandLine = new JaInvocation(
                Path.of("").toAbsolutePath(),
                new Builtin(BuiltinCommand.TEST),
                Optional.empty(),
                List.of("com.example.app", "com.example.other"),
                List.of(),
                List.of("-t", "fast", "--tag=unit"));
        var resolved = new ResolvedToolArguments(
                List.of("--module-path", "missing-modules"),
                Set.of(ModuleDescriptor.newModule("com.example.app").build(),
                        ModuleDescriptor.newModule("com.example.other").build()),
                Map.of());

        int result = runner.run(commandLine, List.of(), resolved, InputStream.nullInputStream(),
                System.out, System.err);

        assertEquals(0, result);
        assertEquals(
                List.of("--module-path", "missing-modules", "--default", "--select-module", "com.example.app", "--select-module",
                        "com.example.other", "--include-tag", "fast", "--include-tag=unit"),
                runWith);
    }

    @Test
    void explicitTestSelectorsFilterDiscoveredMethodsWithoutCachePlanning() throws Exception {
        var runWith = new ArrayList<String>();
        var tools = ToolServices.of(tool("junit", runWith));
        var definition = new ToolDefinition(
                "junit",
                Launch.PROVIDER,
                Optional.empty(),
                Optional.empty(),
                "junit",
                Optional.of("1"),
                Set.of("module-path", "add-modules"),
                List.of("--default"));
        var runner = new ToolRunner(testLayer(), tools, new ToolCatalog(List.of(definition)),
                (arguments, in, out, err) -> {
                    throw new AssertionError("Java must not be launched");
                });
        var commandLine = new JaInvocation(
                Path.of("").toAbsolutePath(),
                new Builtin(BuiltinCommand.TEST),
                Optional.empty(),
                List.of("com.example.app"),
                List.of(),
                List.of("com.example.AppTest", "com.example.AppTest.example"));
        var resolved = new ResolvedToolArguments(
                List.of("--module-path", "missing-modules"),
                Set.of(ModuleDescriptor.newModule("com.example.app").build()),
                Map.of());

        int result = runner.run(commandLine, List.of(), resolved, InputStream.nullInputStream(),
                System.out, System.err);

        assertEquals(0, result);
        assertEquals(
                List.of("--module-path", "missing-modules", "--default", "--select-module", "com.example.app", "--include-methodname",
                        "^\\Qcom\\E[.#]\\Qexample\\E[.#]\\QAppTest\\E(?:#.+)?$", "--include-methodname", "^\\Qcom\\E[.#]\\Qexample\\E[.#]\\QAppTest\\E[.#]\\Qexample\\E(?:#.+)?$", "--fail-if-no-tests"),
                runWith);
    }

    @Test
    void explicitBenchmarkSelectorsFilterJmhDiscovery() throws Exception {
        var runWith = new ArrayList<String>();
        var tools = ToolServices.of(tool("jmh", runWith));
        var definition = new ToolDefinition(
                "jmh",
                Launch.PROVIDER,
                Optional.empty(),
                Optional.empty(),
                "jmh",
                Optional.of("1"),
                Set.of("module-path", "add-modules"),
                List.of("--default"));
        var runner = new ToolRunner(testLayer(), tools, new ToolCatalog(List.of(definition)),
                (arguments, in, out, err) -> {
                    throw new AssertionError("Java must not be launched");
                });
        var commandLine = new JaInvocation(
                Path.of("").toAbsolutePath(),
                new Builtin(BuiltinCommand.BENCH),
                Optional.empty(),
                List.of("com.example.app"),
                List.of(),
                List.of("com.example.ExampleBenchmark", "com.example.ExampleBenchmark.example"));
        var resolved = new ResolvedToolArguments(
                List.of("--module-path", "modules", "--add-modules", "com.example.app"),
                Set.of(ModuleDescriptor.newModule("com.example.app").build()),
                Map.of());

        int result = runner.run(commandLine, List.of(), resolved, InputStream.nullInputStream(),
                System.out, System.err);

        assertEquals(0, result);
        assertEquals(
                List.of("--module-path", "modules", "--add-modules", "com.example.app", "--default", "^\\Qcom.example.ExampleBenchmark\\E(?:\\..+)?$",
                        "^\\Qcom.example.ExampleBenchmark.example\\E(?:\\..+)?$"),
                runWith);
    }

    @Test
    void listsTestSelectorsThroughJUnitDiscovery() throws Exception {
        var runWith = new ArrayList<String>();
        var tools = ToolServices.of(tool("junit", runWith));
        var definition = new ToolDefinition(
                "junit",
                Launch.PROVIDER,
                Optional.empty(),
                Optional.empty(),
                "junit",
                Optional.of("1"),
                Set.of("module-path", "add-modules"),
                List.of("execute", "--details=summary"));
        var runner = new ToolRunner(testLayer(), tools, new ToolCatalog(List.of(definition)),
                (arguments, in, out, err) -> {
                    throw new AssertionError("Java must not be launched");
                });
        var commandLine = new JaInvocation(
                Path.of("").toAbsolutePath(),
                new Builtin(BuiltinCommand.TEST),
                Optional.empty(),
                List.of("com.example.app"),
                List.of(),
                List.of("--ja-list-selectors"));
        var resolved = new ResolvedToolArguments(
                List.of("--module-path", "modules"),
                Set.of(ModuleDescriptor.newModule("com.example.app").build()),
                Map.of());

        assertEquals(
                0,
                runner.run(commandLine, List.of(), resolved, InputStream.nullInputStream(),
                        System.out, System.err));

        assertEquals(
                List.of("--module-path", "modules", "discover", "--details=flat", "--disable-banner", "--disable-ansi-colors",
                        "--select-module", "com.example.app"),
                runWith);
    }

    @Test
    void listsBenchmarkSelectorsThroughJmhDiscovery() throws Exception {
        var runWith = new ArrayList<String>();
        var tools = ToolServices.of(tool("jmh", runWith));
        var definition = new ToolDefinition(
                "jmh",
                Launch.PROVIDER,
                Optional.empty(),
                Optional.empty(),
                "jmh",
                Optional.of("1"),
                Set.of("module-path", "add-modules"),
                List.of("--default"));
        var runner = new ToolRunner(testLayer(), tools, new ToolCatalog(List.of(definition)),
                (arguments, in, out, err) -> {
                    throw new AssertionError("Java must not be launched");
                });
        var commandLine = new JaInvocation(
                Path.of("").toAbsolutePath(),
                new Builtin(BuiltinCommand.BENCH),
                Optional.empty(),
                List.of("com.example.app"),
                List.of(),
                List.of("--ja-list-selectors"));
        var resolved = new ResolvedToolArguments(
                List.of("--module-path", "modules"),
                Set.of(ModuleDescriptor.newModule("com.example.app").build()),
                Map.of());

        assertEquals(
                0,
                runner.run(commandLine, List.of(), resolved, InputStream.nullInputStream(),
                        System.out, System.err));

        assertEquals(List.of("--module-path", "modules", "--default", "-l"), runWith);
    }

    @Test
    void compactsSuccessfulStructuredJUnitSummary() throws Exception {
        var summary =
                """
                Test run finished after 42 ms
                [         2 containers found      ]
                [         0 containers skipped    ]
                [         2 containers started    ]
                [         0 containers aborted    ]
                [         2 containers successful ]
                [         0 containers failed     ]
                [         1 tests found           ]
                [         0 tests skipped         ]
                [         1 tests started         ]
                [         0 tests aborted         ]
                [         1 tests successful      ]
                [         0 tests failed          ]
                """;
        var tool = new ToolProvider() {
            @Override
            public String name() {
                return "junit";
            }

            @Override
            public int run(PrintWriter out, PrintWriter err, String... arguments) {
                out.print(summary);
                return 0;
            }
        };
        var definition = new ToolDefinition(
                "junit",
                Launch.PROVIDER,
                Optional.empty(),
                Optional.of("org.junit.platform.console"),
                "junit",
                Optional.of("1"),
                Set.of(),
                List.of());
        var runner = new ToolRunner(
                testLayer(),
                ToolServices.of(tool),
                new ToolCatalog(List.of(definition)),
                (arguments, in, out, err) -> {
                    throw new AssertionError("Java must not be launched");
                });
        var output = new ByteArrayOutputStream();

        int result;
        try (var out = new PrintStream(output)) {
            result = runner.run(
                    commandLine(BuiltinCommand.TEST, List.of("-t", "fast")),
                    List.of(),
                    new ResolvedToolArguments(List.of(), Set.of(), Map.of()),
                    InputStream.nullInputStream(),
                    out,
                    System.err);
        }

        assertEquals(0, result);
        assertEquals(JUnitTestSummary.compact(summary, 0, 0), output.toString());
    }

    @Test
    void discardsSuccessfulTestOutput() throws Exception {
        var result = runTestWithOutput(0);

        assertEquals(0, result.exitCode());
        assertEquals("", result.standardOutput());
        assertEquals("", result.standardError());
    }

    @Test
    void replaysFailedTestOutput() throws Exception {
        var result = runTestWithOutput(1);

        assertEquals(1, result.exitCode());
        assertEquals("standard output\n", result.standardOutput());
        assertEquals("standard error\n", result.standardError());
    }

    @Test
    void linksStructuredJUnitReportsWithoutReplayingTestOutput() throws Exception {
        var runWith = new ArrayList<String>();
        var tool = new ToolProvider() {
            @Override
            public String name() {
                return "junit";
            }

            @Override
            public int run(PrintWriter out, PrintWriter err, String... arguments) {
                runWith.addAll(List.of(arguments));
                var reports = option(arguments, "--reports-dir");
                try {
                    Files.createDirectories(reports);
                    Files.writeString(reports.resolve("TEST-junit-jupiter.xml"),
                            """
                            <testsuite tests="1" failures="1">
                              <testcase name="fails" classname="example.ExampleTest">
                                <failure message="failed" type="java.lang.AssertionError"/>
                                <system-out>failed output</system-out>
                                <system-err>failed error</system-err>
                              </testcase>
                            </testsuite>
                            """);
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
                out.println("runner failure");
                return 1;
            }
        };
        var definition = new ToolDefinition(
                "junit",
                Launch.PROVIDER,
                Optional.empty(),
                Optional.of("org.junit.platform.console"),
                "junit",
                Optional.of("1"),
                Set.of(),
                List.of());
        var runner = new ToolRunner(
                testLayer(),
                ToolServices.of(tool),
                new ToolCatalog(List.of(definition)),
                (arguments, in, out, err) -> {
                    throw new AssertionError("Java must not be launched");
                });
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();

        int result;
        try (var out = new PrintStream(output);
             var err = new PrintStream(error)) {
            result = runner.run(
                    commandLine(BuiltinCommand.TEST, List.of("-t", "fast")),
                    List.of(),
                    new ResolvedToolArguments(List.of(), Set.of(), Map.of()),
                    InputStream.nullInputStream(),
                    out,
                    err);
        }

        assertEquals(1, result);
        assertTrue(runWith.contains("--config=junit.platform.output.capture.stdout=true"));
        assertTrue(runWith.contains("--config=junit.platform.output.capture.stderr=true"));
        assertTrue(runWith.contains("--config=junit.platform.output.capture.maxBuffer=8192"));
        assertTrue(runWith.stream()
                .anyMatch(argument -> argument.startsWith("--redirect-stdout=")));
        assertTrue(runWith.stream()
                .anyMatch(argument -> argument.startsWith("--redirect-stderr=")));
        assertEquals("runner failure\n", output.toString());
        assertFalse(error.toString()
                         .contains("failed error"));
        assertTrue(error.toString()
                        .contains("Test report:"));
        assertTrue(error.toString()
                        .contains("file:"));
        var report = option(runWith.toArray(String[]::new), "--reports-dir").resolve("TEST-junit-jupiter.xml");
        assertTrue(Files.isRegularFile(report));
        assertTrue(Files.readString(report)
                .contains("failed output"));
        deleteTree(report.getParent()
                         .getParent());
    }

    @Test
    void rejectsJUnitOutputConfiguration() throws Exception {
        var runWith = new ArrayList<String>();
        var tools = ToolServices.of(tool("junit", runWith));
        var definition = new ToolDefinition(
                "junit",
                Launch.PROVIDER,
                Optional.empty(),
                Optional.of("org.junit.platform.console"),
                "junit",
                Optional.of("1"),
                Set.of(),
                List.of());
        var runner = new ToolRunner(testLayer(), tools, new ToolCatalog(List.of(definition)),
                (arguments, in, out, err) -> {
                    throw new AssertionError("Java must not be launched");
                });
        var arguments = List.of(
                "--select-class",
                "example.ExampleTest",
                "--config=junit.platform.output.capture.stdout=false",
                "--config=junit.platform.output.capture.stderr=false",
                "--config=junit.platform.output.capture.maxBuffer=42",
                "--redirect-stdout=stdout.txt",
                "--redirect-stderr=stderr.txt",
                "--reports-dir=reports");

        var failure = assertThrows(
                IllegalArgumentException.class,
                () -> runner.run(
                        commandLine(BuiltinCommand.TEST, arguments),
                        List.of(),
                        new ResolvedToolArguments(List.of(), Set.of(), Map.of()),
                        InputStream.nullInputStream(),
                        System.out,
                        System.err));

        assertEquals("Unsupported test argument: --select-class", failure.getMessage());
        assertEquals(List.of(), runWith);
    }

    @Test
    void reportsAnInactiveFixedToolConsistently() {
        var junit = new ToolDefinition(
                "junit",
                Launch.PROVIDER,
                Optional.of("org.junit.platform.engine"),
                Optional.of("org.junit.platform.console"),
                "junit",
                Optional.empty(),
                Set.of("module-path", "add-modules"),
                List.of());
        var runner = new ToolRunner(testLayer(), ToolServices.of(), new ToolCatalog(List.of(junit)),
                (arguments, in, out, err) -> 0);
        var commandLine = new JaInvocation(
                Path.of("").toAbsolutePath(),
                new Builtin(BuiltinCommand.TEST),
                Optional.empty(),
                List.of(),
                List.of(),
                List.of());

        var failure = assertThrows(
                IllegalArgumentException.class,
                () -> runner.run(
                        commandLine,
                        List.of(),
                        new ResolvedToolArguments(List.of(), Set.of(), Map.of()),
                        InputStream.nullInputStream(),
                        System.out,
                        System.err));

        assertEquals("test is unavailable; missing tool: junit", failure.getMessage());
    }

    private record TestResult(int exitCode, String standardOutput, String standardError) {}

    private static TestResult runTestWithOutput(int exitCode) throws Exception {
        var tool = new ToolProvider() {
            @Override
            public String name() {
                return "junit";
            }

            @Override
            public int run(PrintWriter out, PrintWriter err, String... arguments) {
                out.println("standard output");
                err.println("standard error");
                return exitCode;
            }
        };
        var definition = new ToolDefinition(
                "junit",
                Launch.PROVIDER,
                Optional.empty(),
                Optional.empty(),
                "junit",
                Optional.of("1"),
                Set.of(),
                List.of());
        var runner = new ToolRunner(
                testLayer(),
                ToolServices.of(tool),
                new ToolCatalog(List.of(definition)),
                (arguments, in, out, err) -> {
                    throw new AssertionError("Java must not be launched");
                });
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();

        int result;
        try (var out = new PrintStream(output);
             var err = new PrintStream(error)) {
            result = runner.run(
                    commandLine(BuiltinCommand.TEST, List.of("-t", "fast")),
                    List.of(),
                    new ResolvedToolArguments(List.of(), Set.of(), Map.of()),
                    InputStream.nullInputStream(),
                    out,
                    err);
        }
        return new TestResult(result, output.toString(), error.toString());
    }

    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static Path option(String[] arguments, String name) {
        for (int i = 0; i < arguments.length; i++) {
            if (arguments[i].equals(name)) {
                return Path.of(arguments[++i]);
            }
            if (arguments[i].startsWith(name + "=")) {
                return Path.of(arguments[i].substring(name.length() + 1));
            }
        }
        throw new AssertionError("Missing option: " + name);
    }

    private static JaInvocation commandLine(BuiltinCommand command, List<String> arguments) {
        return new JaInvocation(
                Path.of("").toAbsolutePath(),
                new Builtin(command),
                Optional.empty(),
                List.of(),
                List.of(),
                arguments);
    }

    private static JaInvocation toolCommandLine(String name, List<String> arguments) {
        return toolCommandLine(name, false, arguments);
    }

    private static JaInvocation verboseToolCommandLine(String name, List<String> arguments) {
        return toolCommandLine(name, true, arguments);
    }

    private static JaInvocation toolCommandLine(String name, boolean verbose, List<String> arguments) {
        return new JaInvocation(
                Path.of("").toAbsolutePath(),
                verbose,
                new Tool(name),
                Optional.empty(),
                List.of(),
                List.of(),
                arguments);
    }

    private static ToolProvider tool(String name, List<String> runWith) {
        return tool(name, runWith, List.of());
    }

    private static ToolProvider tool(String name, List<String> runWith, List<String> output) {
        return new ToolProvider() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public int run(PrintWriter out, PrintWriter err, String... arguments) {
                runWith.addAll(List.of(arguments));
                output.forEach(out::println);
                return 0;
            }
        };
    }

    private static ToolProvider checkingTool(String name, List<String> runWith, OptionChecker checker) {
        return new CheckingTool(name, runWith, checker);
    }

    private record CheckingTool(String name, List<String> runWith, OptionChecker checker) implements ToolProvider, OptionChecker {
        @Override
        public int run(PrintWriter out, PrintWriter err, String... arguments) {
            runWith.addAll(List.of(arguments));
            return 0;
        }

        @Override
        public int isSupportedOption(String option) {
            return checker.isSupportedOption(option);
        }
    }
}
