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
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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

import com.netflix.module.ModuleRuntimeAccess;
import com.netflix.module.ModuleRuntimeAccessOptions;
import com.netflix.tools.cli.CommandLine.Completion;
import com.netflix.tools.cli.CommandLine.CompletionRequest;
import com.netflix.tools.launcher.CompletionBundle.Builder;
import com.netflix.tools.launcher.ModuleOptions;
import com.netflix.tools.launcher.ToolLauncher;
import com.netflix.tools.launcher.WarmupPlans;

/**
 * Hosts {@link javax.tools.Tool} and {@link java.util.spi.ToolProvider}
 * services behind one access-aware runtime.
 *
 * <p>Provider instances never escape this class, so declared module access is
 * applied to option inspection, completion, and execution alike.
 */
public final class ToolRuntime {
    private static final String IS_SUPPORTED_OPTION = "--is-supported-option";
    static final String TOOL_SERVICE = Tool.class.getName();
    static final String TOOL_PROVIDER_SERVICE = ToolProvider.class.getName();
    static final Set<String> TOOL_SERVICE_NAMES = Set.of(TOOL_SERVICE, TOOL_PROVIDER_SERVICE);

    private enum Invocation {
        RESOLUTION,
        EXECUTION
    }

    private final Map<String, ToolHandle> tools;
    private final boolean verbose;
    private final Set<String> verboseProviders;
    private final PrintStream verboseLog;
    private final JavaLauncher javaLauncher;

    private ToolRuntime(Map<String, ToolHandle> tools) {
        this(tools, false, Set.of(), null, JavaProcess::launch);
    }

    private ToolRuntime(Map<String, ToolHandle> tools, boolean verbose,
                        Set<String> verboseProviders, PrintStream verboseLog, JavaLauncher javaLauncher) {
        this.tools = Map.copyOf(tools);
        this.verbose = verbose;
        this.verboseProviders = Set.copyOf(verboseProviders);
        this.verboseLog = verboseLog;
        this.javaLauncher = Objects.requireNonNull(javaLauncher);
    }

    // Option checking is launched here when a provider's module access cannot be
    // granted to its existing layer.
    public static void main(String[] arguments) {
        if (arguments.length != 3 || !arguments[0].equals(IS_SUPPORTED_OPTION)) {
            System.err.println("Usage: " + ToolRuntime.class.getName() + " " + IS_SUPPORTED_OPTION + " TOOL OPTION");
            System.exit(2);
            return;
        }
        var tools = load(ModuleLayer.boot());
        var checker = tools.localOptionChecker(arguments[1]);
        System.out.println(checker == null ? -1 : checker.isSupportedOption(arguments[2]));
    }

    public static ToolRuntime of(ToolProvider... providers) {
        var tools = new LinkedHashMap<String, ToolHandle>();
        for (ToolProvider provider : providers) {
            put(tools, new ProviderHandle(provider.name(), provider));
        }
        return new ToolRuntime(tools);
    }

    public static ToolRuntime load(ModuleLayer layer) {
        return load(layer, null);
    }

    static ToolRuntime load(ModuleLayer layer, Set<String> moduleNames) {
        var tools = new LinkedHashMap<String, ToolHandle>();
        ServiceLoader.load(layer, Tool.class).stream()
                .filter(provider -> isEffectiveProvider(layer, provider.type()))
                .filter(provider -> moduleNames == null || moduleNames.contains(provider.type()
                        .getModule()
                        .getName()))
                .map(Provider::get)
                .filter(tool -> !tool.name().isBlank())
                .sorted(Comparator.comparing(Tool::name))
                .map(tool -> new StreamHandle(tool.name(), tool))
                .forEach(handle -> put(tools, handle));
        ServiceLoader.load(layer, ToolProvider.class).stream()
                .filter(provider -> isEffectiveProvider(layer, provider.type()))
                .filter(provider -> moduleNames == null || moduleNames.contains(provider.type()
                        .getModule()
                        .getName()))
                .map(Provider::get)
                .sorted(Comparator.comparing(ToolProvider::name))
                .map(provider -> new ProviderHandle(provider.name(), provider))
                .forEach(handle -> put(tools, handle));
        if (moduleNames == null && !ToolRuntime.class.getModule().isNamed()) {
            ServiceLoader.load(Tool.class, ClassLoader.getSystemClassLoader()).stream()
                    .map(Provider::get)
                    .filter(tool -> !tool.name().isBlank())
                    .map(tool -> new StreamHandle(tool.name(), tool))
                    .forEach(handle -> put(tools, handle));
            ServiceLoader.load(ToolProvider.class, ClassLoader.getSystemClassLoader()).stream()
                    .map(Provider::get)
                    .map(provider -> new ProviderHandle(provider.name(), provider))
                    .forEach(handle -> put(tools, handle));
        }
        return new ToolRuntime(tools);
    }

    private static boolean isEffectiveProvider(ModuleLayer layer, Class<?> type) {
        var module = type.getModule();
        return !module.isNamed() || layer.findModule(module.getName())
                .filter(module::equals)
                .isPresent();
    }

    ToolRuntime withAdditional(ToolRuntime additional) {
        var combined = new LinkedHashMap<>(tools);
        additional.tools.forEach((name, tool) -> {
            if (combined.putIfAbsent(name, tool) != null) {
                throw new IllegalArgumentException("Duplicate tool: " + name);
            }
        });
        return new ToolRuntime(combined, verbose, verboseProviders, verboseLog, javaLauncher);
    }

    ToolRuntime withJavaLauncher(JavaLauncher javaLauncher) {
        return new ToolRuntime(tools, verbose, verboseProviders, verboseLog, javaLauncher);
    }

    ToolRuntime withVerbose(List<ToolDefinition> definitions, PrintStream log) {
        return configureVerbose(definitions, Objects.requireNonNull(log));
    }

    private ToolRuntime configureVerbose(List<ToolDefinition> definitions, PrintStream log) {
        var supported = new LinkedHashSet<>(verboseProviders);
        supported.add("jig");
        definitions.stream()
                .filter(definition -> definition.isSupportedOption("--verbose") == 0)
                .map(ToolDefinition::provider)
                .forEach(supported::add);
        return new ToolRuntime(tools, true, supported, log, javaLauncher);
    }

    ToolRuntime withVerbose(List<ToolDefinition> definitions) {
        if (!verbose) {
            throw new IllegalStateException("Verbose logging is not configured");
        }
        return configureVerbose(definitions, verboseLog);
    }

    boolean verbose() {
        return verbose;
    }

    private static void put(Map<String, ToolHandle> tools, ToolHandle handle) {
        var existing = tools.putIfAbsent(handle.name(), handle);
        if (existing == null || existing.type() == handle.type()) {
            return;
        }
        if (existing.streamAware() == handle.streamAware()) {
            throw new IllegalArgumentException("Duplicate tool: " + handle.name());
        }
        var provider = existing.streamAware() ? handle : existing;
        if (!provider.module().isNamed() || existing.module() != handle.module()) {
            throw new IllegalArgumentException("Duplicate tool: " + handle.name());
        }
        if (handle.streamAware()) {
            tools.put(handle.name(), handle);
        }
    }

    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    public Set<String> names() {
        return Set.copyOf(new TreeSet<>(tools.keySet()));
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
        int result = run(name,
                InputStream.nullInputStream(),
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(errors, true, StandardCharsets.UTF_8),
                arguments.toArray(String[]::new));
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
        return tool(name).type().getClassLoader();
    }

    public Optional<String> moduleName(String name) {
        return Optional.ofNullable(module(name).getName());
    }

    Optional<String> warmup(String name) {
        var module = module(name);
        String resource = "META-INF/com.netflix.tools/tools/" + name + ".properties";
        try (var input = module.getResourceAsStream(resource)) {
            return input == null
                    ? Optional.empty()
                    : WarmupPlans.read(input, module.getName() + "/" + resource);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read warmup configuration " + resource, e);
        }
    }

    public Set<String> resolutionOptions(String name, Set<String> declaredOptions) {
        if (!declaredOptions.isEmpty()) {
            return ModuleOptions.resolutionOptions(declaredOptions);
        }
        if (find(name).map(ToolHandle::javaCompiler).orElse(false)) {
            return ResolutionOptions.JAVAC_OPTIONS;
        }
        var checker = optionChecker(name);
        return checker == null ? Set.of() : ModuleOptions.supportedBy(checker);
    }

    ResolutionOptions resolutionOptions(String name, ResolutionOptions declared) {
        if (declared.active()) {
            return declared;
        }
        if (find(name).map(ToolHandle::javaCompiler).orElse(false)) {
            return ResolutionOptions.JAVAC;
        }
        var checker = optionChecker(name);
        return new ResolutionOptions(checker == null ? Set.of() : ModuleOptions.supportedBy(checker),
                false);
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
        var checker = localOptionChecker(name);
        if (checker == null) {
            return null;
        }
        return option -> isSupportedOption(name, option, checker);
    }

    private OptionChecker localOptionChecker(String name) {
        return find(name).flatMap(ToolHandle::optionChecker).orElse(null);
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
        var access = declaredRuntimeAccess(name);
        if (access.isPresent() && !runtimeAccessIsEffective(access.orElseThrow())) {
            return launch(name, access.orElseThrow(), in, out, err, arguments);
        }
        return runLocal(name, in, out, err, arguments);
    }

    private int runLocal(String name, InputStream in, PrintStream out,
                         PrintStream err, String... arguments) {
        var handle = tool(name);
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        thread.setContextClassLoader(handle.type().getClassLoader());
        try {
            return handle.run(in, out, err, arguments);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private int isSupportedOption(String name, String option, OptionChecker checker) {
        var access = declaredRuntimeAccess(name);
        if (access.isEmpty() || runtimeAccessIsEffective(access.orElseThrow())) {
            return checker.isSupportedOption(option);
        }
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        int result = launchOptionCheck(
                access.orElseThrow(),
                name,
                option,
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(errors, true, StandardCharsets.UTF_8));
        if (result != 0) {
            var diagnostics = errors.toString(StandardCharsets.UTF_8).strip();
            throw new IllegalStateException(diagnostics.isEmpty()
                    ? "Unable to inspect tool option for " + name
                    : diagnostics);
        }
        try {
            return Integer.parseInt(output.toString(StandardCharsets.UTF_8).strip());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid option check response from " + name, e);
        }
    }

    private int launch(String name,
                       RuntimeAccess access,
                       InputStream in,
                       PrintStream out,
                       PrintStream err,
                       String... arguments) {
        return launch(name, runtimeArguments(access), List.of(arguments), in, out, err);
    }

    int launch(String name,
               List<String> runtimeArguments,
               List<String> toolArguments,
               InputStream in,
               PrintStream out,
               PrintStream err) {
        var arguments = new ArrayList<>(runtimeArguments);
        moduleLocation(ToolLauncher.class.getModule()).ifPresent(path -> {
            arguments.addFirst(path.toString());
            arguments.addFirst("--upgrade-module-path");
        });
        arguments.add("--module");
        arguments.add(ToolLauncher.class.getModule().getName() + "/" + ToolLauncher.class.getName());
        arguments.add(name);
        arguments.addAll(toolArguments);
        return launchJava(arguments, in, out, err);
    }

    private int launchOptionCheck(RuntimeAccess access,
                                  String name,
                                  String option,
                                  PrintStream out,
                                  PrintStream err) {
        var arguments = new ArrayList<String>();
        var serviceLocation = moduleLocation(ToolRuntime.class.getModule());
        serviceLocation.ifPresent(path -> {
            arguments.add("--upgrade-module-path");
            arguments.add(path.toString());
        });
        // Self-hosted tests patch freshly compiled classes over a bootstrap module.
        classContent(ToolRuntime.class)
                .filter(path -> serviceLocation.map(location -> !location.equals(path)).orElse(true))
                .ifPresent(path -> {
                    arguments.add("--patch-module");
                    arguments.add(ToolRuntime.class.getModule().getName() + "=" + path);
                });
        arguments.addAll(runtimeArguments(access));
        arguments.add("--module");
        arguments.add(ToolRuntime.class.getModule().getName() + "/" + ToolRuntime.class.getName());
        arguments.add(IS_SUPPORTED_OPTION);
        arguments.add(name);
        arguments.add(option);
        return launchJava(arguments, InputStream.nullInputStream(), out, err);
    }

    private int launchJava(List<String> arguments, InputStream in, PrintStream out, PrintStream err) {
        try {
            return javaLauncher.run(arguments, in, out, err);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to launch Java tool process", e);
        }
    }

    private static ArrayList<String> runtimeArguments(RuntimeAccess access) {
        var arguments = new ArrayList<String>();
        var modulePath = access.module()
                .getLayer()
                .configuration()
                .modules()
                .stream()
                .flatMap(module -> module.reference().location().stream())
                .filter(location -> "file".equalsIgnoreCase(location.getScheme()))
                .map(Path::of)
                .distinct()
                .sorted()
                .map(Path::toString)
                .toList();
        if (!modulePath.isEmpty()) {
            arguments.add("--module-path");
            arguments.add(String.join(System.getProperty("path.separator"), modulePath));
        }
        arguments.add("--add-modules");
        arguments.add(access.module().getName());
        access.options().enableNativeAccess().forEach(module -> arguments.add("--enable-native-access=" + module));
        access.options().enableFinalFieldMutation().forEach(module -> arguments.add("--enable-final-field-mutation=" + module));
        access.options().addExports().forEach(value -> {
            arguments.add("--add-exports");
            arguments.add(value.toFlagValue());
        });
        access.options().addOpens().forEach(value -> {
            arguments.add("--add-opens");
            arguments.add(value.toFlagValue());
        });
        return arguments;
    }

    private static Optional<Path> classContent(Class<?> type) {
        var resource = type.getResource(type.getSimpleName() + ".class");
        if (resource == null || !resource.getProtocol().equals("file")) {
            return Optional.empty();
        }
        try {
            Path root = Path.of(resource.toURI()).getParent();
            for (int i = 0; i < type.getPackageName().split("\\.").length; i++) {
                root = root.getParent();
            }
            return Optional.of(root);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Invalid tool service location", e);
        }
    }

    private static Optional<Path> moduleLocation(Module module) {
        if (!module.isNamed() || module.getLayer() == null) {
            return Optional.empty();
        }
        return module.getLayer()
                .configuration()
                .findModule(module.getName())
                .flatMap(resolved -> resolved.reference().location())
                .filter(location -> "file".equalsIgnoreCase(location.getScheme()))
                .map(Path::of);
    }

    private Optional<RuntimeAccess> declaredRuntimeAccess(String name) {
        var module = module(name);
        if (!module.isNamed() || module.getLayer() == null) {
            return Optional.empty();
        }
        var resolved = module.getLayer().configuration().findModule(module.getName()).orElse(null);
        if (resolved == null) {
            return Optional.empty();
        }
        try {
            return ModuleRuntimeAccess.read(resolved.reference())
                    .filter(access -> !access.isEmpty())
                    .map(access -> new RuntimeAccess(module, access));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read runtime access for " + module.getName(), e);
        }
    }

    @SuppressWarnings("restricted")
    private static boolean runtimeAccessIsEffective(RuntimeAccess access) {
        if (!access.options().enableFinalFieldMutation().isEmpty()) {
            return false;
        }
        var layer = access.module().getLayer();
        for (String name : access.options().enableNativeAccess()) {
            var module = layer.findModule(name).orElse(null);
            if (module == null || !module.isNativeAccessEnabled()) {
                return false;
            }
        }
        for (var export : access.options().addExports()) {
            if (!hasAccess(layer, export, false)) {
                return false;
            }
        }
        for (var open : access.options().addOpens()) {
            if (!hasAccess(layer, open, true)) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("restricted")
    static boolean configureLayer(ModuleLayer.Controller controller,
                                  ModuleLayer layer,
                                  List<String> arguments) {
        var access = ModuleRuntimeAccess.parseArguments(arguments);
        if (!access.enableFinalFieldMutation().isEmpty()) {
            return false;
        }
        for (String name : access.enableNativeAccess()) {
            var module = layer.findModule(name).orElse(null);
            if (module == null) {
                return false;
            }
            if (!module.isNativeAccessEnabled()) {
                if (module.getLayer() != layer) {
                    return false;
                }
                controller.enableNativeAccess(module);
            }
        }
        for (var export : access.addExports()) {
            if (!addAccess(controller, layer, export, false)) {
                return false;
            }
        }
        for (var open : access.addOpens()) {
            if (!addAccess(controller, layer, open, true)) {
                return false;
            }
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
        if (hasAccess(layer, access, open)) {
            return true;
        }
        var source = layer.findModule(access.sourceModule()).orElse(null);
        var target = layer.findModule(access.targetModule()).orElse(null);
        if (source == null || target == null || source.getLayer() != layer) {
            return false;
        }
        if (open) {
            controller.addOpens(source, access.packageName(), target);
        } else {
            controller.addExports(source, access.packageName(), target);
        }
        return true;
    }

    private Module module(String name) {
        return tool(name).module();
    }

    private Optional<ToolHandle> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    private ToolHandle tool(String name) {
        return find(name).orElseThrow(() -> new IllegalArgumentException("Tool " + name + " is not installed"));
    }

    private sealed interface ToolHandle permits StreamHandle, ProviderHandle {
        String name();

        Class<?> type();

        Optional<OptionChecker> optionChecker();

        int run(InputStream in, PrintStream out, PrintStream err, String... arguments);

        boolean streamAware();

        default Module module() {
            return type().getModule();
        }

        default boolean javaCompiler() {
            return false;
        }
    }

    private record StreamHandle(String name, Tool service) implements ToolHandle {
        @Override
        public Class<?> type() {
            return service.getClass();
        }

        @Override
        public Optional<OptionChecker> optionChecker() {
            return service instanceof OptionChecker checker ? Optional.of(checker) : Optional.empty();
        }

        @Override
        public int run(InputStream in, PrintStream out, PrintStream err, String... arguments) {
            return service.run(in, out, err, arguments);
        }

        @Override
        public boolean streamAware() {
            return true;
        }

        @Override
        public boolean javaCompiler() {
            return service instanceof JavaCompiler;
        }
    }

    private record ProviderHandle(String name, ToolProvider service) implements ToolHandle {
        @Override
        public Class<?> type() {
            return service.getClass();
        }

        @Override
        public Optional<OptionChecker> optionChecker() {
            return service instanceof OptionChecker checker ? Optional.of(checker) : Optional.empty();
        }

        @Override
        public int run(InputStream in, PrintStream out, PrintStream err, String... arguments) {
            return service.run(out, err, arguments);
        }

        @Override
        public boolean streamAware() {
            return false;
        }
    }

    private record RuntimeAccess(Module module, ModuleRuntimeAccessOptions options) {}

    private boolean supportsVerbose(String name) {
        if (verboseProviders.contains(name)) {
            return true;
        }
        var checker = optionChecker(name);
        return checker != null && checker.isSupportedOption("--verbose") == 0;
    }
}
