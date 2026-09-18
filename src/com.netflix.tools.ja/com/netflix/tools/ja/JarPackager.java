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
import java.io.PrintStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Creates binary module JAR artifacts. */
final class JarPackager {
    record Result(int exitCode, boolean automatic) {}

    private final ToolRuntime tools;

    JarPackager(ToolRuntime tools) {
        this.tools = tools;
    }

    Result createExact(
            String moduleName,
            Path moduleContent,
            List<String> moduleArguments,
            List<String> runtimeArguments,
            Path archive,
            boolean omitJmod,
            InputStream in,
            PrintStream out,
            PrintStream err)
            throws IOException {
        Path parent = archive.toAbsolutePath()
                             .normalize()
                             .getParent();
        if (parent == null) {
            throw new IllegalArgumentException("jar output has no parent: " + archive);
        }
        Files.createDirectories(parent);
        Path staged = Files.createTempFile(parent, ".ja-jar-", ".jar");
        try {
            Files.delete(staged);
            var arguments = new ArrayList<String>();
            arguments.add("--create");
            arguments.add("--no-manifest");
            arguments.add("--file");
            arguments.add(staged.toString());
            arguments.addAll(moduleArguments);
            arguments.add("-C");
            arguments.add(moduleContent.toString());
            arguments.add(".");
            int result = tools.run("jar", in, out, err, arguments.toArray(String[]::new));
            if (result != 0) {
                return new Result(result, false);
            }
            var filenameDerivedModules = AutomaticModules.findFilenameDerived(runtimeArguments);
            if (!filenameDerivedModules.isEmpty()) {
                AutomaticModuleArchives.rewrite(staged, moduleName);
                AutomaticModules.warnExport(moduleName, filenameDerivedModules, omitJmod, err);
            }
            publish(staged, archive);
            return new Result(0, !filenameDerivedModules.isEmpty());
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static void publish(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException _) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
