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

import java.io.PrintStream;
import java.lang.module.FindException;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Finds automatic modules in resolved arguments and reports their
 * operation-specific effects.
 */
final class AutomaticModules {
    private AutomaticModules() {}

    static Set<String> find(List<String> arguments) {
        var names = new TreeSet<String>();
        for (Path path : ToolArguments.applicationModulePath(arguments)) {
            if (isJmod(path)) {
                continue;
            }
            try {
                ModuleFinder.of(path).findAll().stream()
                        .filter(reference -> reference.descriptor().isAutomatic())
                        .map(reference -> reference.descriptor().name())
                        .forEach(names::add);
            } catch (FindException _) {
                // The command that consumes the module path remains responsible for invalid input.
            }
        }
        return Collections.unmodifiableSortedSet(names);
    }

    static void warn(Set<String> modules, PrintStream err) {
        if (modules.isEmpty()) {
            return;
        }
        printModules("warning: dependencies resolved as automatic modules:", modules, err);
        err.println("Automatic modules are a migration aid and do not define explicit module boundaries.");
    }

    static void warnInstall(List<String> arguments, PrintStream err) {
        Set<String> modules = find(arguments);
        if (modules.isEmpty()) {
            return;
        }
        printModules("warning: dependencies resolved as automatic modules:", modules, err);
        err.println("Automatic modules require java.se, increasing the installed application size.");
    }

    static void warnExport(String module, Set<String> dependencies, boolean omitJmod,
                           PrintStream err) {
        printModules("warning: " + module + " requires automatic modules:", dependencies, err);
        err.println(module + " will be exported as an automatic module.");
        if (omitJmod) {
            err.println("The " + module + " jmod artifact will be omitted.");
        }
    }

    private static void printModules(String heading, Set<String> modules, PrintStream err) {
        err.println(heading);
        modules.forEach(module -> err.println("  " + module));
    }

    private static boolean isJmod(Path path) {
        return Files.isRegularFile(path) && path.getFileName() != null && path.getFileName()
                .toString()
                .endsWith(".jmod");
    }
}
