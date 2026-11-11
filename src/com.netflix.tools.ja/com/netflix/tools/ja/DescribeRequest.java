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
import java.util.Optional;

/** A subject whose declarations, API, or resolved types should be described in the selected context. */
public record DescribeRequest(Subject subject, String target, Optional<String> sourceScope) {
    static final List<String> SOURCE_SCOPES = List.of("none", "body", "signature", "doc", "definition", "symbol", "type", "unit");

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

        boolean supportsSource() {
            return this != TYPES && this != ORIGIN;
        }

        String defaultSourceScope() {
            return switch (this) {
                case MODULE -> "definition";
                case MEMBER -> "doc";
                case TYPE, FILE -> "signature";
                case TYPES, ORIGIN -> throw new IllegalStateException(command + " does not support --source");
            };
        }

        boolean completesTarget(List<String> arguments) {
            if (arguments.isEmpty()) {
                return true;
            }
            if (!supportsSource()) {
                return false;
            }
            if (arguments.size() == 2) {
                return isSourceOption(arguments.getFirst()) && SOURCE_SCOPES.contains(arguments.getLast());
            }
            if (arguments.size() != 1) {
                return false;
            }
            String argument = arguments.getFirst();
            int equals = argument.indexOf('=');
            return equals > 0 && isSourceOption(argument.substring(0, equals))
                    && SOURCE_SCOPES.contains(argument.substring(equals + 1));
        }

        static Subject parse(String value) {
            return Arrays.stream(values()).filter(subject -> subject.command.equals(value)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown describe subject: " + value));
        }
    }

    public DescribeRequest(Subject subject, String target) {
        this(subject, target, Optional.empty());
    }

    public DescribeRequest {
        if (target.isBlank() || target.startsWith("-")) {
            throw new IllegalArgumentException("describe requires a nonempty subject");
        }
        if (sourceScope.isPresent()) {
            if (!subject.supportsSource()) {
                throw new IllegalArgumentException("describe " + subject.command + " does not support --source");
            }
            if (!SOURCE_SCOPES.contains(sourceScope.orElseThrow())) {
                throw new IllegalArgumentException("--source expects one of: " + String.join(", ", SOURCE_SCOPES));
            }
        }
    }

    static DescribeRequest parse(List<String> arguments) {
        if (arguments.isEmpty()) {
            throw usage();
        }
        var subject = Subject.parse(arguments.getFirst());
        String target = null;
        String sourceScope = null;
        for (int index = 1; index < arguments.size(); index++) {
            String argument = arguments.get(index);
            int equals = argument.indexOf('=');
            if (isSourceOption(equals < 0 ? argument : argument.substring(0, equals))) {
                if (sourceScope != null) {
                    throw new IllegalArgumentException("--source accepts only one scope");
                }
                if (equals >= 0) {
                    sourceScope = argument.substring(equals + 1);
                } else {
                    if (++index >= arguments.size()) {
                        throw new IllegalArgumentException("--source requires a scope");
                    }
                    sourceScope = arguments.get(index);
                }
            } else if (argument.startsWith("-") || target != null) {
                throw usage();
            } else {
                target = argument;
            }
        }
        if (target == null) {
            throw usage();
        }
        return new DescribeRequest(subject, target, Optional.ofNullable(sourceScope));
    }

    private static boolean isSourceOption(String argument) {
        return argument.equals("--source") || argument.equals("-s");
    }

    private static IllegalArgumentException usage() {
        return new IllegalArgumentException("Usage: ja describe <module|type|member|file|types|origin> [--source <scope>] <subject>");
    }

    List<String> arguments(Path workingDirectory) {
        String resolvedTarget = subject == Subject.FILE || subject == Subject.TYPES && isFile(target)
                ? workingDirectory.resolve(target).normalize().toString()
                : target;
        if (subject == Subject.TYPES) {
            return List.of("--referenced-types", resolvedTarget);
        }
        if (subject == Subject.ORIGIN) {
            return List.of("-l", "--source", "none", "--show-origin", "--visibility", "all", resolvedTarget);
        }
        var arguments = new ArrayList<>(subject.kinds);
        arguments.addAll(List.of("--source", sourceScope.orElseGet(subject::defaultSourceScope)));
        arguments.add(resolvedTarget);
        return List.copyOf(arguments);
    }

    private static boolean isFile(String target) {
        return target.endsWith(".java") || target.endsWith(".class");
    }
}
