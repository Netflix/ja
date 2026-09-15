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
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.lang.reflect.AccessFlag;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_void;

/** Adds execution probes without introducing a module dependency. */
public final class ExecutionTraceInstrumentation {
    public interface Recording extends AutoCloseable {
        @Override
        void close();
    }

    private static final String RECORDER_PROPERTY = "com.netflix.tools.ja.executionTraceRecorder";
    static final String SCOPE_PROPERTY = "com.netflix.tools.ja.executionTraceScope";
    private static final String TRACE_SCOPE = "[execution-trace]";
    private static final ClassDesc SYSTEM = ClassDesc.of("java.lang.System");
    private static final ClassDesc PROPERTIES = ClassDesc.of("java.util.Properties");
    private static final ClassDesc BI_CONSUMER = ClassDesc.of("java.util.function.BiConsumer");
    private static final MethodTypeDesc GET_PROPERTIES = MethodTypeDesc.of(PROPERTIES);
    private static final MethodTypeDesc GET = MethodTypeDesc.of(CD_Object, CD_Object);
    private static final MethodTypeDesc ACCEPT = MethodTypeDesc.of(CD_void, CD_Object, CD_Object);
    private static final ArrayDeque<String> EXECUTIONS = new ArrayDeque<>();
    private static final ScopedValue<Boolean> RECORDING = ScopedValue.newInstance();

    private ExecutionTraceInstrumentation() {}

    public static byte[] instrument(ClassModel model) {
        return instrument(model, ClassHierarchyResolver.defaultResolver());
    }

    static byte[] instrument(ClassModel model, ClassHierarchyResolver hierarchy) {
        return ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(hierarchy)).transformClass(model, (builder, element) -> {
            if (element instanceof MethodModel method && method.code().isPresent()) {
                builder.transformMethod(method, MethodTransform.transformingCode(probe(method)));
            } else {
                builder.with(element);
            }
        });
    }

    public static ModuleReference instrument(ModuleReference reference) throws IOException {
        return instrument(reference, List.of());
    }

    public static ModuleReference instrument(ModuleReference reference, List<Path> patches) throws IOException {
        return instrument(reference, patches, ClassHierarchyResolver.defaultResolver());
    }

    static ModuleReference instrument(ModuleReference reference, List<Path> patches, ClassHierarchyResolver hierarchy) throws IOException {
        var patchResources = readPatches(patches);
        var descriptor = withPatchPackages(reference.descriptor(), patchResources.keySet());
        return new ModuleReference(descriptor, reference.location()
                .orElse(null)) {
            @Override
            public ModuleReader open() throws IOException {
                return new InstrumentedModuleReader(reference.open(), patchResources, hierarchy);
            }
        };
    }

    public static Optional<String> currentExecution() {
        synchronized (EXECUTIONS) {
            return Optional.ofNullable(EXECUTIONS.peekLast());
        }
    }

    static void executionStarted(String uniqueId) {
        synchronized (EXECUTIONS) {
            EXECUTIONS.addLast(uniqueId);
        }
    }

    static void executionFinished(String uniqueId) {
        synchronized (EXECUTIONS) {
            var current = EXECUTIONS.pollLast();
            if (!uniqueId.equals(current)) {
                throw new IllegalStateException("JUnit execution scope mismatch: expected " + uniqueId + " but was " + current);
            }
        }
    }

    public static Recording recordWith(BiConsumer<String, Object> recorder) {
        BiConsumer<String, Object> guarded = (method, receiver) -> {
            if (!RECORDING.isBound()) {
                ScopedValue.where(RECORDING, true).run(() -> recorder.accept(method, receiver));
            }
        };
        var properties = System.getProperties();
        var previousRecorder = properties.put(RECORDER_PROPERTY, guarded);
        BiConsumer<String, Object> scopes = (execution, started) -> {
            switch (started) {
                case Boolean value when value -> executionStarted(execution);
                case Boolean _ -> executionFinished(execution);
                case null -> throw new IllegalArgumentException("Execution trace scope state is null");
                default -> throw new IllegalArgumentException("Invalid execution trace scope state: " + started);
            }
        };
        var previousScopes = properties.put(SCOPE_PROPERTY, scopes);
        executionStarted(TRACE_SCOPE);
        return () -> {
            boolean restoredScopes = restore(properties, SCOPE_PROPERTY, scopes, previousScopes);
            boolean restoredRecorder = restore(properties, RECORDER_PROPERTY, guarded, previousRecorder);
            executionFinished(TRACE_SCOPE);
            if (!restoredScopes) {
                throw new IllegalStateException("execution trace scope recorder was replaced");
            }
            if (!restoredRecorder) {
                throw new IllegalStateException("execution trace recorder was replaced");
            }
        };
    }

    private static boolean restore(Properties properties, String key, Object current,
            Object previous) {
        return previous == null ? properties.remove(key, current) : properties.replace(key, current, previous);
    }

    private static ModuleDescriptor withPatchPackages(ModuleDescriptor descriptor, Set<String> resources) {
        var packages = new TreeSet<>(descriptor.packages());
        resources.stream()
                .filter(ExecutionTraceInstrumentation::isClass)
                .map(resource -> resource.substring(0, resource.lastIndexOf('/')).replace('/', '.'))
                .forEach(packages::add);
        if (packages.equals(descriptor.packages())) {
            return descriptor;
        }
        var builder = ModuleDescriptor.newModule(descriptor.name(), descriptor.modifiers());
        descriptor.requires().forEach(builder::requires);
        descriptor.exports().forEach(builder::exports);
        descriptor.opens().forEach(builder::opens);
        descriptor.uses().forEach(builder::uses);
        descriptor.provides().forEach(builder::provides);
        builder.packages(packages);
        descriptor.rawVersion().ifPresent(builder::version);
        descriptor.mainClass().ifPresent(builder::mainClass);
        return builder.build();
    }

    private record PatchResource(byte[] content, URI location) {}

    private static final class InstrumentedModuleReader implements ModuleReader {
        private final ModuleReader delegate;
        private final Map<String, PatchResource> patches;
        private final ClassHierarchyResolver hierarchy;

        private InstrumentedModuleReader(ModuleReader delegate, Map<String, PatchResource> patches, ClassHierarchyResolver hierarchy) {
            this.delegate = delegate;
            this.patches = patches;
            this.hierarchy = hierarchy;
        }

        @Override
        public Optional<URI> find(String name) throws IOException {
            var patch = patches.get(name);
            return patch == null ? delegate.find(name) : Optional.of(patch.location());
        }

        @Override
        public Optional<InputStream> open(String name) throws IOException {
            if (isClass(name) && isInstrumentable(name)) {
                return instrumentedClass(name).map(ByteArrayInputStream::new);
            }
            var patch = patches.get(name);
            return patch == null ? delegate.open(name) : Optional.of(new ByteArrayInputStream(patch.content()));
        }

        @Override
        public Optional<ByteBuffer> read(String name) throws IOException {
            if (isClass(name) && isInstrumentable(name)) {
                return instrumentedClass(name).map(ByteBuffer::wrap);
            }
            var input = open(name);
            if (input.isEmpty()) {
                return Optional.empty();
            }
            try (var stream = input.orElseThrow()) {
                return Optional.of(ByteBuffer.wrap(stream.readAllBytes()));
            }
        }

        private Optional<byte[]> instrumentedClass(String name) throws IOException {
            var patch = patches.get(name);
            if (patch != null) {
                return Optional.of(instrument(ClassFile.of().parse(patch.content()), hierarchy));
            }
            var input = delegate.open(name);
            if (input.isEmpty()) {
                return Optional.empty();
            }
            try (var stream = input.orElseThrow()) {
                return Optional.of(instrument(ClassFile.of().parse(stream.readAllBytes()), hierarchy));
            }
        }

        @Override
        public void release(ByteBuffer buffer) {}

        @Override
        public Stream<String> list() throws IOException {
            return Stream.concat(patches.keySet().stream(),
                    delegate.list())
                    .distinct();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    static boolean isInstrumentable(String resource) {
        return !resource.equals("com/netflix/tools/ja/ExecutionTraceInstrumentation.class") && !resource.startsWith("com/netflix/tools/ja/ExecutionTraceInstrumentation$");
    }

    private static boolean isClass(String resource) {
        return resource.endsWith(".class") && !resource.equals("module-info.class");
    }

    private static Map<String, PatchResource> readPatches(List<Path> patches) throws IOException {
        var resources = new LinkedHashMap<String, PatchResource>();
        for (var patch : patches) {
            if (Files.isDirectory(patch)) {
                try (var files = Files.walk(patch)) {
                    for (var file : files.filter(Files::isRegularFile)
                                         .sorted()
                                         .toList()) {
                        var name = patch.relativize(file)
                                        .toString()
                                        .replace(file.getFileSystem()
                                                     .getSeparator(),
                                                "/");
                        resources.putIfAbsent(name,
                                new PatchResource(Files.readAllBytes(file), file.toUri()));
                    }
                }
            } else {
                try (var jar = new JarFile(patch.toFile(), true, ZipFile.OPEN_READ, Runtime.version())) {
                    var entries = new ArrayList<>(jar.versionedStream()
                            .filter(entry -> !entry.isDirectory())
                            .toList());
                    for (var entry : entries) {
                        try (var stream = jar.getInputStream(entry)) {
                            var location = URI.create("jar:" + patch.toUri() + "!/" + entry.getName());
                            resources.putIfAbsent(entry.getName(), new PatchResource(stream.readAllBytes(), location));
                        }
                    }
                }
            }
        }
        return Map.copyOf(resources);
    }

    static String methodId(MethodModel method) {
        var owner = method.parent().orElseThrow();
        return owner.thisClass().asInternalName()
                + "."
                + method.methodName().stringValue()
                + method.methodType().stringValue();
    }

    private static CodeTransform probe(MethodModel method) {
        var owner = method.parent().orElseThrow();
        var methodId = methodId(method);
        var receiver = !method.flags().has(AccessFlag.STATIC) && !method.methodName().equalsString("<init>");
        return new CodeTransform() {
            @Override
            public void atStart(CodeBuilder builder) {
                builder.invokestatic(SYSTEM, "getProperties", GET_PROPERTIES)
                       .ldc(RECORDER_PROPERTY)
                       .invokevirtual(PROPERTIES, "get", GET)
                       .checkcast(BI_CONSUMER)
                       .ldc(methodId);
                if (receiver) {
                    builder.aload(builder.receiverSlot());
                } else {
                    builder.ldc(owner.thisClass()
                                     .asSymbol());
                }
                builder.invokeinterface(BI_CONSUMER, "accept", ACCEPT);
            }

            @Override
            public void accept(CodeBuilder builder, CodeElement element) {
                builder.with(element);
            }
        };
    }
}
