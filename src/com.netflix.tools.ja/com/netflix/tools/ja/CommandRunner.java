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
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Coordinates module resolution, source-tool discovery, and execution of one
 * parsed command.
 *
 * <p>This is the command workflow boundary: parsing belongs to {@link
 * JaInvocation}, while tool selection and execution belong to {@link
 * ToolRunner}.
 */
public final class CommandRunner {
    @FunctionalInterface
    public interface TemporaryDirectoryProvider {
        TemporaryDirectory create() throws IOException;
    }

    @FunctionalInterface
    public interface ToolCatalogProvider {
        ToolCatalog load() throws IOException;
    }

    private final ModuleLayer layer;
    private final ToolServices tools;
    private final ModuleResolver moduleResolver;
    private final ToolCatalogProvider catalogProvider;
    private final TemporaryDirectoryProvider temporaryDirectoryProvider;
    private final JavaLauncher javaLauncher;
    private final Installer installer;
    private final DocumentationBrowser documentationBrowser;

    public CommandRunner(ModuleLayer layer) throws IOException {
        this(layer, ToolServices.load(layer));
    }

    private CommandRunner(ModuleLayer layer, ToolServices tools) {
        this(layer, tools, () -> ToolCatalog.load(layer, toolModules(tools)),
                TemporaryDirectory::create, JavaProcess::launch, null, null);
    }

    public CommandRunner(ModuleLayer layer, ToolServices tools, ToolCatalog catalog,
                         TemporaryDirectoryProvider temporaryDirectoryProvider) {
        this(layer, tools, catalog, temporaryDirectoryProvider, JavaProcess::launch,
                null);
    }

    public CommandRunner(ModuleLayer layer, ToolServices tools, ToolCatalogProvider catalogProvider,
                         TemporaryDirectoryProvider temporaryDirectoryProvider) {
        this(layer, tools, catalogProvider, temporaryDirectoryProvider, JavaProcess::launch,
                null, null);
    }

    public CommandRunner(ModuleLayer layer, ToolServices tools, ToolCatalog catalog,
                         TemporaryDirectoryProvider temporaryDirectoryProvider, JavaLauncher javaLauncher) {
        this(layer, tools, catalog, temporaryDirectoryProvider, javaLauncher, null);
    }

    public CommandRunner(ModuleLayer layer, ToolServices tools, ToolCatalog catalog,
                         TemporaryDirectoryProvider temporaryDirectoryProvider, JavaLauncher javaLauncher, Installer installer) {
        this(layer, tools, catalog, temporaryDirectoryProvider, javaLauncher, installer,
                null);
    }

    public CommandRunner(
            ModuleLayer layer,
            ToolServices tools,
            ToolCatalog catalog,
            TemporaryDirectoryProvider temporaryDirectoryProvider,
            JavaLauncher javaLauncher,
            Installer installer,
            DocumentationBrowser documentationBrowser) {
        this(layer, tools, () -> catalog, temporaryDirectoryProvider, javaLauncher,
                installer, documentationBrowser);
    }

    private CommandRunner(
            ModuleLayer layer,
            ToolServices tools,
            ToolCatalogProvider catalogProvider,
            TemporaryDirectoryProvider temporaryDirectoryProvider,
            JavaLauncher javaLauncher,
            Installer installer,
            DocumentationBrowser documentationBrowser) {
        this.layer = layer;
        this.tools = tools;
        this.moduleResolver = new ModuleResolver(tools);
        this.catalogProvider = catalogProvider;
        this.temporaryDirectoryProvider = temporaryDirectoryProvider;
        this.javaLauncher = javaLauncher;
        this.installer = installer;
        this.documentationBrowser = documentationBrowser;
    }

    public int run(JaInvocation commandLine, InputStream in, PrintStream out,
                   PrintStream err)
            throws IOException {
        if (commandLine.verbose() && !tools.verbose()) {
            var catalog = usesToolCatalog(commandLine) ? catalogProvider.load() : new ToolCatalog(List.of());
            return new CommandRunner(layer, tools.withVerbose(catalog.definitions(), err), () -> catalog, temporaryDirectoryProvider, verbose(javaLauncher, err), installer, documentationBrowser)
                    .run(commandLine, in, out, err);
        }
        if (commandLine.command() instanceof Command.Init(var request)) {
            ModuleInitializer.initialize(commandLine.workingDirectory(), request);
            return 0;
        }
        var catalog = usesToolCatalog(commandLine) ? catalogProvider.load().withDiscovered(tools) : new ToolCatalog(List.of());
        boolean moduleHashesAlreadyVerified = loadsScopedTools(commandLine, catalog);
        var scopedTools = moduleHashesAlreadyVerified ? toolsOnModulePath(commandLine, catalog, in, err).orElse(null) : null;
        return run(commandLine, in, out, err, catalog, scopedTools,
                moduleHashesAlreadyVerified);
    }

    private int run(
            JaInvocation commandLine,
            InputStream in,
            PrintStream out,
            PrintStream err,
            ToolCatalog catalog,
            ScopedTools scopedTools,
            boolean moduleHashesAlreadyVerified)
            throws IOException {
        var selectedTools = scopedTools == null ? tools : scopedTools.services();
        var selectedCatalog = scopedTools == null ? catalog : scopedTools.catalog();
        if (commandLine.verbose()) {
            selectedTools = selectedTools.withVerbose(selectedCatalog.definitions());
        }
        var selectedToolRunner = scopedTools == null ? new ToolRunner(layer, tools, catalog, javaLauncher) : new ToolRunner(scopedTools.controller(), selectedTools, tools, selectedCatalog, javaLauncher);
        CommandAvailability.require(commandLine, selectedTools, selectedCatalog);
        if (commandLine.command() instanceof Command.Tools) {
            selectedCatalog.definitions().forEach(definition -> out.println(definition.name()));
            return 0;
        }
        if (commandLine.command() instanceof Command.Require(var request)) {
            return new ModuleRequirements(moduleResolver, tools).apply(
                    commandLine.moduleSourcePath().orElseThrow(),
                    commandLine.rootModules(),
                    commandLine.resolutionArguments(),
                    request,
                    in,
                    out,
                    err);
        }

        if (commandLine.command().equals(new Command.Builtin(BuiltinCommand.ASSEMBLE))) {
            var moduleSourcePath = commandLine.moduleSourcePath()
                    .orElseThrow(() -> new IllegalArgumentException("assemble requires a module source path"));
            return new AssembleCommand(tools, selectedCatalog.definitions(),
                    selectedToolRunner.providerProjection("javadoc", ModuleResolver.Projection.EMPTY))
                    .run(commandLine, moduleSourcePath, in, out, err);
        }
        if (commandLine.command().equals(new Command.Builtin(BuiltinCommand.MAVEN))) {
            var moduleSourcePath = commandLine.moduleSourcePath()
                    .orElseThrow(() -> new IllegalArgumentException("maven requires a module source path"));
            return new MavenCommand(tools, selectedCatalog.definitions(),
                    selectedToolRunner.providerProjection("javadoc", ModuleResolver.Projection.EMPTY))
                    .run(commandLine, moduleSourcePath, in, out, err);
        }

        var temporaryDirectory = temporaryDirectory(commandLine);
        try {
            var resolutionArguments = new ArrayList<>(commandLine.resolutionArguments());
            if (hasSourceModules(commandLine) && !moduleHashesAlreadyVerified) {
                resolutionArguments.add("--verify-module-hashes");
            }
            var applicationTarget = applicationTarget(commandLine).map(target -> ApplicationTarget.resolve(target, tools, in, err));
            if (applicationTarget.isPresent()) {
                resolutionArguments.addAll(applicationTarget.orElseThrow()
                        .resolutionArguments());
            }
            var initialToolResolution = selectedToolRunner.supports(commandLine)
                    ? Optional.of(selectedToolRunner.initialResolution(commandLine))
                    : Optional.<ToolRunner.ResolutionRequest>empty();
            if (commandLine.command() instanceof Command.Tool
                    && (commandLine.moduleSourcePath().isEmpty()
                            || !initialToolResolution.orElseThrow().resolvesModules())) {
                var invocation = selectedToolRunner.prepare(commandLine, Set.of());
                return selectedToolRunner.run(
                        commandLine,
                        invocation,
                        resolutionArguments,
                        new ResolvedToolArguments(List.of(), Set.of(), Map.of()),
                        in,
                        out,
                        err);
            }
            if (initialToolResolution.isPresent()) {
                resolutionArguments = new ArrayList<>(initialToolResolution.orElseThrow()
                        .arguments(resolutionArguments));
            }
            var includeStatic = commandLine.command() instanceof Command.Install(var request)
                    && request.includeStatic();
            List<String> configurationArguments = List.of();
            Optional<ToolRunner.PreparedInvocation> toolInvocation = Optional.empty();
            if (initialToolResolution.filter(ToolRunner.ResolutionRequest::resolveActivation).isPresent()) {
                if (scopedTools != null && scopedTools.resolutionArguments().equals(resolutionArguments)) {
                    configurationArguments = scopedTools.configurationArguments();
                } else {
                    configurationArguments = moduleResolver.resolve(resolutionArguments, ToolProjections.CONFIGURATION, in, err);
                }
                var activation = ResolvedToolArguments.resolve(List.of(), configurationArguments, layer);
                toolInvocation = Optional.of(selectedToolRunner.prepare(commandLine, activation.modules()));
            } else if (initialToolResolution.isPresent()) {
                toolInvocation = Optional.of(selectedToolRunner.prepare(commandLine, Set.of()));
            }
            var requestedProjection = toolInvocation
                    .map(ToolRunner.PreparedInvocation::projection)
                    .orElseGet(() -> workflowProjection(commandLine, selectedToolRunner));
            if (includeStatic)
                requestedProjection = requestedProjection.withCompileTime(true);
            List<String> launchArguments = requestedProjection.active() ? moduleResolver.resolve(resolutionArguments, requestedProjection,
                    commandLine.toolArguments(), in, err)
                    : List.of();
            if (commandLine.command() instanceof Command.Run(var target) && target.filter(CommandRunner::hasExplicitMainClass).isPresent()) {
                var explicitLaunchArguments = new ArrayList<>(launchArguments);
                explicitLaunchArguments.add("--module");
                explicitLaunchArguments.add(target.orElseThrow());
                launchArguments = List.copyOf(explicitLaunchArguments);
            }
            var filtersSourceModules = commandLine.moduleSourcePath().isPresent()
                    && commandLine.command() instanceof Command.Install;
            List<String> filteringCompileArguments = filtersSourceModules ? moduleResolver.resolve(resolutionArguments, ToolProjections.JAVAC, in, err) : List.of();
            var resolved = configurationArguments.isEmpty() ? new ResolvedToolArguments(launchArguments, Set.of(), Map.of()) : ResolvedToolArguments.resolve(launchArguments, configurationArguments, layer);
            if (applicationTarget.flatMap(ApplicationTarget::version).isPresent()) {
                resolved = resolved.withModuleVersion(
                        applicationTarget.orElseThrow().moduleName(),
                        applicationTarget.orElseThrow()
                                         .version()
                                         .orElseThrow());
            }

            if (toolInvocation.isPresent()) {
                return selectedToolRunner.run(commandLine,
                        toolInvocation.orElseThrow(),
                        resolutionArguments,
                        resolved,
                        in,
                        out,
                        err);
            }
            if (materializes(commandLine))
                return 0;
            if (launchesJava(commandLine)) {
                var arguments = new ArrayList<>(resolved.arguments());
                if (commandLine.command().equals(new Command.Builtin(BuiltinCommand.LIST))) {
                    arguments.add("--list-modules");
                }
                arguments.addAll(commandLine.toolArguments());
                return javaLauncher.run(arguments, in, out, err);
            }
            return runWorkflow(commandLine, resolved, resolutionArguments, filteringCompileArguments, applicationTarget, selectedCatalog,
                    in, out, err);
        } finally {
            if (temporaryDirectory.isPresent())
                temporaryDirectory.get().close();
        }
    }

    private static ModuleResolver.Projection workflowProjection(JaInvocation commandLine, ToolRunner toolRunner) {
        if (commandLine.command() instanceof Command.Run(var target) && target.filter(CommandRunner::hasExplicitMainClass).isPresent()) {
            return ToolProjections.RUNTIME_WITH_ACCESS;
        }
        if (commandLine.command() instanceof Command.Doc || commandLine.command() instanceof Command.Source) {
            var provider = commandLine.command() instanceof Command.Doc(DocRequest.Browse _) ? "jdocserver" : "jist";
            return toolRunner.providerProjection(provider,
                    BuiltinCommand.from(commandLine.command())
                            .orElseThrow()
                            .projection());
        }
        return BuiltinCommand.from(commandLine.command())
                .orElseThrow()
                .projection();
    }

    private static boolean hasExplicitMainClass(String target) {
        return target.indexOf('/') >= 0;
    }

    private static boolean materializes(JaInvocation commandLine) {
        return commandLine.command() instanceof Command.Builtin(var builtin) && builtin.execution() instanceof BuiltinCommand.Execution.Materialize;
    }

    private static boolean launchesJava(JaInvocation commandLine) {
        if (commandLine.command() instanceof Command.Run)
            return true;
        return commandLine.command() instanceof Command.Builtin(var builtin) && builtin.execution() instanceof BuiltinCommand.Execution.LaunchJava;
    }

    private int runWorkflow(
            JaInvocation commandLine,
            ResolvedToolArguments resolved,
            List<String> resolutionArguments,
            List<String> filteringCompileArguments,
            Optional<ApplicationTarget> applicationTarget,
            ToolCatalog selectedCatalog,
            InputStream in,
            PrintStream out,
            PrintStream err)
            throws IOException {
        if (commandLine.command() instanceof Command.Doc(var request)) {
            DocumentationBrowser browser = documentationBrowser == null ? DocumentationServer::browse : documentationBrowser;
            return new DocumentationCommand(tools, browser).run(request, resolved.arguments(), in, out, err);
        }
        if (commandLine.command() instanceof Command.Source(var symbol)) {
            return new SourceCommand(tools).run(symbol, resolved.arguments(), in, out, err);
        }
        if (commandLine.command().equals(new Command.Builtin(BuiltinCommand.GENERATE))) {
            return new SourceGenerator(tools).generate(
                    resolutionArguments,
                    resolved.arguments(),
                    commandLine.moduleSourcePath()
                               .map(ModuleSourcePath::modules)
                               .orElseGet(Map::of),
                    commandLine.toolArguments(),
                    out,
                    err);
        }
        if (commandLine.command() instanceof Command.Install(var request)) {
            return new InstallCommand(tools, selectedCatalog.definitions(), installer)
                    .run(commandLine, request, resolved, filteringCompileArguments, applicationTarget, in,
                            out, err);
        }
        throw new IllegalArgumentException("Command " + commandLine.command() + " is not implemented");
    }

    private Optional<TemporaryDirectory> temporaryDirectory(JaInvocation commandLine) throws IOException {
        if (commandLine.moduleSourcePath().isEmpty() || !usesTemporaryOutput(commandLine)) {
            return Optional.empty();
        }
        return Optional.of(temporaryDirectoryProvider.create());
    }

    private static Optional<String> applicationTarget(JaInvocation commandLine) {
        return commandLine.command() instanceof Command.Install(var request)
                ? request.target()
                : Optional.empty();
    }

    private Optional<ScopedTools> toolsOnModulePath(JaInvocation commandLine, ToolCatalog catalog, InputStream in,
            PrintStream err)
            throws IOException {
        var baseResolutionArguments = List.copyOf(commandLine.resolutionArguments());
        var resolutionArguments = new ArrayList<>(baseResolutionArguments);
        if (hasSourceModules(commandLine)) {
            resolutionArguments.add("--verify-module-hashes");
        }
        var arguments = moduleResolver.resolve(resolutionArguments, ToolProjections.CONFIGURATION, in, err);
        if (ToolArguments.addedModules(arguments).isEmpty())
            return Optional.empty();
        var configuration = Configurations.resolve(layer.configuration(), arguments);
        var moduleNames = configuration.modules().stream()
                .map(java.lang.module.ResolvedModule::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (moduleNames.isEmpty())
            return Optional.empty();

        var controller = ModuleLayer.defineModulesWithOneLoader(configuration, List.of(layer), ClassLoader.getSystemClassLoader());
        var scopedLayer = controller.layer();
        var moduleCatalog = ToolCatalog.load(scopedLayer, moduleNames);
        requireNoDuplicateTools(catalog, moduleCatalog);
        var scopedServices = ToolServices.load(scopedLayer, moduleNames);
        var selectedServices = tools.withAdditional(scopedServices);
        moduleCatalog = moduleCatalog.withDiscovered(scopedServices);
        requireNoDuplicateTools(catalog, moduleCatalog, scopedServices);

        var definitions = new ArrayList<>(catalog.definitions());
        var existing = definitions.stream()
                .map(ToolDefinition::name)
                .collect(java.util.stream.Collectors.toSet());
        moduleCatalog.definitions().stream()
                .filter(definition -> scopedServices.contains(definition.provider()))
                .filter(definition -> !tools.contains(definition.provider()))
                .filter(definition -> scopedServices.moduleName(definition.provider())
                        .filter(moduleNames::contains)
                        .isPresent())
                .filter(definition -> !existing.contains(definition.name()))
                .forEach(definitions::add);
        return Optional.of(new ScopedTools(controller, selectedServices, new ToolCatalog(definitions), baseResolutionArguments, arguments));
    }

    private static void requireNoDuplicateTools(ToolCatalog catalog, ToolCatalog scopedCatalog) {
        requireNoDuplicateTools(catalog, scopedCatalog, null);
    }

    private static void requireNoDuplicateTools(ToolCatalog catalog, ToolCatalog scopedCatalog, ToolServices scopedServices) {
        scopedCatalog.definitions().stream()
                .map(ToolDefinition::name)
                .filter(name -> catalog.find(name).isPresent())
                .filter(name -> scopedServices == null || !providesDeclaredTool(catalog.definition(name), scopedCatalog.definition(name), scopedServices))
                .findFirst()
                .ifPresent(name -> {
                    throw new IllegalArgumentException("Duplicate tool: " + name);
                });
    }

    private static boolean providesDeclaredTool(ToolDefinition definition,
            ToolDefinition scopedDefinition,
            ToolServices scopedServices) {
        var providerModule = scopedServices.moduleName(scopedDefinition.provider());
        return definition.provider().equals(scopedDefinition.provider())
                && (definition.activation().equals(providerModule) || definition.module().equals(providerModule));
    }

    private static JavaLauncher verbose(JavaLauncher launcher, PrintStream log) {
        return (arguments, in, out, err) -> {
            VerboseLog.executing(log, "java", arguments);
            return launcher.run(arguments, in, out, err);
        };
    }

    private static Set<String> toolModules(ToolServices tools) {
        return tools.names().stream()
                .map(tools::moduleName)
                .flatMap(Optional::stream)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static boolean usesToolCatalog(JaInvocation commandLine) {
        if (commandLine.command() instanceof Command.Tool || commandLine.command() instanceof Command.Tools) {
            return true;
        }
        var builtin = BuiltinCommand.from(commandLine.command()).orElse(null);
        return builtin != null
                && (builtin.execution() instanceof BuiltinCommand.Execution.ToolBacked
                        || builtin == BuiltinCommand.DOC
                        || builtin == BuiltinCommand.SOURCE
                        || builtin == BuiltinCommand.INSTALL
                        || builtin == BuiltinCommand.ASSEMBLE
                        || builtin == BuiltinCommand.MAVEN);
    }

    private boolean loadsScopedTools(JaInvocation commandLine, ToolCatalog catalog) {
        if (commandLine.moduleSourcePath().isEmpty())
            return false;
        if (commandLine.command() instanceof Command.Tools)
            return true;
        if (commandLine.command() instanceof Command.Tool(var name)) {
            return !isBundled(catalog.find(name));
        }
        if (!(commandLine.command() instanceof Command.Builtin(var builtin))) {
            return false;
        }
        if (!(builtin.execution() instanceof BuiltinCommand.Execution.ToolBacked(var tool))) {
            return false;
        }
        return !isBundled(catalog.find(tool));
    }

    private boolean isBundled(Optional<ToolDefinition> definition) {
        return definition.filter(this::isBundled).isPresent();
    }

    private boolean isBundled(ToolDefinition definition) {
        return tools.contains(definition.provider());
    }

    private record ScopedTools(ModuleLayer.Controller controller, ToolServices services, ToolCatalog catalog,
            List<String> resolutionArguments, List<String> configurationArguments) {
        ScopedTools {
            resolutionArguments = List.copyOf(resolutionArguments);
            configurationArguments = List.copyOf(configurationArguments);
        }
    }

    private static boolean hasSourceModules(JaInvocation commandLine) {
        if (commandLine.moduleSourcePath().isPresent())
            return true;
        return commandLine.resolutionArguments().stream()
                .anyMatch(argument -> argument.equals("--module-source-path") || argument.startsWith("--module-source-path="));
    }

    private static boolean usesTemporaryOutput(JaInvocation commandLine) {
        return switch (commandLine.command()) {
            case Command.Builtin(var builtin) -> builtin == BuiltinCommand.GENERATE;
            case Command.Tool _ -> false;
            case Command.Tools _ -> false;
            case Command.Init _ -> false;
            case Command.Require _ -> false;
            case Command.Install _ -> false;
            case Command.Run _ -> false;
            case Command.Doc _ -> false;
            case Command.Source _ -> false;
        };
    }
}
