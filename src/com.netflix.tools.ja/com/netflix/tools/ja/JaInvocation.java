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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.lang.model.SourceVersion;

import com.netflix.module.ModuleRuntimeAccess;
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
import com.netflix.tools.ja.RequireRequest.Dependency;
import com.netflix.tools.ja.RequireRequest.RuntimeAccess;
import com.netflix.tools.ja.RequireRequest.UpdatePolicy;

/**
 * Represents a parsed {@code ja} command line, including its working directory,
 * module scope, and tool arguments.
 *
 * <p>Parsing establishes the command and source-module scope without resolving
 * dependencies or interpreting arguments owned by tools.
 */
public record JaInvocation(
        Path workingDirectory,
        boolean verbose,
        Command command,
        Optional<ModuleSourcePath> moduleSourcePath,
        List<String> rootModules,
        List<String> resolutionArguments,
        List<String> toolArguments) {

    public JaInvocation(Path workingDirectory, Command command, Optional<ModuleSourcePath> moduleSourcePath,
                        List<String> rootModules, List<String> resolutionArguments, List<String> toolArguments) {
        this(workingDirectory, false, command, moduleSourcePath, rootModules, resolutionArguments,
                toolArguments);
    }

    public JaInvocation {
        rootModules = List.copyOf(rootModules);
        resolutionArguments = List.copyOf(resolutionArguments);
        toolArguments = List.copyOf(toolArguments);
    }

    public static JaInvocation parse(Path workingDirectory, String[] arguments) throws IOException {
        if (arguments.length == 0) {
            throw new IllegalArgumentException("Missing command");
        }

        var selectedWorkingDirectory = workingDirectory.toAbsolutePath().normalize();

        var rootModules = new ArrayList<String>();
        var resolutionArguments = new ArrayList<String>();
        boolean explicitRoots = false;
        boolean verbose = false;
        int commandIndex = 0;
        while (commandIndex < arguments.length && arguments[commandIndex].equals("--verbose")) {
            if (verbose) {
                throw new IllegalArgumentException("--verbose may only be specified once");
            }
            verbose = true;
            commandIndex++;
        }
        if (commandIndex == arguments.length) {
            throw new IllegalArgumentException("Missing command");
        }

        Command command;
        int commandArguments;
        String commandName = arguments[commandIndex];
        var discoveredBuiltin = BuiltinCommand.find(commandName);
        if (discoveredBuiltin.isPresent()) {
            command = new Builtin(discoveredBuiltin.orElseThrow());
            commandArguments = commandIndex + 1;
        } else if (commandName.equals("tool")) {
            if (arguments.length == commandIndex + 1) {
                command = new Tools();
                commandArguments = commandIndex + 1;
            } else {
                command = new Tool(arguments[commandIndex + 1]);
                commandArguments = commandIndex + 2;
            }
        } else if (commandName.startsWith("-")) {
            throw new IllegalArgumentException("Unknown ja option: " + commandName);
        } else {
            throw new IllegalArgumentException("Unknown command: " + commandName);
        }
        if (command instanceof Builtin(var builtin)
                && builtin.execution() instanceof ToolBacked
                && commandArguments < arguments.length
                && arguments[commandArguments].equals("--")) {
            commandArguments++;
        }

        var toolArguments = new ArrayList<String>();
        if (command.equals(new Builtin(BuiltinCommand.INIT))) {
            command = new Init(parseInitRequest(arguments, commandArguments));
        } else if (command.equals(new Builtin(BuiltinCommand.INSTALL))) {
            command = new Install(parseInstallRequest(selectedWorkingDirectory, arguments, commandArguments));
        } else if (command.equals(new Builtin(BuiltinCommand.REQUIRE))) {
            command = new Require(parseRequireRequest(arguments, commandArguments));
        } else if (command.equals(new Builtin(BuiltinCommand.DOC))) {
            command = new Doc(parseDocRequest(arguments, commandArguments));
        } else if (command.equals(new Builtin(BuiltinCommand.SOURCE))) {
            command = new Source(parseSourceSymbol(arguments, commandArguments));
        } else if (command.equals(new Builtin(BuiltinCommand.DESCRIBE))) {
            boolean separated = false;
            for (int i = commandArguments; i < arguments.length; i++) {
                String option = arguments[i];
                if (!separated && option.equals("--")) {
                    separated = true;
                    toolArguments.add(option);
                } else if (!separated && (option.equals("-m") || option.equals("--module"))) {
                    if (++i >= arguments.length) {
                        throw new IllegalArgumentException(option + " requires a value");
                    }
                    String module = moduleSelection(option, arguments[i]);
                    explicitRoots = true;
                    rootModules.add(module);
                    resolutionArguments.add("-m");
                    resolutionArguments.add(module);
                } else if (!separated && option.startsWith("--module=")) {
                    String module = moduleSelection("--module", option.substring("--module=".length()));
                    explicitRoots = true;
                    rootModules.add(module);
                    resolutionArguments.add("-m");
                    resolutionArguments.add(module);
                } else {
                    toolArguments.add(option);
                }
            }
        } else if (command.equals(new Builtin(BuiltinCommand.RUN))) {
            Optional<String> runTarget = Optional.empty();
            if (commandArguments < arguments.length) {
                String option = arguments[commandArguments];
                if (option.equals("--")) {
                    commandArguments++;
                } else {
                    String target = null;
                    if (option.equals("-m") || option.equals("--module")) {
                        if (++commandArguments >= arguments.length) {
                            throw new IllegalArgumentException(option + " requires a value");
                        }
                        target = arguments[commandArguments++];
                    } else if (option.startsWith("--module=")) {
                        target = option.substring("--module=".length());
                        option = "--module";
                        commandArguments++;
                    }
                    if (target != null) {
                        String module = runTargetModule(option, target);
                        explicitRoots = true;
                        resolutionArguments.add("-m");
                        resolutionArguments.add(module);
                        runTarget = Optional.of(target);
                        if (commandArguments < arguments.length && arguments[commandArguments].equals("--")) {
                            commandArguments++;
                        }
                    }
                }
            }
            for (int i = commandArguments; i < arguments.length; i++) {
                toolArguments.add(arguments[i]);
            }
            command = new Run(runTarget);
        } else if (command.equals(new Builtin(BuiltinCommand.COMPILE))) {
            for (int i = commandArguments; i < arguments.length; i++) {
                String argument = arguments[i];
                if (argument.equals("--recompile")) {
                    resolutionArguments.add(argument);
                } else {
                    throw new IllegalArgumentException("Unknown compile option: " + argument);
                }
            }
        } else {
            for (int i = commandArguments; i < arguments.length; i++) {
                toolArguments.add(arguments[i]);
            }
        }

        boolean init = command instanceof Init;
        boolean install = command instanceof Install;
        boolean sourceInstall = command instanceof Install(var request) && request.target().isEmpty();
        Optional<ModuleSourcePath> moduleSourcePath = Optional.empty();
        if ((!init && !install) || sourceInstall) {
            boolean parseDescriptors = !command.equals(new Builtin(BuiltinCommand.COMPILE));
            moduleSourcePath = ModuleSourcePath.discover(selectedWorkingDirectory, parseDescriptors);
            if (moduleSourcePath.isPresent()) {
                var discovered = moduleSourcePath.get();
                for (var sourcePath : discovered.arguments()) {
                    resolutionArguments.add("--module-source-path");
                    resolutionArguments.add(sourcePath);
                }
                if (!explicitRoots) {
                    var defaults = discovered.defaultRoots();
                    boolean inferLaunchRoot = defaults.size() > 1 && (sourceInstall || command instanceof Run);
                    if (!inferLaunchRoot) {
                        for (String root : defaults) {
                            rootModules.add(root);
                            resolutionArguments.add("-m");
                            resolutionArguments.add(root);
                        }
                    }
                }
            }
        }

        if (sourceInstall) {
            if (moduleSourcePath.isEmpty()) {
                throw new IllegalArgumentException("install requires a module/package reference or a current source module");
            }
            if (rootModules.size() == 1 && !moduleSourcePath.orElseThrow()
                    .modules()
                    .containsKey(rootModules.getFirst())) {
                throw new IllegalArgumentException("install requires a source module: " + rootModules.getFirst());
            }
        }
        if (!init
                && !install
                && !(command instanceof Tool)
                && !(command instanceof Tools)
                && moduleSourcePath.isEmpty()) {
            throw new IllegalArgumentException("No module source path was found");
        }
        if (command instanceof Require(var request)) {
            if (moduleSourcePath.isEmpty()) {
                throw new IllegalArgumentException("require needs a discovered module source path");
            }
            var discovered = moduleSourcePath.orElseThrow();
            for (String root : rootModules) {
                if (!discovered.modules().containsKey(root)) {
                    throw new IllegalArgumentException("require needs a source module: " + root);
                }
            }
            if (!request.updatesVersions() && rootModules.size() != 1) {
                throw new IllegalArgumentException("require needs exactly one selected source module");
            }
        }

        return new JaInvocation(selectedWorkingDirectory, verbose, command, moduleSourcePath, rootModules, resolutionArguments,
                toolArguments);
    }

    private static InitRequest parseInitRequest(String[] arguments, int firstArgument) {
        String moduleName = null;
        Integer release = null;
        String mainClass = null;
        boolean enablePreview = false;
        var runtimeArguments = new ArrayList<String>();
        for (int i = firstArgument; i < arguments.length; i++) {
            String argument = arguments[i];
            if (argument.equals("--release")) {
                if (release != null) {
                    throw new IllegalArgumentException("--release may only be specified once");
                }
                if (++i >= arguments.length) {
                    throw new IllegalArgumentException("--release requires a value");
                }
                release = release(arguments[i]);
            } else if (argument.startsWith("--release=")) {
                if (release != null) {
                    throw new IllegalArgumentException("--release may only be specified once");
                }
                release = release(argument.substring("--release=".length()));
            } else if (argument.equals("--main-class")) {
                if (mainClass != null) {
                    throw new IllegalArgumentException("--main-class may only be specified once");
                }
                if (++i >= arguments.length) {
                    throw new IllegalArgumentException("--main-class requires a value");
                }
                mainClass = arguments[i];
            } else if (argument.startsWith("--main-class=")) {
                if (mainClass != null) {
                    throw new IllegalArgumentException("--main-class may only be specified once");
                }
                mainClass = argument.substring("--main-class=".length());
                if (mainClass.isEmpty()) {
                    throw new IllegalArgumentException("--main-class requires a value");
                }
            } else if (argument.equals("--enable-preview")) {
                enablePreview = true;
            } else if (isRuntimeAccessOption(argument)) {
                runtimeArguments.add(argument);
                if (argument.indexOf('=') < 0) {
                    if (++i >= arguments.length) {
                        throw new IllegalArgumentException(argument + " requires a value");
                    }
                    runtimeArguments.add(arguments[i]);
                } else if (argument.endsWith("=")) {
                    throw new IllegalArgumentException(argument + " requires a value");
                }
            } else if (argument.startsWith("-")) {
                throw new IllegalArgumentException("Unknown init argument: " + argument);
            } else if (moduleName == null) {
                moduleName = argument;
            } else {
                throw new IllegalArgumentException("init accepts one module name");
            }
        }
        if (moduleName == null) {
            throw new IllegalArgumentException("init requires a module name");
        }
        return new InitRequest(moduleName, Optional.ofNullable(release), Optional.ofNullable(mainClass), enablePreview,
                runtimeAccess(runtimeArguments));
    }

    private static int release(String value) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException("--release requires a value");
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid release: " + value, e);
        }
    }

    private static String parseSourceSymbol(String[] arguments, int firstArgument) {
        String symbol = null;
        for (int i = firstArgument; i < arguments.length; i++) {
            String argument = arguments[i];
            if (argument.startsWith("-")) {
                throw new IllegalArgumentException("Unknown source argument: " + argument);
            }
            if (symbol != null) {
                throw new IllegalArgumentException("source accepts only one symbol");
            }
            symbol = argument;
        }
        if (symbol == null) {
            throw new IllegalArgumentException("source requires a symbol");
        }
        return symbol;
    }

    private static DocRequest parseDocRequest(String[] arguments, int firstArgument) {
        boolean browse = false;
        String target = null;
        for (int i = firstArgument; i < arguments.length; i++) {
            String argument = arguments[i];
            if (argument.equals("--browse")) {
                if (browse) {
                    throw new IllegalArgumentException("--browse may only be specified once");
                }
                browse = true;
            } else if (argument.startsWith("-")) {
                throw new IllegalArgumentException("Unknown doc argument: " + argument);
            } else if (target == null) {
                target = argument;
            } else {
                throw new IllegalArgumentException("doc accepts only one symbol or type");
            }
        }
        if (browse) {
            return new Browse(Optional.ofNullable(target));
        }
        if (target == null) {
            throw new IllegalArgumentException("doc requires a symbol or --browse");
        }
        return new Terminal(target);
    }

    private static String moduleSelection(String option, String argument) {
        if (SourceVersion.isName(argument)) {
            return argument;
        }
        throw new IllegalArgumentException(option + " expects a module: " + argument);
    }

    private static String runTargetModule(String option, String argument) {
        int separator = argument.indexOf('/');
        if (separator < 0) {
            if (SourceVersion.isName(argument)) {
                return argument;
            }
        } else if (separator == argument.lastIndexOf('/')) {
            String module = argument.substring(0, separator);
            String mainClass = argument.substring(separator + 1);
            if (SourceVersion.isName(module) && SourceVersion.isName(mainClass)) {
                return module;
            }
        }
        throw new IllegalArgumentException("run " + option + " expects module[/mainClass]: " + argument);
    }

    private static RequireRequest parseRequireRequest(String[] arguments, int firstArgument) {
        var dependencies = new ArrayList<Dependency>();
        var unresolvedTargets = new ArrayList<String>();
        var updateModules = new ArrayList<String>();
        var operands = new ArrayList<String>();
        var runtimeArguments = new ArrayList<String>();
        var updatePolicy = UpdatePolicy.NONE;
        var staticPhase = false;
        var transitive = false;
        for (int i = firstArgument; i < arguments.length; i++) {
            var argument = arguments[i];
            if (argument.equals("--static")) {
                staticPhase = true;
            } else if (argument.equals("--transitive")) {
                transitive = true;
            } else if (argument.equals("-u") || argument.equals("--update")) {
                if (updatePolicy != UpdatePolicy.NONE) {
                    throw new IllegalArgumentException("require update policy may only be specified once");
                }
                updatePolicy = UpdatePolicy.MINOR;
            } else if (argument.startsWith("-u=") || argument.startsWith("--update=")) {
                if (updatePolicy != UpdatePolicy.NONE) {
                    throw new IllegalArgumentException("require update policy may only be specified once");
                }
                updatePolicy = updatePolicy(argument.substring(argument.indexOf('=') + 1));
            } else if (isRuntimeAccessOption(argument)) {
                runtimeArguments.add(argument);
                if (argument.indexOf('=') < 0) {
                    if (++i >= arguments.length) {
                        throw new IllegalArgumentException(argument + " requires a value");
                    }
                    runtimeArguments.add(arguments[i]);
                } else if (argument.endsWith("=")) {
                    throw new IllegalArgumentException(argument + " requires a value");
                }
            } else if (argument.startsWith("-")) {
                throw new IllegalArgumentException("Unknown require argument: " + argument);
            } else {
                operands.add(argument);
            }
        }
        for (var operand : operands) {
            var separator = operand.lastIndexOf('@');
            if (updatePolicy == UpdatePolicy.NONE) {
                if (operand.startsWith("pkg:") || separator < 0) {
                    unresolvedTargets.add(operand);
                } else if (separator == 0 || separator == operand.length() - 1) {
                    throw new IllegalArgumentException("require expects <module>@<version>: " + operand);
                } else {
                    dependencies.add(new Dependency(operand.substring(0, separator), operand.substring(separator + 1)));
                }
            } else {
                if (separator >= 0) {
                    throw new IllegalArgumentException("require cannot combine --update with an explicit version: " + operand);
                }
                updateModules.add(operand);
            }
        }
        return new RequireRequest(dependencies, unresolvedTargets, updateModules, updatePolicy, staticPhase, transitive,
                runtimeAccess(runtimeArguments));
    }

    private static boolean isRuntimeAccessOption(String argument) {
        int separator = argument.indexOf('=');
        String option = separator < 0 ? argument : argument.substring(0, separator);
        return switch (option) {
            case "--enable-native-access", "--enable-final-field-mutation", "--add-exports", "--add-opens" -> true;
            default -> false;
        };
    }

    private static List<RuntimeAccess> runtimeAccess(List<String> arguments) {
        var parsed = ModuleRuntimeAccess.parseArguments(arguments);
        var access = new ArrayList<RuntimeAccess>();
        parsed.enableNativeAccess().forEach(value -> access.add(new RuntimeAccess("enableNativeAccess", value)));
        parsed.enableFinalFieldMutation().forEach(value -> access.add(new RuntimeAccess("enableFinalFieldMutation", value)));
        parsed.addExports().forEach(value -> access.add(new RuntimeAccess("addExports", value.toFlagValue())));
        parsed.addOpens().forEach(value -> access.add(new RuntimeAccess("addOpens", value.toFlagValue())));
        return List.copyOf(access);
    }

    private static UpdatePolicy updatePolicy(String value) {
        return switch (value) {
            case "patch" -> UpdatePolicy.PATCH;
            case "minor" -> UpdatePolicy.MINOR;
            case "major" -> UpdatePolicy.MAJOR;
            default -> throw new IllegalArgumentException("Unknown require update policy: " + value);
        };
    }

    private static InstallRequest parseInstallRequest(Path workingDirectory, String[] arguments, int firstArgument) {
        Optional<String> target = Optional.empty();
        Optional<String> name = Optional.empty();
        Optional<Path> output = Optional.empty();
        boolean force = false;
        boolean includeStatic = false;
        for (int i = firstArgument; i < arguments.length; i++) {
            String argument = arguments[i];
            if (argument.equals("--name")) {
                if (++i >= arguments.length) {
                    throw new IllegalArgumentException("--name requires a value");
                }
                name = Optional.of(arguments[i]);
            } else if (argument.startsWith("--name=")) {
                name = Optional.of(argument.substring("--name=".length()));
            } else if (argument.equals("--force")) {
                force = true;
            } else if (argument.equals("--include-static")) {
                includeStatic = true;
            } else if (argument.equals("--output")) {
                if (++i >= arguments.length) {
                    throw new IllegalArgumentException("--output requires a value");
                }
                if (output.isPresent()) {
                    throw new IllegalArgumentException("--output may only be specified once");
                }
                output = Optional.of(resolve(workingDirectory, arguments[i]));
            } else if (argument.startsWith("--output=")) {
                if (output.isPresent()) {
                    throw new IllegalArgumentException("--output may only be specified once");
                }
                String value = argument.substring("--output=".length());
                if (value.isEmpty()) {
                    throw new IllegalArgumentException("--output requires a value");
                }
                output = Optional.of(resolve(workingDirectory, value));
            } else if (!argument.startsWith("-") && target.isEmpty()) {
                target = Optional.of(argument);
            } else {
                throw new IllegalArgumentException("Unknown install argument: " + argument);
            }
        }
        return new InstallRequest(target, name, force, includeStatic, output);
    }

    private static Path resolve(Path workingDirectory, String value) {
        var path = Path.of(value);
        return (path.isAbsolute() ? path : workingDirectory.resolve(path)).normalize();
    }
}
