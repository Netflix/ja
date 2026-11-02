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
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Provides;
import java.lang.module.ModuleFinder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.Attributes.Name;
import java.util.jar.Manifest;

/**
 * Rewrites a modular JAR as an automatic module when its descriptor cannot be
 * exported safely.
 */
final class AutomaticModuleArchives {
    private AutomaticModuleArchives() {}

    static void rewrite(Path archive, String moduleName) throws IOException {
        var descriptor = ModuleFinder.of(archive).find(moduleName).orElseThrow().descriptor();
        try (var fileSystem = FileSystems.newFileSystem(archive, Map.of())) {
            writeServices(fileSystem.getPath("/META-INF/services"), descriptor);
            Files.deleteIfExists(fileSystem.getPath("/module-info.class"));
            deleteVersionedDescriptors(fileSystem.getPath("/META-INF/versions"));

            Path manifestPath = fileSystem.getPath("/META-INF/MANIFEST.MF");
            Manifest manifest;
            if (Files.isRegularFile(manifestPath)) {
                try (var input = Files.newInputStream(manifestPath)) {
                    manifest = new Manifest(input);
                }
            } else {
                manifest = new Manifest();
            }
            Attributes attributes = manifest.getMainAttributes();
            attributes.putIfAbsent(Name.MANIFEST_VERSION, "1.0");
            attributes.putValue("Automatic-Module-Name", moduleName);
            Files.createDirectories(manifestPath.getParent());
            try (var output = Files.newOutputStream(manifestPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                manifest.write(output);
            }
        }
    }

    private static void writeServices(Path services, ModuleDescriptor descriptor) throws IOException {
        for (Provides provides : descriptor.provides()) {
            Path configuration = services.resolve(provides.service());
            String existing = Files.isRegularFile(configuration) ? Files.readString(configuration, StandardCharsets.UTF_8) : "";
            var configured = new LinkedHashSet<String>();
            existing.lines()
                    .map(line -> line.split("#", 2)[0].strip())
                    .filter(line -> !line.isEmpty())
                    .forEach(configured::add);
            var additions = provides.providers().stream()
                    .filter(configured::add)
                    .toList();
            if (additions.isEmpty()) {
                continue;
            }

            var content = new StringBuilder(existing);
            if (!existing.isEmpty() && !existing.endsWith("\n")) {
                content.append('\n');
            }
            additions.forEach(provider -> content.append(provider).append('\n'));
            Files.createDirectories(services);
            Files.writeString(configuration, content, StandardCharsets.UTF_8);
        }
    }

    private static void deleteVersionedDescriptors(Path versions) throws IOException {
        if (!Files.isDirectory(versions)) {
            return;
        }
        try (var paths = Files.walk(versions)) {
            for (Path descriptor : paths.filter(path -> path.getFileName()
                            .toString()
                            .equals("module-info.class"))
                    .filter(path -> versions.relativize(path).getNameCount() == 2)
                    .toList()) {
                Files.delete(descriptor);
            }
        }
    }
}
