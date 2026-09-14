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
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.jar.Attributes.Name;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.spi.ToolProvider;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

import com.netflix.module.ModuleRuntimeAccess;
import com.netflix.tools.ja.CommandRunner;
import com.netflix.tools.ja.JaInvocation;
import com.netflix.tools.ja.ToolCatalog;
import com.netflix.tools.ja.ToolDefinition;
import com.netflix.tools.ja.ToolDefinition.Launch;
import com.netflix.tools.ja.ToolExecutionException;
import com.netflix.tools.ja.ToolServices;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssembleTest {
    private static final String COMPLETE_RUNTIME_ACCESS_OPTIONS = "add-exports,add-modules,add-opens,enable-final-field-mutation,enable-native-access,enable-preview," + "module-path,upgrade-module-path";

    @Test
    void packagedRuntimeAccessRequirementsMustBeAuthorized(@TempDir Path directory) throws Exception {
        Path library = sourceModule(directory, "com.example.library");
        Files.writeString(library.resolve("module-info.java"),
                """
                /** @enableNativeAccess com.example.library */
                module com.example.library {}
                """);

        Result result = run(directory, "assemble", "--module-version", "1.0", "artifacts");

        assertEquals(0, result.exitCode(), result.error());
        Path libraryJar = artifacts(directory).resolve("com.example.library.jar");
        Path application = Files.createDirectories(directory.resolve("modules/com.example.application"));
        TestModules.writeModuleInfo(application, "com.example.application", "com.example.library");
        String modulePath = application + System.getProperty("path.separator") + libraryJar;

        var failure = assertThrows(IllegalArgumentException.class, () -> ModuleRuntimeAccess.checkLaunch(List.of("--module-path", modulePath, "--module", "com.example.application/com.example.Main")));
        assertTrue(failure.getMessage().contains("--enable-native-access=com.example.library"),
                failure.getMessage());
    }

    @Test
    void includesModuleDeploymentMetadata(@TempDir Path directory) throws Exception {
        String moduleName = "com.example.library";
        Path module = sourceModule(directory, moduleName);
        String metadata = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <name>Example library</name>
                </project>
                """;
        Path deploymentPom = module.resolve("META-INF/com.netflix.tools.ja/maven/deploy.pom");
        Files.createDirectories(deploymentPom.getParent());
        Files.writeString(deploymentPom, metadata);
        Files.writeString(module.resolve("module-info.pom"), "Maven export intermediary");

        Result result = run(directory, "assemble", "--module-version", "1.0", "artifacts");

        assertEquals(0, result.exitCode(), result.error());
        Path output = artifacts(directory);
        assertEquals(metadata, Files.readString(output.resolve(moduleName + ".pom")));
        try (var binary = new ZipFile(output.resolve(moduleName + ".jar").toFile());
             var sources = new ZipFile(output.resolve(moduleName + "-sources.jar").toFile())) {
            assertEquals(metadata, new String(binary.getInputStream(binary.getEntry(
                    "META-INF/com.netflix.tools.ja/maven/deploy.pom")).readAllBytes(), StandardCharsets.UTF_8));
            assertEquals(metadata, new String(sources.getInputStream(sources.getEntry(
                    "META-INF/com.netflix.tools.ja/maven/deploy.pom")).readAllBytes(), StandardCharsets.UTF_8));
            assertNull(binary.getEntry("module-info.pom"));
            assertNull(sources.getEntry("module-info.pom"));
        }
    }

    @Test
    void assemblesEveryModuleOnTheSourcePath(@TempDir Path directory) throws Exception {
        sourceModule(directory, "com.example.api");
        Path runtime = sourceModule(directory, "com.example.runtime");
        Files.writeString(runtime.resolve("module-info.java"),
                """
                /**
                 * @mainClass com.example.Main
                 * @enableNativeAccess com.example.runtime
                 * @addOpens java.base/java.lang=com.example.runtime
                 */
                module com.example.runtime {}
                """);
        Path resources = runtime.resolve("com/example");
        Files.createDirectories(resources);
        Files.writeString(resources.resolve("Main.java"),
                """
                package com.example;
                public final class Main {
                    public static void main(String[] arguments) {}
                }
                """);
        Files.writeString(resources.resolve("messages.properties"), "message=hello\n");

        Result result = run(directory, "assemble", "--module-version", "1.2.3", "artifacts");

        assertEquals(0, result.exitCode(), result.error());
        Path apiVersion = artifacts(directory);
        Path runtimeVersion = artifacts(directory);
        assertTrue(Files.isRegularFile(apiVersion.resolve("com.example.api.jar")));
        assertTrue(Files.isRegularFile(apiVersion.resolve("com.example.api-sources.jar")));
        assertTrue(Files.isRegularFile(apiVersion.resolve("com.example.api-javadoc.jar")));
        Path runtimeJavadoc = runtimeVersion.resolve("com.example.runtime-javadoc.jar");
        assertTrue(Files.isRegularFile(runtimeJavadoc));
        try (var zip = new ZipFile(runtimeJavadoc.toFile())) {
            assertTrue(zip.getEntry("index.html") != null);
        }
        Path runtimeSources = runtimeVersion.resolve("com.example.runtime-sources.jar");
        try (var zip = new ZipFile(runtimeSources.toFile())) {
            assertTrue(zip.getEntry("module-info.java") != null);
            assertTrue(zip.getEntry("module-info.hash") != null);
            assertEquals("message=hello\n", new String(zip.getInputStream(zip.getEntry("com/example/messages.properties"))
                    .readAllBytes(),
                            StandardCharsets.UTF_8));
        }
        Path runtimeJar = runtimeVersion.resolve("com.example.runtime.jar");
        assertTrue(Files.isRegularFile(runtimeJar));
        var descriptor = ModuleFinder.of(runtimeJar)
                .find("com.example.runtime")
                .orElseThrow()
                .descriptor();
        assertEquals("com.example.Main", descriptor.mainClass()
                .orElseThrow());
        assertEquals("1.2.3", descriptor.rawVersion()
                .orElseThrow());
        try (var zip = new ZipFile(runtimeJar.toFile())) {
            assertEquals("message=hello\n", new String(zip.getInputStream(zip.getEntry("com/example/messages.properties"))
                    .readAllBytes(),
                            StandardCharsets.UTF_8));
        }
    }

    @Test
    void excludesCompileOnlyToolContentAndRetainsRuntimeToolContent(@TempDir Path directory) throws Exception {
        sourceModule(directory, "com.example.application");
        sourceModule(directory, "com.example.application.tests");
        Path activationPath = Files.createDirectories(directory.resolve("activation"));
        TestModules.writeModuleInfo(Files.createDirectories(activationPath.resolve("com.example.testing")), "com.example.testing");

        ToolProvider jig = tool(
                "jig",
                (output, arguments) -> {
                    assertTrue(arguments.contains("--no-compile-diagnostics"));
                    int moduleOption = arguments.indexOf("--module");
                    if (moduleOption < 0) {
                        moduleOption = arguments.indexOf("-m");
                    }
                    String moduleName = arguments.get(moduleOption + 1);
                    Path module = Files.createDirectories(directory.resolve("compiled").resolve(moduleName));
                    TestModules.writeModuleInfo(module, moduleName);
                    Path classes = Files.createDirectories(module.resolve("com/example"));
                    if (moduleName.equals("com.example.application")) {
                        Files.writeString(classes.resolve("Application.class"), "application");
                        Files.writeString(classes.resolve("ApplicationTest.class"), "test");
                        Path support = Files.createDirectories(classes.resolve("test"));
                        Files.writeString(support.resolve("Fixture.class"), "fixture");
                        Files.writeString(support.resolve("fixture.properties"), "fixture=true\n");
                        var nested = Files.createDirectories(support.resolve("support"));
                        Files.writeString(nested.resolve("Production.class"), "production");
                        var versioned = Files.createDirectories(module.resolve("META-INF/versions/21/com/example"));
                        Files.writeString(versioned.resolve("Application.class"), "application 21");
                        Files.writeString(versioned.resolve("ApplicationTest.class"), "test 21");
                        var versionedSupport = Files.createDirectories(versioned.resolve("test"));
                        Files.writeString(versionedSupport.resolve("Fixture.class"), "fixture 21");
                        Files.writeString(versionedSupport.resolve("fixture.properties"), "fixture=21\n");
                    } else {
                        Files.writeString(classes.resolve("ApplicationTest.class"), "test");
                        Path support = Files.createDirectories(classes.resolve("test"));
                        Files.writeString(support.resolve("Fixture.class"), "fixture");
                        Files.writeString(support.resolve("fixture.properties"), "fixture=true\n");
                    }

                    String separator = System.getProperty("path.separator");
                    String compilePath = module + separator + activationPath;
                    String runtimePath = moduleName.endsWith(".tests") ? compilePath : module.toString();
                    int write = arguments.indexOf("--write-argfile");
                    if (write >= 0) {
                        String options = arguments.get(arguments.indexOf("--resolve-options") + 1);
                        Path argumentFile = Path.of(arguments.get(write + 1));
                        String content = options.contains("processor-module-path")
                                ? "--module-path\n" + compilePath + "\n"
                                : options.equals(COMPLETE_RUNTIME_ACCESS_OPTIONS) ? "--module-path\n" + runtimePath + "\n" : "";
                        Files.writeString(argumentFile, content);
                    }
                    return 0;
                });
        ToolServices tools = ToolServices.of(jig, ToolProvider.findFirst("jar").orElseThrow(),
                tool("javadoc", (output, arguments) -> 0));
        ToolDefinition testing = new ToolDefinition(
                "testing",
                Launch.PROVIDER,
                Optional.of("com.example.testing"),
                Optional.empty(),
                "testing",
                Optional.of("1"),
                Set.of(),
                List.of(),
                Optional.of("Test"),
                Optional.of("test"));
        var commandLine = JaInvocation.parse(directory, new String[] {"assemble", "--module-version", "1.0", "artifacts"});

        int result = new CommandRunner(ModuleLayer.boot(), tools, new ToolCatalog(List.of(testing)), () -> null)
                .run(commandLine, InputStream.nullInputStream(), new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, result);
        Path application = artifacts(directory).resolve("com.example.application.jar");
        try (var archive = new JarFile(application.toFile())) {
            assertTrue(archive.getEntry("com/example/Application.class") != null);
            assertNull(archive.getEntry("com/example/ApplicationTest.class"));
            assertNull(archive.getEntry("com/example/test/Fixture.class"));
            assertNull(archive.getEntry("com/example/test/fixture.properties"));
            assertTrue(archive.getEntry("com/example/test/support/Production.class") != null);
            assertTrue(archive.getEntry("META-INF/versions/21/com/example/Application.class") != null);
            assertNull(archive.getEntry("META-INF/versions/21/com/example/ApplicationTest.class"));
            assertNull(archive.getEntry("META-INF/versions/21/com/example/test/Fixture.class"));
            assertNull(archive.getEntry("META-INF/versions/21/com/example/test/fixture.properties"));
        }
        Path tests = artifacts(directory).resolve("com.example.application.tests.jar");
        try (var archive = new JarFile(tests.toFile())) {
            assertTrue(archive.getEntry("com/example/ApplicationTest.class") != null);
            assertTrue(archive.getEntry("com/example/test/Fixture.class") != null);
            assertTrue(archive.getEntry("com/example/test/fixture.properties") != null);
        }
    }

    @Test
    void assemblesConsistentBinaryAndSourceViews(@TempDir Path directory) throws Exception {
        String moduleName = "com.example.application";
        Path source = sourceModule(directory, moduleName);
        Path sourcePackage = Files.createDirectories(source.resolve("com/example"));
        Files.writeString(sourcePackage.resolve("Application.java"), "application source");
        Path runtimeModule = Files.createDirectories(directory.resolve("runtime/" + moduleName));
        TestModules.writeModuleInfo(runtimeModule, moduleName);
        Path runtimePackage = Files.createDirectories(runtimeModule.resolve("com/example"));
        Files.writeString(runtimePackage.resolve("Application.class"), "application");
        Files.writeString(runtimePackage.resolve("Patched.class"), "complete");

        ToolProvider jig = tool(
                "jig",
                (output, arguments) -> {
                    assertTrue(arguments.contains("--no-compile-diagnostics"));
                    assertFalse(arguments.stream().anyMatch(argument -> argument.startsWith("source-path=")));
                    String projection = "--module-path\n" + runtimeModule + "\n";
                    int write = arguments.indexOf("--write-argfile");
                    if (write >= 0) {
                        String options = arguments.get(arguments.indexOf("--resolve-options") + 1);
                        if (options.contains("module-source-path")) {
                            assertTrue(options.contains("upgrade-module-path"), options);
                        }
                        Path argumentFile = Path.of(arguments.get(write + 1));
                        Files.writeString(argumentFile, options.equals("main-class,module-version") ? "--main-class\ncom.example.Application\n--module-version\n1.0\n" : projection);
                    }
                    return 0;
                });
        ToolServices tools = ToolServices.of(jig, ToolProvider.findFirst("jar").orElseThrow(),
                tool("javadoc", (output, arguments) -> 0));
        var commandLine = JaInvocation.parse(directory, new String[] {"assemble", "--module-version", "1.0", "artifacts"});

        int result = new CommandRunner(ModuleLayer.boot(), tools, ToolCatalog.load(ModuleLayer.boot()), () -> null)
                .run(commandLine, InputStream.nullInputStream(), new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, result);
        Path artifact = artifacts(directory).resolve(moduleName + ".jar");
        try (var archive = new JarFile(artifact.toFile())) {
            assertEquals("application", entry(archive, "com/example/Application.class"));
            assertEquals("complete", entry(archive, "com/example/Patched.class"));
        }
        var descriptor = ModuleFinder.of(artifact)
                .find(moduleName)
                .orElseThrow()
                .descriptor();
        assertEquals("1.0", descriptor.rawVersion()
                .orElseThrow());
        assertEquals("com.example.Application", descriptor.mainClass()
                .orElseThrow());
        try (var archive = new JarFile(artifact.resolveSibling(moduleName + "-sources.jar")
                .toFile())) {
            assertEquals("application source", entry(archive, "com/example/Application.java"));
        }
    }

    @Test
    void assemblesCompleteModulesWhenOneSelectedModuleRequiresAnother(@TempDir Path directory) throws Exception {
        Path application = sourceModule(directory, "com.example.application");
        Files.writeString(application.resolve("module-info.java"),
                """
                module com.example.application {
                    requires com.example.library;
                }
                """);
        Path library = sourceModule(directory, "com.example.library");
        Files.writeString(library.resolve("module-info.java"),
                """
                module com.example.library {
                    exports com.example.library;
                }
                """);
        Path packageDirectory = Files.createDirectories(library.resolve("com/example/library"));
        Files.writeString(packageDirectory.resolve("Library.java"),
                """
                package com.example.library;
                public final class Library {}
                """);

        Result result = run(directory, "assemble", "--module-version", "1.0", "artifacts");

        assertEquals(0, result.exitCode(), result.error());
        Path libraryJar = artifacts(directory).resolve("com.example.library.jar");
        try (var zip = new ZipFile(libraryJar.toFile())) {
            assertTrue(zip.getEntry("com/example/library/Library.class") != null);
        }
        Path applicationJar = artifacts(directory).resolve("com.example.application.jar");
        var finder = ModuleFinder.of(applicationJar, libraryJar);
        Configuration.resolve(finder, List.of(ModuleLayer.boot().configuration()),
                ModuleFinder.of(), Set.of("com.example.application"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void assemblyExportsAnAutomaticModuleForAnyAutomaticDependency(boolean explicitName, @TempDir Path directory) throws Exception {
        Path source = sourceModule(directory, "com.example.app");
        Files.writeString(source.resolve("module-info.java"),
                """
                module com.example.app {
                    requires org.example.library;
                }
                """);
        Path automatic = explicitName ? writeAutomaticJar(directory.resolve("legacy-library-1.2.3.jar"), "org.example.library") : TestModules.writeAutomaticJar(directory.resolve("org.example.library-1.2.3.jar"));
        ToolProvider jig = tool(
                "jig",
                (output, arguments) -> {
                    assertTrue(arguments.contains("--no-compile-diagnostics"));
                    Path module = Files.createDirectories(directory.resolve("compiled"));
                    if (explicitName) {
                        TestModules.writeModuleInfo(module, "com.example.app", "org.example.library");
                    } else {
                        TestModules.writeModuleInfoWithProvider(module, "com.example.app", "java.util.spi.ToolProvider", "com.example.Provider", "org.example.library");
                    }
                    Path packageDirectory = Files.createDirectories(module.resolve("com/example"));
                    Files.writeString(packageDirectory.resolve("App.class"), "application");
                    if (!explicitName) {
                        Files.writeString(packageDirectory.resolve("Provider.class"), "provider");
                    }
                    int write = arguments.indexOf("--write-argfile");
                    if (write >= 0) {
                        String options = arguments.get(arguments.indexOf("--resolve-options") + 1);
                        Path path = Path.of(arguments.get(write + 1));
                        Files.writeString(path, options.equals(COMPLETE_RUNTIME_ACCESS_OPTIONS) ? "--module-path\n" + module + System.getProperty("path.separator") + automatic + "\n" : "");
                    }
                    return 0;
                });
        ToolServices tools = ToolServices.of(
                jig,
                ToolProvider.findFirst("jar").orElseThrow(),
                ToolProvider.findFirst("jmod").orElseThrow(),
                tool("javadoc", (output, arguments) -> 0));
        var commandLine = JaInvocation.parse(directory,
                new String[] {"assemble", "--jmod", "--module-version", "1.0", "artifacts"});
        var errors = new ByteArrayOutputStream();

        int result = new CommandRunner(ModuleLayer.boot(), tools, ToolCatalog.load(ModuleLayer.boot()), () -> null)
                .run(commandLine, new ByteArrayInputStream(new byte[0]), new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(errors));

        assertEquals(0, result, errors.toString());
        Path artifact = artifacts(directory).resolve("com.example.app.jar");
        var descriptor = ModuleFinder.of(artifact)
                .find("com.example.app")
                .orElseThrow()
                .descriptor();
        assertTrue(descriptor.isAutomatic());
        try (var archive = new JarFile(artifact.toFile())) {
            assertNull(archive.getEntry("module-info.class"));
            assertEquals("com.example.app",
                    archive.getManifest()
                           .getMainAttributes()
                           .getValue("Automatic-Module-Name"));
            if (!explicitName) {
                assertEquals("com.example.Provider\n", new String(archive.getInputStream(archive.getEntry("META-INF/services/java.util.spi.ToolProvider"))
                        .readAllBytes(),
                        StandardCharsets.UTF_8));
            }
        }
        Path jmod = artifact.resolveSibling("com.example.app.jmod");
        assertFalse(Files.exists(jmod));
        assertEquals(
                """
                warning: com.example.app requires automatic modules:
                  org.example.library
                com.example.app will be exported as an automatic module.
                The com.example.app jmod artifact will be omitted.
                """,
                errors.toString().replace(System.lineSeparator(), "\n"));
    }

    @Test
    void assemblesAJmodSourceLayout(@TempDir Path directory) throws Exception {
        Path module = directory.resolve("src/com.example.tool");
        Path classes = moduleAt(module.resolve("classes"), "com.example.tool");
        String metadata = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <name>Example tool</name>
                </project>
                """;
        Path deploymentPom = module.resolve("META-INF/com.netflix.tools.ja/maven/deploy.pom");
        Files.createDirectories(deploymentPom.getParent());
        Files.writeString(deploymentPom, metadata);
        Files.writeString(classes.resolve("module-info.pom"), "Maven export intermediary");
        Path resources = classes.resolve("com/example");
        Files.createDirectories(resources);
        Files.writeString(resources.resolve("tool.properties"), "tool=true\n");
        Path commands = Files.createDirectories(module.resolve("bin"));
        Files.writeString(commands.resolve("example"), "launcher");

        Result result = run(directory, "assemble", "--module-version", "2.0", "artifacts");

        assertEquals(0, result.exitCode(), result.error());
        Path moduleVersion = artifacts(directory);
        Path artifact = moduleVersion.resolve("com.example.tool.jmod");
        assertTrue(Files.isRegularFile(artifact));
        assertTrue(jmodEntries(artifact).contains("bin/example"));
        assertTrue(jmodEntries(artifact).contains("classes/META-INF/com.netflix.tools.ja/maven/deploy.pom"));
        assertFalse(jmodEntries(artifact).contains("classes/module-info.pom"));
        assertEquals(metadata, Files.readString(moduleVersion.resolve("com.example.tool.pom")));
        try (var binary = new ZipFile(moduleVersion.resolve("com.example.tool.jar").toFile());
             var sources = new ZipFile(moduleVersion.resolve("com.example.tool-sources.jar").toFile())) {
            assertTrue(binary.getEntry("META-INF/com.netflix.tools.ja/maven/deploy.pom") != null);
            assertNull(binary.getEntry("module-info.pom"));
            assertTrue(sources.getEntry("module-info.java") != null);
            assertTrue(sources.getEntry("com/example/tool.properties") != null);
            assertTrue(sources.getEntry("META-INF/com.netflix.tools.ja/maven/deploy.pom") != null);
            assertNull(sources.getEntry("module-info.pom"));
            assertNull(sources.getEntry("bin/example"));
            assertNull(sources.getEntry("classes/module-info.java"));
        }
    }

    @Test
    void generatesAotOptInForWarmupTool(@TempDir Path directory) throws Exception {
        var module = directory.resolve("src/com.example.tool");
        var classes = moduleAt(module.resolve("classes"), "com.example.tool");
        Files.writeString(classes.resolve("module-info.java"),
                """
                /**
                 * @enableNativeAccess com.example.tool
                 * @addExports jdk.compiler/com.sun.tools.javac.api=com.example.tool
                 */
                module com.example.tool {
                    provides java.util.spi.ToolProvider with com.example.Probe;
                }
                """);
        var packageDirectory = Files.createDirectories(classes.resolve("com/example"));
        Files.writeString(packageDirectory.resolve("Probe.java"),
                """
                package com.example;
                public final class Probe implements java.util.spi.ToolProvider {
                    public String name() { return "probe"; }
                    public int run(java.io.PrintWriter out, java.io.PrintWriter err,
                                   String... arguments) { return 0; }
                }
                """);
        Path metadata = classes.resolve("META-INF/com.netflix.tools/tools/probe.properties");
        Files.createDirectories(metadata.getParent());
        Files.writeString(metadata, "warmup=--warmup\n");

        var result = run(directory, "assemble", "--module-version", "1.0", "--target-platform", "macos-aarch64",
                "artifacts");

        assertEquals(0, result.exitCode(), result.error());
        var artifact = artifacts(directory).resolve("com.example.tool-osx-aarch_64.jmod");
        assertTrue(Files.isRegularFile(artifact));
        assertTrue(jmodEntries(artifact).contains("conf/com.netflix.tools.launcher/probe.args"));
        try (var zip = new ZipFile(artifact.toFile())) {
            String arguments = new String(zip.getInputStream(zip.getEntry("conf/com.netflix.tools.launcher/probe.args"))
                    .readAllBytes(),
                            StandardCharsets.UTF_8);
            assertEquals(
                    Set.of("--add-modules=com.example.tool", "--enable-native-access=com.example.tool", "--add-exports=jdk.compiler/com.sun.tools.javac.api=com.example.tool", "-L-aot=auto"),
                    arguments.lines().collect(Collectors.toSet()));
        }
    }

    @Test
    void assemblesAJmodWhenRequested(@TempDir Path directory) throws Exception {
        sourceModule(directory, "com.example.library");

        var result = run(directory, "assemble", "--jmod", "--module-version", "1.0", "artifacts");

        assertEquals(0, result.exitCode(), result.error());
        var artifact = artifacts(directory).resolve("com.example.library.jmod");
        assertTrue(Files.isRegularFile(artifact));
        assertTrue(jmodEntries(artifact).contains("classes/module-info.class"));
    }

    @Test
    void assemblesToolServiceAsAPlatformCommand(@TempDir Path directory) throws Exception {
        Path launcher = sourceModule(directory, "com.netflix.tools.launcher");
        Path launcherResource = launcher.resolve("META-INF/com.netflix.tools.launcher/osx-aarch_64/launcher");
        Files.createDirectories(launcherResource.getParent());
        Files.writeString(launcherResource, "workspace launcher");

        Path source = sourceModule(directory, "com.example.commands");
        Files.writeString(source.resolve("module-info.java"),
                """
                /**
                 * @enablePreview
                 * @enableNativeAccess com.example.commands
                 */
                module com.example.commands {
                    provides java.util.spi.ToolProvider with com.example.Probe;
                }
                """);
        Path packageDirectory = source.resolve("com/example");
        Files.createDirectories(packageDirectory);
        Files.writeString(packageDirectory.resolve("Probe.java"),
                """
                package com.example;
                public final class Probe implements java.util.spi.ToolProvider {
                    public String name() { return "probe"; }
                    public int run(java.io.PrintWriter out, java.io.PrintWriter err,
                                   String... arguments) { return 0; }
                }
                """);

        Result result = run(directory, "assemble", "--target-platform", "macos-aarch64", "--module-version", "1.0",
                "artifacts");

        assertEquals(0, result.exitCode(), result.error());
        Path artifact = artifacts(directory).resolve("com.example.commands-osx-aarch_64.jmod");
        assertTrue(Files.isRegularFile(artifact));
        String entries = jmodEntries(artifact);
        assertTrue(entries.contains("bin/probe"));
        assertTrue(entries.contains("conf/com.netflix.tools.launcher/probe.args"), entries);
        try (var zip = new ZipFile(artifact.toFile())) {
            assertEquals("workspace launcher", new String(zip.getInputStream(zip.getEntry("bin/probe"))
                    .readAllBytes(),
                            StandardCharsets.UTF_8));
        }
    }

    @Test
    void assemblesToolServiceWithoutRuntimeOptions(@TempDir Path directory) throws Exception {
        Path source = sourceModule(directory, "com.example.commands");
        Files.writeString(source.resolve("module-info.java"),
                """
                module com.example.commands {
                    provides java.util.spi.ToolProvider with com.example.Probe;
                }
                """);
        Path packageDirectory = Files.createDirectories(source.resolve("com/example"));
        Files.writeString(packageDirectory.resolve("Probe.java"),
                """
                package com.example;
                public final class Probe implements java.util.spi.ToolProvider {
                    public String name() { return "probe"; }
                    public int run(java.io.PrintWriter out, java.io.PrintWriter err,
                                   String... arguments) { return 0; }
                }
                """);

        Result result = run(directory, "assemble", "--target-platform", "macos-aarch64", "--module-version", "1.0",
                "artifacts");

        assertEquals(0, result.exitCode(), result.error());
        Path artifact = artifacts(directory).resolve("com.example.commands-osx-aarch_64.jmod");
        assertTrue(Files.isRegularFile(artifact));
        assertTrue(jmodEntries(artifact).contains("bin/probe"));
    }

    @Test
    void assemblesCompiledProviderThatShadowsABootModule(@TempDir Path directory) throws Exception {
        Path source = sourceModule(directory, "com.netflix.tools.jig");
        Files.writeString(source.resolve("module-info.java"),
                """
                module com.netflix.tools.jig {
                    provides java.util.spi.ToolProvider with com.netflix.tools.jig.Jig;
                }
                """);
        Path packageDirectory = Files.createDirectories(source.resolve("com/netflix/tools/jig"));
        Files.writeString(packageDirectory.resolve("Jig.java"),
                """
                package com.netflix.tools.jig;
                public final class Jig implements java.util.spi.ToolProvider {
                    public String name() { return "jig"; }
                    public int run(java.io.PrintWriter out, java.io.PrintWriter err,
                                   String... arguments) { return 0; }
                }
                """);

        Result result = run(directory, "assemble", "--target-platform", "macos-aarch64", "--module-version", "1.0",
                "artifacts");

        assertEquals(0, result.exitCode(), result.error());
        try (var paths = Files.walk(directory.resolve("artifacts"))) {
            assertTrue(paths.anyMatch(path -> path.getFileName()
                    .toString()
                    .equals("com.netflix.tools.jig-osx-aarch_64.jmod")));
        }
    }

    @Test
    void requiresAVersionAndDestination(@TempDir Path directory) throws Exception {
        sourceModule(directory, "com.example.library");

        Result missingVersion = run(directory, "assemble", "artifacts");
        Result missingDestination = run(directory, "assemble", "--module-version", "1.0");

        assertEquals(2, missingVersion.exitCode());
        assertTrue(missingVersion.error().contains("assemble requires --module-version"),
                missingVersion.error());
        assertEquals(2, missingDestination.exitCode());
        assertTrue(missingDestination.error().contains("assemble requires a destination directory"),
                missingDestination.error());
    }

    @Test
    void rejectsAnExistingDestination(@TempDir Path directory) throws Exception {
        sourceModule(directory, "com.example.library");
        Path destination = artifacts(directory);
        Files.createDirectories(destination);
        Path existing = Files.writeString(destination.resolve("existing"), "existing");

        Result result = run(directory, "assemble", "--module-version", "1.0", "artifacts");

        assertEquals(2, result.exitCode());
        assertTrue(result.error().contains("Assembly destination already exists: " + destination),
                result.error());
        assertEquals("existing", Files.readString(existing));
    }

    @Test
    void leavesNoArtifactsWhenOneModuleFailsCompilation(@TempDir Path directory) throws Exception {
        sourceModule(directory, "com.example.api");
        Path runtime = sourceModule(directory, "com.example.runtime");
        Path packageDirectory = runtime.resolve("com/example");
        Files.createDirectories(packageDirectory);
        Files.writeString(packageDirectory.resolve("Broken.java"), "this is not Java\n");

        Result result = run(directory, "assemble", "--module-version", "1.0", "artifacts");

        assertEquals(1, result.exitCode(), result.error());
        assertFalse(Files.exists(directory.resolve("artifacts")));
        try (var entries = Files.list(directory)) {
            assertFalse(entries.anyMatch(path -> path.getFileName()
                    .toString()
                    .startsWith(".ja-assemble-")));
        }
    }

    private Result run(Path workingDirectory, String... arguments) throws Exception {
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        int exitCode;
        try (var out = new PrintStream(output);
             var err = new PrintStream(errors)) {
            var commandLine = JaInvocation.parse(workingDirectory, arguments);
            exitCode = new CommandRunner(ModuleLayer.boot()).run(commandLine, new ByteArrayInputStream(new byte[0]), out, err);
        } catch (ToolExecutionException e) {
            exitCode = 1;
        } catch (IllegalArgumentException | IllegalStateException e) {
            errors.writeBytes(("ja: " + e.getMessage() + "\n").getBytes(StandardCharsets.UTF_8));
            exitCode = 2;
        }
        return new Result(exitCode, output.toString(StandardCharsets.UTF_8), errors.toString(StandardCharsets.UTF_8));
    }

    private static Path writeAutomaticJar(Path path, String moduleName) throws Exception {
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", moduleName);
        try (var _ = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            return path;
        }
    }

    private static Path sourceModule(Path directory, String module) throws Exception {
        return moduleAt(directory.resolve("src")
                .resolve(module),
                module);
    }

    private static Path moduleAt(Path source, String module) throws Exception {
        Files.createDirectories(source);
        Files.writeString(source.resolve("module-info.java"), "module " + module + " {}\n");
        Files.writeString(source.resolve("module-info.hash"), "");
        return source;
    }

    private static Path artifacts(Path directory) {
        return directory.resolve("artifacts");
    }

    private static String entry(JarFile jar, String name) throws Exception {
        return new String(jar.getInputStream(jar.getJarEntry(name))
                             .readAllBytes(),
                StandardCharsets.UTF_8);
    }

    private static String jmodEntries(Path jmod) throws Exception {
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "jmod")
                .toString(),
                        "list", jmod.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream()
                .readAllBytes(),
                        StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        return output;
    }

    private record Result(int exitCode, String output, String error) {}

    private static ToolProvider tool(String name, Operation operation) {
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
