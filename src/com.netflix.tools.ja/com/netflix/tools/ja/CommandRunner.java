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
import java.lang.ModuleLayer.Controller;
import java.lang.module.ModuleDescriptor;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.netflix.tools.ja.BuiltinCommand.Execution.LaunchJava;
import com.netflix.tools.ja.BuiltinCommand.Execution.Materialize;
import com.netflix.tools.ja.BuiltinCommand.Execution.ToolBacked;
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
import com.netflix.tools.ja.ToolDefinition.Launch;
import com.netflix.tools.ja.ToolRunner.PreparedInvocation;
import com.netflix.tools.ja.ToolRunner.ResolutionRequest;

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
    private final ToolRuntime tools;
    private final ModuleResolver moduleResolver;
    private final ToolCatalogProvider catalogProvider;
    private final TemporaryDirectoryProvider temporaryDirectoryProvider;
    private final JavaLauncher javaLauncher;
    private final Installer installer;

    public CommandRunner(ModuleLayer layer) throws IOException {
        this(layer, ToolRuntime.load(layer));
    }

    private CommandRunner(ModuleLayer layer, ToolRuntime tools) {
        this(layer, tools, () -> ToolCatalog.load(layer, toolModules(tools)),
                TemporaryDirectory::create, JavaProcess::launch, null);
    }

    public CommandRunner(ModuleLayer layer, ToolRuntime tools, ToolCatalog catalog,
                         TemporaryDirectoryProvider temporaryDirectoryProvider) {
        this(layer, tools, catalog, temporaryDirectoryProvider, JavaProcess::launch,
                null);
    }

    public CommandRunner(ModuleLayer layer, ToolRuntime tools, ToolCatalogProvider catalogProvider,
                         TemporaryDirectoryProvider temporaryDirectoryProvider) {
        this(layer, tools, catalogProvider, temporaryDirectoryProvider, JavaProcess::launch,
                null);
    }

    public CommandRunner(ModuleLayer layer, ToolRuntime tools, ToolCatalog catalog,
                         TemporaryDirectoryProvider temporaryDirectoryProvider, JavaLauncher javaLauncher) {
        this(layer, tools, catalog, temporaryDirectoryProvider, javaLauncher, null);
    }

    public CommandRunner(ModuleLayer layer, ToolRuntime tools, ToolCatalog catalog,
                         TemporaryDirectoryProvider temporaryDirectoryProvider, JavaLauncher javaLauncher, Installer installer) {
        this(layer, tools, () -> catalog, temporaryDirectoryProvider, javaLauncher,
                installer);
    }

    private CommandRunner(ModuleLayer layer, ToolRuntime tools, ToolCatalogProvider catalogProvider,
                          TemporaryDirectoryProvider temporaryDirectoryProvider, JavaLauncher javaLauncher, Installer installer) {
        this.layer = layer;
        this.tools = tools;
        this.moduleResolver = new ModuleResolver(tools);
        this.catalogProvider = catalogProvider;
        this.temporaryDirectoryProvider = temporaryDirectoryProvider;
        this.javaLauncher = javaLauncher;
        this.installer = installer;
    }

    public int run(JaInvocation commandLine, InputStream in, PrintStream out,
                   PrintStream err)
            throws IOException {
        if (commandLine.verbose() && !tools.verbose()) {
            var catalog = usesToolCatalog(commandLine) ? catalogProvider.load() : new ToolCatalog(List.of());
            return new CommandRunner(layer, tools.withVerbose(catalog.definitions(), err), () -> catalog, temporaryDirectoryProvider, verbose(javaLauncher, err), installer)
                    .run(commandLine, in, out, err);
        }
        if (commandLine.command() instanceof Init(var request)) {
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
        if (commandLine.command() instanceof Tools) {
            selectedCatalog.definitions().forEach(definition -> out.println(definition.name()));
            return 0;
        }
        if (commandLine.command() instanceof Require(var request)) {
            return new ModuleRequirements(moduleResolver, tools).apply(
                    commandLine.moduleSourcePath().orElseThrow(),
                    commandLine.rootModules(),
                    commandLine.resolutionArguments(),
                    request,
                    in,
                    out,
                    err);
        }

        if (commandLine.command().equals(new Builtin(BuiltinCommand.ASSEMBLE))) {
            var moduleSourcePath = commandLine.moduleSourcePath().orElseThrow(() -> new IllegalArgumentException("assemble requires a module source path"));
            return new AssembleCommand(tools, selectedCatalog.definitions(), selectedToolRunner.providerResolutionOptions("javadoc", ResolutionOptions.EMPTY))
                    .run(commandLine, moduleSourcePath, in, out, err);
        }
        if (commandLine.command().equals(new Builtin(BuiltinCommand.MAVEN))) {
            var moduleSourcePath = commandLine.moduleSourcePath().orElseThrow(() -> new IllegalArgumentException("maven requires a module source path"));
            return new MavenCommand(tools, selectedCatalog.definitions(), selectedToolRunner.providerResolutionOptions("javadoc", ResolutionOptions.EMPTY))
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
            var initialToolResolution = selectedToolRunner.supports(commandLine) ? Optional.of(selectedToolRunner.initialResolution(commandLine)) : Optional.<ResolutionRequest>empty();
            if (commandLine.command() instanceof Tool && (commandLine.moduleSourcePath().isEmpty() || !initialToolResolution.orElseThrow().resolvesModules())) {
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
            List<String> configurationArguments = List.of();
            Set<ModuleDescriptor> resolvedDescriptors = Set.of();
            Optional<PreparedInvocation> toolInvocation = Optional.empty();
            if (initialToolResolution.filter(ResolutionRequest::resolveActivation).isPresent()) {
                ResolvedModules resolvedModules;
                if (scopedTools != null && scopedTools.resolutionArguments().equals(resolutionArguments)) {
                    configurationArguments = scopedTools.configurationArguments();
                    resolvedModules = scopedTools.resolvedModules();
                } else {
                    var modulePathArguments = moduleResolver.resolve(resolutionArguments, ResolutionOptions.MODULE_PATHS, in, err);
                    resolvedModules = ResolvedModules.read(modulePathArguments, layer.configuration());
                }
                var prepared = selectedToolRunner.prepare(commandLine, resolvedModules.names());
                if (selectsActivatedRoots(commandLine)) {
                    var activation = prepared.definition()
                            .activation()
                            .orElse(null);
                    var selectedRoots = activation == null ? commandLine.rootModules() : resolvedModules.rootsRequiredFor(commandLine.rootModules(), activation);
                    if (!selectedRoots.isEmpty()) {
                        commandLine = commandLine.withRootModules(selectedRoots);
                        resolutionArguments = new ArrayList<>(ResolutionArguments.withRoots(resolutionArguments, selectedRoots));
                    }
                }
                resolvedDescriptors = resolvedModules.descriptors(commandLine.rootModules());
                toolInvocation = Optional.of(selectedToolRunner.prepare(commandLine, resolvedModules.names()));
            } else if (initialToolResolution.isPresent()) {
                toolInvocation = Optional.of(selectedToolRunner.prepare(commandLine, Set.of()));
            }
            var requestedOptions = toolInvocation.isPresent() ? toolInvocation.orElseThrow().resolutionOptions() : workflowResolutionOptions(commandLine, selectedToolRunner);
            List<String> launchArguments = requestedOptions.active() ? moduleResolver.resolve(resolutionArguments, requestedOptions,
                    commandLine.toolArguments(), in, err)
                    : List.of();
            if (commandLine.command() instanceof Run(var target) && target.filter(CommandRunner::hasExplicitMainClass).isPresent()) {
                var explicitLaunchArguments = new ArrayList<>(launchArguments);
                explicitLaunchArguments.add("--module");
                explicitLaunchArguments.add(target.orElseThrow());
                launchArguments = List.copyOf(explicitLaunchArguments);
            }
            var filtersSourceModules = commandLine.moduleSourcePath().isPresent() && commandLine.command() instanceof Install;
            List<String> filteringCompileArguments = filtersSourceModules ? moduleResolver.resolve(resolutionArguments, ResolutionOptions.JAVAC, in, err) : List.of();
            var resolved = !resolvedDescriptors.isEmpty()
                    ? new ResolvedToolArguments(launchArguments, resolvedDescriptors, Map.of())
                    : configurationArguments.isEmpty() ? new ResolvedToolArguments(launchArguments, Set.of(), Map.of()) : ResolvedToolArguments.resolve(launchArguments, configurationArguments, layer);
            if (applicationTarget.flatMap(ApplicationTarget::version).isPresent()) {
                resolved = resolved.withModuleVersion(
                        applicationTarget.orElseThrow().moduleName(),
                        applicationTarget.orElseThrow()
                                         .version()
                                         .orElseThrow());
            }

            if (toolInvocation.isPresent()) {
                return selectedToolRunner.run(commandLine, toolInvocation.orElseThrow(), resolutionArguments, resolved, in,
                        out, err);
            }
            if (materializes(commandLine)) {
                return 0;
            }
            if (launchesJava(commandLine)) {
                var arguments = new ArrayList<>(resolved.arguments());
                if (commandLine.command().equals(new Builtin(BuiltinCommand.LIST))) {
                    arguments.add("--list-modules");
                }
                arguments.addAll(commandLine.toolArguments());
                return javaLauncher.run(arguments, in, out, err);
            }
            return runWorkflow(
                    commandLine,
                    resolved,
                    resolutionArguments,
                    filteringCompileArguments,
                    applicationTarget,
                    selectedCatalog,
                    selectedToolRunner,
                    in,
                    out,
                    err);
        } finally {
            if (temporaryDirectory.isPresent()) {
                temporaryDirectory.get().close();
            }
        }
    }

    private static ResolutionOptions workflowResolutionOptions(JaInvocation commandLine, ToolRunner toolRunner) {
        if (commandLine.command() instanceof Run(var target) && target.filter(CommandRunner::hasExplicitMainClass).isPresent()) {
            return ResolutionOptions.RUNTIME_WITH_ACCESS;
        }
        if (commandLine.command() instanceof Doc || commandLine.command() instanceof Source) {
            var provider = commandLine.command() instanceof Doc(Browse _) ? "jdocserver" : "jist";
            return toolRunner.providerResolutionOptions(provider,
                    BuiltinCommand.from(commandLine.command())
                            .orElseThrow()
                            .resolutionOptions());
        }
        return BuiltinCommand.from(commandLine.command())
                .orElseThrow()
                .resolutionOptions();
    }

    private static boolean selectsActivatedRoots(JaInvocation commandLine) {
        return commandLine.command().equals(new Builtin(BuiltinCommand.TEST)) || commandLine.command().equals(new Builtin(BuiltinCommand.BENCH));
    }

    private static boolean hasExplicitMainClass(String target) {
        return target.indexOf('/') >= 0;
    }

    private static boolean materializes(JaInvocation commandLine) {
        return commandLine.command() instanceof Builtin(var builtin) && builtin.execution() instanceof Materialize;
    }

    private static boolean launchesJava(JaInvocation commandLine) {
        if (commandLine.command() instanceof Run) {
            return true;
        }
        return commandLine.command() instanceof Builtin(var builtin) && builtin.execution() instanceof LaunchJava;
    }

    private int runWorkflow(
            JaInvocation commandLine,
            ResolvedToolArguments resolved,
            List<String> resolutionArguments,
            List<String> filteringCompileArguments,
            Optional<ApplicationTarget> applicationTarget,
            ToolCatalog selectedCatalog,
            ToolRunner toolRunner,
            InputStream in,
            PrintStream out,
            PrintStream err)
            throws IOException {
        if (commandLine.command() instanceof Doc(var request)) {
            String provider;
            var arguments = new ArrayList<String>();
            switch (request) {
                case Terminal(var symbol) -> {
                    provider = "jist";
                    arguments.addAll(List.of("--source", "doc", "--break", "--no-line-number", symbol));
                }
                case Browse(var type) -> {
                    provider = "jdocserver";
                    arguments.add(type.map(value -> "--browse=" + value)
                                      .orElse("--browse"));
                }
            }
            return toolRunner.run(toolInvocation(commandLine, provider, arguments), resolutionArguments,
                    resolved, in, out, err);
        }
        if (commandLine.command() instanceof Source(var symbol)) {
            return toolRunner.run(toolInvocation(commandLine, "jist", List.of("--source", "symbol", symbol)), resolutionArguments,
                    resolved, in, out, err);
        }
        if (commandLine.command().equals(new Builtin(BuiltinCommand.GENERATE))) {
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
        if (commandLine.command() instanceof Install(var request)) {
            return new InstallCommand(tools, selectedCatalog.definitions(), installer).run(commandLine, request, resolved, filteringCompileArguments, applicationTarget, in,
                    out, err);
        }
        throw new IllegalArgumentException("Command " + commandLine.command() + " is not implemented");
    }

    private static JaInvocation toolInvocation(JaInvocation invocation, String tool, List<String> arguments) {
        return new JaInvocation(
                invocation.workingDirectory(),
                invocation.verbose(),
                new Tool(tool),
                invocation.moduleSourcePath(),
                invocation.rootModules(),
                invocation.resolutionArguments(),
                arguments);
    }

    private Optional<TemporaryDirectory> temporaryDirectory(JaInvocation commandLine) throws IOException {
        if (commandLine.moduleSourcePath().isEmpty() || !usesTemporaryOutput(commandLine)) {
            return Optional.empty();
        }
        return Optional.of(temporaryDirectoryProvider.create());
    }

    private static Optional<String> applicationTarget(JaInvocation commandLine) {
        return commandLine.command() instanceof Install(var request) ? request.target() : Optional.empty();
    }

    private Optional<ScopedTools> toolsOnModulePath(JaInvocation commandLine, ToolCatalog catalog, InputStream in,
            PrintStream err)
            throws IOException {
        var baseResolutionArguments = List.copyOf(commandLine.resolutionArguments());
        var resolutionArguments = new ArrayList<>(baseResolutionArguments);
        if (hasSourceModules(commandLine)) {
            resolutionArguments.add("--verify-module-hashes");
        }
        var modulePathArguments = moduleResolver.resolve(resolutionArguments, ResolutionOptions.MODULE_PATHS, in, err);
        var resolvedModules = ResolvedModules.read(modulePathArguments, layer.configuration());
        var providerModules = resolvedModules.toolProviderModules();
        if (providerModules.isEmpty()) {
            return Optional.empty();
        }

        var sourceModules = resolvedModules.sourceModules();
        List<String> arguments;
        if (sourceModules.stream().anyMatch(providerModules::contains)) {
            var providerArguments = new ArrayList<>(ResolutionArguments.withAddedModules(modulePathArguments,
                    providerModules.stream()
                            .sorted()
                            .toList()));
            if (hasSourceModules(commandLine)) {
                providerArguments.add("--verify-module-hashes");
            }
            arguments = moduleResolver.resolve(providerArguments, ResolutionOptions.CONFIGURATION, in, err);
        } else {
            arguments = ResolutionArguments.withAddedModules(modulePathArguments,
                    providerModules.stream()
                            .sorted()
                            .toList());
        }
        var preferredModules = new LinkedHashSet<>(sourceModules);
        preferredModules.addAll(providerModules);
        var resolution = Configurations.resolve(layer.configuration(), arguments, preferredModules);
        var moduleNames = resolution.definedModules();
        if (moduleNames.isEmpty()) {
            return Optional.empty();
        }

        var controller = resolution.defineLayer(layer);
        var scopedLayer = controller.layer();
        var moduleCatalog = ToolCatalog.load(scopedLayer, moduleNames);
        var scopedServices = ToolRuntime.load(scopedLayer, moduleNames);
        var selectedServices = tools.withOverrides(scopedServices);
        moduleCatalog = moduleCatalog.withDiscovered(scopedServices);
        requireNoDuplicateTools(catalog, moduleCatalog, scopedServices);

        var definitions = new ArrayList<>(catalog.definitions());
        var existing = definitions.stream()
                .map(ToolDefinition::name)
                .collect(Collectors.toSet());
        moduleCatalog.definitions().stream()
                .filter(definition -> scopedServices.contains(definition.provider()))
                .filter(definition -> !tools.contains(definition.provider()))
                .filter(definition -> scopedServices.moduleName(definition.provider())
                        .filter(moduleNames::contains)
                        .isPresent())
                .filter(definition -> !existing.contains(definition.name()))
                .forEach(definitions::add);
        return Optional.of(new ScopedTools(controller, selectedServices, new ToolCatalog(definitions), baseResolutionArguments, arguments,
                resolvedModules));
    }

    private static void requireNoDuplicateTools(ToolCatalog catalog, ToolCatalog scopedCatalog, ToolRuntime scopedServices) {
        scopedCatalog.definitions().stream()
                .map(ToolDefinition::name)
                .filter(name -> catalog.find(name).isPresent())
                .filter(name -> scopedServices == null || !providesDeclaredTool(catalog.definition(name), scopedCatalog.definition(name), scopedServices))
                .findFirst()
                .ifPresent(name -> {
                    throw new IllegalArgumentException("Duplicate tool: " + name);
                });
    }

    private static boolean providesDeclaredTool(ToolDefinition definition, ToolDefinition scopedDefinition, ToolRuntime scopedServices) {
        return definition.provider().equals(scopedDefinition.provider()) && scopedServices.moduleName(scopedDefinition.provider()).isPresent();
    }

    private static JavaLauncher verbose(JavaLauncher launcher, PrintStream log) {
        return (arguments, in, out, err) -> {
            VerboseLog.executing(log, "java", arguments);
            return launcher.run(arguments, in, out, err);
        };
    }

    private static Set<String> toolModules(ToolRuntime tools) {
        return tools.names().stream()
                .map(tools::moduleName)
                .flatMap(Optional::stream)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static boolean usesToolCatalog(JaInvocation commandLine) {
        if (commandLine.command() instanceof Tool || commandLine.command() instanceof Tools) {
            return true;
        }
        var builtin = BuiltinCommand.from(commandLine.command()).orElse(null);
        return builtin != null
                && (builtin.execution() instanceof ToolBacked
                        || builtin == BuiltinCommand.DOC
                        || builtin == BuiltinCommand.SOURCE
                        || builtin == BuiltinCommand.INSTALL
                        || builtin == BuiltinCommand.ASSEMBLE
                        || builtin == BuiltinCommand.MAVEN);
    }

    private boolean loadsScopedTools(JaInvocation commandLine, ToolCatalog catalog) throws IOException {
        if (commandLine.moduleSourcePath().isEmpty()) {
            return false;
        }
        if (commandLine.command() instanceof Tools) {
            return true;
        }
        Optional<ToolDefinition> definition = switch (commandLine.command()) {
            case Tool(var name) -> catalog.find(name);
            case Builtin(var builtin) -> builtin.execution() instanceof ToolBacked(var tool) ? catalog.find(tool) : Optional.empty();
            default -> Optional.empty();
        };
        if (definition.isEmpty()) {
            return commandLine.command() instanceof Tool || commandLine.command() instanceof Builtin(var builtin) && builtin.execution() instanceof ToolBacked;
        }
        var selected = definition.orElseThrow();
        if (selected.launch() == Launch.JAVA) {
            return false;
        }
        if (!tools.contains(selected.provider())) {
            return commandLine.command() instanceof Tool || commandLine.command() instanceof Builtin(var builtin) && builtin.execution() instanceof ToolBacked;
        }
        var module = selected.module().or(() -> tools.moduleName(selected.provider()));
        return module.isPresent() && sourceSelects(commandLine.moduleSourcePath().orElseThrow(),
                module.orElseThrow());
    }

    private static boolean sourceSelects(ModuleSourcePath sourcePath, String moduleName) throws IOException {
        if (sourcePath.modules().containsKey(moduleName)) {
            return true;
        }
        for (var path : sourcePath.modules().values()) {
            var descriptor = ModuleInfo.descriptor(path.resolve("module-info.java"));
            if (descriptor.requires().stream()
                    .anyMatch(require -> require.name().equals(moduleName))) {
                return true;
            }
        }
        return false;
    }

    private record ScopedTools(Controller controller, ToolRuntime services, ToolCatalog catalog,
            List<String> resolutionArguments, List<String> configurationArguments, ResolvedModules resolvedModules) {
        ScopedTools {
            resolutionArguments = List.copyOf(resolutionArguments);
            configurationArguments = List.copyOf(configurationArguments);
        }
    }

    private static boolean hasSourceModules(JaInvocation commandLine) {
        if (commandLine.moduleSourcePath().isPresent()) {
            return true;
        }
        return commandLine.resolutionArguments().stream()
                .anyMatch(argument -> argument.equals("--module-source-path") || argument.startsWith("--module-source-path="));
    }

    private static boolean usesTemporaryOutput(JaInvocation commandLine) {
        return switch (commandLine.command()) {
            case Builtin(var builtin) -> builtin == BuiltinCommand.GENERATE;
            case Tool _ -> false;
            case Tools _ -> false;
            case Init _ -> false;
            case Require _ -> false;
            case Install _ -> false;
            case Run _ -> false;
            case Doc _ -> false;
            case Source _ -> false;
        };
    }
}
