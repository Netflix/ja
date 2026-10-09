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
import java.io.InputStreamReader;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Version;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import javax.lang.model.SourceVersion;
import javax.tools.OptionChecker;

import com.netflix.tools.launcher.ModuleOptions;

/**
 * Describes a tool metadata manifest and supplements its standard {@link
 * javax.tools.OptionChecker} contract.
 *
 * <p>The metadata supplies launch behavior, activation, defaults, and
 * packaged-content conventions that the JDK tool interfaces cannot express.
 */
public record ToolDefinition(
        String name,
        Launch launch,
        Optional<String> activation,
        Optional<String> module,
        String provider,
        Optional<String> version,
        Set<String> options,
        List<String> defaults,
        Optional<String> classSuffix,
        Optional<String> packageSuffix)
        implements OptionChecker {

    public enum Launch {
        PROVIDER,
        JAVA;

        private static Launch parse(String value) {
            return switch (value) {
                case "provider" -> PROVIDER;
                case "java" -> JAVA;
                default -> throw new IllegalArgumentException("launch must be provider or java");
            };
        }
    }

    private record ModuleReference(String name, Optional<String> version) {
        private ModuleReference {
            ModuleDescriptor.newModule(name).build();
            version.ifPresent(Version::parse);
        }

        private static ModuleReference parse(String value) {
            int separator = value.lastIndexOf('@');
            if (separator < 0) {
                return new ModuleReference(value, Optional.empty());
            }
            if (separator == 0 || separator == value.length() - 1) {
                throw new IllegalArgumentException("module expects <module> or <module>@<version>");
            }
            return new ModuleReference(value.substring(0, separator), Optional.of(value.substring(separator + 1)));
        }
    }

    public ToolDefinition(
            String name,
            Launch launch,
            Optional<String> activation,
            Optional<String> module,
            String provider,
            Optional<String> version,
            Set<String> options,
            List<String> defaults) {
        this(
                name,
                launch,
                activation,
                module,
                provider,
                version,
                options,
                defaults,
                Optional.empty(),
                Optional.empty());
    }

    public ToolDefinition {
        requireIdentifier("name", name);
        Objects.requireNonNull(launch);
        Objects.requireNonNull(activation);
        Objects.requireNonNull(module);
        requireIdentifier("provider", provider);
        Objects.requireNonNull(version);
        options = Set.copyOf(options);
        for (String option : options) {
            if (!option.matches("[a-z][a-z0-9-]*(?:=(?:single|list|main|roots))?")) {
                throw new IllegalArgumentException("Invalid option key: " + option);
            }
        }
        if (options.stream()
                .filter(option -> option.equals("module") || option.startsWith("module="))
                .count()
                > 1) {
            throw new IllegalArgumentException("Conflicting module option forms");
        }
        if (options.contains("module=roots") && !options.contains("add-modules")) {
            throw new IllegalArgumentException("module=roots requires add-modules");
        }
        defaults = List.copyOf(defaults);
        classSuffix = Objects.requireNonNull(classSuffix);
        classSuffix.ifPresent(suffix -> {
            if (!SourceVersion.isIdentifier(suffix) || SourceVersion.isKeyword(suffix)) {
                throw new IllegalArgumentException("Invalid class suffix: " + suffix);
            }
        });
        packageSuffix = Objects.requireNonNull(packageSuffix);
        packageSuffix.ifPresent(suffix -> {
            if (!SourceVersion.isIdentifier(suffix) || SourceVersion.isKeyword(suffix)) {
                throw new IllegalArgumentException("Invalid package suffix: " + suffix);
            }
        });
    }

    public static ToolDefinition read(String name, InputStream input) throws IOException {
        var properties = new Properties();
        properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
        var launch = optional(properties, "launch").map(Launch::parse).orElse(Launch.PROVIDER);
        var moduleReference = optional(properties, "module").map(ModuleReference::parse);
        var module = moduleReference.map(ModuleReference::name);
        var provider = optional(properties, "provider").orElse(name);
        var activation = optional(properties, "activation");
        var moduleVersion = moduleReference.flatMap(ModuleReference::version);
        var declaredVersion = optional(properties, "version");
        if (moduleVersion.isPresent() && declaredVersion.isPresent()) {
            throw new IllegalArgumentException("Tool version is specified in both module and version");
        }
        var version = moduleVersion.or(() -> declaredVersion);
        var options = Set.copyOf(commaSeparatedValues(properties, "options"));
        var defaults = optional(properties, "defaults").map(ArgumentFiles::parse).orElseGet(List::of);
        var classSuffix = optional(properties, "class-suffix");
        var packageSuffix = optional(properties, "package-suffix");
        return new ToolDefinition(name, launch, activation, module, provider, version,
                options, defaults, classSuffix, packageSuffix);
    }

    @Override
    public int isSupportedOption(String option) {
        return ModuleOptions.checker(options).isSupportedOption(option);
    }

    ResolutionOptions resolutionOptions() {
        return new ResolutionOptions(ModuleOptions.resolutionOptions(options), false);
    }

    public String resolveVersion(Optional<Version> selectedVersion) {
        if (version.isPresent()) {
            return version.get();
        }
        if (activation.isEmpty()) {
            throw new IllegalStateException("Tool " + name + " does not declare a version");
        }
        if (selectedVersion.isEmpty()) {
            throw new IllegalStateException("Activation module " + activation.get() + " has no selected version");
        }
        return selectedVersion.orElseThrow().toString();
    }

    private static List<String> commaSeparatedValues(Properties properties, String key) {
        return optional(properties, key).map(value -> Arrays.stream(value.split(","))
                .map(String::strip)
                .filter(element -> !element.isEmpty())
                .toList())
                .orElseGet(List::of);
    }

    private static Optional<String> optional(Properties properties, String key) {
        return Optional.ofNullable(properties.getProperty(key))
                .map(String::strip)
                .filter(value -> !value.isEmpty());
    }

    private static void requireIdentifier(String field, String value) {
        Objects.requireNonNull(value);
        if (value.isBlank()) {
            throw new IllegalArgumentException("Tool " + field + " must not be empty");
        }
    }
}
