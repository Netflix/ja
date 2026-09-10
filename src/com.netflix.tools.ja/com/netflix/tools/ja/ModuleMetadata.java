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

import java.nio.file.Files;
import java.nio.file.Path;

final class ModuleMetadata {
    static final Path DEPLOYMENT_POM = Path.of("META-INF", "com.netflix.tools.ja", "maven", "deploy.pom");
    static final Path MAVEN_EXPORT_POM = Path.of("module-info.pom");

    private ModuleMetadata() {}

    static Path deploymentPom(Path moduleSource) {
        return moduleRoot(moduleSource).resolve(DEPLOYMENT_POM);
    }

    private static Path moduleRoot(Path moduleSource) {
        if (moduleSource.getFileName() != null
                && moduleSource.getFileName().toString().equals("classes")
                && Files.isRegularFile(moduleSource.resolve("module-info.java"))) {
            return moduleSource.getParent();
        }
        return moduleSource;
    }
}
