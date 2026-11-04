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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** A question about code related to a subject in the selected context. */
public record FindRequest(Question question, String target, Search search, List<String> kinds) {
    private static final Set<String> DECLARATION_KINDS = Set.of(
            "module", "package", "type", "class", "interface", "enum", "record", "annotation", "method", "field");

    public enum Search {
        SOURCE,
        EXTERNAL,
        ALL
    }

    public enum Question {
        USAGES("usages", "Find references to a type or member", List.of("--usages")),
        CALLERS("callers", "Find calls to a member or a type's declared executables", List.of("--callers")),
        CALLEES("callees", "Find calls from a member or a type's declared executables", List.of("--callees")),
        DECLARATIONS("declarations", "Find declarations whose names contain a query", List.of()),
        IMPL("impl", "Find subtypes or overriding methods", List.of("--kind", "type", "--kind", "method")),
        SUPERTYPES("supertypes", "Find parent types", List.of("--supertypes"));

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

    public FindRequest(Question question, String target) {
        this(question, target, Search.SOURCE, List.of());
    }

    public FindRequest {
        if (target.isBlank() || target.startsWith("-")) {
            throw new IllegalArgumentException("find requires a nonempty subject");
        }
        kinds = List.copyOf(kinds);
        if (question != Question.DECLARATIONS && !kinds.isEmpty()) {
            throw new IllegalArgumentException("--kind is supported only by find declarations");
        }
    }

    static FindRequest parse(List<String> arguments) {
        if (arguments.isEmpty()) {
            throw usage();
        }
        var question = Question.parse(arguments.getFirst());
        var search = Search.SOURCE;
        var kinds = new ArrayList<String>();
        String target = null;
        for (int index = 1; index < arguments.size(); index++) {
            String argument = arguments.get(index);
            if (argument.equals("--external") || argument.equals("--all")) {
                var selected = argument.equals("--external") ? Search.EXTERNAL : Search.ALL;
                if (search != Search.SOURCE) {
                    throw new IllegalArgumentException("--external and --all may not be combined");
                }
                search = selected;
            } else if (argument.equals("--kind")) {
                if (question != Question.DECLARATIONS || ++index >= arguments.size()) {
                    throw usage();
                }
                kinds.add(parseKind(arguments.get(index)));
            } else if (argument.startsWith("--kind=")) {
                if (question != Question.DECLARATIONS) {
                    throw usage();
                }
                kinds.add(parseKind(argument.substring("--kind=".length())));
            } else if (argument.startsWith("-") || target != null) {
                throw usage();
            } else {
                target = argument;
            }
        }
        if (target == null) {
            throw usage();
        }
        return new FindRequest(question, target, search, kinds);
    }

    private static String parseKind(String kind) {
        if (!DECLARATION_KINDS.contains(kind)) {
            throw new IllegalArgumentException("Unknown declaration kind: " + kind);
        }
        return kind;
    }

    private static IllegalArgumentException usage() {
        return new IllegalArgumentException(
                "Usage: ja find <declarations|usages|callers|callees|impl|supertypes> [--external|--all] [--kind <kind>] <subject>");
    }

    List<String> arguments() {
        if (question == Question.DECLARATIONS) {
            var arguments = new ArrayList<>(List.of("-l", "--source", "none", "--visibility", "all"));
            kinds.forEach(kind -> arguments.addAll(List.of("--kind", kind)));
            arguments.add("*" + target + "*");
            return List.copyOf(arguments);
        }
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
