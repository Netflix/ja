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
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.netflix.tools.ja.JmodPackager.Artifacts;

/**
 * Assembles flat, module-named binary, source, documentation, deployment
 * metadata, and JMOD artifacts.
 */
final class ModuleAssembler {
    record Options(String version, String targetPlatform, boolean jmod) {}

    private record Plan(
            String moduleName,
            Path moduleSource,
            Map<String, Path> observableSources,
            Path compilationRoot,
            Path compileArguments,
            Path runtimeArguments,
            Path jarArguments,
            Path javadocArguments,
            Path javadocOutput,
            String targetPlatform,
            boolean jmod) {}

    private static final List<String> JAVADOC_TAGS = List.of(
            "release:X",
            "mainClass:X",
            "enablePreview:X",
            "processWith:X",
            "enableNativeAccess:X",
            "enableFinalFieldMutation:X",
            "addOpens:X",
            "addExports:X");

    private final ToolRuntime tools;
    private final List<ToolDefinition> definitions;
    private final ResolutionOptions javadocOptions;
    private final JarPackager jars;
    private final JmodPackager jmods;

    ModuleAssembler(ToolRuntime tools, List<ToolDefinition> definitions, ResolutionOptions javadocOptions) {
        this.tools = tools;
        this.definitions = List.copyOf(definitions);
        this.javadocOptions = javadocOptions;
        this.jars = new JarPackager(tools);
        this.jmods = new JmodPackager(tools);
    }

    int assemble(
            JaInvocation commandLine,
            ModuleSourcePath moduleSourcePath,
            Options options,
            Path destination,
            InputStream in,
            PrintStream out,
            PrintStream err)
            throws IOException {
        requireSourceModules(commandLine, moduleSourcePath);
        Path output = destination.toAbsolutePath().normalize();
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Assembly destination already exists: " + output);
        }
        Path parent = output.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("Assembly destination has no parent: " + output);
        }
        Files.createDirectories(parent);
        Path staged = Files.createTempDirectory(parent, ".ja-assemble-");
        boolean complete = false;
        try {
            Path work = Files.createDirectory(staged.resolve(".work"));
            List<Plan> plans = plans(commandLine, moduleSourcePath, options, work, err);
            for (Plan plan : plans) {
                int result = assemble(plan, staged, in, out, err);
                if (result != 0) {
                    return result;
                }
            }
            deleteTree(work);
            move(staged, output);
            complete = true;
            return 0;
        } finally {
            if (!complete) {
                deleteTree(staged);
            }
        }
    }

    private static void requireSourceModules(JaInvocation commandLine, ModuleSourcePath moduleSourcePath) {
        if (commandLine.rootModules().isEmpty()) {
            throw new IllegalArgumentException("assemble requires at least one source module");
        }
        for (String module : commandLine.rootModules()) {
            if (!moduleSourcePath.modules().containsKey(module)) {
                throw new IllegalArgumentException("Assemble requires a source module: " + module);
            }
        }
    }

    private List<Plan> plans(JaInvocation commandLine, ModuleSourcePath moduleSourcePath, Options options,
            Path work, PrintStream err)
            throws IOException {
        var selectedArguments = new ArrayList<>(commandLine.resolutionArguments());
        selectedArguments.add("--verify-module-hashes");
        selectedArguments.add("--module-version");
        selectedArguments.add(options.version());
        var artifactRuntime = new ResolutionOptions(ResolutionOptions.COMPLETE_RUNTIME_WITH_ACCESS.options(), false);
        writeOptions(selectedArguments, artifactRuntime, work.resolve("selected-runtime.args"), err);
        var plans = new ArrayList<Plan>();
        int index = 0;
        for (String module : commandLine.rootModules()) {
            Path moduleWork = Files.createDirectory(work.resolve(Integer.toString(index++)));
            Path compilationRoot = moduleWork.resolve("modules");
            Path compileArguments = moduleWork.resolve("compile.args");
            Path runtimeArguments = moduleWork.resolve("runtime.args");
            Path jarArguments = moduleWork.resolve("jar.args");
            Path javadocArguments = moduleWork.resolve("javadoc.args");
            Path javadocOutput = moduleWork.resolve("javadoc");

            var arguments = new ArrayList<>(ResolutionArguments.withRoots(commandLine.resolutionArguments(), List.of(module)));
            arguments.add("--verify-module-hashes");
            arguments.add("--module-version");
            arguments.add(options.version());
            writeOptions(arguments, ResolutionOptions.JAVAC, compileArguments, err);
            writeOptions(arguments, artifactRuntime, runtimeArguments, err);
            writeOptions(arguments, new ResolutionOptions(Set.of("main-class", "module-version"), false),
                    jarArguments, err);
            writeOptions(arguments, javadocOptions, javadocArguments, err);
            plans.add(
                    new Plan(
                            module,
                            moduleSourcePath.modules().get(module),
                            moduleSourcePath.modules(),
                            compilationRoot,
                            compileArguments,
                            runtimeArguments,
                            jarArguments,
                            javadocArguments,
                            javadocOutput,
                            options.targetPlatform(),
                            options.jmod()));
        }
        return List.copyOf(plans);
    }

    private void writeOptions(List<String> resolutionArguments, ResolutionOptions options, Path argumentFile,
            PrintStream err) {
        var arguments = new ArrayList<>(resolutionArguments);
        arguments.add("--resolve-options");
        arguments.add(options.options().stream()
                .sorted()
                .collect(Collectors.joining(",")));
        if (options.validateRuntimeAccess()) {
            arguments.add("--validate-runtime-access");
        }
        if (!options.emitCompileDiagnostics()) {
            arguments.add("--no-compile-diagnostics");
        }
        arguments.add("--write-argfile");
        arguments.add(argumentFile.toString());
        int result;
        try (var output = new PrintStream(OutputStream.nullOutputStream())) {
            result = tools.run("jig", InputStream.nullInputStream(), output, err,
                    arguments.toArray(String[]::new));
        }
        if (result != 0) {
            throw new ToolExecutionException(result);
        }
    }

    private int assemble(Plan plan, Path output, InputStream in,
                         PrintStream out, PrintStream err)
            throws IOException {
        copyDeploymentMetadata(plan, output);
        List<String> compileArguments = ArgumentFiles.parse(Files.readString(plan.compileArguments()));
        List<String> runtimeArguments = ArgumentFiles.parse(Files.readString(plan.runtimeArguments()));
        int sourcesResult = archiveSources(plan, output.resolve(plan.moduleName() + "-sources.jar"), in,
                out, err);
        if (sourcesResult != 0) {
            return sourcesResult;
        }
        int javadocResult = javadoc(plan, in, out, err);
        if (javadocResult != 0) {
            return javadocResult;
        }
        int documentationResult = archive(plan.javadocOutput(), output.resolve(plan.moduleName() + "-javadoc.jar"), in,
                out, err);
        if (documentationResult != 0) {
            return documentationResult;
        }

        var moduleArguments = ArgumentFiles.parse(Files.readString(plan.jarArguments()));
        try (var filteredPath = FilteredModulePath.prepare(plan.observableSources(), compileArguments,
                runtimeArguments, definitions)) {
            var filteredArguments = filteredPath.arguments();
            var content = filteredPath.module(plan.moduleName());
            var jmodArtifacts = new Artifacts(
                    plan.moduleName(),
                    plan.moduleSource(),
                    plan.observableSources(),
                    plan.compilationRoot(),
                    content,
                    plan.compilationRoot().getParent(),
                    output,
                    plan.moduleName(),
                    filteredArguments,
                    moduleArguments,
                    plan.targetPlatform(),
                    plan.jmod());
            var jar = jars.createExact(
                    plan.moduleName(),
                    content,
                    moduleArguments,
                    filteredArguments,
                    output.resolve(plan.moduleName() + ".jar"),
                    jmods.creates(jmodArtifacts),
                    in,
                    out,
                    err);
            if (jar.exitCode() != 0 || jar.automatic()) {
                return jar.exitCode();
            }
            return jmods.create(jmodArtifacts, in, out, err);
        }
    }

    private static void copyDeploymentMetadata(Plan plan, Path output) throws IOException {
        Path deploymentPom = ModuleMetadata.deploymentPom(plan.moduleSource());
        if (Files.isRegularFile(deploymentPom)) {
            Files.copy(deploymentPom, output.resolve(plan.moduleName() + ".pom"));
        }
    }

    private int javadoc(Plan plan, InputStream in, PrintStream out,
                        PrintStream err)
            throws IOException {
        Files.createDirectories(plan.javadocOutput());
        var arguments = new ArrayList<String>();
        arguments.add("@" + plan.javadocArguments());
        arguments.add("-quiet");
        arguments.add("-notimestamp");
        arguments.add("-Xdoclint:all,-missing");
        for (String tag : JAVADOC_TAGS) {
            arguments.add("-tag");
            arguments.add(tag);
        }
        arguments.add("-d");
        arguments.add(plan.javadocOutput()
                          .toString());
        return tools.run("javadoc", in, out, err, arguments.toArray(String[]::new));
    }

    private int archiveSources(Plan plan, Path archive, InputStream in,
                               PrintStream out, PrintStream err) throws IOException {
        Path content = plan.moduleSource();
        Path deploymentPom = ModuleMetadata.deploymentPom(content);
        boolean addDeploymentPom = Files.isRegularFile(deploymentPom) && !deploymentPom.startsWith(content);
        boolean removeMavenExportPom = Files.isRegularFile(content.resolve(ModuleMetadata.MAVEN_EXPORT_POM));
        if (!addDeploymentPom && !removeMavenExportPom) {
            return archive(content, archive, in, out, err);
        }

        Path staged = Files.createDirectory(plan.compilationRoot().getParent().resolve("sources"));
        copyTree(content, staged);
        Files.deleteIfExists(staged.resolve(ModuleMetadata.MAVEN_EXPORT_POM));
        if (addDeploymentPom) {
            Path target = staged.resolve(ModuleMetadata.DEPLOYMENT_POM);
            Files.createDirectories(target.getParent());
            Files.copy(deploymentPom, target);
        }
        return archive(staged, archive, in, out, err);
    }

    private static void copyTree(Path source, Path destination) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path input : paths.filter(Files::isRegularFile).toList()) {
                Path output = destination.resolve(source.relativize(input).toString());
                Files.createDirectories(output.getParent());
                Files.copy(input, output, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    private int archive(Path content, Path archive, InputStream in,
                        PrintStream out, PrintStream err) {
        return tools.run(
                "jar",
                in,
                out,
                err,
                "--create",
                "--no-manifest",
                "--file",
                archive.toString(),
                "-C",
                content.toString(),
                ".");
    }

    private static void move(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException _) {
            Files.move(source, destination);
        }
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path entry : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }
}
