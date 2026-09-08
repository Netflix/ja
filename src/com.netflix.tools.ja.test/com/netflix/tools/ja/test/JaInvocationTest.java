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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.netflix.tools.ja.BuiltinCommand;
import com.netflix.tools.ja.Command.Builtin;
import com.netflix.tools.ja.Command.Doc;
import com.netflix.tools.ja.Command.Init;
import com.netflix.tools.ja.Command.Install;
import com.netflix.tools.ja.Command.Require;
import com.netflix.tools.ja.Command.Run;
import com.netflix.tools.ja.Command.Source;
import com.netflix.tools.ja.Command.Tool;
import com.netflix.tools.ja.Command.Tools;
import com.netflix.tools.ja.DocRequest.Browse;
import com.netflix.tools.ja.DocRequest.Terminal;
import com.netflix.tools.ja.InitRequest;
import com.netflix.tools.ja.InstallRequest;
import com.netflix.tools.ja.JaInvocation;
import com.netflix.tools.ja.ModuleSourcePath;
import com.netflix.tools.ja.RequireRequest;
import com.netflix.tools.ja.RequireRequest.Dependency;
import com.netflix.tools.ja.RequireRequest.RuntimeAccess;
import com.netflix.tools.ja.RequireRequest.UpdatePolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JaInvocationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void parsesInitWithoutDiscoveringAModuleSourcePath() throws IOException {
        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"init", "com.example.application"});

        assertEquals(new Init(new InitRequest("com.example.application", Optional.empty(), Optional.empty(), false,
                List.of())),
                commandLine.command());
        assertTrue(commandLine.moduleSourcePath()
                              .isEmpty());
        assertEquals(List.of(), commandLine.rootModules());
        assertEquals(List.of(), commandLine.resolutionArguments());
    }

    @Test
    void parsesStandardModuleOptionsForInit() throws IOException {
        var commandLine = JaInvocation.parse(
                temporaryDirectory,
                new String[] {
                    "init",
                    "--release=25",
                    "--main-class",
                    "com.example.application.Main",
                    "--enable-preview",
                    "--enable-native-access",
                    "org.example.nativebinding,org.example.other",
                    "--enable-final-field-mutation=org.example.model",
                    "--add-opens",
                    "java.base/java.lang=com.example.application,org.example.friend",
                    "--add-exports=jdk.compiler/com.sun.tools.javac.tree=com.example.application",
                    "com.example.application"
                });

        assertEquals(
                new Init(
                        new InitRequest(
                                "com.example.application",
                                Optional.of(25),
                                Optional.of("com.example.application.Main"),
                                true,
                                List.of(
                                        new RuntimeAccess("enableNativeAccess", "org.example.nativebinding"),
                                        new RuntimeAccess("enableNativeAccess", "org.example.other"),
                                        new RuntimeAccess("enableFinalFieldMutation", "org.example.model"),
                                        new RuntimeAccess("addExports", "jdk.compiler/com.sun.tools.javac.tree=com.example.application"),
                                        new RuntimeAccess("addOpens", "java.base/java.lang=com.example.application"),
                                        new RuntimeAccess("addOpens", "java.base/java.lang=org.example.friend")))),
                commandLine.command());
    }

    @Test
    void initRejectsDuplicateAndUnknownOptions() {
        var duplicateRelease = assertThrows(
                IllegalArgumentException.class,
                () -> JaInvocation.parse(temporaryDirectory,
                        new String[] {"init", "--release", "21", "--release=25", "com.example.app"}));
        var duplicateMainClass = assertThrows(
                IllegalArgumentException.class,
                () -> JaInvocation.parse(temporaryDirectory,
                        new String[] {"init", "--main-class", "com.example.Main", "--main-class=com.example.Other", "com.example.app"}));
        var unknown = assertThrows(IllegalArgumentException.class,
                () -> JaInvocation.parse(temporaryDirectory, new String[] {"init", "--process-with", "com.example.processor", "com.example.app"}));

        assertEquals("--release may only be specified once", duplicateRelease.getMessage());
        assertEquals("--main-class may only be specified once", duplicateMainClass.getMessage());
        assertEquals("Unknown init argument: --process-with", unknown.getMessage());
    }

    @Test
    void initRequiresExactlyOneValidModuleName() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class, () -> JaInvocation.parse(temporaryDirectory, new String[] {"init"}));
        IllegalArgumentException extra = assertThrows(IllegalArgumentException.class, () -> JaInvocation.parse(temporaryDirectory, new String[] {"init", "com.example.app", "other"}));
        IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class, () -> JaInvocation.parse(temporaryDirectory, new String[] {"init", "com.example-app"}));

        assertEquals("init requires a module name", missing.getMessage());
        assertEquals("init accepts one module name", extra.getMessage());
        assertEquals("Invalid module name: com.example-app", invalid.getMessage());
    }

    @Test
    void parsesTerminalDocumentationRequest() throws IOException {
        module(temporaryDirectory.resolve("src"), "com.example.app");

        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"doc", "java.lang.String.isEmpty"});

        assertEquals(new Doc(new Terminal("java.lang.String.isEmpty")), commandLine.command());
        assertEquals(List.of(), commandLine.toolArguments());
    }

    @Test
    void parsesSourceRequest() throws IOException {
        module(temporaryDirectory.resolve("src"), "com.example.app");

        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"source", "java.lang.String.isEmpty"});

        assertEquals(new Source("java.lang.String.isEmpty"), commandLine.command());
        assertEquals(List.of(), commandLine.toolArguments());
    }

    @Test
    void sourceRequiresExactlyOneSymbol() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class, () -> JaInvocation.parse(temporaryDirectory, new String[] {"source"}));
        IllegalArgumentException extra = assertThrows(IllegalArgumentException.class, () -> JaInvocation.parse(temporaryDirectory, new String[] {"source", "java.lang.String", "java.lang.Integer"}));

        assertEquals("source requires a symbol", missing.getMessage());
        assertEquals("source accepts only one symbol", extra.getMessage());
    }

    @Test
    void parsesBrowserDocumentationRequest() throws IOException {
        module(temporaryDirectory.resolve("src"), "com.example.app");

        JaInvocation overview = JaInvocation.parse(temporaryDirectory, new String[] {"doc", "--browse"});
        JaInvocation type = JaInvocation.parse(temporaryDirectory, new String[] {"doc", "--browse", "java.lang.String"});

        assertEquals(new Doc(new Browse(Optional.empty())), overview.command());
        assertEquals(new Doc(new Browse(Optional.of("java.lang.String"))), type.command());
    }

    @Test
    void documentationRequiresASymbolOrBrowseMode() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> JaInvocation.parse(temporaryDirectory, new String[] {"doc"}));

        assertEquals("doc requires a symbol or --browse", failure.getMessage());
    }

    @Test
    void documentationAcceptsOnlyOneTarget() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> JaInvocation.parse(temporaryDirectory, new String[] {"doc", "java.lang.String", "java.lang.Integer"}));

        assertEquals("doc accepts only one symbol or type", failure.getMessage());
    }

    @Test
    void parsesVerboseBeforeTheCommand() throws IOException {
        module(temporaryDirectory.resolve("src"), "com.example.app");

        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"--verbose", "compile"});

        assertTrue(commandLine.verbose());
        assertEquals(new Builtin(BuiltinCommand.COMPILE), commandLine.command());
    }

    @Test
    void rejectsDuplicateVerboseOptions() {
        var failure = assertThrows(IllegalArgumentException.class, () -> JaInvocation.parse(temporaryDirectory, new String[] {"--verbose", "--verbose", "compile"}));

        assertEquals("--verbose may only be specified once", failure.getMessage());
    }

    @Test
    void suppliedWorkingDirectoryControlsModuleSourcePathDiscovery() throws IOException {
        Path moduleRoot = temporaryDirectory.resolve("module-root");
        module(moduleRoot.resolve("src"), "com.example.app");

        var commandLine = JaInvocation.parse(moduleRoot, new String[] {"compile"});

        assertEquals(moduleRoot.toAbsolutePath().normalize(),
                commandLine.workingDirectory());
        assertEquals(List.of("com.example.app"), commandLine.rootModules());
        assertEquals(
                List.of("--module-source-path", moduleRoot.resolve("src").toString(),
                        "-m", "com.example.app"),
                commandLine.resolutionArguments());
    }

    @Test
    void doesNotApplyOrRejectJavaToolOptions() throws IOException {
        Path options = Files.createDirectories(temporaryDirectory.resolve(".java-tool-options"));
        Files.writeString(options.resolve("javac.args"), "--release\n25\n");
        Path legacyOptions = Files.createDirectories(temporaryDirectory.resolve(".java/options"));
        Files.writeString(legacyOptions.resolve("compile.args"), "--release\n24\n");
        Path moduleRoot = temporaryDirectory.resolve("module-root");
        module(moduleRoot.resolve("src"), "com.example.app");

        var commandLine = JaInvocation.parse(moduleRoot, new String[] {"compile"});

        assertEquals(List.of("com.example.app"), commandLine.rootModules());
    }

    @Test
    void moduleSourcePathRootDefaultsToEveryModule() throws IOException {
        Path sourcePath = temporaryDirectory.resolve("src");
        module(sourcePath, "com.example.app");
        module(sourcePath, "com.example.lib");

        var commandLine = JaInvocation.parse(temporaryDirectory,
                new String[] {"test", "-t", "fast", "--tag", "unit"});

        assertEquals(new Builtin(BuiltinCommand.TEST), commandLine.command());
        assertEquals(List.of("com.example.app", "com.example.lib"), commandLine.rootModules());
        assertEquals(
                List.of("--module-source-path", sourcePath.toString(), "-m", "com.example.app", "-m",
                        "com.example.lib"),
                commandLine.resolutionArguments());
        assertEquals(List.of("-t", "fast", "--tag", "unit"), commandLine.toolArguments());
    }

    @Test
    void enclosingModuleOverridesModuleSourcePathDefault() throws IOException {
        Path sourcePath = temporaryDirectory.resolve("src");
        module(sourcePath, "com.example.app");
        module(sourcePath, "com.example.lib");

        var commandLine = JaInvocation.parse(sourcePath.resolve("com.example.app"), new String[] {"test"});

        assertEquals(List.of("com.example.app"), commandLine.rootModules());
        assertEquals(
                List.of("--module-source-path", sourcePath.toString(), "-m", "com.example.app"),
                commandLine.resolutionArguments());
    }

    @Test
    void moduleSelectionBeforeTheCommandIsRejected() throws IOException {
        module(temporaryDirectory.resolve("src"), "com.example.app");

        for (String[] arguments : List.of(
                new String[] {"-m", "com.example.app", "test"},
                new String[] {"--module", "com.example.app", "test"},
                new String[] {"--module=com.example.app", "test"})) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> JaInvocation.parse(temporaryDirectory, arguments));

            assertEquals("Unknown ja option: " + arguments[0], failure.getMessage());
        }
    }

    @Test
    void toolModuleQualifierRemainsAToolArgument() throws IOException {
        module(temporaryDirectory.resolve("src"), "com.example.app");

        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"tool", "probe", "--module", "com.example.app"});

        assertEquals(List.of("--module", "com.example.app"), commandLine.toolArguments());
    }

    @Test
    void runModuleQualifierAcceptsAModuleWithoutAMainClass() throws IOException {
        module(temporaryDirectory.resolve("src"), "com.example.app");

        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"run", "-m", "com.example.app", "argument"});

        assertEquals(List.of(), commandLine.rootModules());
        assertEquals(Optional.of("com.example.app"), runTarget(commandLine));
        assertEquals(List.of("argument"), commandLine.toolArguments());
    }

    @Test
    void unqualifiedRunArgumentsRemainApplicationArguments() throws IOException {
        module(temporaryDirectory.resolve("src"), "com.example.app");

        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"run", "data/input.txt"});

        assertEquals(Optional.empty(), runTarget(commandLine));
        assertEquals(List.of("data/input.txt"), commandLine.toolArguments());
    }

    @Test
    void rejectsMalformedRunModuleQualifier() throws IOException {
        module(temporaryDirectory.resolve("src"), "com.example.app");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> JaInvocation.parse(temporaryDirectory, new String[] {"run", "--module=com.example.app/"}));

        assertEquals("run --module expects module[/mainClass]: com.example.app/", failure.getMessage());
    }

    @Test
    void builtInArgumentsArePassedToItsFixedTool() throws IOException {
        module(temporaryDirectory.resolve("src"), "com.example.app");

        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"test", "--tool", "junit"});

        assertEquals(List.of("--tool", "junit"), commandLine.toolArguments());
        assertEquals(
                List.of("--module-source-path", temporaryDirectory.resolve("src").toString(),
                        "-m", "com.example.app"),
                commandLine.resolutionArguments());
    }

    @Test
    void toolSelectionMustFollowTheCommand() throws IOException {
        module(temporaryDirectory.resolve("src"), "com.example.app");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> JaInvocation.parse(temporaryDirectory, new String[] {"--tool", "junit", "test"}));

        assertEquals("Unknown ja option: --tool", failure.getMessage());
    }

    @Test
    void runPassesToolNamedArgumentsToTheApplication() throws IOException {
        module(temporaryDirectory.resolve("src"), "com.example.app");

        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"run", "--tool", "launcher"});

        assertEquals(List.of("--tool", "launcher"), commandLine.toolArguments());
    }

    @Test
    void separatorPassesToolOptionToApplication() throws IOException {
        module(temporaryDirectory.resolve("src"), "com.example.app");

        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"run", "--", "--tool", "application-tool"});

        assertEquals(List.of("--tool", "application-tool"), commandLine.toolArguments());
    }

    @Test
    void separatorPassesToolOptionToComposableTool() throws IOException {
        module(temporaryDirectory.resolve("src"), "com.example.app");

        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"test", "--", "--tool", "test-tool"});

        assertEquals(List.of("--tool", "test-tool"), commandLine.toolArguments());
    }

    @Test
    void rejectsResolutionArguments() throws IOException {
        module(temporaryDirectory.resolve("src"), "com.example.app");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> JaInvocation.parse(temporaryDirectory, new String[] {"--module-path", "modules", "compile"}));

        assertEquals("Unknown ja option: --module-path", failure.getMessage());
    }

    @Test
    void compileAcceptsRecompile() throws IOException {
        module(temporaryDirectory.resolve("src"), "com.example.app");

        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"compile", "--recompile"});

        assertEquals(
                List.of(
                        "--recompile",
                        "--module-source-path",
                        temporaryDirectory.resolve("src").toString(),
                        "-m",
                        "com.example.app"),
                commandLine.resolutionArguments());
    }

    @Test
    void compileRejectsUnknownOptions() throws IOException {
        module(temporaryDirectory.resolve("src"), "com.example.app");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> JaInvocation.parse(temporaryDirectory, new String[] {"compile", "-d", "modules"}));

        assertEquals("Unknown compile option: -d", failure.getMessage());
    }

    @Test
    void rejectsJigResolutionFlags() throws IOException {
        module(temporaryDirectory.resolve("src"), "com.example.app");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> JaInvocation.parse(temporaryDirectory, new String[] {"--prefer-jmod", "compile"}));

        assertEquals("Unknown ja option: --prefer-jmod", failure.getMessage());
    }

    @Test
    void parsesRequireRequestForOneSelectedSourceModule() throws IOException {
        Path sourcePath = temporaryDirectory.resolve("src");
        module(sourcePath, "com.example.app");
        module(sourcePath, "com.example.lib");

        var commandLine = JaInvocation.parse(sourcePath.resolve("com.example.app"),
                new String[] {"require", "--static", "--transitive", "org.example.library@1.2.3"});

        assertEquals(new Require(new RequireRequest(List.of(new Dependency("org.example.library", "1.2.3")), true, true)),
                commandLine.command());
    }

    @Test
    void requireNeedsExactlyOneSelectedSourceModule() throws IOException {
        Path sourcePath = temporaryDirectory.resolve("src");
        module(sourcePath, "com.example.app");
        module(sourcePath, "com.example.lib");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> JaInvocation.parse(temporaryDirectory, new String[] {"require", "org.example.library@1.2.3"}));

        assertEquals("require needs exactly one selected source module", failure.getMessage());
    }

    @Test
    void requireUpdateDefaultsToEveryModuleOnTheSourcePath() throws IOException {
        Path sourcePath = temporaryDirectory.resolve("src");
        module(sourcePath, "com.example.app");
        module(sourcePath, "com.example.lib");

        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"require", "-u"});

        assertEquals(List.of("com.example.app", "com.example.lib"), commandLine.rootModules());
    }

    @Test
    void parsesBareCompatibleRequireUpdate() throws IOException {
        module(temporaryDirectory.resolve("src"), "com.example.app");

        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"require", "-u"});

        assertEquals(new Require(new RequireRequest(List.of(), List.of(), UpdatePolicy.MINOR, false,
                false)),
                commandLine.command());
    }

    @Test
    void parsesNamedRequireUpdatePolicy() throws IOException {
        module(temporaryDirectory.resolve("src"), "com.example.app");

        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"require", "-u=patch", "org.example.library"});

        assertEquals(
                new RequireRequest(List.of(), List.of("org.example.library"), UpdatePolicy.PATCH, false,
                        false),
                requireRequest(commandLine));
    }

    @Test
    void requireUpdateRejectsExplicitVersions() throws IOException {
        module(temporaryDirectory.resolve("src"), "com.example.app");

        var failure = assertThrows(IllegalArgumentException.class, () -> JaInvocation.parse(temporaryDirectory, new String[] {"require", "--update=major", "org.example.library@2.0.0"}));

        assertTrue(failure.getMessage()
                          .contains("cannot combine --update with an explicit version"));
    }

    @Test
    void requireUpdateRejectsRuntimeAccess() throws IOException {
        module(temporaryDirectory.resolve("src"), "com.example.app");

        var failure = assertThrows(IllegalArgumentException.class, () -> JaInvocation.parse(temporaryDirectory, new String[] {"require", "--update", "--enable-native-access=com.example.library"}));

        assertEquals("require cannot combine --update with runtime access", failure.getMessage());
    }

    @Test
    void jigIntegrityModesAreNotJaOptions() throws IOException {
        module(temporaryDirectory.resolve("src"), "com.example.app");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> JaInvocation.parse(temporaryDirectory, new String[] {"--verify-module-hashes", "compile"}));

        assertEquals("Unknown ja option: --verify-module-hashes", failure.getMessage());
    }

    @Test
    void rejectsExplicitModuleSourcePath() throws IOException {
        module(temporaryDirectory.resolve("src"), "com.example.app");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> JaInvocation.parse(temporaryDirectory, new String[] {"--module-source-path", "custom/*", "test"}));

        assertEquals("Unknown ja option: --module-source-path", failure.getMessage());
    }

    @Test
    void parsesAdditionalToolCommand() throws IOException {
        module(temporaryDirectory.resolve("src"), "com.example.app");

        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"tool", "jshell", "--help"});

        assertEquals(new Tool("jshell"), commandLine.command());
        assertEquals(List.of("--help"), commandLine.toolArguments());
    }

    @Test
    void directProviderToolsAreNotTopLevelCommands() throws IOException {
        module(temporaryDirectory.resolve("src"), "com.example.app");

        for (String name : List.of("shell", "deps")) {
            var failure = assertThrows(IllegalArgumentException.class, () -> JaInvocation.parse(temporaryDirectory, new String[] {name}));

            assertEquals("Unknown command: " + name, failure.getMessage());
        }
    }

    @Test
    void archiveToolsAreNotTopLevelCommands() throws IOException {
        module(temporaryDirectory.resolve("src"), "com.example.app");

        for (String name : List.of("jar", "mod")) {
            var failure = assertThrows(IllegalArgumentException.class, () -> JaInvocation.parse(temporaryDirectory, new String[] {name}));

            assertEquals("Unknown command: " + name, failure.getMessage());
        }
    }

    @Test
    void parsesBareToolCommand() throws IOException {
        module(temporaryDirectory.resolve("src"), "com.example.app");

        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"tool"});

        assertEquals(new Tools(), commandLine.command());
        assertEquals(List.of(), commandLine.toolArguments());
    }

    @Test
    void retainsAssemblyArguments() throws IOException {
        module(temporaryDirectory.resolve("src"), "com.example.app");

        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"assemble", "--module-version", "1.2.3", "build/artifacts"});

        assertEquals(new Builtin(BuiltinCommand.ASSEMBLE), commandLine.command());
        assertEquals(List.of("--module-version", "1.2.3", "build/artifacts"), commandLine.toolArguments());
        assertEquals(List.of("com.example.app"), commandLine.rootModules());
    }

    @Test
    void retainsMavenOperationArguments() throws IOException {
        module(temporaryDirectory.resolve("src"), "com.example.app");

        var export = JaInvocation.parse(temporaryDirectory, new String[] {"maven", "export", "."});
        var deploy = JaInvocation.parse(temporaryDirectory,
                new String[] {"maven", "deploy", "--module-version", "1.2.3", "--repository", "releases=https://example.test"});

        assertEquals(new Builtin(BuiltinCommand.MAVEN), export.command());
        assertEquals(List.of("export", "."), export.toolArguments());
        assertEquals(List.of("com.example.app"), export.rootModules());
        assertEquals(
                List.of("deploy", "--module-version", "1.2.3", "--repository", "releases=https://example.test"),
                deploy.toolArguments());
    }

    @Test
    void parsesExternalApplicationInstallWithoutSourceDiscovery() throws IOException {
        module(temporaryDirectory.resolve("src"), "com.example.source");

        var commandLine = JaInvocation.parse(temporaryDirectory,
                new String[] {"install", "com.example.foo@1.2.3", "--name", "foo-tool", "--force"});

        assertEquals(
                new Install(new InstallRequest("com.example.foo@1.2.3", Optional.of("foo-tool"), true)),
                commandLine.command());
        assertEquals(Optional.empty(), commandLine.moduleSourcePath());
        assertEquals(List.of(), commandLine.resolutionArguments());
    }

    @Test
    void rejectsJdkInstallOption() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> JaInvocation.parse(temporaryDirectory, new String[] {"install", "--jdk", "base-jdk", "com.example.foo@1.0"}));

        assertEquals("Unknown install argument: --jdk", failure.getMessage());
    }

    @Test
    void rejectsPreferJmodInstallOption() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> JaInvocation.parse(temporaryDirectory, new String[] {"install", "--prefer-jmod", "com.example.foo@1.0"}));

        assertEquals("Unknown install argument: --prefer-jmod", failure.getMessage());
    }

    @Test
    void parsesStandaloneInstallOutput() throws IOException {
        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"install", "--output=images/ja", "com.netflix.tools.ja@1.2.3"});

        assertEquals(Optional.of(temporaryDirectory.resolve("images/ja")), installRequest(commandLine).output());
    }

    @Test
    void bareInstallSelectsTheCurrentSourceModule() throws IOException {
        Path sourcePath = temporaryDirectory.resolve("src");
        module(sourcePath, "com.example.app");
        Path source = sourcePath.resolve("com.example.app");

        var commandLine = JaInvocation.parse(source, new String[] {"install"});

        assertEquals(Optional.empty(), installRequest(commandLine).target());
        assertEquals(List.of("com.example.app"), commandLine.rootModules());
        assertEquals(Optional.of("com.example.app"),
                commandLine.moduleSourcePath().flatMap(ModuleSourcePath::enclosingModule));
    }

    @Test
    void bareInstallLetsJigInferTheApplicationRoot() throws IOException {
        Path source = temporaryDirectory.resolve("src");
        module(source, "com.example.app");
        module(source, "com.example.other");

        var commandLine = JaInvocation.parse(temporaryDirectory, new String[] {"install"});

        assertEquals(List.of(), commandLine.rootModules());
        assertFalse(commandLine.resolutionArguments()
                               .contains("-m"));
    }

    @Test
    void enclosingModuleSelectsABareInstall() throws IOException {
        Path source = temporaryDirectory.resolve("src");
        module(source, "com.example.app");
        module(source, "com.example.other");

        var commandLine = JaInvocation.parse(source.resolve("com.example.app"), new String[] {"install"});

        assertEquals(List.of("com.example.app"), commandLine.rootModules());
        assertEquals(Optional.empty(), installRequest(commandLine).target());
    }

    private static void module(Path sourcePath, String name) throws IOException {
        Path directory = Files.createDirectories(sourcePath.resolve(name));
        Files.writeString(directory.resolve("module-info.java"), "module " + name + " {}\n");
    }

    private static Optional<String> runTarget(JaInvocation commandLine) {
        return ((Run) commandLine.command()).target();
    }

    private static RequireRequest requireRequest(JaInvocation commandLine) {
        return ((Require) commandLine.command()).request();
    }

    private static InstallRequest installRequest(JaInvocation commandLine) {
        return ((Install) commandLine.command()).request();
    }
}
