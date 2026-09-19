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

package com.netflix.tools.ja;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.ModuleLayer.Controller;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.CD_void;

/** Supplies JUnit execution boundaries to the JDK-only trace recorder. */
public final class JUnitExecutionTrace {
    private static final String CONSOLE_MODULE = "org.junit.platform.console";
    private static final String COMMONS_MODULE = "org.junit.platform.commons";
    private static final String ENGINE_MODULE = "org.junit.jupiter.engine";
    private static final String MODULE_NAME = "com.netflix.tools.ja.junit.trace";
    private static final String CLASS_NAME = MODULE_NAME + ".Listener";
    private static final String CLASS_RESOURCE = CLASS_NAME.replace('.', '/') + ".class";
    private static final String LISTENER = "org.junit.platform.launcher.TestExecutionListener";
    private static final String DISABLE_PARALLEL_EXECUTION = "--config=junit.jupiter.execution.parallel.enabled=false";
    private static final ClassDesc TYPE = ClassDesc.of(CLASS_NAME);
    private static final ClassDesc OBJECT = ClassDesc.of("java.lang.Object");
    private static final ClassDesc SYSTEM = ClassDesc.of("java.lang.System");
    private static final ClassDesc PROPERTIES = ClassDesc.of("java.util.Properties");
    private static final ClassDesc BI_CONSUMER = ClassDesc.of("java.util.function.BiConsumer");
    private static final ClassDesc BOOLEAN = ClassDesc.of("java.lang.Boolean");
    private static final ClassDesc TEST_PLAN = ClassDesc.of("org.junit.platform.launcher.TestPlan");
    private static final ClassDesc TEST_IDENTIFIER = ClassDesc.of("org.junit.platform.launcher.TestIdentifier");
    private static final ClassDesc TEST_EXECUTION_RESULT = ClassDesc.of("org.junit.platform.engine.TestExecutionResult");
    private static final MethodTypeDesc VOID_METHOD = MethodTypeDesc.of(CD_void);
    private static final MethodTypeDesc GET_PROPERTIES = MethodTypeDesc.of(PROPERTIES);
    private static final MethodTypeDesc GET = MethodTypeDesc.of(CD_Object, CD_Object);
    private static final MethodTypeDesc ACCEPT = MethodTypeDesc.of(CD_void, CD_Object, CD_Object);
    private static final MethodTypeDesc UNIQUE_ID = MethodTypeDesc.of(CD_String);
    private static final byte[] CONTENT = listenerClass();

    public record Runtime(Controller controller, ToolRuntime tools) {
        public int run(PrintStream out, PrintStream err, String... arguments) {
            return tools.run("junit", InputStream.nullInputStream(), out, err,
                    arguments(arguments));
        }

        private static String[] arguments(String[] arguments) {
            var traced = Arrays.copyOf(arguments, arguments.length + 1);
            traced[arguments.length] = DISABLE_PARALLEL_EXECUTION;
            return traced;
        }
    }

    private JUnitExecutionTrace() {}

    static Set<String> runtimeModules() {
        return Set.of(CONSOLE_MODULE, ENGINE_MODULE);
    }

    public static Runtime runtime(ResolvedClassModels classes, ModuleLayer parent, List<String> runtimeArguments) throws IOException {
        return runtimeIfSupported(classes, parent, runtimeArguments).orElseThrow(() -> new IllegalArgumentException("Test runtime requirements cannot be applied to the instrumented module layer"));
    }

    static Optional<Runtime> runtimeIfSupported(ResolvedClassModels classes, ModuleLayer parent, List<String> runtimeArguments) throws IOException {
        var controller = classes.instrumentedLayer(parent, runtimeModules(), List.of(listenerModule()));
        var layer = controller.layer();
        if (!ToolRuntime.configureLayer(controller, layer, runtimeArguments)) {
            return Optional.empty();
        }
        var commons = layer.findModule(COMMONS_MODULE).orElseThrow();
        var engine = layer.findModule(ENGINE_MODULE).orElseThrow();
        for (var name : classes.modules()) {
            var module = layer.findModule(name).orElseThrow();
            if (module.getLayer() != layer) {
                continue;
            }
            for (var packageName : module.getPackages()) {
                if (!module.isOpen(packageName, commons)) {
                    controller.addOpens(module, packageName, commons);
                }
                if (!module.isOpen(packageName, engine)) {
                    controller.addOpens(module, packageName, engine);
                }
            }
        }
        var tools = ToolRuntime.load(layer, Set.of(CONSOLE_MODULE));
        if (!tools.contains("junit")) {
            throw new IllegalStateException("JUnit console module does not provide the junit tool");
        }
        return Optional.of(new Runtime(controller, tools));
    }

    public static ModuleReference listenerModule() {
        var descriptor = ModuleDescriptor.newModule(MODULE_NAME)
                .requires("org.junit.platform.launcher")
                .packages(Set.of(MODULE_NAME))
                .provides(LISTENER, List.of(CLASS_NAME))
                .build();
        return new ModuleReference(descriptor, URI.create("memory:/" + MODULE_NAME)) {
            @Override
            public ModuleReader open() {
                return new ListenerModuleReader();
            }
        };
    }

    private static byte[] listenerClass() {
        return ClassFile.of().build(
                TYPE,
                builder -> {
                    builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER);
                    builder.withInterfaceSymbols(ClassDesc.of(LISTENER));
                    builder.withMethodBody(
                            "<init>",
                            VOID_METHOD,
                            ClassFile.ACC_PUBLIC,
                            code -> code.aload(0)
                                        .invokespecial(OBJECT, "<init>", VOID_METHOD)
                                        .return_());
                    builder.withMethodBody("testPlanExecutionStarted", MethodTypeDesc.of(CD_void, TEST_PLAN), ClassFile.ACC_PUBLIC,
                            code -> scope(code, _ -> code.ldc("[test-plan]"), true));
                    builder.withMethodBody("testPlanExecutionFinished", MethodTypeDesc.of(CD_void, TEST_PLAN), ClassFile.ACC_PUBLIC,
                            code -> scope(code, _ -> code.ldc("[test-plan]"), false));
                    builder.withMethodBody(
                            "executionStarted",
                            MethodTypeDesc.of(CD_void, TEST_IDENTIFIER),
                            ClassFile.ACC_PUBLIC,
                            code -> scope(
                                    code,
                                    id -> id.aload(1).invokevirtual(TEST_IDENTIFIER, "getUniqueId", UNIQUE_ID),
                                    true));
                    builder.withMethodBody(
                            "executionFinished",
                            MethodTypeDesc.of(CD_void, TEST_IDENTIFIER, TEST_EXECUTION_RESULT),
                            ClassFile.ACC_PUBLIC,
                            code -> scope(
                                    code,
                                    id -> id.aload(1).invokevirtual(TEST_IDENTIFIER, "getUniqueId", UNIQUE_ID),
                                    false));
                });
    }

    private static void scope(CodeBuilder code, Consumer<CodeBuilder> uniqueId, boolean started) {
        code.invokestatic(SYSTEM, "getProperties", GET_PROPERTIES)
            .ldc(ExecutionTraceInstrumentation.SCOPE_PROPERTY)
            .invokevirtual(PROPERTIES, "get", GET)
            .checkcast(BI_CONSUMER);
        uniqueId.accept(code);
        code.getstatic(BOOLEAN, started ? "TRUE" : "FALSE", BOOLEAN)
            .invokeinterface(BI_CONSUMER, "accept", ACCEPT)
            .return_();
    }

    private static final class ListenerModuleReader implements ModuleReader {
        @Override
        public Optional<URI> find(String name) {
            return name.equals(CLASS_RESOURCE) ? Optional.of(URI.create("memory:/" + CLASS_RESOURCE)) : Optional.empty();
        }

        @Override
        public Optional<InputStream> open(String name) {
            return name.equals(CLASS_RESOURCE) ? Optional.of(new ByteArrayInputStream(CONTENT)) : Optional.empty();
        }

        @Override
        public Optional<ByteBuffer> read(String name) {
            return name.equals(CLASS_RESOURCE) ? Optional.of(ByteBuffer.wrap(CONTENT)) : Optional.empty();
        }

        @Override
        public void release(ByteBuffer buffer) {}

        @Override
        public Stream<String> list() {
            return Stream.of(CLASS_RESOURCE);
        }

        @Override
        public void close() throws IOException {}
    }
}
