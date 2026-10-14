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

package com.netflix.tools.launcher;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.tools.OptionChecker;
import javax.tools.ToolProvider;

/** Tool option support declared by metadata. */
public final class ModuleOptions {
    private static final Map<String, Option> KNOWN_OPTIONS = Map.ofEntries(
            option(JdkModuleOptions.modulePath()),
            option(JdkCompilationOptions.processorModulePath()),
            option(JdkModuleOptions.upgradeModulePath()),
            option(JdkModuleOptions.patchModule()),
            option(JdkCompilationOptions.moduleSourcePath()),
            option(JdkCompilationOptions.sourcePath()),
            option("class-path", JdkOptionDescriptors.option("class-path", "--class-path", "PATH", "Where to find classes", "-classpath", "-cp")),
            option("processor-path", JdkOptionDescriptors.option("processor-path", "--processor-path", "PATH", "Where to find annotation processors", "-processorpath")),
            option("source", JdkOptionDescriptors.option("source", "--source", "RELEASE", "Source language version", "-source")),
            option("target", JdkOptionDescriptors.option("target", "--target", "RELEASE", "Target class file version", "-target")),
            option("encoding", JdkOptionDescriptors.option("encoding", "-encoding", "CHARSET", "Source file encoding")),
            option("system", JdkOptionDescriptors.option("system", "--system", "PATH", "Where to find system modules")),
            option("add-reads", JdkOptionDescriptors.option("add-reads", "--add-reads", "MODULE=TARGET", "Add module reads")),
            option("module", JdkOptionDescriptors.option("module=single", "--module", "MODULE", "Select the module", "-m")),
            option(JdkModuleOptions.addModules()),
            option(JdkModuleOptions.describeModule()),
            option(JdkModuleOptions.mainClass()),
            option(JdkModuleOptions.moduleVersion()),
            option(JdkCompilationOptions.release()),
            option(JdkModuleOptions.multiRelease()),
            option(JdkCompilationOptions.enablePreview()),
            option(ModuleRuntimeAccessArguments.enableNativeAccess()),
            option(ModuleRuntimeAccessArguments.enableFinalFieldMutation()),
            option(ModuleRuntimeAccessArguments.addOpens()),
            option(ModuleRuntimeAccessArguments.addExports()),
            nonResolutionOption(JdkToolOptions.verbose()),
            option(JdkCompilationOptions.destination()));

    private ModuleOptions() {}

    public static Set<String> optionNames() {
        return KNOWN_OPTIONS.values().stream()
                .flatMap(option -> option.descriptor().names().stream())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Returns the standard output option specifications accepted by an option
     * checker.
     */
    public static Set<String> supportedBy(OptionChecker checker) {
        Objects.requireNonNull(checker);
        return KNOWN_OPTIONS.entrySet().stream()
                .filter(entry -> entry.getValue().resolution())
                .filter(
                        entry -> entry.getValue().descriptor().names().stream()
                                .anyMatch(name -> checker.isSupportedOption(name) >= 0))
                .map(Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Returns the subset of declared options that jig can resolve. */
    public static Set<String> resolutionOptions(Set<String> declared) {
        return declared.stream()
                .filter(specification -> {
                    var option = KNOWN_OPTIONS.get(baseSpecification(specification));
                    return option == null || option.resolution();
                })
                .collect(Collectors.toUnmodifiableSet());
    }

    public static Set<String> resolutionOptions(Set<String> declared, Set<String> discovered) {
        var options = new LinkedHashSet<>(discovered);
        var explicit = resolutionOptions(declared);
        if (explicit.stream().anyMatch(option -> baseSpecification(option).equals("module"))) {
            options.removeIf(option -> baseSpecification(option).equals("module"));
        }
        options.addAll(explicit);
        return Set.copyOf(options);
    }

    /** Returns an option checker for the exact declared option specifications. */
    public static OptionChecker checker(Set<String> declared) {
        Set<String> options = declared.stream()
                .map(ModuleOptions::baseSpecification)
                .collect(Collectors.toUnmodifiableSet());
        return spelling -> {
            var known = KNOWN_OPTIONS.entrySet().stream()
                    .filter(entry -> entry.getValue()
                                          .descriptor()
                                          .names()
                                          .contains(spelling))
                    .findFirst()
                    .orElse(null);
            if (known == null || !options.contains(known.getKey())) {
                return -1;
            }
            return platformOperands(spelling,
                    known.getValue()
                         .descriptor()
                         .argumentCount());
        };
    }

    /** Returns the platform compiler arity for an option, or {@code -1}. */
    public static int platformOperands(String option) {
        return platformOperands(option, -1);
    }

    private static int platformOperands(String option, int fallback) {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler != null) {
            int operands = compiler.isSupportedOption(option);
            if (operands >= 0) {
                return operands;
            }
            try (var fileManager = compiler.getStandardFileManager(null, null, null)) {
                operands = fileManager.isSupportedOption(option);
                if (operands >= 0) {
                    return operands;
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return fallback;
    }

    private static String baseSpecification(String specification) {
        int separator = specification.indexOf('=');
        return separator < 0 ? specification : specification.substring(0, separator);
    }

    private static Entry<String, Option> option(ToolOption descriptor) {
        return option(descriptor.key(), descriptor);
    }

    private static Entry<String, Option> option(String key, ToolOption descriptor) {
        return Map.entry(key, new Option(descriptor, true));
    }

    private static Entry<String, Option> nonResolutionOption(ToolOption descriptor) {
        return Map.entry(descriptor.key(), new Option(descriptor, false));
    }

    private record Option(ToolOption descriptor, boolean resolution) {}
}
