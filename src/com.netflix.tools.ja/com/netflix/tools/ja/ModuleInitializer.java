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
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.jar.Attributes.Name;
import java.util.jar.Manifest;

/**
 * Creates a new source module in the current directory or discovered module
 * source path.
 */
final class ModuleInitializer {
    private ModuleInitializer() {}

    static void initialize(Path workingDirectory, InitRequest request) throws IOException {
        if (!Files.isDirectory(workingDirectory)) {
            throw new IllegalArgumentException("Working directory is not a directory: " + workingDirectory);
        }

        Path moduleDirectory = ModuleSourcePath.moduleDirectory(workingDirectory, request.moduleName());
        if (Files.exists(moduleDirectory, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(moduleDirectory)) {
            throw new IllegalArgumentException("Module directory is not a directory: " + moduleDirectory);
        }

        Path descriptor = ModuleInfo.descriptorPath(moduleDirectory);
        if (Files.exists(descriptor, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Module descriptor already exists: " + descriptor);
        }
        for (Path parent = moduleDirectory.getParent();
             parent != null;
             parent = parent.getParent()) {
            Path parentDescriptor = ModuleInfo.descriptorPath(parent);
            if (Files.exists(parentDescriptor, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Cannot initialize a module beneath " + parentDescriptor);
            }
        }

        if (request.release().filter(release -> release == 8).isPresent()) {
            descriptor = moduleDirectory.resolve("META-INF/versions/9/module-info.java");
            Path manifestPath = moduleDirectory.resolve("META-INF/MANIFEST.MF");
            var manifest = new Manifest();
            if (Files.exists(manifestPath)) {
                try (var input = Files.newInputStream(manifestPath)) {
                    manifest.read(input);
                }
            }
            var attributes = manifest.getMainAttributes();
            attributes.putIfAbsent(Name.MANIFEST_VERSION, "1.0");
            attributes.put(Name.MULTI_RELEASE, "true");
            Files.createDirectories(descriptor.getParent());
            try (var output = Files.newOutputStream(manifestPath)) {
                manifest.write(output);
            }
        } else {
            Files.createDirectories(moduleDirectory);
        }
        Files.writeString(descriptor, descriptor(request), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static String descriptor(InitRequest request) {
        var source = new StringBuilder();
        if (request.release().isPresent()
                || request.mainClass().isPresent()
                || request.enablePreview()
                || !request.runtimeAccess().isEmpty()) {
            source.append("/**\n");
            request.release().ifPresent(value -> source.append(" * @release ")
                    .append(value)
                    .append('\n'));
            request.mainClass().ifPresent(value -> source.append(" * @mainClass ")
                    .append(value)
                    .append('\n'));
            if (request.enablePreview()) {
                source.append(" * @enablePreview\n");
            }
            request.runtimeAccess().forEach(
                    access -> source.append(" * @")
                                    .append(access.tag())
                                    .append(' ')
                                    .append(access.value())
                                    .append('\n'));
            source.append(" */\n");
        }
        return source.append("module ")
                     .append(request.moduleName())
                     .append(" {\n}\n")
                     .toString();
    }
}
