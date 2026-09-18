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
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.lang.module.FindException;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.jar.JarFile;

/**
 * Finds automatic modules in resolved arguments and reports their
 * operation-specific effects.
 */
final class AutomaticModules {
    private AutomaticModules() {}

    static Set<String> find(List<String> arguments) {
        return find(arguments, reference -> true);
    }

    static Set<String> findFilenameDerived(List<String> arguments) {
        return find(arguments, reference -> !hasExplicitName(reference));
    }

    private static Set<String> find(List<String> arguments, Predicate<ModuleReference> include) {
        var names = new TreeSet<String>();
        for (Path path : ToolArguments.applicationModulePath(arguments)) {
            if (isJmod(path)) {
                continue;
            }
            try {
                ModuleFinder.of(path).findAll().stream()
                        .filter(reference -> reference.descriptor().isAutomatic())
                        .filter(include)
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
        printModules("warning: " + module + " requires automatic modules with filename-derived names:",
                dependencies, err);
        err.println(module + " will be exported as an automatic module.");
        if (omitJmod) {
            err.println("The " + module + " jmod artifact will be omitted.");
        }
    }

    private static void printModules(String heading, Set<String> modules, PrintStream err) {
        err.println(heading);
        modules.forEach(module -> err.println("  " + module));
    }

    private static boolean hasExplicitName(ModuleReference reference) {
        var location = reference.location();
        if (location.isEmpty() || !"file".equalsIgnoreCase(location.orElseThrow().getScheme())) {
            return false;
        }
        Path archive = Path.of(location.orElseThrow());
        if (!Files.isRegularFile(archive)) {
            return false;
        }
        try (var jar = new JarFile(archive.toFile())) {
            var manifest = jar.getManifest();
            return manifest != null
                    && manifest.getMainAttributes().getValue("Automatic-Module-Name") != null;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not inspect automatic module " + archive, e);
        }
    }

    private static boolean isJmod(Path path) {
        return Files.isRegularFile(path) && path.getFileName() != null && path.getFileName()
                .toString()
                .endsWith(".jmod");
    }
}
