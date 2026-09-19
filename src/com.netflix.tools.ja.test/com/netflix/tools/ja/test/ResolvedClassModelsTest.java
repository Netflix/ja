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

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.netflix.tools.ja.ExecutionTraceInstrumentation;
import com.netflix.tools.ja.ResolvedClassModels;
import com.netflix.tools.ja.ResolvedClassModels.Entry;
import com.netflix.tools.ja.ResolvedClassModels.ModuleInputs;
import com.netflix.tools.ja.ResolvedClassModels.ModuleState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.lang.constant.ConstantDescs.CD_void;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResolvedClassModelsTest {
    private static final MethodTypeDesc VOID_METHOD = MethodTypeDesc.of(CD_void);

    private record Hit(String method, Object receiver) {}

    @Test
    void includesRootModulesResolvedFromParentConfiguration(@TempDir Path directory) throws Exception {
        var modules = Files.createDirectories(directory.resolve("modules"));
        var module = Files.createDirectories(modules.resolve("example.module"));
        TestModules.writeModuleInfo(module, "example.module");
        writeClass(module, "example.Test");
        var parent = Configuration.resolve(
                ModuleFinder.of(modules),
                List.of(ResolvedClassModelsTest.class
                        .getModule()
                        .getLayer()
                        .configuration()),
                ModuleFinder.of(),
                Set.of("example.module"));

        var classes = resolve(parent, List.of("example.module"), List.of("--module-path", modules.toString()));

        assertEquals(Set.of("example.module"), classes.modules());
        assertEquals(List.of("example/Test.class"),
                classes.classes("example.module").stream()
                        .map(Entry::resource)
                        .toList());
    }

    @Test
    void retainsExplicitModulePathRootsAsFixedPoints(@TempDir Path directory) throws Exception {
        var parentModules = Files.createDirectories(directory.resolve("parent-modules"));
        var parentModule = Files.createDirectories(parentModules.resolve("example.module"));
        TestModules.writeModuleInfo(parentModule, "example.module");
        writeClass(parentModule, "parent.Parent");
        var parent = Configuration.resolve(
                ModuleFinder.of(parentModules),
                List.of(ResolvedClassModelsTest.class
                        .getModule()
                        .getLayer()
                        .configuration()),
                ModuleFinder.of(),
                Set.of("example.module"));

        var applicationModules = Files.createDirectories(directory.resolve("application-modules"));
        var applicationModule = Files.createDirectories(applicationModules.resolve("example.module"));
        TestModules.writeModuleInfo(applicationModule, "example.module");
        writeClass(applicationModule, "application.Application");

        var classes = resolve(parent, List.of("example.module"), List.of("--module-path", applicationModules.toString()));

        assertEquals(List.of("application/Application.class"),
                classes.classes("example.module").stream()
                        .map(Entry::resource)
                        .toList());
    }

    @Test
    void sourceModulesTakePrecedenceOverLinkedParentModules(@TempDir Path directory) throws Exception {
        var requirements = Map.of(
                "com.netflix.tools.cli", List.<String>of(),
                "com.netflix.tools.cli.test", List.of("com.netflix.tools.cli"),
                "com.netflix.tools.ja", List.of("com.netflix.tools.cli"),
                "com.netflix.tools.ja.test", List.of("com.netflix.tools.ja", "com.netflix.tools.cli"),
                "com.netflix.tools.jmh", List.of("com.netflix.tools.cli"),
                "com.netflix.tools.launcher", List.of("com.netflix.tools.cli"),
                "com.netflix.tools.launcher.test", List.of("com.netflix.tools.launcher"));
        var modules = Files.createDirectories(directory.resolve("modules"));
        var sourceClasses = new LinkedHashMap<String, String>();
        int index = 0;
        for (var entry : requirements.entrySet()) {
            var module = Files.createDirectories(modules.resolve(entry.getKey()));
            TestModules.writeModuleInfo(module, entry.getKey(), entry.getValue().toArray(String[]::new));
            var className = "source.Module" + index++;
            writeClass(module, className);
            sourceClasses.put(entry.getKey(), className.replace('.', '/') + ".class");
        }

        var classes = resolve(ResolvedClassModelsTest.class.getModule().getLayer().configuration(),
                List.copyOf(requirements.keySet()), List.of("--module-path", modules.toString()));

        for (var entry : sourceClasses.entrySet()) {
            assertEquals(List.of(entry.getValue()),
                    classes.classes(entry.getKey()).stream()
                            .map(Entry::resource)
                            .toList());
        }
    }

    @Test
    void definesResolvedModulePathDependenciesInTheChildLayer(@TempDir Path directory) throws Exception {
        var parentModules = Files.createDirectories(directory.resolve("parent-modules"));
        TestModules.writeJar(parentModules.resolve("example.library.jar"), "example.library");
        var parentConfiguration = Configuration.resolve(
                ModuleFinder.of(parentModules),
                List.of(ResolvedClassModelsTest.class
                        .getModule()
                        .getLayer()
                        .configuration()),
                ModuleFinder.of(),
                Set.of("example.library"));
        var parentLayer = ModuleLayer.defineModulesWithOneLoader(parentConfiguration, List.of(ResolvedClassModelsTest.class.getModule().getLayer()),
                ClassLoader.getSystemClassLoader())
                .layer();
        var modules = Files.createDirectories(directory.resolve("modules"));
        var library = Files.createDirectories(modules.resolve("example.library"));
        TestModules.writeModuleInfo(library, "example.library");
        var root = Files.createDirectories(modules.resolve("example.root"));
        TestModules.writeModuleInfo(root, "example.root", "example.library");
        writeClass(root, "root.Root");
        var classes = resolve(parentConfiguration, List.of("example.root"), List.of("--module-path", modules.toString(), "--add-modules", "example.library"));

        var layer = classes.instrumentedLayer(parentLayer, Set.of(), List.of()).layer();
        var resolvedLibrary = layer.configuration()
                .findModule("example.library")
                .orElseThrow();

        assertSame(layer,
                layer.findModule("example.root")
                     .orElseThrow()
                     .getLayer());
        assertSame(layer,
                layer.findModule("example.library")
                     .orElseThrow()
                     .getLayer());
        assertEquals(library.toUri(),
                resolvedLibrary.reference()
                               .location()
                               .orElseThrow());
    }

    @Test
    void prefersRuntimeAccessModulesFromTheModulePathOverTheParentConfiguration(@TempDir Path directory) throws Exception {
        var parentModules = Files.createDirectories(directory.resolve("parent-modules"));
        var parentTool = TestModules.writeJar(parentModules.resolve("example.tool.jar"), "example.tool");
        var parentConfiguration = Configuration.resolve(
                ModuleFinder.of(parentModules),
                List.of(ResolvedClassModelsTest.class.getModule().getLayer().configuration()),
                ModuleFinder.of(),
                Set.of("example.tool"));
        var parentLayer = ModuleLayer.defineModulesWithOneLoader(
                parentConfiguration,
                List.of(ResolvedClassModelsTest.class.getModule().getLayer()),
                ClassLoader.getSystemClassLoader()).layer();
        var applicationModules = Files.createDirectories(directory.resolve("application-modules"));
        var root = Files.createDirectories(applicationModules.resolve("example.root"));
        TestModules.writeModuleInfo(root, "example.root");
        writeClass(root, "root.Root");
        var runtimeModules = Files.createDirectories(directory.resolve("runtime-modules"));
        var runtimeTool = Files.createDirectories(runtimeModules.resolve("example.tool"));
        TestModules.writeModuleInfo(runtimeTool, "example.tool");
        var runtimeArguments = List.of(
                "--module-path", runtimeModules.toString(),
                "--add-modules", "example.tool",
                "--add-exports", "jdk.compiler/com.sun.source.util=example.tool",
                "--add-exports", "jdk.javadoc/jdk.javadoc.internal.tool=example.tool");
        var classes = ResolvedClassModels.resolve(
                parentConfiguration,
                new ModuleInputs(Set.of("example.root"), List.of("--module-path", applicationModules.toString())),
                new ModuleInputs(Set.of(), runtimeArguments),
                "test-runtime");

        var layer = classes.instrumentedLayer(parentLayer, Set.of(), List.of()).layer();
        var resolvedTool = layer.configuration().findModule("example.tool").orElseThrow();

        assertNotEquals(parentTool.toUri(), resolvedTool.reference().location().orElseThrow());
        assertEquals(runtimeTool.toUri(), resolvedTool.reference().location().orElseThrow());
        assertSame(layer, layer.findModule("jdk.compiler").orElseThrow().getLayer());
        assertSame(layer, layer.findModule("jdk.internal.md").orElseThrow().getLayer());
        assertSame(layer, layer.findModule("jdk.internal.opt").orElseThrow().getLayer());
        assertSame(layer, layer.findModule("jdk.javadoc").orElseThrow().getLayer());
        assertSame(layer, layer.findModule("jdk.zipfs").orElseThrow().getLayer());
    }

    @Test
    void definesAnInstrumentedModuleAndItsParentDependentsInTheChildLayer(@TempDir Path directory) throws Exception {
        var parentModules = Files.createDirectories(directory.resolve("parent-modules"));
        TestModules.writeJar(parentModules.resolve("example.library.jar"), "example.library");
        TestModules.writeJarWithTransitive(parentModules.resolve("example.bridge.jar"), "example.bridge", "example.library");
        var parentConfiguration = Configuration.resolve(
                ModuleFinder.of(parentModules),
                List.of(ResolvedClassModelsTest.class
                        .getModule()
                        .getLayer()
                        .configuration()),
                ModuleFinder.of(),
                Set.of("example.bridge"));
        var parentLayer = ModuleLayer.defineModulesWithOneLoader(parentConfiguration, List.of(ResolvedClassModelsTest.class.getModule().getLayer()),
                ClassLoader.getSystemClassLoader())
                .layer();
        var modules = Files.createDirectories(directory.resolve("modules"));
        var library = Files.createDirectories(modules.resolve("example.library"));
        TestModules.writeModuleInfo(library, "example.library");
        writeClass(library, "library.Library");
        var root = Files.createDirectories(modules.resolve("example.root"));
        TestModules.writeModuleInfo(root, "example.root", "example.bridge");
        writeClass(root, "root.Root");
        var classes = resolve(parentConfiguration, List.of("example.root"),
                List.of(
                "--module-path", modules.toString(),
                "--module-path", parentModules.toString()));

        assertEquals(List.of("library/Library.class"),
                classes.classes("example.library").stream()
                        .map(Entry::resource)
                        .toList());

        var layer = classes.instrumentedLayer(parentLayer, Set.of(), List.of()).layer();
        var resolvedLibrary = layer.configuration()
                .findModule("example.library")
                .orElseThrow();

        assertSame(layer,
                layer.findModule("example.root")
                     .orElseThrow()
                     .getLayer());
        assertSame(layer,
                layer.findModule("example.library")
                     .orElseThrow()
                     .getLayer());
        assertSame(layer,
                layer.findModule("example.bridge")
                     .orElseThrow()
                     .getLayer());
        assertEquals(library.toUri(),
                resolvedLibrary.reference()
                               .location()
                               .orElseThrow());
    }

    @Test
    void definesDependenciesResolvedFromTheApplicationLayerInTheTestLayer(@TempDir Path directory) throws Exception {
        var parentModules = Files.createDirectories(directory.resolve("parent-modules"));
        TestModules.writeJar(parentModules.resolve("example.library.jar"), "example.library");
        var parentConfiguration = Configuration.resolve(
                ModuleFinder.of(parentModules),
                List.of(ResolvedClassModelsTest.class
                        .getModule()
                        .getLayer()
                        .configuration()),
                ModuleFinder.of(),
                Set.of("example.library"));
        var parentLayer = ModuleLayer.defineModulesWithOneLoader(parentConfiguration, List.of(ResolvedClassModelsTest.class.getModule().getLayer()),
                ClassLoader.getSystemClassLoader())
                .layer();
        var modules = Files.createDirectories(directory.resolve("modules"));
        var root = Files.createDirectories(modules.resolve("example.root"));
        TestModules.writeModuleInfo(root, "example.root", "example.library");
        writeClass(root, "root.Root");
        var classes = resolve(parentConfiguration, List.of("example.root"),
                List.of(
                "--module-path", modules.toString(),
                "--module-path", parentModules.toString()));

        var layer = classes.instrumentedLayer(parentLayer, Set.of(), List.of()).layer();

        assertSame(layer,
                layer.findModule("example.library")
                     .orElseThrow()
                     .getLayer());
    }

    @Test
    void definesAutomaticDependenciesInTheTestLayer(@TempDir Path directory) throws Exception {
        var moduleName = "example.resource.tests";
        var module = Files.createDirectories(directory.resolve(moduleName));
        TestModules.writeModuleInfo(module, moduleName, "example.dependency");
        var dependency = TestModules.writeAutomaticJar(directory.resolve("example.dependency.jar"));
        try (var fileSystem = FileSystems.newFileSystem(dependency)) {
            var resource = fileSystem.getPath("META-INF/example.resource");
            Files.createDirectories(resource.getParent());
            Files.writeString(resource, "content");
        }
        var currentLayer = ResolvedClassModelsTest.class.getModule().getLayer();
        var infrastructureLayer = currentLayer.parents().stream()
                .filter(candidate -> candidate.configuration()
                        .findModule("java.base")
                        .isPresent())
                .findFirst()
                .orElse(currentLayer);
        var parentConfiguration = Configuration.resolve(ModuleFinder.of(directory), List.of(infrastructureLayer.configuration()), ModuleFinder.of(),
                Set.of(moduleName));
        var parent = ModuleLayer.defineModulesWithOneLoader(parentConfiguration, List.of(infrastructureLayer), ClassLoader.getSystemClassLoader()).layer();
        var classes = resolve(parent.configuration(), List.of(moduleName), List.of("--module-path", directory.toString()));

        var layer = classes.instrumentedLayer(parent, Set.of(), List.of()).layer();

        assertSame(layer,
                layer.findModule("example.dependency")
                     .orElseThrow()
                     .getLayer());
        assertEquals(1,
                layer.findLoader(moduleName)
                     .resources("META-INF/example.resource")
                     .count());
    }

    @Test
    void retainsRuntimeModulesAsOpaqueInputs(@TempDir Path directory) throws Exception {
        var applicationModules = Files.createDirectories(directory.resolve("application-modules"));
        var runtimeModules = Files.createDirectories(directory.resolve("runtime-modules"));
        var runtime = Files.createDirectories(runtimeModules.resolve("example.runtime"));
        TestModules.writeModuleInfo(runtime, "example.runtime");
        writeClass(runtime, "runtime.Runtime");
        var root = Files.createDirectories(applicationModules.resolve("example.root"));
        TestModules.writeModuleInfo(root, "example.root");
        writeClass(root, "root.Root");

        var first = ResolvedClassModels.resolve(
                ResolvedClassModelsTest.class
                        .getModule()
                        .getLayer()
                        .configuration(),
                new ModuleInputs(Set.of("example.root"), List.of("--module-path", applicationModules.toString())),
                new ModuleInputs(Set.of("example.runtime"), List.of("--module-path", runtimeModules.toString())),
                "test-runtime");
        var firstRuntimeHash = first.moduleStates().stream()
                .filter(state -> state.moduleName().equals("example.runtime"))
                .findFirst()
                .orElseThrow()
                .moduleHash();

        writeChangedClass(runtime, "runtime.Runtime");
        var second = ResolvedClassModels.resolve(
                ResolvedClassModelsTest.class
                        .getModule()
                        .getLayer()
                        .configuration(),
                new ModuleInputs(Set.of("example.root"), List.of("--module-path", applicationModules.toString())),
                new ModuleInputs(Set.of("example.runtime"), List.of("--module-path", runtimeModules.toString())),
                "test-runtime");
        var secondRuntimeHash = second.moduleStates().stream()
                .filter(state -> state.moduleName().equals("example.runtime"))
                .findFirst()
                .orElseThrow()
                .moduleHash();

        assertEquals(Set.of("example.root"), first.modules());
        assertEquals(Set.of("example.root", "example.runtime"),
                first.moduleStates().stream()
                        .map(ModuleState::moduleName)
                        .collect(Collectors.toSet()));
        assertNotEquals(firstRuntimeHash, secondRuntimeHash);
    }

    @Test
    void resolvesStaticJUnitDependenciesWithTheTestRuntime(@TempDir Path directory) throws Exception {
        var applicationModules = Files.createDirectories(directory.resolve("application-modules"));
        var runtimeModules = Files.createDirectories(directory.resolve("runtime-modules"));
        var junit = Files.createDirectories(runtimeModules.resolve("org.junit.example"));
        TestModules.writeModuleInfo(junit, "org.junit.example");
        var launcher = Files.createDirectories(runtimeModules.resolve("example.launcher"));
        TestModules.writeModuleInfo(launcher, "example.launcher");
        var root = Files.createDirectories(applicationModules.resolve("example.root"));
        TestModules.writeModuleInfo(root, "example.root", Set.of("org.junit.example"));
        writeClass(root, "root.Root");

        var classes = ResolvedClassModels.resolve(
                ResolvedClassModelsTest.class
                        .getModule()
                        .getLayer()
                        .configuration(),
                new ModuleInputs(Set.of("example.root"), List.of("--module-path", applicationModules.toString())),
                new ModuleInputs(Set.of("example.launcher"), List.of("--module-path", runtimeModules.toString())),
                "test-runtime");
        var layer = classes.instrumentedLayer(ResolvedClassModelsTest.class.getModule().getLayer(),
                Set.of("example.launcher"), List.of())
                           .layer();

        var rootModule = layer.findModule("example.root").orElseThrow();
        var junitModule = layer.findModule("org.junit.example").orElseThrow();
        assertTrue(rootModule.canRead(junitModule));
    }

    @Test
    void doesNotReadUnusedClassesWhenCreatingInstrumentedLayer(@TempDir Path directory) throws Exception {
        var modules = Files.createDirectories(directory.resolve("modules"));
        var dependency = Files.createDirectories(modules.resolve("example.dependency"));
        TestModules.writeModuleInfo(dependency, "example.dependency");
        var unusedClass = dependency.resolve("unused/Broken.class");
        Files.createDirectories(unusedClass.getParent());
        Files.write(unusedClass, new byte[] {0});
        var root = Files.createDirectories(modules.resolve("example.root"));
        TestModules.writeModuleInfo(root, "example.root", "example.dependency");
        writeClass(root, "root.Root");
        var classes = resolve(ResolvedClassModelsTest.class
                .getModule()
                .getLayer()
                .configuration(),
                List.of("example.root"), List.of("--module-path", modules.toString()));

        var layer = classes.instrumentedLayer(ResolvedClassModelsTest.class.getModule().getLayer(),
                Set.of(), List.of())
                           .layer();

        assertTrue(layer.findModule("example.root").isPresent());
        assertTrue(layer.findModule("example.dependency").isPresent());
    }

    @Test
    void instrumentsDirectoryDependenciesAsWellAsRoots(@TempDir Path directory) throws Exception {
        var modules = Files.createDirectories(directory.resolve("modules"));
        var dependency = Files.createDirectories(modules.resolve("example.dependency"));
        TestModules.writeModuleInfo(dependency, "example.dependency");
        writeClass(dependency, "dependency.Dependency");
        var root = Files.createDirectories(modules.resolve("example.root"));
        TestModules.writeModuleInfo(root, "example.root", "example.dependency");
        writeClass(root, "root.Root");
        var classes = resolve(ResolvedClassModelsTest.class
                .getModule()
                .getLayer()
                .configuration(),
                List.of("example.root"), List.of("--module-path", modules.toString()));
        var controller = classes.instrumentedLayer(ResolvedClassModelsTest.class.getModule().getLayer(),
                Set.of(), List.of());
        var layer = controller.layer();
        var dependencyModule = layer.findModule("example.dependency").orElseThrow();
        controller.addExports(dependencyModule, "dependency", ResolvedClassModelsTest.class.getModule());
        var type = layer.findLoader("example.dependency").loadClass("dependency.Dependency");
        var hits = new ArrayList<Hit>();

        try (var recording = ExecutionTraceInstrumentation.recordWith((method, receiver) -> hits.add(new Hit(method, receiver)))) {
            type.getMethod("test").invoke(null);
        }

        assertEquals("dependency/Dependency.test()V", hits.getFirst()
                .method());
        assertSame(type, hits.getFirst()
                             .receiver());
    }

    private static ResolvedClassModels resolve(Configuration parent, List<String> roots, List<String> arguments) {
        return ResolvedClassModels.resolve(
                parent,
                new ModuleInputs(Set.copyOf(roots), arguments),
                new ModuleInputs(Set.of(), List.of()),
                "test-runtime");
    }

    private static void writeClass(Path root, String name) throws Exception {
        writeClass(root, name, false);
    }

    private static void writeChangedClass(Path root, String name) throws Exception {
        writeClass(root, name, true);
    }

    private static void writeClass(Path root, String name, boolean changed) throws Exception {
        var path = root.resolve(name.replace('.', '/') + ".class");
        Files.createDirectories(path.getParent());
        Files.write(path,
                ClassFile.of().build(ClassDesc.of(name),
                        builder -> {
                            builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
                            builder.withMethodBody("test", VOID_METHOD, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                                    code -> {
                                        if (changed) {
                                            code.iconst_0().pop();
                                        }
                                        code.return_();
                                    });
                        }));
    }
}
