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
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Version;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Identifies an application module and version and produces the arguments
 * needed to resolve it.
 */
public record ApplicationTarget(String moduleName, Optional<String> version) {
    public ApplicationTarget {
        ModuleDescriptor.newModule(moduleName).build();
        version.ifPresent(Version::parse);
    }

    public ApplicationTarget(String moduleName, String version) {
        this(moduleName, Optional.of(version));
    }

    public static ApplicationTarget resolve(String value, ToolRuntime tools, InputStream in,
            PrintStream err) {
        if (!value.startsWith("pkg:")) {
            ApplicationTarget target = parseRequirement(value);
            if (target.version().isPresent()) {
                return target;
            }
            return new ApplicationTarget(target.moduleName(), ModuleVersions.latestAvailable(tools, target.moduleName(), err));
        }

        Optional<String> version = packageVersion(value);
        var output = new ByteArrayOutputStream();
        int result;
        try (var lookupOutput = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            result = tools.run("jig", in, lookupOutput, err, "--lookup-module", value);
        }
        if (result != 0) {
            throw new ToolExecutionException(result);
        }
        String moduleName = output.toString(StandardCharsets.UTF_8).strip();
        if (moduleName.isEmpty() || moduleName.lines().count() != 1) {
            throw new IllegalArgumentException("Jig returned an invalid module name for " + value);
        }
        return new ApplicationTarget(moduleName, version.orElseGet(() -> ModuleVersions.latestAvailable(tools, moduleName, err)));
    }

    public List<String> resolutionArguments() {
        return version.map(value -> List.of("--add-requires", moduleName + "@" + value)).orElseGet(List::of);
    }

    private static ApplicationTarget parseRequirement(String value) {
        int separator = value.lastIndexOf('@');
        if (separator < 0) {
            return new ApplicationTarget(value, Optional.empty());
        }
        if (separator == 0 || separator == value.length() - 1) {
            throw new IllegalArgumentException("Application target requires a module, <module>@<version>, or a package URL");
        }
        return new ApplicationTarget(value.substring(0, separator), value.substring(separator + 1));
    }

    private static Optional<String> packageVersion(String value) {
        URI url;
        try {
            url = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid package URL: " + value, e);
        }
        String packagePath = url.getRawSchemeSpecificPart();
        int query = packagePath.indexOf('?');
        if (query >= 0) {
            packagePath = packagePath.substring(0, query);
        }
        int separator = packagePath.lastIndexOf('@');
        if (separator < 0) {
            return Optional.empty();
        }
        if (separator == 0 || separator == packagePath.length() - 1) {
            throw new IllegalArgumentException("Package URL requires a version: " + value);
        }
        return Optional.of(URLDecoder.decode(packagePath.substring(separator + 1)
                .replace("+", "%2B"),
                StandardCharsets.UTF_8));
    }
}
