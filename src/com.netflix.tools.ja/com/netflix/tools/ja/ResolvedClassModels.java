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

import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.ModuleLayer.Controller;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.constant.ClassDesc;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.lang.module.ResolvedModule;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

import com.netflix.module.ModuleHash;
import com.netflix.module.ModuleRuntimeAccess;
import com.netflix.tools.ja.Configurations.Resolution;
import com.netflix.tools.ja.ExecutionTrace.Event;
import com.netflix.tools.ja.TestDiscovery.TestMethod;
import com.netflix.tools.ja.TestResultStore.Trace;

/** Application class files and module state from a resolved configuration. */
public final class ResolvedClassModels {
    public record Entry(String moduleName, String resource, ClassModel model) {}

    public record ModuleInputs(Set<String> roots, List<String> arguments) {
        public ModuleInputs {
            roots = Set.copyOf(roots);
            arguments = List.copyOf(arguments);
        }
    }

    private record LayerFoundation(ModuleLayer layer, boolean complete) {}

    private record LoadedClass(String moduleName, String resource, ClassModel model,
            byte[] hash) {}

    private record ResolvedMethod(MethodModel model, LoadedClass owner) {}

    public record ModuleState(String moduleName, ModuleHash moduleHash, List<ModuleHash> patchHashes) {
        public ModuleState {
            patchHashes = List.copyOf(patchHashes);
        }
    }

    private final Resolution resolution;
    private final Map<String, ModuleReference> modules;
    private final Map<String, List<Path>> patches;
    private final Set<String> roots;
    private final Set<String> runtimeModules;
    private final Map<String, ModuleReference> runtimeAccessModules;
    private final String runtimeImageHash;
    private final Map<String, Path> moduleDirectories;
    private final Map<String, List<Entry>> classes = new LinkedHashMap<>();
    private final Map<String, Optional<LoadedClass>> classModels = new LinkedHashMap<>();
    private final Map<ClassModel, LoadedClass> loadedClasses = new IdentityHashMap<>();
    private final Map<String, Optional<ResolvedMethod>> instrumentedMethods = new LinkedHashMap<>();
    private final Map<String, ModuleState> moduleStates = new LinkedHashMap<>();
    private final ObservedCodeHash codeHash = new ObservedCodeHash();
    private ClassHierarchyResolver instrumentedClassHierarchy;
    private Set<String> instrumentedModuleNames;
    private List<ModuleState> executionModuleStates;

    public static ResolvedClassModels resolve(Configuration parent, ModuleInputs applicationInputs, ModuleInputs runtimeInputs,
            String runtimeImageHash) {
        if (runtimeImageHash.isEmpty()) {
            throw new IllegalArgumentException("runtimeImageHash is empty");
        }
        var applicationPath = ModuleFinder.of(ToolArguments.applicationModulePath(applicationInputs.arguments())
                .toArray(Path[]::new));
        var applicationPathModules = applicationPath.findAll();
        var applicationBeforeModules = new LinkedHashSet<>(applicationInputs.roots());
        applicationPathModules.stream()
                .filter(ResolvedClassModels::isInstrumented)
                .map(reference -> reference.descriptor().name())
                .forEach(applicationBeforeModules::add);
        var application = resolveInputs(parent, applicationInputs, withDependents(applicationBeforeModules, applicationPathModules));
        var effectiveRuntimeInputs = withJUnitDependencies(application.configuration(), applicationInputs, runtimeInputs);
        var runtime = resolveInputs(application.configuration(), effectiveRuntimeInputs, runtimeModulePathClosure(effectiveRuntimeInputs));
        var runtimeModules = runtime.reachableModules(effectiveRuntimeInputs.roots()).stream()
                .map(module -> module.name())
                .collect(Collectors.toUnmodifiableSet());
        var modules = new LinkedHashMap<String, ModuleReference>();
        application.reachablePathModules(applicationInputs.roots()).stream()
                .forEach(module -> modules.put(module.name(), module.reference()));
        runtime.reachableModules(effectiveRuntimeInputs.roots()).stream()
                .filter(module -> application.isPathModule(module.name()) || runtime.isPathModule(module.name()))
                .forEach(module -> modules.putIfAbsent(module.name(), module.reference()));
        var layerArguments = new ArrayList<>(applicationInputs.arguments());
        layerArguments.addAll(runtimeInputs.arguments());
        return new ResolvedClassModels(runtime, modules, ToolArguments.patchModules(applicationInputs.arguments()), applicationInputs.roots(),
                runtimeModules, runtimeAccessModules(runtime, layerArguments), runtimeImageHash);
    }

    private static ModuleInputs withJUnitDependencies(Configuration application, ModuleInputs applicationInputs, ModuleInputs runtimeInputs) {
        var roots = new LinkedHashSet<>(runtimeInputs.roots());
        applicationInputs.roots().stream()
                .map(application::findModule)
                .flatMap(Optional::stream)
                .flatMap(module -> module.reference().descriptor().requires().stream())
                .map(require -> require.name())
                .filter(name -> name.startsWith("org.junit."))
                .forEach(roots::add);
        return new ModuleInputs(roots, runtimeInputs.arguments());
    }

    @SuppressWarnings("restricted")
    private static Set<String> runtimeModulePathClosure(ModuleInputs inputs) {
        var roots = new LinkedHashSet<>(inputs.roots());
        var access = ModuleRuntimeAccess.parseArguments(inputs.arguments());
        roots.addAll(access.enableNativeAccess());
        access.addExports().forEach(export -> {
            roots.add(export.sourceModule());
            roots.add(export.targetModule());
        });
        access.addOpens().forEach(open -> {
            roots.add(open.sourceModule());
            roots.add(open.targetModule());
        });
        return modulePathClosure(inputs.arguments(), roots);
    }

    private static Set<String> modulePathClosure(List<String> arguments, Set<String> roots) {
        var paths = ToolArguments.applicationModulePath(arguments);
        var finder = ModuleFinder.of(paths.toArray(Path[]::new));
        var modules = new LinkedHashSet<String>();
        var pending = new ArrayDeque<>(roots);
        while (!pending.isEmpty()) {
            var name = pending.removeFirst();
            var reference = finder.find(name).orElse(null);
            if (reference == null || !modules.add(name)) {
                continue;
            }
            reference.descriptor().requires().stream()
                    .map(require -> require.name())
                    .forEach(pending::addLast);
        }
        return Set.copyOf(modules);
    }

    private static Resolution resolveInputs(Configuration parent, ModuleInputs inputs, Set<String> beforeModules) {
        var arguments = new ArrayList<>(inputs.arguments());
        for (var root : inputs.roots()) {
            arguments.add("--add-modules");
            arguments.add(root);
        }
        return Configurations.resolve(parent, arguments, beforeModules);
    }

    private ResolvedClassModels(
            Resolution resolution,
            Map<String, ModuleReference> modules,
            Map<String, List<Path>> patches,
            Set<String> roots,
            Set<String> runtimeModules,
            Map<String, ModuleReference> runtimeAccessModules,
            String runtimeImageHash) {
        this.resolution = resolution;
        this.modules = Map.copyOf(modules);
        this.patches = Map.copyOf(patches);
        this.roots = Set.copyOf(roots);
        this.runtimeModules = Set.copyOf(runtimeModules);
        this.runtimeAccessModules = Map.copyOf(runtimeAccessModules);
        this.runtimeImageHash = runtimeImageHash;
        var directories = new LinkedHashMap<String, Path>();
        modules.forEach(
                (name, reference) -> reference.location()
                        .filter(location -> location.getScheme().equals("file"))
                        .map(Path::of)
                        .filter(Files::isDirectory)
                        .ifPresent(path -> directories.put(name, path)));
        this.moduleDirectories = Map.copyOf(directories);
    }

    public Set<String> modules() {
        return modules.keySet().stream()
                .filter(module -> !runtimeModules.contains(module))
                .collect(Collectors.toUnmodifiableSet());
    }

    boolean isDirectoryModule(String moduleName) {
        module(moduleName);
        return moduleDirectories.containsKey(moduleName);
    }

    Set<String> requirements(String moduleName) {
        return module(moduleName).descriptor().requires().stream()
                .map(require -> require.name())
                .collect(Collectors.toUnmodifiableSet());
    }

    public List<Entry> classes(String moduleName) throws IOException {
        var existing = classes.get(moduleName);
        if (existing != null) {
            return existing;
        }
        var resources = new TreeSet<String>();
        for (var patch : patches.getOrDefault(moduleName, List.of())) {
            listPatchClasses(patch, resources);
        }
        try (var reader = module(moduleName).open();
             var listed = reader.list()) {
            listed.filter(ResolvedClassModels::isClass).forEach(resources::add);
        }
        var result = new ArrayList<Entry>();
        for (var resource : resources) {
            classModel(moduleName, resource).ifPresent(loaded -> result.add(new Entry(moduleName, resource, loaded.model())));
        }
        var content = List.copyOf(result);
        classes.put(moduleName, content);
        return content;
    }

    public List<Entry> classes() throws IOException {
        var result = new ArrayList<Entry>();
        for (var module : roots.stream()
                .sorted()
                .toList()) {
            result.addAll(classes(module));
        }
        return List.copyOf(result);
    }

    Optional<TestExecution> observedExecution(TestMethod test, Set<Event> events) throws IOException {
        return observedExecution(test, events, null);
    }

    Optional<TestExecution> observedExecution(TestMethod test, Trace previous) throws IOException {
        return observedExecution(test, previous.events(), previous);
    }

    private Optional<TestExecution> observedExecution(TestMethod test, Set<Event> events, Trace previous) throws IOException {
        var testClass = loadedClasses.get(test.model()
                .parent()
                .orElseThrow());
        if (testClass == null) {
            throw new IllegalStateException("Test class was not loaded from the resolved modules: " + test.className());
        }
        var observedClasses = new LinkedHashMap<String, LoadedClass>();
        addObservedClass(observedClasses, testClass);
        var executedMethods = new ArrayList<MethodModel>();
        executedMethods.add(test.model());
        var receiverClasses = new ArrayList<ClassModel>();
        receiverClasses.add(testClass.model());
        for (var event : events) {
            var method = instrumentedMethod(event.method());
            if (method.isEmpty()) {
                return Optional.empty();
            }
            var resolved = method.orElseThrow();
            executedMethods.add(resolved.model());
            addObservedClass(observedClasses, resolved.owner());
            if (event.receiverModule() != null && event.receiverClass() != null) {
                var receiver = instrumentedClass(event.receiverModule(),
                        event.receiverClass().replace('.', '/'));
                if (receiver.isEmpty()) {
                    return Optional.empty();
                }
                var loaded = receiver.orElseThrow();
                receiverClasses.add(loaded.model());
                addObservedClass(observedClasses, loaded);
            }
        }
        var observedClassHash = observedClassHash(observedClasses);
        var currentCodeHash = previous != null && observedClassHash.equals(previous.observedClassHash())
                ? previous.codeHash()
                : codeHash.hash(executedMethods, receiverClasses, instrumentedClassHierarchy());
        return Optional.of(new TestExecution(test.selector(), currentCodeHash, observedClassHash, moduleStates(),
                runtimeImageHash, events));
    }

    private static void addObservedClass(Map<String, LoadedClass> classes, LoadedClass loaded) {
        classes.putIfAbsent(loaded.moduleName() + "/" + loaded.resource(), loaded);
    }

    private static String observedClassHash(Map<String, LoadedClass> classes) {
        var digest = new Sha256().add(classes.size());
        classes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> digest.add(entry.getKey()).add(entry.getValue()
                        .hash()));
        return digest.hex();
    }

    public Controller instrumentedLayer(ModuleLayer parent, Set<String> executionRoots, List<ModuleReference> supportModules) throws IOException {
        var layerRoots = new LinkedHashSet<>(roots);
        layerRoots.addAll(runtimeModules);
        layerRoots.addAll(executionRoots);
        layerRoots.addAll(runtimeAccessModules.keySet());
        supportModules.stream()
                .map(reference -> reference.descriptor().name())
                .forEach(layerRoots::add);
        var foundation = layerFoundation(parent);
        if (foundation.complete()) {
            layerRoots.addAll(modules.keySet());
        }
        var resolution = Configurations.resolve(foundation.layer().configuration(), instrumentedModules(executionRoots, supportModules, foundation.complete()),
                finder(modules), layerRoots);
        return resolution.defineLayer(foundation.layer());
    }

    private LayerFoundation layerFoundation(ModuleLayer applicationLayer) {
        if (!definesResolvedModules(applicationLayer)) {
            return new LayerFoundation(applicationLayer, true);
        }
        return applicationLayer.parents().stream()
                .filter(candidate -> candidate.configuration()
                        .findModule("java.base")
                        .isPresent())
                .filter(candidate -> !definesResolvedModules(candidate))
                .map(candidate -> new LayerFoundation(candidate, true))
                .findFirst()
                // Linked self-hosting runtimes have application modules in their
                // infrastructure layer, so redefining all modules would expose
                // duplicate names to automatic modules.
                .orElseGet(() -> new LayerFoundation(applicationLayer, false));
    }

    private boolean definesResolvedModules(ModuleLayer layer) {
        return modules.keySet().stream()
                .anyMatch(name -> layer.findModule(name)
                                       .filter(module -> module.getLayer() == layer)
                                       .isPresent());
    }

    private ModuleFinder instrumentedModules(Set<String> executionRoots, List<ModuleReference> supportModules, boolean completeLayer) throws IOException {
        var references = executionModules(executionRoots);
        for (var reference : supportModules) {
            var name = reference.descriptor().name();
            if (references.putIfAbsent(name, reference) != null) {
                throw new IllegalArgumentException("Duplicate support module: " + name);
            }
        }
        runtimeAccessModules.forEach(references::putIfAbsent);
        var instrumented = instrumentedModules();
        var hierarchy = instrumentedClassHierarchy();
        var selected = completeLayer ? modules.keySet() : layerModules(instrumented);
        for (var name : selected) {
            var reference = modules.get(name);
            references.put(name, instrumented.contains(name) ? ExecutionTraceInstrumentation.instrument(reference, patches.getOrDefault(name, List.of()), hierarchy) : reference);
        }
        return finder(references);
    }

    private Set<String> layerModules(Set<String> instrumented) {
        var selected = new LinkedHashSet<>(instrumented);
        runtimeModules.stream()
                .filter(modules::containsKey)
                .forEach(selected::add);
        return withDependents(selected, modules.values());
    }

    private static Set<String> withDependents(Set<String> selectedModules, Iterable<ModuleReference> references) {
        var selected = new LinkedHashSet<>(selectedModules);
        boolean changed;
        do {
            changed = false;
            for (var reference : references) {
                var name = reference.descriptor().name();
                if (selected.contains(name)) {
                    continue;
                }
                if (reference.descriptor().requires().stream()
                        .map(require -> require.name())
                        .anyMatch(selected::contains)) {
                    changed |= selected.add(name);
                }
            }
        } while (changed);
        return Set.copyOf(selected);
    }

    private Set<String> instrumentedModules() {
        if (instrumentedModuleNames == null) {
            instrumentedModuleNames = modules.keySet().stream()
                    .filter(name -> !runtimeModules.contains(name))
                    .filter(moduleDirectories::containsKey)
                    .collect(Collectors.toUnmodifiableSet());
        }
        return instrumentedModuleNames;
    }

    private ClassHierarchyResolver instrumentedClassHierarchy() {
        if (instrumentedClassHierarchy == null) {
            instrumentedClassHierarchy = ClassHierarchyResolver.ofResourceParsing(this::openHierarchyClass)
                    .orElse(ClassHierarchyResolver.defaultResolver())
                    .cached(ConcurrentHashMap::new);
        }
        return instrumentedClassHierarchy;
    }

    private InputStream openHierarchyClass(ClassDesc type) {
        if (!type.isClassOrInterface()) {
            return null;
        }
        var descriptor = type.descriptorString();
        var resource = descriptor.substring(1, descriptor.length() - 1) + ".class";
        try {
            for (var moduleName : candidateModules(resource.substring(0, resource.length() - ".class".length()))) {
                var input = openClassResource(moduleName, resource);
                if (input.isPresent()) {
                    return input.orElseThrow();
                }
            }
            return null;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private Optional<ResolvedMethod> instrumentedMethod(String methodId) throws IOException {
        var existing = instrumentedMethods.get(methodId);
        if (existing != null) {
            return existing;
        }
        int descriptor = methodId.indexOf('(');
        int separator = descriptor < 0 ? -1 : methodId.lastIndexOf('.', descriptor);
        Optional<ResolvedMethod> result = Optional.empty();
        if (separator > 0) {
            var owner = methodId.substring(0, separator);
            var name = methodId.substring(separator + 1, descriptor);
            var type = methodId.substring(descriptor);
            for (var moduleName : candidateModules(owner)) {
                var model = instrumentedClass(moduleName, owner);
                if (model.isEmpty()) {
                    continue;
                }
                var ownerClass = model.orElseThrow();
                result = ownerClass.model().methods().stream()
                        .filter(method -> method.methodName().equalsString(name))
                        .filter(method -> method.methodType().equalsString(type))
                        .findFirst()
                        .map(method -> new ResolvedMethod(method, ownerClass));
                if (result.isPresent()) {
                    break;
                }
            }
        }
        instrumentedMethods.put(methodId, result);
        return result;
    }

    private List<String> candidateModules(String internalClassName) {
        int separator = internalClassName.lastIndexOf('/');
        var packageName = separator < 0 ? "" : internalClassName.substring(0, separator).replace('/', '.');
        var owners = instrumentedModules().stream()
                .filter(name -> module(name).descriptor()
                        .packages()
                        .contains(packageName))
                .sorted()
                .toList();
        if (!owners.isEmpty()) {
            return owners;
        }
        return instrumentedModules().stream()
                .filter(name -> !patches.getOrDefault(name, List.of()).isEmpty())
                .sorted()
                .toList();
    }

    private Optional<LoadedClass> instrumentedClass(String moduleName, String internalClassName) throws IOException {
        if (!instrumentedModules().contains(moduleName)) {
            return Optional.empty();
        }
        return classModel(moduleName, internalClassName + ".class");
    }

    private Optional<LoadedClass> classModel(String moduleName, String resource) throws IOException {
        var key = moduleName + "/" + resource;
        var existing = classModels.get(key);
        if (existing != null) {
            return existing;
        }
        Optional<LoadedClass> result;
        var input = openClassResource(moduleName, resource);
        if (input.isEmpty()) {
            result = Optional.empty();
        } else {
            try (var stream = input.orElseThrow()) {
                var content = stream.readAllBytes();
                var loaded = new LoadedClass(
                        moduleName,
                        resource,
                        ClassFile.of().parse(content),
                        Sha256.hashBytes(content));
                loadedClasses.put(loaded.model(), loaded);
                result = Optional.of(loaded);
            }
        }
        classModels.put(key, result);
        return result;
    }

    private Optional<InputStream> openClassResource(String moduleName, String resource) throws IOException {
        for (var patch : patches.getOrDefault(moduleName, List.of())) {
            var input = openPatchResource(patch, resource);
            if (input.isPresent()) {
                return input;
            }
        }
        var directory = moduleDirectories.get(moduleName);
        if (directory != null) {
            try {
                return Optional.of(Files.newInputStream(directory.resolve(resource)));
            } catch (NoSuchFileException _) {
                return Optional.empty();
            }
        }
        var reader = module(moduleName).open();
        try {
            var input = reader.open(resource);
            if (input.isEmpty()) {
                reader.close();
                return Optional.empty();
            }
            return Optional.of(closeWith(input.orElseThrow(), reader));
        } catch (Throwable throwable) {
            try {
                reader.close();
            } catch (Throwable closeFailure) {
                throwable.addSuppressed(closeFailure);
            }
            throw throwable;
        }
    }

    public List<ModuleState> moduleStates() throws IOException {
        if (executionModuleStates != null) {
            return executionModuleStates;
        }
        var result = new ArrayList<ModuleState>();
        for (var entry : modules.entrySet()) {
            result.add(moduleState(entry.getKey()));
        }
        executionModuleStates = List.copyOf(result);
        return executionModuleStates;
    }

    private ModuleState moduleState(String moduleName) throws IOException {
        var existing = moduleStates.get(moduleName);
        if (existing != null) {
            return existing;
        }
        var reference = module(moduleName);
        boolean instrumented = !runtimeModules.contains(moduleName) && isInstrumented(reference);
        var patchHashes = new ArrayList<ModuleHash>();
        for (var patch : patches.getOrDefault(moduleName, List.of())) {
            patchHashes.add(instrumented ? IncrementalModuleHasher.patchSha256(patch) : ModuleHash.patchSha256(patch));
        }
        var state = new ModuleState(moduleName, instrumented ? IncrementalModuleHasher.moduleSha256(reference) : ModuleHash.moduleSha256(reference),
                patchHashes);
        moduleStates.put(moduleName, state);
        return state;
    }

    private ModuleReference module(String name) {
        var reference = modules.get(name);
        if (reference == null) {
            throw new IllegalArgumentException("Module is not in the resolved configuration: " + name);
        }
        return reference;
    }

    private static boolean isInstrumented(ModuleReference reference) {
        return reference.location()
                        .filter(location -> location.getScheme().equals("file"))
                        .map(Path::of)
                        .filter(Files::isDirectory)
                        .isPresent();
    }

    private Map<String, ModuleReference> executionModules(Set<String> roots) {
        var references = new LinkedHashMap<String, ModuleReference>();
        resolution.reachableModules(roots).stream()
                .filter(module -> module.reference()
                                        .location()
                                        .map(location -> !location.getScheme().equals("jrt"))
                                        .orElse(true))
                .forEach(module -> references.put(module.name(), module.reference()));
        return references;
    }

    @SuppressWarnings("restricted")
    private static Map<String, ModuleReference> runtimeAccessModules(Resolution resolution, List<String> arguments) {
        var access = ModuleRuntimeAccess.parseArguments(arguments);
        var names = new LinkedHashSet<String>();
        for (var name : access.enableNativeAccess()) {
            addRuntimeAccessModule(resolution, names, name);
        }
        for (var export : access.addExports()) {
            addRuntimeAccessModules(resolution, names, export.sourceModule(), export.targetModule());
        }
        for (var open : access.addOpens()) {
            addRuntimeAccessModules(resolution, names, open.sourceModule(), open.targetModule());
        }
        return resolution.reachableModules(
                        names,
                        ResolvedClassModels::canDefineForRuntimeAccess,
                        module -> module.reference()
                                        .location()
                                        .map(location -> location.getScheme().equals("jrt"))
                                        .orElse(false))
                .stream()
                .collect(Collectors.toUnmodifiableMap(ResolvedModule::name, ResolvedModule::reference));
    }

    private static void addRuntimeAccessModules(Resolution resolution, Set<String> names, String source,
            String target) {
        if (canDefineForRuntimeAccess(resolution, source) && canDefineForRuntimeAccess(resolution, target)) {
            names.add(source);
            names.add(target);
        }
    }

    private static void addRuntimeAccessModule(Resolution resolution, Set<String> names, String name) {
        if (canDefineForRuntimeAccess(resolution, name)) {
            names.add(name);
        }
    }

    private static boolean canDefineForRuntimeAccess(Resolution resolution, String name) {
        return resolution.findModule(name)
                         .filter(ResolvedClassModels::canDefineForRuntimeAccess)
                         .isPresent();
    }

    private static boolean canDefineForRuntimeAccess(ResolvedModule module) {
        return module.reference()
                     .location()
                     .map(location -> !location.getScheme().equals("jrt") || !module.name().startsWith("java."))
                     .orElse(false);
    }

    private static ModuleFinder finder(Map<String, ModuleReference> references) {
        var content = Map.copyOf(references);
        return new ModuleFinder() {
            @Override
            public Optional<ModuleReference> find(String name) {
                return Optional.ofNullable(content.get(name));
            }

            @Override
            public Set<ModuleReference> findAll() {
                return Set.copyOf(content.values());
            }
        };
    }

    private static void listPatchClasses(Path path, Set<String> resources) throws IOException {
        if (Files.isDirectory(path)) {
            try (var files = Files.walk(path)) {
                files.filter(Files::isRegularFile)
                     .map(
                             file -> path.relativize(file)
                                         .toString()
                                         .replace(file.getFileSystem().getSeparator(), "/"))
                     .filter(ResolvedClassModels::isClass)
                     .forEach(resources::add);
            }
            return;
        }
        try (var jar = new JarFile(path.toFile(), true, ZipFile.OPEN_READ, Runtime.version())) {
            jar.versionedStream()
               .filter(entry -> !entry.isDirectory())
               .map(JarEntry::getName)
               .filter(ResolvedClassModels::isClass)
               .forEach(resources::add);
        }
    }

    private static Optional<InputStream> openPatchResource(Path path, String resource) throws IOException {
        if (Files.isDirectory(path)) {
            var file = path.resolve(resource);
            return Files.isRegularFile(file) ? Optional.of(Files.newInputStream(file)) : Optional.empty();
        }
        var jar = new JarFile(path.toFile(), true, ZipFile.OPEN_READ, Runtime.version());
        try {
            var entry = jar.versionedStream()
                           .filter(candidate -> !candidate.isDirectory())
                           .filter(candidate -> candidate.getName().equals(resource))
                           .findFirst();
            if (entry.isEmpty()) {
                jar.close();
                return Optional.empty();
            }
            return Optional.of(closeWith(jar.getInputStream(entry.orElseThrow()), jar));
        } catch (Throwable throwable) {
            try {
                jar.close();
            } catch (Throwable closeFailure) {
                throwable.addSuppressed(closeFailure);
            }
            throw throwable;
        }
    }

    private static InputStream closeWith(InputStream input, Closeable resource) {
        return new FilterInputStream(input) {
            @Override
            public void close() throws IOException {
                try (resource) {
                    super.close();
                }
            }
        };
    }

    private static boolean isClass(String resource) {
        return resource.endsWith(".class") && !resource.equals("module-info.class");
    }
}
