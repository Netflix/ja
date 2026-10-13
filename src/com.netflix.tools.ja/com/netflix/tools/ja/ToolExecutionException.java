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

import java.io.Serial;

/** Carries a non-zero tool exit code through command workflows. */
public final class ToolExecutionException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final int exitCode;

    public ToolExecutionException(int exitCode) {
        this.exitCode = exitCode;
    }

    int exitCode() {
        return exitCode;
    }
}
