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

import java.util.Optional;

/** Models the command selected by a parsed {@code ja} command line. */
public sealed interface Command {
    record Builtin(BuiltinCommand command) implements Command {}

    record Tool(String name) implements Command {
        public Tool {
            if (name.isBlank()) {
                throw new IllegalArgumentException("Tool name must not be empty");
            }
        }
    }

    record Tools() implements Command {}

    record Init(InitRequest request) implements Command {}

    record Require(RequireRequest request) implements Command {}

    record Install(InstallRequest request) implements Command {}

    record Run(Optional<String> target) implements Command {}

    record Doc(DocRequest request) implements Command {}

    record Source(String symbol) implements Command {
        public Source {
            if (symbol.isBlank()) {
                throw new IllegalArgumentException("Source symbol must not be empty");
            }
        }
    }
}
