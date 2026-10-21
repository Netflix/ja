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

/** A subject whose declaration or API should be described in the selected context. */
public record DescribeRequest(Subject subject, String target) {
    public enum Subject {
        MODULE("module", "Describe a Java module", List.of("--kind", "module")),
        TYPE("type", "Describe a Java type and its API", List.of("--kind", "type", "--kind", "method", "--kind", "field")),
        MEMBER("member", "Describe a method or field", List.of("--kind", "method", "--kind", "field")),
        FILE("file", "Describe declarations in a source or class file", List.of("--kind", "type", "--kind", "method", "--kind", "field"));

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
            return this == TYPE ? List.of("--kind", "type") : kinds;
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
            throw new IllegalArgumentException("Usage: ja describe <module|type|member|file> <subject>");
        }
        return new DescribeRequest(Subject.parse(arguments.getFirst()), arguments.getLast());
    }

    List<String> arguments(Path workingDirectory) {
        var arguments = new ArrayList<>(subject.kinds);
        arguments.addAll(List.of("--source", switch (subject) {
            case MODULE -> "definition";
            case MEMBER -> "doc";
            case TYPE, FILE -> "signature";
        }));
        arguments.add(subject == Subject.FILE ? workingDirectory.resolve(target).normalize().toString() : target);
        return List.copyOf(arguments);
    }
}
