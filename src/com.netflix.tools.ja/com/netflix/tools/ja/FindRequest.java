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

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** A question about code related to a subject in the selected context. */
public record FindRequest(Question question, String target) {
    public enum Question {
        USAGES("usages", "Find references to a type or member", List.of("--usages")),
        CALLERS("callers", "Find calls to a method or constructor", List.of("--callers")),
        IMPL("impl", "Find subtypes or overriding methods", List.of("--kind", "type", "--kind", "method"));

        private final String command;
        private final String description;
        private final List<String> options;

        Question(String command, String description, List<String> options) {
            this.command = command;
            this.description = description;
            this.options = options;
        }

        String command() {
            return command;
        }

        String description() {
            return description;
        }

        List<String> completionArguments() {
            return options;
        }

        static Question parse(String value) {
            return Arrays.stream(values()).filter(question -> question.command.equals(value)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown find question: " + value));
        }
    }

    public FindRequest {
        if (target.isBlank() || target.startsWith("-")) {
            throw new IllegalArgumentException("find requires a nonempty subject");
        }
    }

    static FindRequest parse(List<String> arguments) {
        if (arguments.size() != 2) {
            throw new IllegalArgumentException("Usage: ja find <usages|callers|impl> <subject>");
        }
        return new FindRequest(Question.parse(arguments.getFirst()), arguments.getLast());
    }

    List<String> arguments() {
        return question == Question.IMPL
                ? List.of("-l", "--kind", "type", "--source", "none", "--visibility", "all", target)
                : List.of(question.options.getFirst(), target);
    }

    /** Leaves method validation, including inherited methods absent from declaration listings, to jist. */
    List<String> implementationArguments(String symbols) {
        var typeKinds = Set.of("class", "interface", "enum", "record", "annotation");
        boolean type = symbols.lines().anyMatch(line -> {
            int separator = line.indexOf(' ');
            return separator > 0 && typeKinds.contains(line.substring(0, separator)) && line.substring(separator + 1).equals(target);
        });
        return List.of(type ? "--subtypes" : "--overrides", target);
    }
}
