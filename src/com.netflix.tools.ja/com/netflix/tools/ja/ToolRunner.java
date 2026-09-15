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
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.lang.model.SourceVersion;
import javax.tools.OptionChecker;

import com.netflix.module.ModuleRuntimeAccess;
import com.netflix.module.ModuleRuntimeAccessOptions;
import com.netflix.tools.launcher.ModuleOptions;
import com.netflix.tools.launcher.ToolLauncher;

/**
 * Runs a discovered tool with only the resolved module arguments accepted by
 * its contracts.
 *
 * <p>Standard options are selected through the provider's {@link OptionChecker}
 * and tool metadata; the user's tool arguments remain unchanged.
 */
public final class ToolRunner {
    record Resolution(ResolutionOptions resolutionOptions,
                      Optional<List<String>> explicitModules,
                      Optional<String> implicitModule) {
        Resolution {
            explicitModules = explicitModules.map(List::copyOf);
        }

        boolean participates() {
            return resolutionOptions.active();
        }

        boolean moduleSelected() {
            return explicitModules.isPresent() || implicitModule.isPresent();
        }

        List<String> arguments(List<String> arguments) {
            return explicitModules.map(modules -> ResolutionArguments.withRoots(arguments, modules)).orElse(arguments);
        }

        List<String> toolArguments(List<String> arguments) {
            if (implicitModule.isEmpty())
                return arguments;
            var selected = new ArrayList<String>();
            selected.add("--module");
            selected.add(implicitModule.orElseThrow());
            selected.addAll(arguments);
            return List.copyOf(selected);
        }
    }

    record ResolutionRequest(Resolution resolution, boolean resolveActivation) {
        boolean resolvesModules() {
            return resolution.participates() || resolveActivation;
        }

        List<String> arguments(List<String> arguments) {
            return resolution.arguments(arguments);
        }
    }

    record PreparedInvocation(ToolDefinition definition,
                              Resolution resolution,
                              List<String> arguments) {
        PreparedInvocation {
            arguments = List.copyOf(arguments);
        }

        ResolutionOptions resolutionOptions() {
            return resolution.resolutionOptions();
        }
    }

    private static final class RuntimeArguments implements Supplier<List<String>> {
        private Supplier<List<String>> resolver;
        private List<String> arguments;

        private RuntimeArguments(Supplier<List<String>> resolver) {
            this.resolver = resolver;
        }

        @Override
        public synchronized List<String> get() {
            if (resolver != null) {
                arguments = List.copyOf(resolver.get());
                resolver = null;
            }
            return arguments;
        }
    }

    private final ModuleLayer layer;
    private final ModuleLayer.Controller controller;
    private final ToolServices tools;
    private final ToolServices directTools;
    private final ToolCatalog catalog;
    private final JavaLauncher javaLauncher;
    private final TestRunner testRunner;

    public ToolRunner(ModuleLayer layer, ToolServices tools, ToolCatalog catalog, JavaLauncher javaLauncher) {
        this(layer, tools, catalog, javaLauncher, TestResultStore.defaults());
    }

    public ToolRunner(ModuleLayer layer,
                      ToolServices tools,
                      ToolCatalog catalog,
                      JavaLauncher javaLauncher,
                      TestResultStore testResults) {
        this(layer,
                tools,
                catalog,
                javaLauncher,
                testResults,
                new RuntimeImageHash(Path.of(System.getProperty("java.home")),
                        testResults.root().resolve("runtime-images")));
    }

    public ToolRunner(ModuleLayer layer,
                      ToolServices tools,
                      ToolCatalog catalog,
                      JavaLauncher javaLauncher,
                      TestResultStore testResults,
                      RuntimeImageHash runtimeImage) {
        this.layer = layer;
        this.controller = null;
        this.tools = tools;
        this.directTools = tools;
        this.catalog = catalog;
        this.javaLauncher = javaLauncher;
        this.testRunner = new TestRunner(testResults, runtimeImage);
    }

    ToolRunner(ModuleLayer.Controller controller,
               ToolServices tools,
               ToolServices directTools,
               ToolCatalog catalog,
               JavaLauncher javaLauncher) {
        this.layer = controller.layer();
        this.controller = controller;
        this.tools = tools;
        this.directTools = directTools;
        this.catalog = catalog;
        this.javaLauncher = javaLauncher;
        this.testRunner = new TestRunner();
    }

    boolean supports(JaInvocation commandLine) {
        return switch (commandLine.command()) {
            case Command.Tool _ -> true;
            case Command.Builtin(var builtin) -> builtin.execution() instanceof BuiltinCommand.Execution.ToolBacked;
            default -> false;
        };
    }

    private ResolutionOptions initialResolutionOptions(JaInvocation commandLine) {
        return switch (commandLine.command()) {
            case Command.Tool(var name) -> resolutionOptions(catalog.definition(name));
            case Command.Builtin(var builtin) -> builtin.resolutionOptions();
            default -> throw new IllegalArgumentException("Command " + commandLine.command() + " does not run a tool");
        };
    }

    ResolutionRequest initialResolution(JaInvocation commandLine) {
        return new ResolutionRequest(
                resolution(commandLine, initialResolutionOptions(commandLine)),
                hasActivation(commandLine));
    }

    PreparedInvocation prepare(JaInvocation commandLine, Set<String> selectedModules) {
        var definition = select(commandLine, selectedModules);
        var resolution = resolution(commandLine, contractResolutionOptions(commandLine, definition));
        return new PreparedInvocation(definition, resolution, arguments(commandLine, definition));
    }

    private Resolution resolution(JaInvocation commandLine, ResolutionOptions toolOptions) {
        var resolutionOptions = toolOptions;
        var explicitModules = explicitModules(commandLine, resolutionOptions.options());
        Optional<String> implicitModule = Optional.empty();
        // A single-module tool consumes ja's selected root, not every root
        // introduced while resolving tool activation and static requirements.
        if (resolutionOptions.options().contains("module=single")) {
            if (explicitModules.isPresent()) {
                requireSingleModule(commandLine, explicitModules.orElseThrow());
            } else {
                requireSingleModule(commandLine, commandLine.rootModules());
                implicitModule = Optional.of(commandLine.rootModules().getFirst());
            }
            resolutionOptions = withoutSingleModule(resolutionOptions);
        }
        return new Resolution(resolutionOptions, explicitModules, implicitModule);
    }

    ResolutionOptions providerResolutionOptions(String name, ResolutionOptions fallback) {
        return catalog.find(name)
                .map(definition -> declaredResolutionOptions(definition, fallback))
                .orElse(fallback);
    }

    public int run(JaInvocation commandLine,
                   List<String> resolutionArguments,
                   ResolvedToolArguments resolved,
                   InputStream in,
                   PrintStream out,
                   PrintStream err) throws IOException {
        return run(commandLine,
                prepare(commandLine, resolved.modules()),
                resolutionArguments,
                resolved,
                in,
                out,
                err);
    }

    int run(JaInvocation commandLine,
            PreparedInvocation invocation,
            List<String> resolutionArguments,
            ResolvedToolArguments resolved,
            InputStream in,
            PrintStream out,
            PrintStream err) throws IOException {
        var definition = invocation.definition();
        var resolution = invocation.resolution();
        var runtimeArguments = runtimeArguments(commandLine,
                resolutionArguments,
                resolved,
                definition,
                resolution,
                in,
                err);
        var arguments = invocation.arguments();
        boolean listSelectors = arguments.equals(List.of(SelectorCompletion.REQUEST));
        if (commandLine.command().equals(new Command.Builtin(BuiltinCommand.BENCH))) {
            arguments = listSelectors ? List.of("-l") : benchmarkArguments(arguments);
        }
        if (commandLine.command().equals(new Command.Builtin(BuiltinCommand.TEST))) {
            if (listSelectors) {
                return runSelected(commandLine,
                        resolved,
                        withDefaults(definition,
                                List.of("discover", "--details=flat", "--disable-banner", "--disable-ansi-colors")),
                        resolution,
                        runtimeArguments,
                        moduleSelectors(commandLine.rootModules()),
                        in,
                        out,
                        err);
            }
            return testRunner.run(layer,
                                  commandLine.rootModules(),
                                  resolved.arguments(),
                                  runtimeArguments::get,
                                  definition,
                                  arguments,
                                  in,
                                  out,
                                  err,
                                  (testArguments, testIn, testOut, testErr) ->
                                          runSelected(commandLine,
                                                  resolved,
                                                  definition,
                                                  resolution,
                                                  runtimeArguments,
                                                  testArguments,
                                                  testIn,
                                                  testOut,
                                                  testErr));
        }
        return runSelected(commandLine,
                           resolved,
                           definition,
                           resolution,
                           runtimeArguments,
                           arguments,
                           in,
                           out,
                           err);
    }

    private static List<String> benchmarkArguments(List<String> selectors) {
        return selectors.stream()
                .map(selector -> "^" + Pattern.quote(selector) + "(?:\\..+)?$")
                .toList();
    }

    private static List<String> moduleSelectors(List<String> modules) {
        var arguments = new ArrayList<String>();
        for (String module : modules) {
            arguments.add("--select-module");
            arguments.add(module);
        }
        return List.copyOf(arguments);
    }

    private static ToolDefinition withDefaults(ToolDefinition definition, List<String> defaults) {
        return new ToolDefinition(definition.name(),
                definition.launch(),
                definition.activation(),
                definition.module(),
                definition.provider(),
                definition.version(),
                definition.options(),
                definition.compileTime(),
                definition.validateRuntimeAccess(),
                defaults,
                definition.classSuffix(),
                definition.packageSuffix());
    }

    private RuntimeArguments runtimeArguments(JaInvocation commandLine,
                                              List<String> resolutionArguments,
                                              ResolvedToolArguments resolved,
                                              ToolDefinition definition,
                                              Resolution resolution,
                                              InputStream in,
                                              PrintStream err) {
        return new RuntimeArguments(() -> {
            if (style(definition) != ToolDefinition.Launch.JAVA) {
                return resolved.arguments();
            }
            var version = definition.resolveVersion(
                    definition.activation().flatMap(resolved::moduleVersion));
            return new ToolResolver(tools).resolveLauncher(definition,
                    version,
                    resolution.resolutionOptions().compileTime(),
                    resolutionArguments,
                    commandLine.toolArguments(),
                    in,
                    err);
        });
    }

    private int runSelected(JaInvocation commandLine,
                            ResolvedToolArguments resolved,
                            ToolDefinition definition,
                            Resolution resolution,
                            RuntimeArguments runtimeArguments,
                            List<String> arguments,
                            InputStream in,
                            PrintStream out,
                            PrintStream err) throws IOException {
        var style = style(definition);
        arguments = resolution.toolArguments(arguments);
        if (style == ToolDefinition.Launch.JAVA) {
            return runJava(definition,
                           arguments,
                           runtimeArguments.get(),
                           in,
                           out,
                           err);
        }
        return runProvider(commandLine,
                           definition,
                           resolution.resolutionOptions().options(),
                           resolution.moduleSelected(),
                           arguments,
                           resolved,
                           in,
                           out,
                           err);
    }

    private boolean supportsVerbose(ToolDefinition definition) {
        if (definition.isSupportedOption("--verbose") == 0)
            return true;
        if (!tools.contains(definition.provider()))
            return false;
        return tools.optionChecker(definition.provider(), _ -> -1).isSupportedOption("--verbose") == 0;
    }

    private ToolDefinition select(JaInvocation commandLine, Set<String> selectedModules) {
        if (commandLine.command() instanceof Command.Tool(var name)) {
            return catalog.named(name, selectedModules);
        }
        var builtin = builtin(commandLine);
        return switch (builtin.execution()) {
            case BuiltinCommand.Execution.ToolBacked(var tool) -> {
                var definition = catalog.definition(tool);
                if (definition.activation()
                        .filter(activation -> !selectedModules.contains(activation))
                        .isPresent()) {
                    throw new IllegalArgumentException(CommandAvailability.message(builtin.commandName(), List.of(tool)));
                }
                yield definition;
            }
            default -> throw new IllegalArgumentException("Command " + builtin.commandName() + " does not run a tool");
        };
    }

    private static ToolDefinition.Launch style(ToolDefinition definition) {
        return definition.launch();
    }

    private List<String> arguments(JaInvocation commandLine, ToolDefinition definition) {
        var arguments = commandLine.toolArguments();
        if (!commandLine.verbose()
                || !supportsVerbose(definition)
                || arguments.contains("--verbose")) {
            return arguments;
        }
        var verboseArguments = new ArrayList<String>();
        verboseArguments.add("--verbose");
        verboseArguments.addAll(arguments);
        return List.copyOf(verboseArguments);
    }

    private int runJava(ToolDefinition definition,
                        List<String> nativeArguments,
                        List<String> runtimeArguments,
                        InputStream in,
                        PrintStream out,
                        PrintStream err) throws IOException {
        var arguments = new ArrayList<>(runtimeArguments);
        arguments.addAll(definition.defaults());
        arguments.addAll(nativeArguments);
        return javaLauncher.run(arguments, in, out, err);
    }

    private int runProvider(JaInvocation commandLine,
                            ToolDefinition definition,
                            Set<String> resolutionOptions,
                            boolean selectedModule,
                            List<String> nativeArguments,
                            ResolvedToolArguments resolved,
                            InputStream in,
                            PrintStream out,
                            PrintStream err) throws IOException {
        var checker = tools.contains(definition.provider())
                ? tools.optionChecker(definition.provider(), definition)
                : definition;
        if (selectedModule) {
            var delegate = checker;
            checker = option -> option.equals("--module") ? -1 : delegate.isSupportedOption(option);
        }
        var sourceOptions = ModuleOptions.checker(resolutionOptions);
        var arguments = new ArrayList<>(ToolArguments.select(resolved.arguments(), sourceOptions, checker));
        arguments.addAll(definition.defaults());
        arguments.addAll(nativeArguments);
        if (!directTools.contains(definition.provider())) {
            return runModularProvider(commandLine,
                    definition,
                    resolved,
                    arguments,
                    in,
                    out,
                    err);
        }
        return runDirectProvider(definition, arguments, in, out, err);
    }

    private int runDirectProvider(ToolDefinition definition,
                                  List<String> toolArguments,
                                  InputStream in,
                                  PrintStream out,
                                  PrintStream err) throws IOException {
        var module = directTools.moduleName(definition.provider())
                .or(() -> definition.module());
        if (module.isEmpty() || !declaresRuntimeAccess(module.orElseThrow())) {
            return tools.run(definition.provider(),
                    in,
                    out,
                    err,
                    toolArguments.toArray(String[]::new));
        }
        var runtimeArguments =
                new ModuleResolver(tools)
                        .resolve(List.of("--add-modules", module.orElseThrow()),
                                providerRuntimeOptions(),
                                in,
                                err);
        if (runtimeAccessIsEffective(layer, runtimeArguments)) {
            return tools.run(definition.provider(),
                    in,
                    out,
                    err,
                    toolArguments.toArray(String[]::new));
        }
        // A controller cannot grant access from a module in a parent layer. Launching the
        // provider applies boot-layer access before any of its classes are loaded.
        return launchProvider(definition.provider(), runtimeArguments, toolArguments, in, out, err);
    }

    private boolean declaresRuntimeAccess(String module) throws IOException {
        var resolved = layer.configuration().findModule(module).orElse(null);
        return resolved != null
                && ModuleRuntimeAccess.read(resolved.reference())
                        .filter(access -> !access.isEmpty())
                        .isPresent();
    }

    private int launchProvider(String provider,
                               List<String> runtimeArguments,
                               List<String> toolArguments,
                               InputStream in,
                               PrintStream out,
                               PrintStream err) throws IOException {
        var arguments = new ArrayList<>(runtimeArguments);
        arguments.add("--module");
        arguments.add(ToolLauncher.class.getModule().getName() + "/" + ToolLauncher.class.getName());
        arguments.add(provider);
        arguments.addAll(toolArguments);
        return javaLauncher.run(arguments, in, out, err);
    }

    private static ResolutionOptions providerRuntimeOptions() {
        return new ResolutionOptions(
                Set.of("module-path", "add-modules", "enable-native-access", "enable-final-field-mutation", "add-opens", "add-exports"),
                false,
                true);
    }

    private int runModularProvider(JaInvocation commandLine,
                                   ToolDefinition definition,
                                   ResolvedToolArguments resolved,
                                   List<String> toolArguments,
                                   InputStream in,
                                   PrintStream out,
                                   PrintStream err) throws IOException {
        var executionController = controller;
        var executionLayer = layer;
        var executionTools = tools;
        boolean providerAvailable = executionTools.contains(definition.provider());
        var module =
                (providerAvailable ? executionTools.moduleName(definition.provider()) : definition.module())
                        .or(() -> definition.module())
                        .orElseThrow(() ->
                                new IllegalArgumentException("Tool " + definition.name() + " is not installed and has no module"));
        List<String> resolutionArguments;
        if (providerAvailable && commandLine.moduleSourcePath().isPresent()) {
            var scopedArguments = new ArrayList<>(commandLine.resolutionArguments());
            scopedArguments.add("-m");
            scopedArguments.add(module);
            scopedArguments.add("--verify-module-hashes");
            resolutionArguments = scopedArguments;
        } else {
            String moduleRequirement = module
                    + "@"
                    + definition.resolveVersion(definition.activation().flatMap(resolved::moduleVersion));
            if (commandLine.moduleSourcePath().isPresent()) {
                var scopedArguments = new ArrayList<>(ResolutionArguments.rootsAsAddedModules(commandLine.resolutionArguments()));
                scopedArguments.add("--add-requires");
                scopedArguments.add(moduleRequirement);
                scopedArguments.add("--verify-module-hashes");
                resolutionArguments = scopedArguments;
            } else {
                resolutionArguments = List.of("--add-requires", moduleRequirement);
            }
        }
        var runtimeArguments =
                new ModuleResolver(tools)
                        .resolve(resolutionArguments,
                                 providerRuntimeOptions(),
                                 in,
                                 err);
        if (!executionTools.contains(definition.provider())) {
            var paths = ToolArguments.modulePath(runtimeArguments);
            var finder = ModuleFinder.of(paths.toArray(java.nio.file.Path[]::new));
            var configuration =
                    Configuration.resolve(finder,
                                          List.of(executionLayer.configuration()),
                                          ModuleFinder.of(),
                                          Set.of(module));
            executionController = ModuleLayer.defineModulesWithOneLoader(configuration, List.of(executionLayer), ClassLoader.getSystemClassLoader());
            executionLayer = executionController.layer();
            executionTools = tools.withAdditional(ToolServices.load(executionLayer, Set.of(module)));
        }
        if (!executionTools.contains(definition.provider())) {
            throw new IllegalArgumentException("Module " + module + " does not provide tool " + definition.provider());
        }
        if (!configureLayer(executionController, executionLayer, runtimeArguments)) {
            return launchProvider(definition.provider(), runtimeArguments, toolArguments, in, out, err);
        }
        return executionTools.run(definition.provider(),
                in,
                out,
                err,
                toolArguments.toArray(String[]::new));
    }

    @SuppressWarnings("restricted")
    static boolean runtimeAccessIsEffective(ModuleLayer layer, List<String> arguments) {
        var access = ModuleRuntimeAccess.parseArguments(arguments);
        if (!access.enableFinalFieldMutation().isEmpty())
            return false;
        for (String name : access.enableNativeAccess()) {
            var module = layer.findModule(name).orElse(null);
            if (module == null || !module.isNativeAccessEnabled())
                return false;
        }
        for (var export : access.addExports()) {
            if (!hasAccess(layer, export, false))
                return false;
        }
        for (var open : access.addOpens()) {
            if (!hasAccess(layer, open, true))
                return false;
        }
        return true;
    }

    @SuppressWarnings("restricted")
    static boolean configureLayer(ModuleLayer.Controller controller,
            ModuleLayer layer,
            List<String> arguments) {
        var access = ModuleRuntimeAccess.parseArguments(arguments);
        if (!access.enableFinalFieldMutation().isEmpty())
            return false;
        for (String name : access.enableNativeAccess()) {
            var module = layer.findModule(name).orElse(null);
            if (module == null)
                return false;
            if (!module.isNativeAccessEnabled()) {
                if (module.getLayer() != layer)
                    return false;
                controller.enableNativeAccess(module);
            }
        }
        for (var export : access.addExports()) {
            if (!addAccess(controller, layer, export, false))
                return false;
        }
        for (var open : access.addOpens()) {
            if (!addAccess(controller, layer, open, true))
                return false;
        }
        return true;
    }

    private static boolean hasAccess(ModuleLayer layer,
            ModuleRuntimeAccessOptions.PackageAccess access,
            boolean open) {
        var source = layer.findModule(access.sourceModule()).orElse(null);
        var target = layer.findModule(access.targetModule()).orElse(null);
        return source != null
                && target != null
                && (open
                        ? source.isOpen(access.packageName(), target)
                        : source.isExported(access.packageName(), target));
    }

    private static boolean addAccess(ModuleLayer.Controller controller,
            ModuleLayer layer,
            ModuleRuntimeAccessOptions.PackageAccess access,
            boolean open) {
        if (hasAccess(layer, access, open))
            return true;
        var source = layer.findModule(access.sourceModule()).orElse(null);
        var target = layer.findModule(access.targetModule()).orElse(null);
        if (source == null || target == null)
            return false;
        if (source.getLayer() != layer)
            return false;
        if (open) {
            controller.addOpens(source, access.packageName(), target);
        } else {
            controller.addExports(source, access.packageName(), target);
        }
        return true;
    }

    private ResolutionOptions contractResolutionOptions(JaInvocation commandLine, ToolDefinition definition) {
        return switch (commandLine.command()) {
            case Command.Tool _ -> resolutionOptions(definition);
            case Command.Builtin(var builtin) -> declaredResolutionOptions(definition, builtin.resolutionOptions());
            default -> throw new IllegalArgumentException("Command " + commandLine.command() + " does not run a tool");
        };
    }

    private ResolutionOptions declaredResolutionOptions(ToolDefinition definition, ResolutionOptions fallback) {
        var declared = definition.resolutionOptions();
        var options = declared.options().isEmpty()
                ? fallback.options()
                : resolutionOptions(definition).options();
        return new ResolutionOptions(options,
                declared.compileTime() || fallback.compileTime(),
                declared.validateRuntimeAccess() || fallback.validateRuntimeAccess(),
                declared.emitCompileDiagnostics() || fallback.emitCompileDiagnostics());
    }

    private ResolutionOptions resolutionOptions(ToolDefinition definition) {
        return tools.resolutionOptions(definition.provider(), definition.resolutionOptions());
    }

    private static ResolutionOptions withoutSingleModule(ResolutionOptions resolutionOptions) {
        var options = new LinkedHashSet<>(resolutionOptions.options());
        options.remove("module=single");
        return new ResolutionOptions(options,
                resolutionOptions.compileTime(),
                resolutionOptions.validateRuntimeAccess(),
                resolutionOptions.emitCompileDiagnostics());
    }

    private boolean hasActivation(JaInvocation commandLine) {
        if (commandLine.command() instanceof Command.Tool(var name)) {
            return catalog.definition(name)
                    .activation()
                    .isPresent();
        }
        return BuiltinCommand.from(commandLine.command())
                .map(BuiltinCommand::execution)
                .filter(BuiltinCommand.Execution.ToolBacked.class::isInstance)
                .map(BuiltinCommand.Execution.ToolBacked.class::cast)
                .map(BuiltinCommand.Execution.ToolBacked::tool)
                .flatMap(catalog::find)
                .flatMap(ToolDefinition::activation)
                .isPresent();
    }

    private static void requireSingleModule(JaInvocation commandLine, List<String> modules) {
        if (modules.size() == 1)
            return;
        String name = switch (commandLine.command()) {
            case Command.Tool(var tool) -> tool;
            default -> BuiltinCommand.from(commandLine.command()).orElseThrow().commandName();
        };
        throw new IllegalArgumentException(name + " requires one selected module; use --module <name> or -C <module-directory>");
    }

    private Optional<List<String>> explicitModules(JaInvocation commandLine, Set<String> options) {
        if (ModuleOptions.checker(options).isSupportedOption("--module") < 0) {
            return Optional.empty();
        }
        var checker = argumentChecker(commandLine, options);

        var modules = new LinkedHashSet<String>();
        var arguments = commandLine.toolArguments();
        for (int i = 0; i < arguments.size(); i++) {
            var argument = arguments.get(i);
            if (argument.equals("--"))
                break;
            if (argument.equals("-m") || argument.equals("--module")) {
                if (++i >= arguments.size()) {
                    throw new IllegalArgumentException(argument + " requires a value");
                }
                addModules(modules, argument, arguments.get(i));
                continue;
            }
            if (argument.startsWith("--module=")) {
                addModules(modules, "--module", argument.substring("--module=".length()));
                continue;
            }
            if (!argument.startsWith("-"))
                continue;
            var separator = argument.indexOf('=');
            var option = separator < 0 ? argument : argument.substring(0, separator);
            var operands = checker.isSupportedOption(option);
            if (separator < 0 && operands > 0) {
                i = Math.min(arguments.size(), i + operands);
            }
        }
        return modules.isEmpty()
                ? Optional.empty()
                : Optional.of(List.copyOf(modules));
    }

    private OptionChecker argumentChecker(JaInvocation commandLine, Set<String> options) {
        var fallback = ModuleOptions.checker(options);
        var definition =
                switch (commandLine.command()) {
                    case Command.Tool(var name) -> catalog.find(name);
                    case Command.Builtin(var builtin) ->
                            builtin.execution() instanceof BuiltinCommand.Execution.ToolBacked(var tool)
                                    ? catalog.find(tool)
                                    : Optional.<ToolDefinition>empty();
                    default -> Optional.<ToolDefinition>empty();
                };
        if (definition.isEmpty())
            return fallback;
        var selected = definition.orElseThrow();
        return tools.contains(selected.provider())
                ? tools.optionChecker(selected.provider(), fallback)
                : fallback;
    }

    private static void addModules(Set<String> modules, String option, String value) {
        for (var target : value.split(",")) {
            var separator = target.indexOf('/');
            var module = (separator < 0 ? target : target.substring(0, separator)).strip();
            if (!SourceVersion.isName(module)) {
                throw new IllegalArgumentException(option + " expects module[,module...][/mainClass]: " + value);
            }
            modules.add(module);
        }
    }

    private static BuiltinCommand builtin(JaInvocation commandLine) {
        return BuiltinCommand.from(commandLine.command()).orElseThrow(() -> new IllegalArgumentException("The tool command is not built in"));
    }
}
