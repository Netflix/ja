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

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Coordinates the search origins assigned to concurrent project-context queries. */
final class InsightSearchCoordinator {
    private static final Set<String> APPLICATION_PATH_OPTIONS = Set.of(
            "--class-path", "-classpath", "-cp", "--module-path", "-p", "--upgrade-module-path",
            "--module-source-path", "--patch-module", "--system");
    private static final Set<String> APPLICATION_OPTIONS = Set.of(
            "--release", "--limit-modules", "--add-modules", "--module", "--add-reads");
    private static final Set<String> SYSTEM_PATH_OPTIONS = Set.of(
            "--system", "--module-path", "-p", "--upgrade-module-path", "--module-source-path", "--patch-module");
    private static final Set<String> SYSTEM_OPTIONS = Set.of(
            "--release", "--limit-modules", "--add-modules", "--module");

    private final FindRequest.Search search;
    private final Set<List<String>> applicationSearches = new HashSet<>();
    private final Set<List<String>> systemSearches = new HashSet<>();

    InsightSearchCoordinator(FindRequest.Search search) {
        this.search = search;
    }

    synchronized List<String> arguments(List<String> arguments, List<String> resolutionArguments, Path workingDirectory) {
        boolean excludeApplication = search == FindRequest.Search.SOURCE
                || !applicationSearches.add(applicationKey(resolutionArguments, workingDirectory));
        boolean excludeSystem = search != FindRequest.Search.ALL
                || !systemSearches.add(systemKey(resolutionArguments, workingDirectory));
        var selected = new ArrayList<String>();
        if (excludeApplication) {
            selected.add("--exclude-application-classes");
        }
        if (excludeSystem) {
            selected.add("--exclude-system-modules");
        }
        selected.addAll(arguments);
        return List.copyOf(selected);
    }

    static List<String> arguments(FindRequest.Search search, List<String> arguments) {
        var selected = new ArrayList<String>();
        if (search == FindRequest.Search.SOURCE) {
            selected.add("--exclude-application-classes");
        }
        if (search != FindRequest.Search.ALL) {
            selected.add("--exclude-system-modules");
        }
        selected.addAll(arguments);
        return List.copyOf(selected);
    }

    private static List<String> applicationKey(List<String> arguments, Path workingDirectory) {
        return selectedOptions(arguments, workingDirectory, APPLICATION_PATH_OPTIONS, APPLICATION_OPTIONS);
    }

    private static List<String> systemKey(List<String> arguments, Path workingDirectory) {
        var selected = new ArrayList<>(selectedOptions(arguments, workingDirectory, SYSTEM_PATH_OPTIONS, SYSTEM_OPTIONS));
        if (selected.stream().noneMatch(value -> value.equals("--system"))) {
            selected.add("--system");
            selected.add(normalizePath(System.getProperty("java.home"), workingDirectory));
        }
        return List.copyOf(selected);
    }

    private static List<String> selectedOptions(
            List<String> arguments,
            Path workingDirectory,
            Set<String> pathOptions,
            Set<String> valueOptions) {
        var selected = new ArrayList<String>();
        for (int index = 0; index < arguments.size(); index++) {
            String argument = arguments.get(index);
            String option = matchingOption(argument, pathOptions, valueOptions);
            if (option == null) {
                continue;
            }
            String value;
            if (argument.equals(option)) {
                if (++index >= arguments.size()) {
                    throw new IllegalArgumentException(option + " requires an argument");
                }
                value = arguments.get(index);
            } else {
                value = argument.substring(option.length() + 1);
            }
            selected.add(option);
            selected.add(pathOptions.contains(option) ? normalizeOptionValue(option, value, workingDirectory) : value);
        }
        return List.copyOf(selected);
    }

    private static String matchingOption(String argument, Set<String> pathOptions, Set<String> valueOptions) {
        for (String option : pathOptions) {
            if (argument.equals(option) || argument.startsWith(option + "=")) {
                return option;
            }
        }
        for (String option : valueOptions) {
            if (argument.equals(option) || argument.startsWith(option + "=")) {
                return option;
            }
        }
        return null;
    }

    private static String normalizeOptionValue(String option, String value, Path workingDirectory) {
        if (option.equals("--patch-module") || option.equals("--module-source-path")) {
            int separator = value.indexOf('=');
            if (separator >= 0) {
                return value.substring(0, separator + 1)
                        + normalizePathList(value.substring(separator + 1), workingDirectory);
            }
        }
        if (option.equals("--system") && value.equals("none")) {
            return value;
        }
        return normalizePathList(value, workingDirectory);
    }

    private static String normalizePathList(String value, Path workingDirectory) {
        return Arrays.stream(value.split(Pattern.quote(File.pathSeparator), -1))
                .map(path -> normalizePath(path, workingDirectory))
                .reduce((left, right) -> left + File.pathSeparator + right)
                .orElse("");
    }

    private static String normalizePath(String value, Path workingDirectory) {
        var path = value.isEmpty() ? workingDirectory : Path.of(value);
        if (!path.isAbsolute()) {
            path = workingDirectory.resolve(path);
        }
        return path.toAbsolutePath().normalize().toString();
    }
}
