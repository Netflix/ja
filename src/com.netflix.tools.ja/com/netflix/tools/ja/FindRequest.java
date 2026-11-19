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
import java.util.regex.Pattern;

/** A question about code related to a subject in the selected context. */
public record FindRequest(Question question, String target, Search search, List<String> kinds,
        List<String> expressions, boolean ignoreCase) {
    private static final List<String> DECLARATION_KINDS = List.of(
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
        this(question, target, Search.SOURCE, List.of(), List.of(), false);
    }

    public FindRequest {
        if (target == null && (question != Question.DECLARATIONS || expressions.isEmpty())
                || target != null && (target.isBlank() || target.startsWith("-"))) {
            throw new IllegalArgumentException("find requires a nonempty subject");
        }
        kinds = List.copyOf(kinds);
        expressions = List.copyOf(expressions);
        if (question != Question.DECLARATIONS && (!expressions.isEmpty() || ignoreCase)) {
            throw new IllegalArgumentException("--regexp and --ignore-case are supported only by find declarations");
        }
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
        var expressions = new ArrayList<String>();
        boolean ignoreCase = false;
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
            } else if (question == Question.DECLARATIONS && (argument.equals("-i") || argument.equals("--ignore-case"))) {
                ignoreCase = true;
            } else if (question == Question.DECLARATIONS && (argument.equals("-e") || argument.equals("--regexp"))) {
                if (++index >= arguments.size()) throw usage();
                expressions.add(arguments.get(index));
            } else if (question == Question.DECLARATIONS && (argument.startsWith("--regexp=") || argument.startsWith("-e="))) {
                expressions.add(argument.substring(argument.indexOf('=') + 1));
            } else if (argument.startsWith("-") || target != null) {
                throw usage();
            } else {
                target = argument;
            }
        }
        if (target == null && expressions.isEmpty()) {
            throw usage();
        }
        return new FindRequest(question, target, search, kinds, expressions, ignoreCase);
    }

    private static String parseKind(String kind) {
        if (!DECLARATION_KINDS.contains(kind)) {
            throw new IllegalArgumentException(
                    "Unknown declaration kind: " + kind + "; expected one of: " + String.join(", ", DECLARATION_KINDS));
        }
        return kind;
    }

    private static IllegalArgumentException usage() {
        return new IllegalArgumentException(
                "Usage: ja find <declarations|usages|callers|callees|impl|supertypes> [--external|--all] [--kind <kind>] [-i] [-e <expression>] [<subject>]");
    }

    List<String> arguments() {
        if (question == Question.DECLARATIONS) {
            var arguments = new ArrayList<>(List.of("-l", "--source", "none", "--visibility", "all"));
            kinds.forEach(kind -> arguments.addAll(List.of("--kind", kind)));
            if (ignoreCase) arguments.add("-i");
            if (expressions.isEmpty()) {
                arguments.addAll(List.of("-e", Pattern.quote(target)));
            } else {
                expressions.forEach(expression -> arguments.addAll(List.of("-e", expression)));
                if (target != null) arguments.add(target);
            }
            return List.copyOf(arguments);
        }
        return question == Question.IMPL
                ? List.of("-l", "--kind", "type", "--source", "none", "--visibility", "all", target)
                : List.of(question.options.getFirst(), target);
    }

    /** Leaves method validation, including inherited methods absent from declaration listings, to jist. */
    List<String> implementationArguments(List<String> symbols) {
        var types = symbols.stream().filter(name -> name.equals(target)
                        || target.indexOf('.') < 0 && name.substring(name.lastIndexOf('.') + 1).equals(target))
                .distinct()
                .toList();
        if (types.isEmpty()) {
            return List.of("--overrides", target);
        }
        var arguments = new ArrayList<String>();
        types.forEach(name -> arguments.addAll(List.of("--subtypes", name)));
        return List.copyOf(arguments);
    }
}
