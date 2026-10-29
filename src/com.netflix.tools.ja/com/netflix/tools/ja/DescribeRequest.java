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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** A subject whose declarations, API, or resolved types should be described in the selected context. */
public record DescribeRequest(Subject subject, String target) {
    public enum Subject {
        MODULE("module", "Describe a Java module", List.of("--kind", "module")),
        TYPE("type", "Describe a Java type and its API", List.of("--kind", "type", "--kind", "method", "--kind", "field")),
        MEMBER("member", "Describe a method or field", List.of("--kind", "method", "--kind", "field")),
        FILE("file", "Describe declarations in a source or class file", List.of("--kind", "type", "--kind", "method", "--kind", "field")),
        TYPES("types", "List resolved types for a Java type or source file", List.of("--referenced-types")),
        ORIGIN("origin", "Show where a Java symbol is resolved", List.of());

        private final String command;
        private final String description;
        private final List<String> kinds;

        Subject(String command, String description, List<String> kinds) {
            this.command = command;
            this.description = description;
            this.kinds = kinds;
        }

        String command() {
            return command;
        }

        String description() {
            return description;
        }

        List<String> completionArguments() {
            return this == TYPE || this == TYPES ? List.of("--kind", "type") : kinds;
        }

        static Subject parse(String value) {
            return Arrays.stream(values()).filter(subject -> subject.command.equals(value)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown describe subject: " + value));
        }
    }

    public DescribeRequest {
        if (target.isBlank() || target.startsWith("-")) {
            throw new IllegalArgumentException("describe requires a nonempty subject");
        }
    }

    static DescribeRequest parse(List<String> arguments) {
        if (arguments.size() != 2) {
            throw new IllegalArgumentException("Usage: ja describe <module|type|member|file|types|origin> <subject>");
        }
        return new DescribeRequest(Subject.parse(arguments.getFirst()), arguments.getLast());
    }

    List<String> arguments(Path workingDirectory) {
        String resolvedTarget = subject == Subject.FILE || subject == Subject.TYPES && isFile(target)
                ? workingDirectory.resolve(target).normalize().toString()
                : target;
        if (subject == Subject.TYPES) {
            return List.of("--referenced-types", resolvedTarget);
        }
        if (subject == Subject.ORIGIN) {
            return List.of("-l", "--source", "none", "--qualified-path", "--visibility", "all", resolvedTarget);
        }
        var arguments = new ArrayList<>(subject.kinds);
        arguments.addAll(List.of("--source", switch (subject) {
            case MODULE -> "definition";
            case MEMBER -> "doc";
            case TYPE, FILE, TYPES -> "signature";
            case ORIGIN -> throw new AssertionError();
        }));
        arguments.add(resolvedTarget);
        return List.copyOf(arguments);
    }

    private static boolean isFile(String target) {
        return target.endsWith(".java") || target.endsWith(".class");
    }
}
