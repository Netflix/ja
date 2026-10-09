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
import java.io.PrintStream;
import java.io.PrintWriter;
import java.lang.module.ModuleFinder;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.spi.ToolProvider;

import com.netflix.tools.ja.ApplicationTarget;
import com.netflix.tools.ja.InstallRequest;
import com.netflix.tools.ja.InstallationDirectories;
import com.netflix.tools.ja.InstalledCommandModule;
import com.netflix.tools.ja.InstalledCommandModule.Generated;
import com.netflix.tools.ja.Installer;
import com.netflix.tools.ja.Installer.CommandModuleFactory;
import com.netflix.tools.ja.Installer.NativeLauncher;
import com.netflix.tools.ja.Installer.RuntimeModuleResolver;
import com.netflix.tools.ja.ToolExecutionException;
import com.netflix.tools.ja.ToolRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstallerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void requiresAnApplicationMainClass() throws Exception {
        Path application = Files.writeString(temporaryDirectory.resolve("foo-1.2.3.jar"), "application");
        var installer = new Installer(
                ToolRuntime.of(tool("jlink", (out, arguments) -> 0)),
                new InstallationDirectories(temporaryDirectory.resolve("store"), temporaryDirectory.resolve("commands")),
                false,
                commandModule(),
                nativeLauncher(),
                fixedRuntimeModules());

        var failure = assertThrows(
                IllegalArgumentException.class,
                () -> installer.install(
                        new ApplicationTarget("com.example.foo", "1.2.3"),
                        new InstallRequest("com.example.foo@1.2.3", Optional.empty(), false),
                        List.of("--module-path", application.toString(), "--module", "com.example.foo"),
                        new ByteArrayInputStream(new byte[0]),
                        new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream())));

        assertEquals("Application launch target has no main class: com.example.foo", failure.getMessage());
    }

    @Test
    void stagesExplodedApplicationModules() throws Exception {
        Path modules = Files.createDirectories(temporaryDirectory.resolve("modules"));
        Path application = Files.createDirectories(modules.resolve("com.example.foo"));
        Path applicationPackage = Files.createDirectories(application.resolve("com/example"));
        Files.writeString(applicationPackage.resolve("Main.class"), "application");
        writeModuleInfo(application, "com.example.foo");
        Path unrelated = Files.createDirectories(modules.resolve("com.example.unrelated"));
        writeModuleInfo(unrelated, "com.example.unrelated");
        Path store = temporaryDirectory.resolve("store");
        Path commands = temporaryDirectory.resolve("commands");
        ToolRuntime tools = ToolRuntime.of(tool("javac",
                (out, arguments) -> {
                    Path output = Path.of(arguments.get(arguments.indexOf("-d") + 1));
                    Files.createDirectories(output);
                    return 0;
                }),
                tool(
                        "jlink",
                        (out, arguments) -> {
                            Path image = Path.of(arguments.get(arguments.indexOf("--output") + 1));
                            Files.createDirectories(image.resolve("bin"));
                            Files.createDirectories(image.resolve("lib"));
                            Files.writeString(image.resolve("bin/java"), "java");
                            Files.writeString(image.resolve("lib/modules"), "modules");
                            return 0;
                        }));
        var installer = new Installer(tools, new InstallationDirectories(store, commands), false);

        int result = installer.install(
                new ApplicationTarget("com.example.foo", "1.2.3"),
                new InstallRequest(Optional.empty(), Optional.empty(), false),
                List.of("--module-path", modules.toString(), "--module", "com.example.foo/com.example.Main"),
                new ByteArrayInputStream(new byte[0]),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, result);
        assertEquals("application", Files.readString(store.resolve("com.example.foo@1.2.3/app/modules/com.example.foo/com/example/Main.class")));
        assertFalse(Files.exists(store.resolve("com.example.foo@1.2.3/app/modules/com.example.unrelated")));
    }

    @Test
    void stagesAnExplodedModulePathEntry() throws Exception {
        Path application = Files.createDirectories(temporaryDirectory.resolve("com.example.foo"));
        Path applicationPackage = Files.createDirectories(application.resolve("com/example"));
        Files.writeString(applicationPackage.resolve("Main.class"), "application");
        writeModuleInfo(application, "com.example.foo");
        Path store = temporaryDirectory.resolve("store");
        ToolRuntime tools = ToolRuntime.of(tool("javac",
                (out, arguments) -> {
                    Path output = Path.of(arguments.get(arguments.indexOf("-d") + 1));
                    Files.createDirectories(output);
                    return 0;
                }),
                tool("jlink",
                        (out, arguments) -> {
                            Path image = Path.of(arguments.get(arguments.indexOf("--output") + 1));
                            Files.createDirectories(image.resolve("bin"));
                            return 0;
                        }));
        var installer = new Installer(tools, new InstallationDirectories(store, temporaryDirectory.resolve("commands")), false);

        int result = installer.install(
                new ApplicationTarget("com.example.foo", "1.2.3"),
                new InstallRequest(Optional.empty(), Optional.empty(), false),
                List.of("--module-path", application.toString(), "--module", "com.example.foo/com.example.Main"),
                new ByteArrayInputStream(new byte[0]),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, result);
        assertEquals("application", Files.readString(store.resolve("com.example.foo@1.2.3/app/modules/com.example.foo/com/example/Main.class")));
    }

    @Test
    void installsAnApplicationWithAnUpgradePathModule() throws Exception {
        Path application = TestModules.writeJar(temporaryDirectory.resolve("foo.jar"), "com.example.foo");
        Path store = temporaryDirectory.resolve("store");
        ToolRuntime tools = ToolRuntime.of(tool("jlink",
                (out, arguments) -> {
                    Path image = Path.of(arguments.get(arguments.indexOf("--output") + 1));
                    Files.createDirectories(image.resolve("bin"));
                    return 0;
                }));
        var installer = new Installer(tools, new InstallationDirectories(store, temporaryDirectory.resolve("commands")),
                false, commandModule(), nativeLauncher(), fixedRuntimeModules());

        installer.install(
                new ApplicationTarget("com.example.foo", "1.2.3"),
                new InstallRequest("com.example.foo@1.2.3", Optional.empty(), false),
                List.of("--upgrade-module-path", application.toString(), "--module", "com.example.foo/com.example.Main"),
                new ByteArrayInputStream(new byte[0]),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()));

        assertEquals(-1L, Files.mismatch(application, store.resolve("com.example.foo@1.2.3/app/modules/foo.jar")));
    }

    @Test
    void linksExplicitApplicationAndActivatesItsEntrypoint() throws Exception {
        Path repository = Files.createDirectories(temporaryDirectory.resolve("repository"));
        Path application = TestModules.writeJar(repository.resolve("foo.jar"), "com.example.foo", "com.example.library");
        Path dependency = TestModules.writeJar(repository.resolve("library.jar"), "com.example.library");
        Path store = temporaryDirectory.resolve("store");
        Path commands = temporaryDirectory.resolve("commands");
        var jlinkArguments = new ArrayList<String>();
        ToolRuntime tools = ToolRuntime.of(tool(
                "jlink",
                (out, arguments) -> {
                    jlinkArguments.addAll(arguments);
                    Path output = Path.of(arguments.get(arguments.indexOf("--output") + 1));
                    Files.createDirectories(output.resolve("bin"));
                    Files.createDirectories(output.resolve("lib"));
                    Files.writeString(output.resolve("bin/java"), "java");
                    Files.writeString(output.resolve("lib/modules"), "image");
                    return 0;
                }));
        var installer = new Installer(tools, new InstallationDirectories(store, commands), false, commandModule(),
                nativeLauncher(), fixedRuntimeModules());

        int result = installer.install(
                new ApplicationTarget("com.example.foo", "1.2.3"),
                new InstallRequest("com.example.foo@1.2.3", Optional.empty(), false),
                List.of(
                        "--module-path",
                        application + System.getProperty("path.separator") + dependency,
                        "--add-modules",
                        "com.example.foo",
                        "--enable-preview",
                        "--enable-native-access",
                        "com.example.foo",
                        "--enable-final-field-mutation",
                        "com.example.foo",
                        "--add-opens",
                        "com.example.foo/internal=com.example.friend",
                        "--add-exports",
                        "com.example.foo/api=com.example.friend",
                        "--module",
                        "com.example.foo/com.example.Main"),
                new ByteArrayInputStream(new byte[0]),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, result);
        assertTrue(jlinkArguments.contains("com.netflix.tools.launcher,java.base") || jlinkArguments.contains("java.base,com.netflix.tools.launcher"));
        String runtimeModulePath = jlinkArguments.get(jlinkArguments.indexOf("--module-path") + 1);
        List<Path> runtimeModulePaths = Arrays.stream(runtimeModulePath.split(Pattern.quote(System.getProperty("path.separator"))))
                .map(Path::of)
                .toList();
        assertTrue(runtimeModulePaths.stream().anyMatch(path -> Files.isRegularFile(path.resolve("com.netflix.tools.launcher.jmod")) || ModuleFinder.of(path)
                .find("com.netflix.tools.launcher")
                .isPresent()),
                runtimeModulePath);
        assertFalse(jlinkArguments.contains("--generate-cds-archive"));
        assertTrue(jlinkArguments.stream()
                .noneMatch(argument -> argument.startsWith("--add-options=")));
        assertFalse(jlinkArguments.contains("--release-info"));
        Path image = store.resolve("com.example.foo@1.2.3");
        List<String> launcherOptions = Files.readAllLines(image.resolve("conf/com.netflix.tools.launcher/foo.args"));
        assertTrue(launcherOptions.contains("--add-modules=com.example.foo.launcher"));
        assertTrue(launcherOptions.contains("--add-modules=com.example.foo"));
        assertTrue(launcherOptions.contains("--enable-preview"));
        assertTrue(launcherOptions.contains("--enable-native-access=com.example.foo"));
        assertTrue(launcherOptions.contains("--enable-final-field-mutation=com.example.foo"));
        assertFalse(launcherOptions.contains("-L-aot=auto"));
        assertTrue(launcherOptions.contains("--add-opens=com.example.foo/internal=com.example.friend"));
        assertTrue(launcherOptions.contains("--add-exports=com.example.foo/api=com.example.friend"));
        assertTrue(launcherOptions.contains("--add-opens=com.example.foo/com.example=com.example.foo.launcher"));
        assertTrue(Files.isRegularFile(image.resolve("bin/foo")));
        assertEquals("native", Files.readString(image.resolve("bin/foo")));
        assertEquals(-1L, Files.mismatch(application, image.resolve("app/modules/foo.jar")));
        assertEquals(-1L, Files.mismatch(dependency, image.resolve("app/modules/library.jar")));
        assertTrue(Files.isRegularFile(image.resolve("app/modules/com.example.foo.launcher/module-info.class")));
        assertTrue(Files.readString(image.resolve("app/modules.hash"))
                .matches("[0-9a-f]{64}\\n"));
        assertEquals(image.resolve("bin/foo").toRealPath(),
                activeEntrypoint(commands, "foo"));
        assertFalse(Files.exists(image.resolve("runtime")));
    }

    @Test
    void enablesAotForAnInstalledToolWithAWarmupContract() throws Exception {
        Path application = TestModules.writeJar(temporaryDirectory.resolve("foo.jar"), "com.example.foo");
        Path store = temporaryDirectory.resolve("store");
        ToolRuntime tools = ToolRuntime.of(tool(
                "jlink",
                (out, arguments) -> {
                    Path image = Path.of(arguments.get(arguments.indexOf("--output") + 1));
                    Files.createDirectories(image.resolve("bin"));
                    Files.createDirectories(image.resolve("lib"));
                    Files.writeString(image.resolve("lib/modules"), "modules");
                    return 0;
                }));
        var installer = new Installer(
                tools,
                new InstallationDirectories(store, temporaryDirectory.resolve("commands")),
                false,
                (name, target, arguments, output, in, out, err) -> {
                    Path classes = Files.createDirectories(output.resolve("classes"));
                    Files.writeString(classes.resolve("module-info.class"), "command");
                    return new Generated(InstalledCommandModule.moduleName(target.moduleName()), classes, Optional.of("com.example"), true);
                },
                nativeLauncher(),
                fixedRuntimeModules());

        int result = installer.install(
                new ApplicationTarget("com.example.foo", "1.2.3"),
                new InstallRequest("com.example.foo@1.2.3", Optional.empty(), false),
                List.of("--module-path", application.toString(), "--module", "com.example.foo/com.example.Main"),
                new ByteArrayInputStream(new byte[0]),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, result);
        assertEquals(
                List.of("--add-modules=com.example.foo.launcher", "--add-opens=com.example.foo/com.example=com.example.foo.launcher", "-L-aot=auto"),
                Files.readAllLines(store.resolve("com.example.foo@1.2.3/conf/com.netflix.tools.launcher/foo.args")));
    }

    @Test
    void linksOnlySystemModulesRequiredByTheApplication() throws Exception {
        Path modules = Files.createDirectory(temporaryDirectory.resolve("modules"));
        Path store = temporaryDirectory.resolve("store");
        var jlinkArguments = new ArrayList<String>();
        ToolRuntime tools = ToolRuntime.of(tool(
                "jlink",
                (out, arguments) -> {
                    jlinkArguments.addAll(arguments);
                    Path output = Path.of(arguments.get(arguments.indexOf("--output") + 1));
                    Files.createDirectories(output.resolve("bin"));
                    return 0;
                }));
        var installer = new Installer(tools, new InstallationDirectories(store, temporaryDirectory.resolve("commands")),
                false, commandModule(), nativeLauncher());

        installer.install(
                new ApplicationTarget("java.logging", "1.0"),
                new InstallRequest("java.logging@1.0", Optional.empty(), false),
                List.of("--module-path", modules.toString(), "--module", "java.logging/com.example.Main"),
                new ByteArrayInputStream(new byte[0]),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()));

        String modulesArgument = jlinkArguments.get(jlinkArguments.indexOf("--add-modules") + 1);
        assertEquals(
                Set.of("com.netflix.tools.launcher", "java.base", "java.compiler", "java.logging"),
                Set.of(modulesArgument.split(",")));
    }

    @Test
    void writesStandaloneOutputWithoutActivatingACommand() throws Exception {
        Path application = TestModules.writeJar(temporaryDirectory.resolve("foo.jar"), "com.example.foo");
        Path output = temporaryDirectory.resolve("images/foo");
        Path commands = temporaryDirectory.resolve("commands");
        ToolRuntime tools = ToolRuntime.of(tool("jlink",
                (out, arguments) -> {
                    Path image = Path.of(arguments.get(arguments.indexOf("--output") + 1));
                    Files.createDirectories(image.resolve("bin"));
                    return 0;
                }));
        var installer = new Installer(tools, new InstallationDirectories(temporaryDirectory.resolve("store"), commands),
                false, commandModule(), nativeLauncher(), fixedRuntimeModules());

        installer.install(
                new ApplicationTarget("com.example.foo", "1.2.3"),
                new InstallRequest(Optional.of("com.example.foo@1.2.3"), Optional.empty(), false, false,
                        Optional.of(output)),
                List.of("--module-path", application.toString(), "--module", "com.example.foo/com.example.Main"),
                new ByteArrayInputStream(new byte[0]),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()));

        assertTrue(Files.isRegularFile(output.resolve("bin/foo")));
        assertFalse(Files.exists(commands.resolve("foo")));
    }

    @Test
    void forcedStandaloneReplacementRetainsThePreviousImage() throws Exception {
        Path application = TestModules.writeJar(temporaryDirectory.resolve("foo.jar"), "com.example.foo");
        Path output = temporaryDirectory.resolve("images/foo");
        var generation = new AtomicInteger();
        ToolRuntime tools = ToolRuntime.of(tool(
                "jlink",
                (out, arguments) -> {
                    Path image = Path.of(arguments.get(arguments.indexOf("--output") + 1));
                    Files.createDirectories(image.resolve("bin"));
                    Files.writeString(image.resolve("release"), Integer.toString(generation.incrementAndGet()));
                    return 0;
                }));
        var installer = new Installer(
                tools,
                new InstallationDirectories(temporaryDirectory.resolve("store"), temporaryDirectory.resolve("commands")),
                false,
                commandModule(),
                nativeLauncher(),
                fixedRuntimeModules());
        var target = new ApplicationTarget("com.example.foo", "1.2.3");
        List<String> launch = List.of("--module-path", application.toString(), "--module", "com.example.foo/com.example.Main");

        installer.install(
                target,
                new InstallRequest(Optional.of("com.example.foo@1.2.3"), Optional.empty(), false, false,
                        Optional.of(output)),
                launch,
                new ByteArrayInputStream(new byte[0]),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()));
        installer.install(
                target,
                new InstallRequest(Optional.of("com.example.foo@1.2.3"), Optional.empty(), true, false,
                        Optional.of(output)),
                launch,
                new ByteArrayInputStream(new byte[0]),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()));

        assertEquals("2", Files.readString(output.resolve("release")));
        try (var children = Files.list(output.getParent())) {
            List<Path> previous = children.filter(path -> path.getFileName()
                    .toString()
                    .startsWith(".foo.old-"))
                    .toList();
            assertEquals(1, previous.size());
            assertEquals("1", Files.readString(previous.getFirst()
                    .resolve("release")));
        }
    }

    @Test
    void includesJavaSeWhenTheApplicationGraphContainsAnAutomaticModule() throws Exception {
        Path application = TestModules.writeJar(temporaryDirectory.resolve("foo.jar"), "com.example.foo", "com.example.library");
        Path automaticDependency = TestModules.writeAutomaticJar(temporaryDirectory.resolve("com.example.library-1.2.3.jar"));
        var jlinkArguments = new ArrayList<String>();
        ToolRuntime tools = ToolRuntime.of(tool(
                "jlink",
                (output, arguments) -> {
                    jlinkArguments.addAll(arguments);
                    Path image = Path.of(arguments.get(arguments.indexOf("--output") + 1));
                    Files.createDirectories(image.resolve("bin"));
                    return 0;
                }));
        var installer = new Installer(
                tools,
                new InstallationDirectories(temporaryDirectory.resolve("store"), temporaryDirectory.resolve("commands")),
                false,
                commandModule(),
                nativeLauncher());

        var errors = new ByteArrayOutputStream();
        installer.install(
                new ApplicationTarget("com.example.foo", "1.2.3"),
                new InstallRequest("com.example.foo@1.2.3", Optional.empty(), false),
                List.of("--module-path", application + System.getProperty("path.separator") + automaticDependency, "--module", "com.example.foo/com.example.Main"),
                new ByteArrayInputStream(new byte[0]),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(errors));

        String modulesArgument = jlinkArguments.get(jlinkArguments.indexOf("--add-modules") + 1);
        assertTrue(Set.of(modulesArgument.split(","))
                .contains("java.se"));
        assertEquals(
                """
                warning: dependencies resolved as automatic modules:
                  com.example.library
                Automatic modules require java.se, increasing the installed application size.
                """,
                errors.toString().replace(System.lineSeparator(), "\n"));
    }

    @Test
    void includesStaticApplicationRequirementsWhenRequested() throws Exception {
        Path application = TestModules.writeJarWithStatic(temporaryDirectory.resolve("foo.jar"), "com.example.foo", "com.example.optional");
        Path optional = TestModules.writeJar(temporaryDirectory.resolve("optional.jar"), "com.example.optional");
        Path store = temporaryDirectory.resolve("store");
        ToolRuntime tools = ToolRuntime.of(tool("jlink",
                (output, arguments) -> {
                    Path image = Path.of(arguments.get(arguments.indexOf("--output") + 1));
                    Files.createDirectories(image.resolve("bin"));
                    return 0;
                }));
        var installer = new Installer(tools, new InstallationDirectories(store, temporaryDirectory.resolve("commands")),
                false, commandModule(), nativeLauncher(), fixedRuntimeModules());

        installer.install(
                new ApplicationTarget("com.example.foo", "1.2.3"),
                new InstallRequest(Optional.of("com.example.foo@1.2.3"), Optional.empty(), false, true,
                        Optional.empty()),
                List.of("--module-path", application + System.getProperty("path.separator") + optional, "--module", "com.example.foo/com.example.Main"),
                new ByteArrayInputStream(new byte[0]),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()));

        assertEquals(-1L, Files.mismatch(optional, store.resolve("com.example.foo@1.2.3/app/modules/optional.jar")));
    }

    @Test
    void versionsTheGeneratedCommandModuleFromTheApplication() throws Exception {
        Path application = TestModules.writeAutomaticJar(temporaryDirectory.resolve("com.example.foo-1.2.3.jar"));
        var javacArguments = new ArrayList<String>();
        ToolRuntime tools = ToolRuntime.of(tool(
                "javac",
                (output, arguments) -> {
                    javacArguments.addAll(arguments);
                    Path classes = Path.of(arguments.get(arguments.indexOf("-d") + 1));
                    Files.createDirectories(classes);
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
                new InstallationDirectories(temporaryDirectory.resolve("store"), temporaryDirectory.resolve("commands")),
                false,
                null,
                nativeLauncher());

        installer.install(
                new ApplicationTarget("com.example.foo", "1.2.3"),
                new InstallRequest("com.example.foo@1.2.3", Optional.empty(), false),
                List.of("--module-path", application.toString(), "--module", "com.example.foo/com.example.Main"),
                new ByteArrayInputStream(new byte[0]),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()));

        int versionOption = javacArguments.indexOf("--module-version");
        assertTrue(versionOption >= 0, javacArguments.toString());
        assertEquals("1.2.3", javacArguments.get(versionOption + 1));
        assertTrue(Files.isDirectory(temporaryDirectory.resolve("store/com.example.foo@1.2.3/app/modules/com.example.foo.launcher")));
    }

    @Test
    void requiresForceToReinstallTheSameApplicationVersion() throws Exception {
        Path application = TestModules.writeJar(temporaryDirectory.resolve("foo.jar"), "com.example.foo");
        Path store = temporaryDirectory.resolve("store");
        Path commands = temporaryDirectory.resolve("commands");
        ToolRuntime tools = ToolRuntime.of(tool("jlink",
                (out, arguments) -> {
                    Path output = Path.of(arguments.get(arguments.indexOf("--output") + 1));
                    Files.createDirectories(output.resolve("bin"));
                    return 0;
                }));
        var installer = new Installer(tools, new InstallationDirectories(store, commands), false, commandModule(),
                nativeLauncher(), fixedRuntimeModules());
        var target = new ApplicationTarget("com.example.foo", "1.2.3");
        var request = new InstallRequest("com.example.foo@1.2.3", Optional.empty(), false);
        List<String> launch = List.of("--module-path", application.toString(), "--module", "com.example.foo/com.example.Main");

        installer.install(target, request, launch, new ByteArrayInputStream(new byte[0]),
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));

        FileAlreadyExistsException failure = assertThrows(
                FileAlreadyExistsException.class,
                () -> installer.install(target, request, launch, new ByteArrayInputStream(new byte[0]),
                        new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream())));

        assertTrue(failure.getMessage().contains("use --force"),
                failure.getMessage());

        int result = installer.install(target, new InstallRequest("com.example.foo@1.2.3", Optional.empty(), true), launch, new ByteArrayInputStream(new byte[0]),
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, result);
        Path replacement = activeEntrypoint(commands, "foo").getParent().getParent();
        assertNotEquals(store.resolve("com.example.foo@1.2.3")
                             .toRealPath(),
                replacement);
        assertTrue(replacement.getFileName()
                              .toString()
                              .startsWith("com.example.foo@1.2.3+"));
        assertTrue(Files.isDirectory(store.resolve("com.example.foo@1.2.3")));
    }

    @Test
    void applicationHashTracksAotFileMetadata() throws Exception {
        Path application = TestModules.writeJar(temporaryDirectory.resolve("foo.jar"), "com.example.foo");
        Path commandClasses = Files.createDirectories(temporaryDirectory.resolve("command"));
        Files.writeString(commandClasses.resolve("module-info.class"), "command");
        FileTime initialTime = FileTime.fromMillis(1_000);
        Files.setLastModifiedTime(application, initialTime);
        Files.setLastModifiedTime(commandClasses.resolve("module-info.class"), initialTime);
        Path store = temporaryDirectory.resolve("store");
        Path commands = temporaryDirectory.resolve("commands");
        var installer = new Installer(ToolRuntime.of(tool("jlink",
                (out, arguments) -> {
                    Path output = Path.of(arguments.get(arguments.indexOf("--output") + 1));
                    Files.createDirectories(output.resolve("bin"));
                    return 0;
                })),
                new InstallationDirectories(store, temporaryDirectory.resolve("commands")), false, (name, target, arguments, output, in, out, err) ->
                        new Generated(InstalledCommandModule.moduleName(target.moduleName()), commandClasses, Optional.of("com.example")),
                nativeLauncher(), fixedRuntimeModules());
        var target = new ApplicationTarget("com.example.foo", "1.2.3");
        List<String> launch = List.of("--module-path", application.toString(), "--module", "com.example.foo/com.example.Main");

        installer.install(target, new InstallRequest("com.example.foo@1.2.3", Optional.empty(), false), launch, new ByteArrayInputStream(new byte[0]),
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));
        Path hash = store.resolve("com.example.foo@1.2.3/app/modules.hash");
        String initialHash = Files.readString(hash);

        Files.setLastModifiedTime(application, FileTime.fromMillis(2_000));
        installer.install(target, new InstallRequest("com.example.foo@1.2.3", Optional.empty(), true), launch, new ByteArrayInputStream(new byte[0]),
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));

        Path activeImage = activeEntrypoint(commands, "foo").getParent().getParent();
        assertNotEquals(initialHash, Files.readString(activeImage.resolve("app/modules.hash")));
        assertEquals(2L, Files.getLastModifiedTime(activeImage.resolve("app/modules/foo.jar"))
                .to(TimeUnit.SECONDS));
    }

    @Test
    void leavesNoApplicationWhenRuntimeLinkingFails() throws Exception {
        Path repository = Files.createDirectories(temporaryDirectory.resolve("repository"));
        Path application = Files.writeString(repository.resolve("foo-1.2.3.jar"), "application");
        Path dependency = Files.writeString(repository.resolve("library-4.5.6.jar"), "dependency");
        Path store = temporaryDirectory.resolve("store");
        Path commands = temporaryDirectory.resolve("commands");
        ToolRuntime tools = ToolRuntime.of(tool("jlink", (out, arguments) -> 1));
        var installer = new Installer(tools, new InstallationDirectories(store, commands), false, commandModule(),
                nativeLauncher(), fixedRuntimeModules());

        var errors = new ByteArrayOutputStream();
        assertThrows(
                ToolExecutionException.class,
                () -> installer.install(
                        new ApplicationTarget("com.example.foo", "1.2.3"),
                        new InstallRequest("com.example.foo@1.2.3", Optional.empty(), false),
                        List.of("--module-path", application + System.getProperty("path.separator") + dependency,
                                "--enable-native-access", "com.example.foo", "--module", "com.example.foo/com.example.Main"),
                        new ByteArrayInputStream(new byte[0]),
                        new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(errors)));
        assertFalse(Files.exists(store.resolve("com.example.foo@1.2.3")));
    }

    @Test
    void rejectsWindowsLauncherThatWouldBeShadowedByAnExecutable() throws Exception {
        Path store = Files.createDirectories(temporaryDirectory.resolve("windows"));
        Files.writeString(store.resolve("foo.exe"), "other launcher");
        var installer = new Installer(
                ToolRuntime.of(tool("jlink", (out, arguments) -> 0)),
                new InstallationDirectories(store, store),
                true,
                commandModule(),
                nativeLauncher(),
                fixedRuntimeModules());

        assertThrows(
                FileAlreadyExistsException.class,
                () -> installer.install(
                        new ApplicationTarget("com.example.foo", "1.2.3"),
                        new InstallRequest("com.example.foo@1.2.3", Optional.empty(), false),
                        List.of("--module-path", temporaryDirectory.resolve("foo.jar").toString(),
                                "--module", "com.example.foo/com.example.Main"),
                        new ByteArrayInputStream(new byte[0]),
                        new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream())));
    }

    @Test
    void exportsWindowsCommandThroughPathextName() throws Exception {
        Path application = TestModules.writeJar(temporaryDirectory.resolve("foo.jar"), "com.example.foo");
        Path root = temporaryDirectory.resolve("windows");
        ToolRuntime tools = ToolRuntime.of(tool(
                "jlink",
                (out, arguments) -> {
                    Path image = Path.of(arguments.get(arguments.indexOf("--output") + 1));
                    Files.createDirectories(image.resolve("bin"));
                    Files.createDirectories(image.resolve("lib"));
                    Files.writeString(image.resolve("bin/java.exe"), "java");
                    Files.writeString(image.resolve("lib/modules"), "modules");
                    return 0;
                }));
        var installer = new Installer(tools, new InstallationDirectories(root, root), true, commandModule(),
                nativeLauncher(), fixedRuntimeModules());

        installer.install(
                new ApplicationTarget("com.example.foo", "1.2.3"),
                new InstallRequest("com.example.foo@1.2.3", Optional.empty(), false),
                List.of("--module-path", application.toString(), "--module", "com.example.foo/com.example.Main"),
                new ByteArrayInputStream(new byte[0]),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()));

        assertTrue(Files.isRegularFile(root.resolve("foo.exe")));
        assertEquals(root.resolve("com.example.foo@1.2.3/bin/foo.exe").toRealPath(),
                activeEntrypoint(root, "foo"));
    }

    @Test
    void activatesTheLastInstalledVersionOfTheSameModule() throws Exception {
        Path application = TestModules.writeJar(temporaryDirectory.resolve("foo.jar"), "com.example.foo");
        Path store = temporaryDirectory.resolve("store");
        Path commands = temporaryDirectory.resolve("commands");
        ToolRuntime tools = ToolRuntime.of(tool(
                "jlink",
                (out, arguments) -> {
                    Path image = Path.of(arguments.get(arguments.indexOf("--output") + 1));
                    Files.createDirectories(image.resolve("bin"));
                    Files.createDirectories(image.resolve("lib"));
                    Files.writeString(image.resolve("bin/java"), "java");
                    Files.writeString(image.resolve("lib/modules"), "modules");
                    return 0;
                }));
        var installer = new Installer(tools, new InstallationDirectories(store, commands), false, commandModule(),
                nativeLauncher(), fixedRuntimeModules());
        List<String> launch = List.of("--module-path", application.toString(), "--module", "com.example.foo/com.example.Main");

        for (String version : List.of("1.0", "2.0")) {
            installer.install(
                    new ApplicationTarget("com.example.foo", version),
                    new InstallRequest("com.example.foo@" + version, Optional.empty(), false),
                    launch,
                    new ByteArrayInputStream(new byte[0]),
                    new PrintStream(new ByteArrayOutputStream()),
                    new PrintStream(new ByteArrayOutputStream()));
        }

        assertTrue(Files.isDirectory(store.resolve("com.example.foo@1.0")));
        assertTrue(Files.isDirectory(store.resolve("com.example.foo@2.0")));
        assertEquals(store.resolve("com.example.foo@2.0/bin/foo").toRealPath(),
                activeEntrypoint(commands, "foo"));
    }

    private static CommandModuleFactory commandModule() {
        return (name, target, arguments, output, in, out, err) -> {
            Path classes = Files.createDirectories(output.resolve("classes"));
            Files.writeString(classes.resolve("module-info.class"), "command");
            return new Generated(InstalledCommandModule.moduleName(target.moduleName()),
                    classes, Optional.of("com.example"));
        };
    }

    private static void writeModuleInfo(Path directory, String moduleName) throws Exception {
        TestModules.writeModuleInfo(directory, moduleName);
    }

    private static NativeLauncher nativeLauncher() {
        return destination -> {
            Files.createDirectories(destination.getParent());
            Files.writeString(destination, "native");
        };
    }

    private static Path activeEntrypoint(Path commands, String name) throws Exception {
        Path current = commands.resolve(name + ".current");
        Path target = Path.of(Files.readString(current)
                .strip());
        return (target.isAbsolute() ? target : current.getParent().resolve(target)).toRealPath();
    }

    private static RuntimeModuleResolver fixedRuntimeModules() {
        return (target, arguments, includeStatic) -> Set.of("com.netflix.tools.launcher", "java.base");
    }

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
