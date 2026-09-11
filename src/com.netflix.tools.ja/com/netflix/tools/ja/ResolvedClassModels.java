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

import java.io.IOException;
import java.lang.ModuleLayer.Controller;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.constant.ClassDesc;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.lang.reflect.AccessFlag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

import com.netflix.module.ModuleHash;
import com.netflix.module.ModuleRuntimeAccess;
import com.netflix.tools.ja.ExecutionTrace.Event;
import com.netflix.tools.ja.TestDiscovery.TestMethod;

/** Application class files and module state from a resolved configuration. */
public final class ResolvedClassModels {
    public record Entry(String moduleName, String resource, ClassModel model) {}

    public record ModuleInputs(Set<String> roots, List<String> arguments) {
        public ModuleInputs {
            roots = Set.copyOf(roots);
            arguments = List.copyOf(arguments);
        }
    }

    private record Resolution(Configuration configuration, Set<String> pathModules) {}

    private record LayerFoundation(ModuleLayer layer, boolean complete) {}

    public record ModuleState(String moduleName, ModuleHash moduleHash, List<ModuleHash> patchHashes) {
        public ModuleState {
            patchHashes = List.copyOf(patchHashes);
        }
    }

    private final Configuration configuration;
    private final Map<String, ModuleReference> modules;
    private final Map<String, List<Path>> patches;
    private final Set<String> roots;
    private final Set<String> runtimeModules;
    private final Map<String, ModuleReference> runtimeAccessModules;
    private final String runtimeImageHash;
    private final Map<String, List<Entry>> classes = new LinkedHashMap<>();
    private final Map<String, ModuleState> moduleStates = new LinkedHashMap<>();
    private final ObservedCodeHash codeHash = new ObservedCodeHash();
    private Map<String, MethodModel> instrumentedMethods;
    private Map<String, ClassModel> instrumentedClasses;
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
        var runtimeModules = Configurations.reachableModules(runtime.configuration(), effectiveRuntimeInputs.roots()).stream()
                .map(module -> module.name())
                .collect(Collectors.toUnmodifiableSet());
        var modules = new LinkedHashMap<String, ModuleReference>();
        Configurations.reachableModules(application.configuration(), applicationInputs.roots()).stream()
                .filter(module -> application.pathModules().contains(module.name()))
                .sorted(Comparator.comparing(module -> module.name()))
                .forEach(module -> modules.put(module.name(), module.reference()));
        Configurations.reachableModules(runtime.configuration(), effectiveRuntimeInputs.roots()).stream()
                .filter(module -> application.pathModules().contains(module.name()) || runtime.pathModules().contains(module.name()))
                .sorted(Comparator.comparing(module -> module.name()))
                .forEach(module -> modules.putIfAbsent(module.name(), module.reference()));
        var layerArguments = new ArrayList<>(applicationInputs.arguments());
        layerArguments.addAll(runtimeInputs.arguments());
        return new ResolvedClassModels(runtime.configuration(), modules, ToolArguments.patchModules(applicationInputs.arguments()),
                applicationInputs.roots(), runtimeModules, runtimeAccessModules(runtime.configuration(), layerArguments), runtimeImageHash);
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
        var configuration = Configurations.resolve(parent, arguments, beforeModules);
        var paths = ToolArguments.applicationModulePath(arguments);
        var pathModules = ModuleFinder.of(paths.toArray(Path[]::new)).findAll().stream()
                .map(reference -> reference.descriptor().name())
                .collect(Collectors.toCollection(TreeSet::new));
        return new Resolution(configuration, Set.copyOf(pathModules));
    }

    private ResolvedClassModels(Configuration configuration, Map<String, ModuleReference> modules, Map<String, List<Path>> patches,
            Set<String> roots, Set<String> runtimeModules, Map<String, ModuleReference> runtimeAccessModules, String runtimeImageHash) {
        this.configuration = configuration;
        this.modules = Map.copyOf(modules);
        this.patches = Map.copyOf(patches);
        this.roots = Set.copyOf(roots);
        this.runtimeModules = Set.copyOf(runtimeModules);
        this.runtimeAccessModules = Map.copyOf(runtimeAccessModules);
        this.runtimeImageHash = runtimeImageHash;
    }

    public Set<String> modules() {
        return modules.keySet().stream()
                .filter(module -> !runtimeModules.contains(module))
                .collect(Collectors.toUnmodifiableSet());
    }

    boolean isDirectoryModule(String moduleName) {
        return isInstrumented(module(moduleName));
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
        var reference = module(moduleName);
        var content = new LinkedHashMap<String, byte[]>();
        for (var patch : patches.getOrDefault(moduleName, List.of())) {
            readPatch(patch, content);
        }
        try (var reader = reference.open();
             var resources = reader.list()) {
            for (var resource : resources.filter(ResolvedClassModels::isClass)
                    .sorted()
                    .toList()) {
                if (content.containsKey(resource)) {
                    continue;
                }
                var input = reader.open(resource);
                if (input.isPresent()) {
                    try (var stream = input.orElseThrow()) {
                        content.put(resource, stream.readAllBytes());
                    }
                }
            }
        }
        var result = content.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(
                        entry -> new Entry(moduleName, entry.getKey(),
                                ClassFile.of().parse(entry.getValue())))
                .toList();
        classes.put(moduleName, result);
        return result;
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
        indexInstrumentedClasses();
        var executedMethods = new ArrayList<MethodModel>();
        executedMethods.add(test.model());
        var receiverClasses = new ArrayList<ClassModel>();
        receiverClasses.add(test.model()
                                .parent()
                                .orElseThrow());
        for (var event : events) {
            var method = instrumentedMethods.get(event.method());
            if (method == null) {
                return Optional.empty();
            }
            executedMethods.add(method);
            if (event.receiverModule() != null && event.receiverClass() != null) {
                var receiver = instrumentedClasses.get(event.receiverModule() + "/" + event.receiverClass().replace('.', '/'));
                if (receiver == null) {
                    return Optional.empty();
                }
                receiverClasses.add(receiver);
            }
        }
        return Optional.of(new TestExecution(test.selector(), codeHash.hash(executedMethods, receiverClasses,
                instrumentedClassHierarchy()), moduleStates(), runtimeImageHash, events));
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
        var configuration = Configuration.resolve(
                instrumentedModules(executionRoots, supportModules, foundation.complete()),
                List.of(foundation.layer().configuration()),
                finder(modules),
                layerRoots);
        return ModuleLayer.defineModulesWithOneLoader(configuration, List.of(foundation.layer()), ClassLoader.getSystemClassLoader());
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
        return modules.entrySet().stream()
                .filter(entry -> !runtimeModules.contains(entry.getKey()))
                .filter(entry -> isInstrumented(entry.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    private ClassHierarchyResolver instrumentedClassHierarchy() throws IOException {
        indexInstrumentedClasses();
        var interfaces = new LinkedHashSet<ClassDesc>();
        var superclasses = new LinkedHashMap<ClassDesc, ClassDesc>();
        for (var model : instrumentedClasses.values()) {
            var type = model.thisClass().asSymbol();
            if (model.flags().has(AccessFlag.INTERFACE)) {
                interfaces.add(type);
            } else {
                model.superclass().ifPresent(superclass -> superclasses.put(type, superclass.asSymbol()));
            }
        }
        return ClassHierarchyResolver.of(interfaces, superclasses)
                .orElse(ClassHierarchyResolver.defaultResolver());
    }

    private void indexInstrumentedClasses() throws IOException {
        if (instrumentedMethods != null) {
            return;
        }
        var methods = new LinkedHashMap<String, MethodModel>();
        var classModels = new LinkedHashMap<String, ClassModel>();
        for (var name : instrumentedModules()) {
            for (var entry : classes(name)) {
                var model = entry.model();
                classModels.put(name + "/" + model.thisClass().asInternalName(), model);
                for (var method : model.methods()) {
                    var methodId = ExecutionTraceInstrumentation.methodId(method);
                    if (methods.put(methodId, method) != null) {
                        throw new IllegalArgumentException("Duplicate method: " + methodId);
                    }
                }
            }
        }
        instrumentedMethods = Map.copyOf(methods);
        instrumentedClasses = Map.copyOf(classModels);
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
        var visited = new LinkedHashSet<String>();
        var remaining = new ArrayDeque<>(roots);
        while (!remaining.isEmpty()) {
            var name = remaining.removeFirst();
            if (!visited.add(name)) {
                continue;
            }
            var module = configuration.findModule(name).orElseThrow(() -> new IllegalArgumentException("Module is not in the resolved configuration: " + name));
            if (module.reference()
                      .location()
                      .map(location -> location.getScheme().equals("jrt"))
                      .orElse(false)) {
                continue;
            }
            references.put(name, module.reference());
            module.reads().stream()
                    .map(read -> read.name())
                    .forEach(remaining::addLast);
        }
        return references;
    }

    @SuppressWarnings("restricted")
    private static Map<String, ModuleReference> runtimeAccessModules(Configuration configuration, List<String> arguments) {
        var access = ModuleRuntimeAccess.parseArguments(arguments);
        var names = new LinkedHashSet<String>();
        for (var name : access.enableNativeAccess()) {
            addRuntimeAccessModule(configuration, names, name);
        }
        for (var export : access.addExports()) {
            addRuntimeAccessModules(configuration, names, export.sourceModule(), export.targetModule());
        }
        for (var open : access.addOpens()) {
            addRuntimeAccessModules(configuration, names, open.sourceModule(), open.targetModule());
        }
        var references = new LinkedHashMap<String, ModuleReference>();
        for (var name : names) {
            configuration.findModule(name)
                    .map(module -> module.reference())
                    .ifPresent(reference -> references.put(name, reference));
        }
        return Map.copyOf(references);
    }

    private static void addRuntimeAccessModules(Configuration configuration, Set<String> names, String source, String target) {
        if (canDefineForRuntimeAccess(configuration, source) && canDefineForRuntimeAccess(configuration, target)) {
            names.add(source);
            names.add(target);
        }
    }

    private static void addRuntimeAccessModule(Configuration configuration, Set<String> names, String name) {
        if (canDefineForRuntimeAccess(configuration, name)) {
            names.add(name);
        }
    }

    private static boolean canDefineForRuntimeAccess(Configuration configuration, String name) {
        return configuration.findModule(name)
                .map(module -> module.reference())
                .flatMap(ModuleReference::location)
                .map(location -> !location.getScheme().equals("jrt") || !name.startsWith("java."))
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

    private static void readPatch(Path path, Map<String, byte[]> content) throws IOException {
        if (Files.isDirectory(path)) {
            try (var files = Files.walk(path)) {
                for (var file : files.filter(Files::isRegularFile)
                                     .sorted()
                                     .toList()) {
                    var resource = path.relativize(file)
                                       .toString()
                                       .replace(file.getFileSystem()
                                                    .getSeparator(),
                                               "/");
                    if (isClass(resource)) {
                        content.putIfAbsent(resource, Files.readAllBytes(file));
                    }
                }
            }
            return;
        }
        try (var jar = new JarFile(path.toFile(), true, ZipFile.OPEN_READ, Runtime.version())) {
            var entries = jar.versionedStream()
                             .filter(entry -> !entry.isDirectory())
                             .filter(entry -> isClass(entry.getName()))
                             .sorted(Comparator.comparing(JarEntry::getName))
                             .toList();
            for (var entry : entries) {
                try (var stream = jar.getInputStream(entry)) {
                    content.putIfAbsent(entry.getName(), stream.readAllBytes());
                }
            }
        }
    }

    private static boolean isClass(String resource) {
        return resource.endsWith(".class") && !resource.equals("module-info.class");
    }
}
