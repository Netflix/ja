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

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.lang.model.SourceVersion;

import com.netflix.tools.ja.RequireRequest.RuntimeAccess;

/** Describes the source module and module options supplied to {@code ja init}. */
public record InitRequest(String moduleName, Optional<Integer> release, Optional<String> mainClass,
        boolean enablePreview, List<RuntimeAccess> runtimeAccess) {

    public InitRequest {
        if (!SourceVersion.isName(moduleName)) {
            throw new IllegalArgumentException("Invalid module name: " + moduleName);
        }
        release = Objects.requireNonNull(release, "release");
        mainClass = Objects.requireNonNull(mainClass, "mainClass");
        runtimeAccess = List.copyOf(runtimeAccess);
        release.ifPresent(value -> {
            if (value <= 0) {
                throw new IllegalArgumentException("Invalid release: " + value);
            }
        });
        mainClass.ifPresent(value -> {
            if (!SourceVersion.isName(value)) {
                throw new IllegalArgumentException("Invalid main class: " + value);
            }
        });
    }
}
