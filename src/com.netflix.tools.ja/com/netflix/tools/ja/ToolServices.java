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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.ServiceLoader.Provider;
import java.util.Set;
import java.util.TreeSet;
import java.util.spi.ToolProvider;
import javax.tools.JavaCompiler;
import javax.tools.OptionChecker;
import javax.tools.Tool;

import com.netflix.tools.cli.CommandLine.Completion;
import com.netflix.tools.cli.CommandLine.CompletionRequest;
import com.netflix.tools.launcher.CompletionBundle.Builder;
import com.netflix.tools.launcher.ModuleOptions;

/**
 * Provides one named execution and option-checking view over {@link
 * javax.tools.Tool} and {@link java.util.spi.ToolProvider} services.
 *
 * <p>The rest of {@code ja} can therefore discover, inspect, and run either JDK
 * tool interface without maintaining separate execution paths.
 */
public final class ToolServices {
    static final String TOOL_SERVICE = Tool.class.getName();
    static final String TOOL_PROVIDER_SERVICE = ToolProvider.class.getName();
    static final Set<String> TOOL_SERVICE_NAMES = Set.of(TOOL_SERVICE, TOOL_PROVIDER_SERVICE);

    private enum Invocation {
        RESOLUTION,
        EXECUTION
    }

    private final Map<String, ToolProvider> providers;
    private final Map<String, Tool> compilerTools;
    private final boolean verbose;
    private final Set<String> verboseProviders;
    private final PrintStream verboseLog;

    private ToolServices(Map<String, ToolProvider> providers, Map<String, Tool> compilerTools) {
        this(providers, compilerTools, false, Set.of(), null);
    }

    private ToolServices(Map<String, ToolProvider> providers, Map<String, Tool> compilerTools, boolean verbose,
                         Set<String> verboseProviders, PrintStream verboseLog) {
        this.providers = Map.copyOf(providers);
        this.compilerTools = Map.copyOf(compilerTools);
        this.verbose = verbose;
        this.verboseProviders = Set.copyOf(verboseProviders);
        this.verboseLog = verboseLog;
    }

    public static ToolServices of(ToolProvider... providers) {
        var byName = new LinkedHashMap<String, ToolProvider>();
        for (ToolProvider provider : providers) {
            if (byName.putIfAbsent(provider.name(), provider) != null) {
                throw new IllegalArgumentException("Duplicate tool: " + provider.name());
            }
        }
        return new ToolServices(byName, Map.of());
    }

    public static ToolServices load(ModuleLayer layer) {
        return load(layer, null);
    }

    static ToolServices load(ModuleLayer layer, Set<String> moduleNames) {
        var providers = new LinkedHashMap<String, ToolProvider>();
        var compilerTools = new LinkedHashMap<String, Tool>();
        ServiceLoader.load(layer, Tool.class).stream()
                .filter(provider -> isEffectiveProvider(layer, provider.type()))
                .filter(provider -> moduleNames == null || moduleNames.contains(provider.type()
                        .getModule()
                        .getName()))
                .map(Provider::get)
                .filter(tool -> !tool.name().isBlank())
                .sorted(Comparator.comparing(Tool::name))
                .forEach(tool -> putUnique(compilerTools, tool.name(), tool));
        ServiceLoader.load(layer, ToolProvider.class).stream()
                .filter(provider -> isEffectiveProvider(layer, provider.type()))
                .filter(provider -> moduleNames == null || moduleNames.contains(provider.type()
                        .getModule()
                        .getName()))
                .map(Provider::get)
                .sorted(Comparator.comparing(ToolProvider::name))
                .forEach(provider -> putUnique(providers, provider.name(), provider));
        if (moduleNames == null && !ToolServices.class.getModule().isNamed()) {
            ServiceLoader.load(Tool.class, ClassLoader.getSystemClassLoader()).stream()
                    .map(Provider::get)
                    .filter(tool -> !tool.name().isBlank())
                    .forEach(tool -> putUnique(compilerTools, tool.name(), tool));
            ServiceLoader.load(ToolProvider.class, ClassLoader.getSystemClassLoader()).stream()
                    .map(Provider::get)
                    .forEach(provider -> putUnique(providers, provider.name(), provider));
        }
        requireCompatibleContracts(providers, compilerTools);
        return new ToolServices(providers, compilerTools);
    }

    private static boolean isEffectiveProvider(ModuleLayer layer, Class<?> type) {
        var module = type.getModule();
        return !module.isNamed() || layer.findModule(module.getName())
                .filter(module::equals)
                .isPresent();
    }

    ToolServices withAdditional(ToolServices additional) {
        var providers = new LinkedHashMap<>(this.providers);
        var compilerTools = new LinkedHashMap<>(this.compilerTools);
        additional.compilerTools.forEach((name, tool) -> {
            requireAbsent(name);
            compilerTools.put(name, tool);
        });
        additional.providers.forEach((name, provider) -> {
            if (!additional.compilerTools.containsKey(name)) {
                requireAbsent(name);
                providers.put(name, provider);
            }
        });
        return new ToolServices(providers, compilerTools, verbose, verboseProviders, verboseLog);
    }

    ToolServices withVerbose(List<ToolDefinition> definitions, PrintStream log) {
        return configureVerbose(definitions, Objects.requireNonNull(log));
    }

    private ToolServices configureVerbose(List<ToolDefinition> definitions, PrintStream log) {
        var supported = new LinkedHashSet<>(verboseProviders);
        supported.add("jig");
        definitions.stream()
                .filter(definition -> definition.isSupportedOption("--verbose") == 0)
                .map(ToolDefinition::provider)
                .forEach(supported::add);
        return new ToolServices(providers, compilerTools, true, supported, log);
    }

    ToolServices withVerbose(List<ToolDefinition> definitions) {
        if (!verbose) {
            throw new IllegalStateException("Verbose logging is not configured");
        }
        return configureVerbose(definitions, verboseLog);
    }

    boolean verbose() {
        return verbose;
    }

    private void requireAbsent(String name) {
        if (contains(name)) {
            throw new IllegalArgumentException("Duplicate tool: " + name);
        }
    }

    private static <T> void putUnique(Map<String, T> tools, String name, T tool) {
        var existing = tools.putIfAbsent(name, tool);
        if (existing != null && existing.getClass() != tool.getClass()) {
            throw new IllegalArgumentException("Duplicate tool: " + name);
        }
    }

    private static void requireCompatibleContracts(Map<String, ToolProvider> providers, Map<String, Tool> compilerTools) {
        providers.forEach(
                (name, provider) -> {
                    var compilerTool = compilerTools.get(name);
                    if (compilerTool == null) {
                        return;
                    }
                    var providerType = provider.getClass();
                    var compilerType = compilerTool.getClass();
                    var module = providerType.getModule();
                    if (providerType != compilerType && (!module.isNamed() || module != compilerType.getModule())) {
                        throw new IllegalArgumentException("Duplicate tool: " + name);
                    }
                });
    }

    public boolean contains(String name) {
        return providers.containsKey(name) || compilerTools.containsKey(name);
    }

    public Set<String> names() {
        var names = new TreeSet<String>();
        names.addAll(providers.keySet());
        names.addAll(compilerTools.keySet());
        return Set.copyOf(names);
    }

    public List<Completion> complete(String name, CompletionRequest request) {
        Objects.requireNonNull(name);
        Objects.requireNonNull(request);
        var checker = optionChecker(name);
        if (checker == null || checker.isSupportedOption("__complete") != 0) {
            return List.of();
        }
        var arguments = new ArrayList<String>();
        arguments.add("__complete");
        if (!request.invocation()
                    .workingDirectory()
                    .toString()
                    .isEmpty()) {
            arguments.add("-C");
            arguments.add(request.invocation()
                                 .workingDirectory()
                                 .toString());
        }
        arguments.addAll(request.invocation()
                                .arguments());
        arguments.add(request.current());

        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        int result;
        var tool = compilerTools.get(name);
        if (tool != null) {
            result = tool.run(InputStream.nullInputStream(), output, errors, arguments.toArray(String[]::new));
        } else {
            result = provider(name).run(new PrintWriter(output, true, StandardCharsets.UTF_8),
                    new PrintWriter(errors, true, StandardCharsets.UTF_8), arguments.toArray(String[]::new));
        }
        if (result != 0) {
            return List.of();
        }
        return completionProtocol(output.toString(StandardCharsets.UTF_8));
    }

    private static List<Completion> completionProtocol(String output) {
        var completions = new ArrayList<Completion>();
        for (String line : output.lines().toList()) {
            if (line.startsWith(":")) {
                return line.equals(":0") ? List.copyOf(completions) : List.of();
            }
            int separator = line.indexOf('\t');
            String value = separator < 0 ? line : line.substring(0, separator);
            String description = separator < 0 ? "" : line.substring(separator + 1);
            if (!value.isEmpty()) {
                completions.add(new Completion(value, description));
            }
        }
        return List.of();
    }

    void contributeCompletions(Builder completions) {
        Objects.requireNonNull(completions);
        names().stream()
                .filter(name -> {
                    var checker = optionChecker(name);
                    return checker != null && checker.isSupportedOption("__complete") == 0;
                })
                .sorted()
                .forEach(completions::add);
    }

    public ClassLoader classLoader(String name) {
        var tool = compilerTools.get(name);
        if (tool != null) {
            return tool.getClass().getClassLoader();
        }
        return provider(name).getClass().getClassLoader();
    }

    public Optional<String> moduleName(String name) {
        var tool = compilerTools.get(name);
        var type = tool == null ? provider(name).getClass() : tool.getClass();
        return Optional.ofNullable(type.getModule()
                .getName());
    }

    public Set<String> resolutionOptions(String name, Set<String> declaredOptions) {
        if (!declaredOptions.isEmpty()) {
            return ModuleOptions.resolutionOptions(declaredOptions);
        }
        if (compilerTools.get(name) instanceof JavaCompiler) {
            return ResolutionOptions.JAVAC_OPTIONS;
        }
        var checker = optionChecker(name);
        return checker == null ? Set.of() : ModuleOptions.supportedBy(checker);
    }

    ResolutionOptions resolutionOptions(String name, ResolutionOptions declared) {
        if (declared.active()) {
            return declared;
        }
        if (compilerTools.get(name) instanceof JavaCompiler) {
            return ResolutionOptions.JAVAC;
        }
        var checker = optionChecker(name);
        return new ResolutionOptions(checker == null ? Set.of() : ModuleOptions.supportedBy(checker),
                false, false);
    }

    public OptionChecker optionChecker(String name, OptionChecker fallback) {
        Objects.requireNonNull(fallback);
        var checker = optionChecker(name);
        if (checker == null) {
            return fallback;
        }
        var platform = checker;
        return option -> {
            int operands = platform.isSupportedOption(option);
            return operands >= 0 ? operands : fallback.isSupportedOption(option);
        };
    }

    private OptionChecker optionChecker(String name) {
        var tool = compilerTools.get(name);
        if (tool instanceof OptionChecker checker) {
            return checker;
        }
        if (providers.get(name) instanceof OptionChecker checker) {
            return checker;
        }
        return null;
    }

    int resolveModules(InputStream in, PrintStream out, PrintStream err,
                       String... arguments) {
        return run(Invocation.RESOLUTION, "jig", in, out, err, arguments);
    }

    public int run(String name, InputStream in, PrintStream out,
                   PrintStream err, String... arguments) {
        return run(Invocation.EXECUTION, name, in, out, err, arguments);
    }

    private int run(Invocation invocation, String name, InputStream in,
                    PrintStream out, PrintStream err, String... arguments) {
        if (verbose && supportsVerbose(name) && !List.of(arguments).contains("--verbose")) {
            var verboseArguments = new String[arguments.length + 1];
            verboseArguments[0] = "--verbose";
            System.arraycopy(arguments, 0, verboseArguments, 1, arguments.length);
            arguments = verboseArguments;
        }
        if (verbose) {
            if (invocation == Invocation.RESOLUTION) {
                VerboseLog.resolving(verboseLog, List.of(arguments));
            } else {
                VerboseLog.executing(verboseLog, name, List.of(arguments));
            }
        }
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        thread.setContextClassLoader(classLoader(name));
        try {
            var tool = compilerTools.get(name);
            if (tool != null) {
                return tool.run(in, out, err, arguments);
            }
            return provider(name).run(out, err, arguments);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private boolean supportsVerbose(String name) {
        if (verboseProviders.contains(name)) {
            return true;
        }
        var checker = optionChecker(name);
        return checker != null && checker.isSupportedOption("--verbose") == 0;
    }

    private ToolProvider provider(String name) {
        var provider = providers.get(name);
        if (provider == null) {
            throw new IllegalArgumentException("Tool " + name + " is not installed");
        }
        return provider;
    }
}
