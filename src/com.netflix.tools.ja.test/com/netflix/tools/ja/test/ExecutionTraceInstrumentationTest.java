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
import java.io.PrintStream;
import java.lang.classfile.Annotation;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassFile.ClassHierarchyResolverOption;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.netflix.tools.ja.ExecutionTrace;
import com.netflix.tools.ja.ExecutionTrace.Event;
import com.netflix.tools.ja.ExecutionTraceInstrumentation;
import com.netflix.tools.ja.JUnitExecutionTrace;
import com.netflix.tools.ja.ResolvedClassModels;
import com.netflix.tools.ja.ResolvedClassModels.ModuleInputs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_void;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionTraceInstrumentationTest {
    private static final ClassDesc TRACED = ClassDesc.of("example.Traced");
    private static final MethodTypeDesc VOID_METHOD = MethodTypeDesc.of(CD_void);

    private record Hit(String method, Object receiver) {}

    @Test
    void runsAJupiterMethodFromAnInstrumentedChildLayer(@TempDir Path directory) throws Exception {
        var moduleName = "example.instrumented.tests";
        var module = Files.createDirectories(directory.resolve(moduleName));
        TestModules.writeModuleInfo(module, moduleName, "org.junit.jupiter.api");
        var testType = ClassDesc.of("example.LayerTest");
        var supplierType = ClassDesc.of("example.RootSupplier");
        var supplierInterface = ClassDesc.of("java.util.function.Supplier");
        var hierarchy = ClassHierarchyResolver.of(Set.of(), Map.of(supplierType, CD_Object)).orElse(ClassHierarchyResolver.defaultResolver());
        var original = ClassFile.of(ClassHierarchyResolverOption.of(hierarchy)).build(testType,
                builder -> {
                    builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
                    builder.withMethodBody(
                            "<init>",
                            VOID_METHOD,
                            ClassFile.ACC_PUBLIC,
                            code -> code.aload(0)
                                        .invokespecial(CD_Object, "<init>", VOID_METHOD)
                                        .return_());
                    builder.withMethod("test", VOID_METHOD, ClassFile.ACC_PUBLIC,
                            method -> {
                                method.with(RuntimeVisibleAnnotationsAttribute.of(Annotation.of(ClassDesc.of("org.junit.jupiter.api.Test"))));
                                method.withCode(code -> {
                                    var other = code.newLabel();
                                    var merged = code.newLabel();
                                    code.iconst_1()
                                        .ifeq(other)
                                        .new_(supplierType)
                                        .dup()
                                        .invokespecial(supplierType, "<init>", VOID_METHOD)
                                        .goto_(merged)
                                        .labelBinding(other)
                                        .ldc("value")
                                        .labelBinding(merged)
                                        .pop()
                                        .return_();
                                });
                            });
                });
        var classFile = module.resolve("example/LayerTest.class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, original);
        var supplier = ClassFile.of().build(supplierType,
                builder -> {
                    builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
                    builder.withInterfaceSymbols(supplierInterface);
                    builder.withMethodBody(
                            "<init>",
                            VOID_METHOD,
                            ClassFile.ACC_PUBLIC,
                            code -> code.aload(0)
                                        .invokespecial(CD_Object, "<init>", VOID_METHOD)
                                        .return_());
                    builder.withMethodBody("get", MethodTypeDesc.of(CD_Object), ClassFile.ACC_PUBLIC,
                            code -> code.ldc("value").areturn());
                });
        Files.write(module.resolve("example/RootSupplier.class"), supplier);
        var classes = ResolvedClassModels.resolve(
                ExecutionTraceInstrumentationTest.class
                        .getModule()
                        .getLayer()
                        .configuration(),
                new ModuleInputs(Set.of(moduleName), List.of("--module-path", directory.toString())),
                new ModuleInputs(Set.of(), List.of()),
                "test-runtime");
        var runtime = JUnitExecutionTrace.runtime(classes, ExecutionTraceInstrumentationTest.class.getModule().getLayer(),
                List.of("--add-exports", moduleName + "/example=org.junit.platform.console"));
        var layer = runtime.controller().layer();
        var sourceModule = layer.findModule(moduleName).orElseThrow();
        var targetModule = layer.findModule("org.junit.platform.console").orElseThrow();
        assertTrue(sourceModule.isExported("example", targetModule));
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        var trace = new ExecutionTrace();
        int result;
        try (var ignored = ExecutionTraceInstrumentation.recordWith(trace);
             var out = new PrintStream(output, true, StandardCharsets.UTF_8);
             var err = new PrintStream(errors, true, StandardCharsets.UTF_8)) {
            result = runtime.run(out, err, "execute", "--select-module", moduleName, "--disable-banner",
                    "--details=summary");
        }

        assertEquals(0, result, errors.toString(StandardCharsets.UTF_8));
        assertTrue(output.toString(StandardCharsets.UTF_8)
                         .contains("1 tests successful"));
        var execution = trace.executions().stream()
                .filter(candidate -> candidate.contains("[method:test()]"))
                .findFirst()
                .orElseThrow();
        assertEquals(
                Set.of("example/LayerTest.<init>()V", "example/LayerTest.test()V", "example/RootSupplier.<init>()V"),
                trace.events(execution).stream()
                        .map(Event::method)
                        .filter(method -> method.startsWith("example/"))
                        .collect(Collectors.toSet()));
        assertTrue(trace.executions().stream()
                .anyMatch(candidate -> candidate.endsWith("[class:example.LayerTest]")));
    }

    @Test
    void aChildConfigurationCanShadowAParentModule(@TempDir Path directory) throws Exception {
        var moduleName = ExecutionTraceInstrumentationTest.class.getModule().getName();
        var module = Files.createDirectories(directory.resolve(moduleName));
        TestModules.writeModuleInfo(module, moduleName);
        var finder = ModuleFinder.of(directory);
        var replacement = finder.find(moduleName).orElseThrow();

        var configuration = Configuration.resolve(
                finder,
                List.of(ExecutionTraceInstrumentationTest.class
                        .getModule()
                        .getLayer()
                        .configuration()),
                ModuleFinder.of(),
                Set.of(moduleName));

        assertSame(replacement,
                configuration.findModule(moduleName)
                             .orElseThrow()
                             .reference());
    }

    @Test
    void instrumentsANamedModuleThatReadsOnlyJavaBase(@TempDir Path directory) throws Exception {
        var moduleName = "example.root";
        var module = Files.createDirectories(directory.resolve(moduleName));
        TestModules.writeModuleInfo(module, moduleName);
        var original = ClassFile.of().build(TRACED,
                builder -> {
                    builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
                    builder.withMethodBody("baseMethod", VOID_METHOD, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                            code -> code.return_());
                });
        var classFile = module.resolve("example/Traced.class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, original);
        Files.writeString(module.resolve("content.txt"), "base");
        var patch = Files.createDirectories(directory.resolve("patch"));
        var patched = ClassFile.of().build(TRACED,
                builder -> {
                    builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
                    builder.withMethodBody("staticMethod", VOID_METHOD, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                            code -> code.return_());
                });
        var patchedClass = patch.resolve("example/Traced.class");
        Files.createDirectories(patchedClass.getParent());
        Files.write(patchedClass, patched);
        var addedType = ClassDesc.of("added.Patched");
        var addedClass = patch.resolve("added/Patched.class");
        Files.createDirectories(addedClass.getParent());
        Files.write(
                addedClass,
                ClassFile.of().build(
                        addedType,
                        builder -> builder.withMethodBody("method", VOID_METHOD, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                                code -> code.return_())));
        Files.writeString(patch.resolve("content.txt"), "patch");
        var reference = ModuleFinder.of(directory)
                .find(moduleName)
                .orElseThrow();
        var instrumented = ExecutionTraceInstrumentation.instrument(reference, List.of(patch));
        var configuration = Configuration.resolve(
                new SingleModuleFinder(instrumented),
                List.of(ExecutionTraceInstrumentationTest.class
                        .getModule()
                        .getLayer()
                        .configuration()),
                ModuleFinder.of(),
                Set.of(moduleName));
        var controller = ModuleLayer.defineModulesWithOneLoader(configuration, List.of(ExecutionTraceInstrumentationTest.class.getModule().getLayer()),
                ClassLoader.getSystemClassLoader());
        var rootModule = controller.layer()
                .findModule(moduleName)
                .orElseThrow();
        controller.addExports(rootModule, "example", ExecutionTraceInstrumentationTest.class.getModule());
        var type = controller.layer()
                             .findLoader(moduleName)
                             .loadClass("example.Traced");
        var hits = new ArrayList<Hit>();

        try (var ignored = ExecutionTraceInstrumentation.recordWith((method, receiver) -> hits.add(new Hit(method, receiver)))) {
            type.getMethod("staticMethod").invoke(null);
        }

        assertEquals(
                Set.of("java.base"),
                rootModule.getDescriptor().requires().stream()
                        .map(require -> require.name())
                        .collect(Collectors.toSet()));
        assertTrue(rootModule.getDescriptor()
                             .packages()
                             .contains("added"));
        assertEquals(List.of(new Hit("example/Traced.staticMethod()V", type)), hits);
        try (var reader = instrumented.open();
             var content = reader.open("content.txt").orElseThrow()) {
            assertEquals("patch", new String(content.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void recordsCallbacksThroughUninstrumentedCode() throws Exception {
        var callbackType = ClassDesc.of("example.Callback");
        var libraryType = ClassDesc.of("example.Library");
        var implementationType = ClassDesc.of("example.CallbackImpl");
        var callback = ClassFile.of().build(callbackType,
                builder -> {
                    builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT);
                    builder.withMethod("run", VOID_METHOD, ClassFile.ACC_PUBLIC | ClassFile.ACC_ABSTRACT, _ -> {});
                });
        var library = ClassFile.of().build(libraryType,
                builder -> {
                    builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
                    builder.withMethodBody(
                            "execute",
                            MethodTypeDesc.of(CD_void, callbackType),
                            ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                            code -> code.aload(0)
                                        .invokeinterface(callbackType, "run", VOID_METHOD)
                                        .return_());
                });
        var implementation = ClassFile.of().build(implementationType,
                builder -> {
                    builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
                    builder.withInterfaceSymbols(callbackType);
                    builder.withMethodBody(
                            "<init>",
                            VOID_METHOD,
                            ClassFile.ACC_PUBLIC,
                            code -> code.aload(0)
                                        .invokespecial(CD_Object, "<init>", VOID_METHOD)
                                        .return_());
                    builder.withMethodBody("run", VOID_METHOD, ClassFile.ACC_PUBLIC, code -> code.return_());
                });
        var loader = new ByteLoader();
        var callbackClass = loader.define("example.Callback", callback);
        var libraryClass = loader.define("example.Library", library);
        var instrumented = ExecutionTraceInstrumentation.instrument(ClassFile.of()
                .parse(implementation));
        var implementationClass = loader.define("example.CallbackImpl", instrumented);
        var hits = new ArrayList<Hit>();

        try (var ignored = ExecutionTraceInstrumentation.recordWith((method, receiver) -> hits.add(new Hit(method, receiver)))) {
            var instance = implementationClass.getConstructor().newInstance();
            libraryClass.getMethod("execute", callbackClass).invoke(null, instance);

            assertEquals("example/CallbackImpl.<init>()V", hits.get(0)
                    .method());
            assertSame(implementationClass, hits.get(0)
                    .receiver());
            assertEquals("example/CallbackImpl.run()V", hits.get(1)
                    .method());
            assertSame(instance, hits.get(1)
                    .receiver());
        }
    }

    @Test
    void recordsApplicationConcurrencyWithoutThreadScope() throws Exception {
        var runnableType = ClassDesc.of("example.TracedRunnable");
        var runnable = ClassFile.of().build(runnableType,
                builder -> {
                    builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
                    builder.withInterfaceSymbols(ClassDesc.of("java.lang.Runnable"));
                    builder.withMethodBody(
                            "<init>",
                            VOID_METHOD,
                            ClassFile.ACC_PUBLIC,
                            code -> code.aload(0)
                                        .invokespecial(CD_Object, "<init>", VOID_METHOD)
                                        .return_());
                    builder.withMethodBody("run", VOID_METHOD, ClassFile.ACC_PUBLIC, code -> code.return_());
                });
        var instrumented = ExecutionTraceInstrumentation.instrument(ClassFile.of()
                .parse(runnable));
        var type = new ByteLoader().define("example.TracedRunnable", instrumented);
        Runnable instance;
        try (var ignored = ExecutionTraceInstrumentation.recordWith((_, _) -> {})) {
            instance = (Runnable) type.getConstructor().newInstance();
        }
        var trace = new ExecutionTrace();

        try (var executor = Executors.newSingleThreadExecutor();
             var forkJoin = new ForkJoinPool(2)) {
            executor.submit(() -> {}).get();
            forkJoin.submit(() -> {}).get();
            ForkJoinPool.commonPool()
                    .submit(() -> {})
                    .get();

            try (var ignored = ExecutionTraceInstrumentation.recordWith(trace)) {
                executor.submit(instance).get();
                forkJoin.submit(instance).get();
                IntStream.range(0, 100)
                        .parallel()
                        .forEach(_ -> instance.run());
                var virtual = Thread.ofVirtual()
                        .inheritInheritableThreadLocals(false)
                        .start(instance);
                virtual.join();
            }
        }

        assertEquals(
                Set.of("example/TracedRunnable.run()V"),
                trace.events("[execution-trace]").stream()
                        .map(Event::method)
                        .filter(method -> method.startsWith("example/"))
                        .collect(Collectors.toSet()));
    }

    @Test
    void recordsTransformedMethodEntriesWithoutLinkingToJa() throws Exception {
        var original = ClassFile.of().build(TRACED,
                builder -> {
                    builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
                    builder.withMethodBody(
                            "<init>",
                            VOID_METHOD,
                            ClassFile.ACC_PUBLIC,
                            code -> code.aload(0)
                                        .invokespecial(CD_Object, "<init>", VOID_METHOD)
                                        .return_());
                    builder.withMethodBody("instanceMethod", VOID_METHOD, ClassFile.ACC_PUBLIC, code -> code.return_());
                    builder.withMethodBody("staticMethod", VOID_METHOD, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                            code -> code.return_());
                });
        var instrumented = ExecutionTraceInstrumentation.instrument(ClassFile.of()
                .parse(original));
        var type = new ByteLoader().define("example.Traced", instrumented);
        var hits = new ArrayList<Hit>();

        try (var ignored = ExecutionTraceInstrumentation.recordWith((method, receiver) -> hits.add(new Hit(method, receiver)))) {
            var instance = type.getConstructor().newInstance();
            type.getMethod("instanceMethod").invoke(instance);
            type.getMethod("staticMethod").invoke(null);

            assertEquals("example/Traced.<init>()V", hits.get(0)
                    .method());
            assertSame(type, hits.get(0)
                                 .receiver());
            assertEquals("example/Traced.instanceMethod()V", hits.get(1)
                    .method());
            assertSame(instance, hits.get(1)
                    .receiver());
            assertEquals("example/Traced.staticMethod()V", hits.get(2)
                    .method());
            assertSame(type, hits.get(2)
                                 .receiver());
        }
    }

    private record SingleModuleFinder(ModuleReference reference) implements ModuleFinder {
        @Override
        public Optional<ModuleReference> find(String name) {
            return reference.descriptor()
                            .name()
                            .equals(name)
                    ? Optional.of(reference) : Optional.empty();
        }

        @Override
        public Set<ModuleReference> findAll() {
            return Set.of(reference);
        }
    }

    private static final class ByteLoader extends ClassLoader {
        private Class<?> define(String name, byte[] content) {
            return defineClass(name, content, 0, content.length);
        }
    }
}
