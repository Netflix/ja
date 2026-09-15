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

import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import javax.tools.OptionChecker;

/**
 * Extracts, selects, and composes standard module-system arguments for tools
 * and launchers.
 */
public final class ToolArguments {
    private ToolArguments() {}

    public static List<Path> modulePath(List<String> arguments) {
        return paths(arguments, Set.of("--module-path"));
    }

    public static List<Path> applicationModulePath(List<String> arguments) {
        return paths(arguments, Set.of("--upgrade-module-path", "--module-path"));
    }

    static Map<String, Path> moduleSourcePath(List<String> arguments) {
        var modules = new LinkedHashMap<String, Path>();
        for (String value : optionValues(arguments, "--module-source-path")) {
            for (String entry : value.split(Pattern.quote(System.getProperty("path.separator")))) {
                int separator = entry.indexOf('=');
                if (separator > 0 && separator < entry.length() - 1) {
                    modules.putIfAbsent(entry.substring(0, separator), Path.of(entry.substring(separator + 1)));
                }
            }
        }
        return Map.copyOf(modules);
    }

    static Map<String, List<Path>> patchModules(List<String> arguments) {
        var patches = new LinkedHashMap<String, List<Path>>();
        for (int i = 0; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            String value = null;
            if (argument.equals("--patch-module") && i + 1 < arguments.size()) {
                value = arguments.get(++i);
            } else if (argument.startsWith("--patch-module=")) {
                value = argument.substring("--patch-module=".length());
            }
            if (value == null) {
                continue;
            }
            int separator = value.indexOf('=');
            if (separator <= 0 || separator == value.length() - 1) {
                throw new IllegalArgumentException("Invalid --patch-module value: " + value);
            }
            patches.put(value.substring(0, separator), splitPaths(value.substring(separator + 1)));
        }
        return Map.copyOf(patches);
    }

    static Set<String> addedModules(List<String> arguments) {
        var modules = new LinkedHashSet<String>();
        for (int i = 0; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            String value = null;
            if (argument.equals("--add-modules")) {
                if (++i >= arguments.size()) {
                    throw new IllegalArgumentException("--add-modules requires an argument");
                }
                value = arguments.get(i);
            } else if (argument.startsWith("--add-modules=")) {
                value = argument.substring("--add-modules=".length());
            }
            if (value == null) {
                continue;
            }
            for (String module : value.split(",")) {
                if (!module.isBlank()) {
                    modules.add(module.strip());
                }
            }
        }
        return Set.copyOf(modules);
    }

    public static Optional<Path> moduleLocation(String moduleName, List<String> arguments) {
        return moduleReference(moduleName, arguments).flatMap(ModuleReference::location)
                .filter(location -> location.getScheme().equalsIgnoreCase("file"))
                .map(Path::of);
    }

    public static Optional<String> moduleVersion(String moduleName, List<String> arguments) {
        return moduleReference(moduleName, arguments).flatMap(reference -> reference.descriptor().rawVersion());
    }

    private static Optional<ModuleReference> moduleReference(String moduleName, List<String> arguments) {
        for (Path path : applicationModulePath(arguments)) {
            var reference = ModuleFinder.of(path).find(moduleName);
            if (reference.isPresent()) {
                return reference;
            }
        }
        return Optional.empty();
    }

    private static List<String> optionValues(List<String> arguments, String option) {
        var values = new ArrayList<String>();
        for (int i = 0; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            if (argument.equals(option) && i + 1 < arguments.size()) {
                values.add(arguments.get(++i));
            } else if (argument.startsWith(option + "=")) {
                values.add(argument.substring(option.length() + 1));
            }
        }
        return List.copyOf(values);
    }

    private static List<Path> paths(List<String> arguments, Set<String> options) {
        var paths = new ArrayList<Path>();
        for (int i = 0; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            String value = null;
            if (options.contains(argument) && i + 1 < arguments.size()) {
                value = arguments.get(++i);
            } else {
                for (String option : options) {
                    if (argument.startsWith(option + "=")) {
                        value = argument.substring(option.length() + 1);
                        break;
                    }
                }
            }
            if (value != null) {
                paths.addAll(splitPaths(value));
            }
        }
        return List.copyOf(paths);
    }

    private static List<Path> splitPaths(String value) {
        var paths = new ArrayList<Path>();
        String separator = Pattern.quote(System.getProperty("path.separator"));
        for (String path : value.split(separator)) {
            if (!path.isEmpty()) {
                paths.add(Path.of(path));
            }
        }
        return List.copyOf(paths);
    }

    public static List<String> select(List<String> arguments, OptionChecker source, OptionChecker consumer) {
        var selected = new ArrayList<String>();
        for (int i = 0; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            int separator = argument.startsWith("-") ? argument.indexOf('=') : -1;
            String option = separator < 0 ? argument : argument.substring(0, separator);
            int operands = source.isSupportedOption(option);
            if (operands < 0) {
                throw new IllegalArgumentException("Unknown resolved Java option: " + argument);
            }
            int supportedOperands = consumer.isSupportedOption(option);
            if (supportedOperands >= 0 && supportedOperands != operands) {
                throw new IllegalArgumentException("Inconsistent Java option arity: " + option);
            }
            boolean supported = supportedOperands >= 0;
            if (supported) {
                selected.add(argument);
            }
            if (separator >= 0) {
                continue;
            }
            for (int operand = 0; operand < operands; operand++) {
                if (++i >= arguments.size()) {
                    throw new IllegalArgumentException(option + " requires an argument");
                }
                if (supported) {
                    selected.add(arguments.get(i));
                }
            }
        }
        return List.copyOf(selected);
    }
}
