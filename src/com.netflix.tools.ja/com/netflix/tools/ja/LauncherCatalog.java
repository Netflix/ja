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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Locates and copies native launcher binaries for supported target platforms. */
public final class LauncherCatalog {
    public record Platform(String target, String classifier, String executable) {}

    private static final String MODULE_NAME = "com.netflix.tools.launcher";
    private static final String RESOURCE_ROOT = "META-INF/com.netflix.tools.launcher";
    private static final List<Platform> PLATFORMS = List.of(
            new Platform("macos-aarch64", "osx-aarch_64", "launcher"),
            new Platform("macos-x86_64", "osx-x86_64", "launcher"),
            new Platform("linux-aarch64", "linux-aarch_64", "launcher"),
            new Platform("linux-x86_64", "linux-x86_64", "launcher"),
            new Platform("windows-aarch64", "windows-aarch_64", "launcher.exe"),
            new Platform("windows-x86_64", "windows-x86_64", "launcher.exe"));

    private LauncherCatalog() {}

    public static List<Platform> platforms(String requestedTarget) {
        if (requestedTarget == null) {
            return PLATFORMS;
        }
        String normalized = switch (requestedTarget) {
            case "macos-amd64" -> "macos-x86_64";
            case "linux-amd64" -> "linux-x86_64";
            case "windows-amd64" -> "windows-x86_64";
            default -> requestedTarget;
        };
        return PLATFORMS.stream()
                .filter(platform -> platform.target().equals(normalized))
                .findFirst()
                .map(List::of)
                .orElseThrow(() -> new IllegalArgumentException("Unsupported target platform: " + requestedTarget));
    }

    static Platform currentPlatform() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String architecture = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        String targetOs = os.contains("mac")
                ? "macos"
                : os.contains("win")
                        ? "windows"
                        : os.contains("linux") ? "linux" : null;
        String targetArchitecture = switch (architecture) {
            case "aarch64", "arm64" -> "aarch64";
            case "amd64", "x86_64" -> "x86_64";
            default -> null;
        };
        if (targetOs == null || targetArchitecture == null) {
            throw new IllegalArgumentException("Unsupported host platform: " + os + "-" + architecture);
        }
        return platforms(targetOs + "-" + targetArchitecture).getFirst();
    }

    static void copy(Platform platform, Path destination) throws IOException {
        copy(platform, Path.of("."), destination);
    }

    static void copyDispatcher(Platform platform, Path destination) throws IOException {
        copyDispatcher(platform, Path.of("."), Map.of(), destination);
    }

    static void copyDispatcher(Platform platform, Path compilationRoot, Map<String, Path> moduleSources,
                               Path destination) throws IOException {
        String executable = platform.executable().endsWith(".exe") ? "dispatcher.exe" : "dispatcher";
        copy(platform, compilationRoot, moduleSources, executable, destination);
    }

    static void copy(Platform platform, Path compilationRoot, Path destination) throws IOException {
        copy(platform, compilationRoot, Map.of(), destination);
    }

    static void copy(Platform platform, Path compilationRoot, Map<String, Path> moduleSources,
                     Path destination)
            throws IOException {
        copy(platform, compilationRoot, moduleSources, platform.executable(), destination);
    }

    private static void copy(Platform platform, Path compilationRoot, Map<String, Path> moduleSources,
            String executable, Path destination)
            throws IOException {
        String resource = RESOURCE_ROOT + "/" + platform.classifier() + "/" + executable;
        Path compiledResource = compilationRoot.resolve(MODULE_NAME).resolve(resource);
        Path sourceModule = moduleSources.get(MODULE_NAME);
        Path sourceResource = sourceModule == null ? null : sourceModule.resolve(resource);
        if (Files.isRegularFile(compiledResource)) {
            Files.copy(compiledResource, destination);
        } else if (sourceResource != null && Files.isRegularFile(sourceResource)) {
            Files.copy(sourceResource, destination);
        } else {
            Module module = ModuleLayer.boot()
                    .findModule(MODULE_NAME)
                    .orElseThrow(() -> new IllegalArgumentException("Launcher module is not installed"));
            try (InputStream input = module.getResourceAsStream(resource)) {
                if (input == null) {
                    throw new IllegalArgumentException("Launcher module has no " + platform.target() + " binary");
                }
                Files.copy(input, destination);
            }
        }
        if (!executable.endsWith(".exe")) {
            try {
                Files.setPosixFilePermissions(
                        destination,
                        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                                PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE));
            } catch (UnsupportedOperationException _) {
                // The JMOD command section establishes executability on non-POSIX hosts.
            }
        }
    }
}
