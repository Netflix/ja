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

package com.netflix.tools.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.tools.OptionChecker;

/**
 * An enumerable description of a command line with parsing, help, preparation,
 * and delegated completion support.
 *
 * <p>This file is self-contained. It can be embedded in another named module by
 * copying it and changing only its package declaration.
 */
public final class CommandLine implements OptionChecker {
    private static final String COMPLETION_COMMAND = "__complete";
    private static final ToolOption COMPLETION = ToolOption.flag(COMPLETION_COMMAND, "");
    private static final ToolOption VERSION = ToolOption.flag("--version", "Print version information");

    public enum Cardinality {
        ZERO_OR_ONE,
        EXACTLY_ONE,
        ZERO_OR_MORE,
        ONE_OR_MORE
    }

    private final String description;
    private final List<ToolOption> options;
    private final List<ToolOption.Group> optionGroups;
    private final Map<String, ToolOption> optionsByName;
    private final List<Subcommand> commands;
    private final Map<String, Subcommand> commandsByName;
    private final Operand operand;
    private final boolean workingDirectory;
    private final boolean argumentFiles;
    private final boolean javaToolOptions;
    private final Module versionModule;

    private CommandLine(
            String description,
            List<ToolOption> options,
            List<ToolOption.Group> optionGroups,
            List<Subcommand> commands,
            Operand operand,
            boolean workingDirectory,
            boolean argumentFiles,
            boolean javaToolOptions,
            Module versionModule) {
        this.description = description;
        this.options = List.copyOf(options);
        this.optionGroups = List.copyOf(optionGroups);
        this.commands = List.copyOf(commands);
        this.operand = operand;
        this.workingDirectory = workingDirectory;
        this.argumentFiles = argumentFiles;
        this.javaToolOptions = javaToolOptions;
        this.versionModule = versionModule;
        var byName = new LinkedHashMap<String, ToolOption>();
        for (ToolOption option : options) {
            for (String name : option.names()) {
                var previous = byName.putIfAbsent(name, option);
                if (previous != null) {
                    throw new IllegalArgumentException("Duplicate option name: " + name);
                }
            }
        }
        optionsByName = Map.copyOf(byName);
        var commandsByName = new LinkedHashMap<String, Subcommand>();
        for (Subcommand command : commands) {
            var previous = commandsByName.putIfAbsent(command.name(), command);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate command: " + command.name());
            }
        }
        this.commandsByName = Map.copyOf(commandsByName);
        if (!commands.isEmpty() && operand != null) {
            throw new IllegalArgumentException("A command line cannot declare both commands and operands");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public String description() {
        return description;
    }

    public List<ToolOption> options() {
        return options;
    }

    public List<ToolOption.Group> optionGroups() {
        return optionGroups;
    }

    public List<Subcommand> commands() {
        return commands;
    }

    @Override
    public int isSupportedOption(String option) {
        var declared = optionsByName.get(option);
        return declared == null ? -1 : declared.argumentCount();
    }

    public ToolInvocation prepare(ToolInvocation invocation) {
        var prepared = prepareDirectory(invocation);
        if (!argumentFiles) {
            return prepared;
        }
        return new ToolInvocation(prepared.workingDirectory(), ToolOptions.expand(prepared.arguments()));
    }

    public ToolInvocation prepare(String invocationName, ToolInvocation invocation, PrintWriter diagnostics) {
        Objects.requireNonNull(invocationName);
        Objects.requireNonNull(diagnostics);
        var prepared = prepareDirectory(invocation);
        var arguments = new ArrayList<String>();
        if (javaToolOptions) {
            arguments.addAll(ToolOptions.configured(invocationName, prepared.workingDirectory(), diagnostics, workingDirectory));
        }
        arguments.addAll(argumentFiles ? ToolOptions.expand(prepared.arguments()) : prepared.arguments());
        if (arguments.equals(prepared.arguments())) {
            return prepared;
        }
        return new ToolInvocation(prepared.workingDirectory(), arguments);
    }

    private ToolInvocation prepareDirectory(ToolInvocation invocation) {
        Objects.requireNonNull(invocation);
        if (!workingDirectory) {
            return invocation;
        }
        Path selected = invocation.workingDirectory();
        List<String> arguments = invocation.arguments();
        if (!arguments.isEmpty() && arguments.getFirst().equals("--")) {
            return new ToolInvocation(selected, arguments.subList(1, arguments.size()));
        }
        var remaining = new ArrayList<String>(arguments.size());
        boolean changed = false;
        for (int i = 0; i < arguments.size(); ) {
            String argument = Objects.requireNonNull(arguments.get(i));
            if (argument.equals("--") || !looksLikeOption(argument)) {
                remaining.addAll(arguments.subList(i, arguments.size()));
                break;
            }
            int equals = argument.indexOf('=');
            String name = equals < 0 ? argument : argument.substring(0, equals);
            var option = optionsByName.get(name);
            if (option == null) {
                remaining.addAll(arguments.subList(i, arguments.size()));
                break;
            }
            if (name.equals("-C")) {
                String directory;
                if (equals >= 0) {
                    directory = argument.substring(equals + 1);
                    if (directory.isEmpty()) {
                        throw new IllegalArgumentException("-C requires DIRECTORY");
                    }
                } else {
                    if (++i >= arguments.size()) {
                        throw new IllegalArgumentException("-C requires DIRECTORY");
                    }
                    directory = Objects.requireNonNull(arguments.get(i));
                }
                selected = selected.resolve(directory);
                changed = true;
                i++;
                continue;
            }
            remaining.add(argument);
            i++;
            if (option.argument().isPresent()
                    && !option.optionalArgument()
                    && equals < 0
                    && i < arguments.size()) {
                remaining.add(Objects.requireNonNull(arguments.get(i++)));
            }
        }
        return changed ? new ToolInvocation(selected, remaining) : invocation;
    }

    public ParsedArguments parse(ToolInvocation invocation) {
        var prepared = prepare(invocation);
        return parseArguments(prepared.arguments());
    }

    public ParsedArguments parse(String invocationName, ToolInvocation invocation, PrintWriter diagnostics) {
        var prepared = prepare(invocationName, invocation, diagnostics);
        return parseArguments(prepared.arguments());
    }

    public ParsedArguments parse(String... arguments) {
        Objects.requireNonNull(arguments);
        return parse(new ToolInvocation(Arrays.asList(arguments)));
    }

    private ParsedArguments parseArguments(List<String> arguments) {
        var values = new LinkedHashMap<ToolOption, List<String>>();
        var optionOccurrences = new ArrayList<ToolOption>();
        var operands = new ArrayList<String>();
        ParsedArguments.SelectedCommand selectedCommand = null;
        boolean optionsEnabled = true;
        for (int i = 0; i < arguments.size(); i++) {
            String argument = Objects.requireNonNull(arguments.get(i));
            if (optionsEnabled && argument.equals("--")) {
                optionsEnabled = false;
                continue;
            }
            if (!optionsEnabled || !looksLikeOption(argument)) {
                if (!commands.isEmpty()) {
                    var command = commandsByName.get(argument);
                    if (command == null) {
                        throw new IllegalArgumentException("Unknown command: " + argument);
                    }
                    var commandArguments = command.commandLine().parseArguments(arguments.subList(i + 1, arguments.size()));
                    selectedCommand = new ParsedArguments.SelectedCommand(command.name(), commandArguments);
                    break;
                }
                operands.add(argument);
                if (operand != null && operand.remainder()) {
                    optionsEnabled = false;
                }
                continue;
            }

            int equals = argument.indexOf('=');
            String name = equals < 0 ? argument : argument.substring(0, equals);
            var option = optionsByName.get(name);
            if (option == null) {
                if (operand != null && operand.remainder()) {
                    operands.addAll(arguments.subList(i, arguments.size()));
                    break;
                }
                throw new IllegalArgumentException("Unknown option: " + name);
            }
            optionOccurrences.add(option);
            var occurrences = values.computeIfAbsent(option, _ -> new ArrayList<>());
            if (option.argument().isEmpty()) {
                if (equals >= 0) {
                    throw new IllegalArgumentException(name + " does not accept an argument");
                }
                continue;
            }
            if (option.optionalArgument()) {
                if (equals >= 0) {
                    addOptionValue(option, name, argument.substring(equals + 1), occurrences);
                }
                continue;
            }
            if (equals >= 0) {
                addOptionValue(option, name, argument.substring(equals + 1), occurrences);
                continue;
            }
            if (++i >= arguments.size()) {
                throw new IllegalArgumentException(name + " requires " + option.argument().orElseThrow());
            }
            addOptionValue(option, name, Objects.requireNonNull(arguments.get(i)), occurrences);
        }
        validateOperands(operands);
        if (!commands.isEmpty() && selectedCommand == null) {
            throw new IllegalArgumentException("Missing command");
        }
        return new ParsedArguments(values, optionOccurrences, operands, selectedCommand);
    }

    public String help(String invocationName) {
        Objects.requireNonNull(invocationName);
        if (invocationName.isBlank()) {
            throw new IllegalArgumentException("Invocation name is blank");
        }
        var help = new StringBuilder("Usage: ").append(invocationName);
        if (hasVisibleOptions()) {
            help.append(" [OPTIONS]");
        }
        if (operand != null) {
            help.append(' ').append(operand.usage());
        }
        help.append('\n');
        if (!description.isEmpty()) {
            help.append('\n')
                .append(description)
                .append('\n');
        }
        if (!commands.isEmpty()) {
            help.append("\nCommands:\n");
            for (Subcommand command : commands) {
                help.append("  ")
                    .append(command.name())
                    .append("  ")
                    .append(command.description())
                    .append('\n');
            }
        }
        if (operand != null) {
            help.append("\nArguments:\n  ")
                .append(operand.name())
                .append("  ")
                .append(operand.description())
                .append('\n');
        }
        if (hasVisibleOptions()) {
            help.append('\n').append(optionsHelp());
        }
        return help.toString();
    }

    public String optionsHelp() {
        if (!hasVisibleOptions()) {
            return "";
        }
        var help = new StringBuilder("Options:\n");
        for (ToolOption option : options) {
            if (option.hidden()) {
                continue;
            }
            help.append("  ")
                .append(display(option))
                .append("  ")
                .append(option.description())
                .append('\n');
        }
        return help.toString();
    }

    public List<Completion> complete(CompletionRequest request) {
        Objects.requireNonNull(request);
        var arguments = new ArrayList<>(request.invocation()
                .arguments());
        arguments.add(request.current());
        return complete(arguments);
    }

    /**
     * Completes an argument list whose final element is the current partial
     * argument.
     */
    public List<Completion> complete(List<String> arguments) {
        Objects.requireNonNull(arguments);
        String prefix = arguments.isEmpty() ? "" : Objects.requireNonNull(arguments.getLast());
        var delegated = commandCompletion(arguments);
        if (delegated != null) {
            return delegated;
        }
        var valueOption = valueOption(arguments);
        if (valueOption != null) {
            return valueOption.choices().stream()
                    .filter(choice -> choice.startsWith(prefix))
                    .map(choice -> new Completion(choice, valueOption.description()))
                    .sorted(Comparator.comparing(Completion::value))
                    .toList();
        }
        if (prefix.startsWith("__")) {
            return List.of();
        }
        if (!commands.isEmpty() && !prefix.startsWith("-")) {
            return commands.stream()
                    .filter(command -> command.name().startsWith(prefix))
                    .map(command -> new Completion(command.name(), command.description()))
                    .sorted(Comparator.comparing(Completion::value))
                    .toList();
        }
        if (!prefix.startsWith("-") || optionsEnded(arguments)) {
            return List.of();
        }
        int equals = prefix.indexOf('=');
        if (equals >= 0) {
            String name = prefix.substring(0, equals);
            var option = optionsByName.get(name);
            if (option == null || option.choices().isEmpty()) {
                return List.of();
            }
            return option.choices().stream()
                    .map(choice -> new Completion(name + "=" + choice, option.description()))
                    .filter(candidate -> candidate.value().startsWith(prefix))
                    .sorted(Comparator.comparing(Completion::value))
                    .toList();
        }
        var completions = new ArrayList<Completion>();
        for (ToolOption option : options) {
            if (option.hidden()) {
                continue;
            }
            for (String name : option.names()) {
                if (name.startsWith(prefix)) {
                    completions.add(new Completion(name, option.description()));
                }
            }
        }
        completions.sort(Comparator.comparing(Completion::value));
        return List.copyOf(completions);
    }

    /**
     * Handles {@code --version} when version reporting is enabled.
     *
     * @return zero after printing the version, or empty for an ordinary invocation
     */
    public OptionalInt runVersion(String invocationName, PrintWriter out, String... arguments) {
        Objects.requireNonNull(invocationName);
        Objects.requireNonNull(out);
        Objects.requireNonNull(arguments);
        if (versionModule == null) {
            return OptionalInt.empty();
        }
        if (!Arrays.asList(arguments).equals(List.of("--version"))) {
            return OptionalInt.empty();
        }
        String version = versionModule.getDescriptor() == null ? null : versionModule.getDescriptor()
                .rawVersion()
                .orElse(null);
        out.println(invocationName + " " + (version == null ? "dev" : version));
        out.flush();
        return OptionalInt.of(0);
    }

    /**
     * Handles {@code __complete [COMPLETED...] [CURRENT]} when present. Each
     * candidate is written as {@code value<TAB>description}, followed by the
     * directive {@code :0}.
     *
     * @return the completion exit code, or empty when this is an ordinary invocation
     */
    public OptionalInt runCompletion(PrintWriter out, PrintWriter err, String... arguments) {
        Objects.requireNonNull(arguments);
        return runCompletion(out, err, this::complete, new ToolInvocation(Arrays.asList(arguments)));
    }

    /** Handles the completion protocol using a tool-specific semantic completer. */
    public OptionalInt runCompletion(PrintWriter out, PrintWriter err, Function<CompletionRequest, List<Completion>> completer,
            ToolInvocation invocation) {
        Objects.requireNonNull(out);
        Objects.requireNonNull(err);
        Objects.requireNonNull(completer);
        Objects.requireNonNull(invocation);
        List<String> arguments = invocation.arguments();
        if (!optionsByName.containsKey(COMPLETION_COMMAND) || arguments.isEmpty() || !arguments.getFirst().equals(COMPLETION_COMMAND)) {
            return OptionalInt.empty();
        }
        List<String> words = arguments.subList(1, arguments.size());
        String current = words.isEmpty() ? "" : words.getLast();
        List<String> completed = words.isEmpty() ? List.of() : words.subList(0, words.size() - 1);
        var request = new CompletionRequest(new ToolInvocation(invocation.workingDirectory(), completed), current);
        for (Completion candidate : Objects.requireNonNull(completer.apply(request))) {
            out.print(protocolField(candidate.value()));
            out.print('\t');
            out.println(protocolField(candidate.description()));
        }
        out.println(":0");
        out.flush();
        return OptionalInt.of(0);
    }

    private ToolOption valueOption(List<String> arguments) {
        int current = arguments.size() - 1;
        boolean optionsEnabled = true;
        for (int i = 0; i < current; i++) {
            String argument = Objects.requireNonNull(arguments.get(i));
            if (optionsEnabled && argument.equals("--")) {
                return null;
            }
            if (!optionsEnabled || !looksLikeOption(argument)) {
                if (operand != null && operand.remainder()) {
                    optionsEnabled = false;
                }
                continue;
            }
            int equals = argument.indexOf('=');
            String name = equals < 0 ? argument : argument.substring(0, equals);
            var option = optionsByName.get(name);
            if (option != null
                    && option.argumentCount() > 0
                    && equals < 0
                    && ++i == current) {
                return option;
            }
        }
        return null;
    }

    private List<Completion> commandCompletion(List<String> arguments) {
        if (commands.isEmpty() || arguments.isEmpty()) {
            return null;
        }
        int current = arguments.size() - 1;
        boolean optionsEnabled = true;
        for (int i = 0; i < current; i++) {
            String argument = Objects.requireNonNull(arguments.get(i));
            if (optionsEnabled && argument.equals("--")) {
                optionsEnabled = false;
                continue;
            }
            if (optionsEnabled && looksLikeOption(argument)) {
                int equals = argument.indexOf('=');
                String name = equals < 0 ? argument : argument.substring(0, equals);
                var option = optionsByName.get(name);
                if (option != null && option.argumentCount() > 0 && equals < 0) {
                    i++;
                }
                continue;
            }
            var command = commandsByName.get(argument);
            if (command == null) {
                return List.of();
            }
            return command.commandLine().complete(arguments.subList(i + 1, arguments.size()));
        }
        return null;
    }

    private boolean optionsEnded(List<String> arguments) {
        int current = arguments.size() - 1;
        for (int i = 0; i < current; i++) {
            String argument = Objects.requireNonNull(arguments.get(i));
            if (argument.equals("--")) {
                return true;
            }
            if (!looksLikeOption(argument)) {
                if (operand != null && operand.remainder()) {
                    return true;
                }
                continue;
            }
            int equals = argument.indexOf('=');
            String name = equals < 0 ? argument : argument.substring(0, equals);
            var option = optionsByName.get(name);
            if (option != null
                    && option.argumentCount() > 0
                    && equals < 0
                    && ++i == current) {
                return true;
            }
        }
        return false;
    }

    private boolean hasVisibleOptions() {
        return options.stream().anyMatch(option -> !option.hidden());
    }

    private static boolean looksLikeOption(String argument) {
        return argument.startsWith("__") || argument.startsWith("-") && !argument.equals("-");
    }

    private static void addOptionValue(ToolOption option, String name, String value,
            List<String> occurrences) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " requires " + option.argument().orElseThrow());
        }
        if (!option.choices().isEmpty() && !option.choices().contains(value)) {
            throw new IllegalArgumentException(name + " expects one of: " + String.join(", ", option.choices()));
        }
        occurrences.add(value);
    }

    private void validateOperands(List<String> operands) {
        int count = operands.size();
        if (operand == null) {
            if (count != 0) {
                throw new IllegalArgumentException("Unexpected argument: " + operands.getFirst());
            }
            return;
        }
        boolean valid = switch (operand.cardinality()) {
            case ZERO_OR_ONE -> count <= 1;
            case EXACTLY_ONE -> count == 1;
            case ZERO_OR_MORE -> true;
            case ONE_OR_MORE -> count >= 1;
        };
        if (!valid) {
            throw new IllegalArgumentException("Invalid number of " + operand.name() + " arguments: " + count);
        }
    }

    private static String display(ToolOption option) {
        var names = new ArrayList<>(option.names());
        names.sort(Comparator.comparingInt(String::length)
                .thenComparing(Comparator.naturalOrder()));
        var display = new StringBuilder(String.join(", ", names));
        option.argument().ifPresent(argument -> {
            if (option.optionalArgument()) {
                display.append("[=<")
                       .append(argument)
                       .append(">]");
            } else {
                display.append(" <")
                       .append(argument)
                       .append('>');
            }
        });
        return display.toString();
    }

    private static String protocolField(String value) {
        return value.replace('\t', ' ')
                    .replace('\r', ' ')
                    .replace('\n', ' ');
    }

    /** Working-directory context and arguments for one tool invocation. */
    public record ToolInvocation(Path workingDirectory, List<String> arguments) {
        public ToolInvocation {
            workingDirectory = Objects.requireNonNull(workingDirectory);
            arguments = List.copyOf(arguments);
        }

        public ToolInvocation(List<String> arguments) {
            this(Path.of(""), arguments);
        }

        public static ToolInvocation of(String... arguments) {
            Objects.requireNonNull(arguments);
            return new ToolInvocation(Arrays.asList(arguments));
        }
    }

    /** A prepared invocation and the partial argument currently being completed. */
    public record CompletionRequest(ToolInvocation invocation, String current) {
        public CompletionRequest {
            invocation = Objects.requireNonNull(invocation);
            current = Objects.requireNonNull(current);
        }
    }

    /** One command-line completion candidate. */
    public record Completion(String value, String description) {
        public Completion {
            Objects.requireNonNull(value);
            Objects.requireNonNull(description);
        }
    }

    /** A named command and the command line it selects. */
    public record Subcommand(String name, String description, CommandLine commandLine) {
        public Subcommand {
            Objects.requireNonNull(name);
            Objects.requireNonNull(description);
            Objects.requireNonNull(commandLine);
            if (name.isBlank()
                    || name.startsWith("-")
                    || name.startsWith("__")
                    || name.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("Invalid command name: " + name);
            }
        }
    }

    private record Operand(String name, String description, Cardinality cardinality,
                           boolean remainder) {
        private Operand {
            Objects.requireNonNull(name);
            Objects.requireNonNull(description);
            Objects.requireNonNull(cardinality);
            if (name.isBlank()) {
                throw new IllegalArgumentException("Operand name is blank");
            }
        }

        String usage() {
            return switch (cardinality) {
                case ZERO_OR_ONE -> "[" + name + "]";
                case EXACTLY_ONE -> name;
                case ZERO_OR_MORE -> "[" + name + "...]";
                case ONE_OR_MORE -> name + "...";
            };
        }
    }

    /** Options and operands parsed from a command line. */
    public static final class ParsedArguments {
        private final Map<ToolOption, List<String>> values;
        private final List<ToolOption> optionOccurrences;
        private final List<String> operands;
        private final SelectedCommand command;

        private ParsedArguments(Map<ToolOption, List<String>> values, List<ToolOption> optionOccurrences, List<String> operands,
                                SelectedCommand command) {
            var copied = new LinkedHashMap<ToolOption, List<String>>();
            values.forEach((option, occurrences) -> copied.put(option, List.copyOf(occurrences)));
            this.values = Map.copyOf(copied);
            this.optionOccurrences = List.copyOf(optionOccurrences);
            this.operands = List.copyOf(operands);
            this.command = command;
        }

        public boolean contains(ToolOption option) {
            return values.containsKey(Objects.requireNonNull(option));
        }

        public List<String> values(ToolOption option) {
            return values.getOrDefault(Objects.requireNonNull(option), List.of());
        }

        /** Options in their original command-line order, including repetitions. */
        public List<ToolOption> optionOccurrences() {
            return optionOccurrences;
        }

        public List<String> operands() {
            return operands;
        }

        public Optional<SelectedCommand> command() {
            return Optional.ofNullable(command);
        }

        public record SelectedCommand(String name, ParsedArguments arguments) {
            public SelectedCommand {
                Objects.requireNonNull(name);
                Objects.requireNonNull(arguments);
            }
        }
    }

    /** Describes one command-line option and its aliases. */
    public static final class ToolOption {
        public interface Group {
            List<ToolOption> options();

            default Set<String> optionKeys() {
                return options().stream()
                        .map(ToolOption::key)
                        .collect(Collectors.toUnmodifiableSet());
            }
        }

        private final String key;
        private final List<String> names;
        private final String argument;
        private final boolean optionalArgument;
        private final List<String> choices;
        private final String description;
        private final boolean hidden;

        private ToolOption(String key, List<String> names, String argument,
                           boolean optionalArgument, List<String> choices, String description) {
            this.key = requireKey(key);
            this.names = List.copyOf(names);
            this.argument = argument;
            this.optionalArgument = optionalArgument;
            this.choices = List.copyOf(choices);
            this.description = Objects.requireNonNull(description);
            if (this.names.isEmpty()) {
                throw new IllegalArgumentException("An option requires a name");
            }
            var distinct = new LinkedHashSet<String>();
            for (String name : this.names) {
                requireName(name);
                if (!distinct.add(name)) {
                    throw new IllegalArgumentException("Duplicate option name: " + name);
                }
            }
            hidden = hiddenName(this.names.getFirst());
            if (this.names.stream().anyMatch(name -> hiddenName(name) != hidden)) {
                throw new IllegalArgumentException("An option cannot mix hidden and visible names");
            }
            if (argument != null && argument.isBlank()) {
                throw new IllegalArgumentException("Option argument name is blank");
            }
            if (argument == null && (optionalArgument || !choices.isEmpty())) {
                throw new IllegalArgumentException("Option choices require an argument");
            }
            var distinctChoices = new LinkedHashSet<String>();
            for (String choice : this.choices) {
                if (choice.isBlank()) {
                    throw new IllegalArgumentException("Option choice is blank");
                }
                if (!distinctChoices.add(choice)) {
                    throw new IllegalArgumentException("Duplicate option choice: " + choice);
                }
            }
        }

        public static OptionBuilder builder(String name) {
            return new OptionBuilder(name);
        }

        public static ToolOption flag(String name, String description, String... aliases) {
            return withAliases(builder(name).description(description), aliases).build();
        }

        public static ToolOption option(String name, String argument, String description,
                String... aliases) {
            return withAliases(builder(name).argument(argument).description(description), aliases).build();
        }

        private static OptionBuilder withAliases(OptionBuilder builder, String... aliases) {
            Objects.requireNonNull(aliases);
            for (String alias : aliases) {
                builder.alias(alias);
            }
            return builder;
        }

        public String key() {
            return key;
        }

        public List<String> names() {
            return names;
        }

        public Optional<String> argument() {
            return Optional.ofNullable(argument);
        }

        public boolean optionalArgument() {
            return optionalArgument;
        }

        public List<String> choices() {
            return choices;
        }

        public String description() {
            return description;
        }

        public boolean hidden() {
            return hidden;
        }

        public int argumentCount() {
            return argument == null || optionalArgument ? 0 : 1;
        }

        private static String defaultKey(String name) {
            if (hiddenName(name)) {
                return name.substring(2);
            }
            int first = 0;
            while (first < name.length() && name.charAt(first) == '-') {
                first++;
            }
            return name.substring(first);
        }

        private static String requireKey(String key) {
            Objects.requireNonNull(key);
            if (key.isBlank()) {
                throw new IllegalArgumentException("Option key is blank");
            }
            return key;
        }

        private static void requireName(String name) {
            Objects.requireNonNull(name);
            boolean hidden = hiddenName(name) && name.length() > 2;
            boolean shortName = name.length() > 1 && name.charAt(0) == '-' && name.charAt(1) != '-';
            boolean longName = name.length() > 2 && name.startsWith("--") && name.charAt(2) != '-';
            if ((!hidden && !shortName && !longName)
                    || name.indexOf('=') >= 0
                    || name.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("Invalid option name: " + name);
            }
        }

        private static boolean hiddenName(String name) {
            return name.startsWith("__");
        }

        public static final class OptionBuilder {
            private String key;
            private final List<String> names = new ArrayList<>();
            private String argument;
            private boolean optionalArgument;
            private final List<String> choices = new ArrayList<>();
            private String description = "";

            private OptionBuilder(String name) {
                requireName(name);
                key = defaultKey(name);
                names.add(name);
            }

            public OptionBuilder key(String value) {
                key = requireKey(value);
                return this;
            }

            public OptionBuilder alias(String name) {
                requireName(name);
                names.add(name);
                return this;
            }

            public OptionBuilder argument(String name) {
                return argument(name, false);
            }

            public OptionBuilder optionalArgument(String name) {
                return argument(name, true);
            }

            private OptionBuilder argument(String name, boolean optional) {
                if (argument != null) {
                    throw new IllegalStateException("Option argument is already declared");
                }
                argument = Objects.requireNonNull(name);
                optionalArgument = optional;
                return this;
            }

            public OptionBuilder choices(String... values) {
                for (String value : values) {
                    choices.add(Objects.requireNonNull(value));
                }
                return this;
            }

            public OptionBuilder description(String value) {
                description = Objects.requireNonNull(value);
                return this;
            }

            public ToolOption build() {
                return new ToolOption(key, names, argument, optionalArgument, choices, description);
            }
        }
    }

    private static final class ToolOptions {
        private static final String OPTIONS_DIRECTORY = ".java-tool-options";

        private ToolOptions() {}

        static List<String> configured(String tool, Path effectiveDirectory, PrintWriter diagnostics,
                boolean workingDirectoryOption) {
            try {
                optionFileName(tool);
                Path effective = normalizeDirectory(effectiveDirectory);
                Path optionFile = findOptions(tool, effective, workingDirectoryOption);
                if (optionFile == null) {
                    return List.of();
                }
                diagnostics.println(tool + ": picked up options from " + displayPath(displayRoot(optionFile), optionFile));
                return JavaArgumentFiles.read(optionFile);
            } catch (IOException e) {
                throw new ConfigurationException(e.getMessage(), e);
            }
        }

        static List<String> expand(List<String> arguments) {
            try {
                return JavaArgumentFiles.expand(arguments);
            } catch (IOException e) {
                throw new ConfigurationException(e.getMessage(), e);
            }
        }

        private static Path displayRoot(Path optionFile) {
            for (var directory = optionFile.getParent();
                 directory != null;
                 directory = directory.getParent()) {
                if (directory.getFileName() != null && directory.getFileName()
                        .toString()
                        .equals(OPTIONS_DIRECTORY)) {
                    return directory.getParent();
                }
            }
            return null;
        }

        private static Path findOptions(String tool, Path effective, boolean workingDirectoryOption) throws IOException {
            for (Path directory = effective;
                 directory != null;
                 directory = directory.getParent()) {
                Path optionsDirectory = directory.resolve(OPTIONS_DIRECTORY);
                if (!Files.isDirectory(optionsDirectory)) {
                    continue;
                }
                return selectOptions(tool, effective, directory, optionsDirectory, workingDirectoryOption);
            }
            return null;
        }

        private static Path selectOptions(String tool, Path effective, Path root,
                Path optionsDirectory, boolean workingDirectoryOption)
                throws IOException {
            String fileName = optionFileName(tool);
            Path relative = root.relativize(effective);
            if (relative.toString().isEmpty()) {
                Path direct = optionsDirectory.resolve(fileName);
                if (Files.isRegularFile(direct)) {
                    return direct.toRealPath();
                }
            }
            for (Path scope = relative;
                 scope != null && !scope.toString().isEmpty();
                 scope = scope.getParent()) {
                Path options = optionsDirectory.resolve(scope).resolve(fileName);
                if (Files.isRegularFile(options)) {
                    return options.toRealPath();
                }
            }
            var contained = containedOptions(fileName, effective, root, optionsDirectory, relative);
            if (contained.size() > 1) {
                throw ambiguousOptions(tool, effective, contained, workingDirectoryOption);
            }
            if (contained.size() == 1) {
                return contained.getFirst().options();
            }
            Path fallback = optionsDirectory.resolve(fileName);
            return Files.isRegularFile(fallback) ? fallback.toRealPath() : null;
        }

        private static String optionFileName(String tool) {
            if (tool.isBlank()
                    || tool.equals(".")
                    || tool.equals("..")
                    || tool.indexOf('/') >= 0
                    || tool.indexOf('\\') >= 0) {
                throw new ConfigurationException("Invalid tool name: " + tool);
            }
            return tool + ".args";
        }

        private static List<OptionScope> containedOptions(String fileName, Path effective, Path root,
                Path optionsDirectory, Path relative)
                throws IOException {
            Path subtree = optionsDirectory.resolve(relative);
            if (!Files.isDirectory(subtree)) {
                return List.of();
            }
            var candidates = new ArrayList<OptionScope>();
            try (var files = Files.walk(subtree)) {
                for (Path options : files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName()
                                            .toString()
                                            .equals(fileName))
                        .toList()) {
                    Path scope = root.resolve(optionsDirectory.relativize(options.getParent()));
                    if (!Files.isDirectory(scope)) {
                        continue;
                    }
                    Path directory = normalizeDirectory(scope);
                    if (directory.startsWith(effective)) {
                        candidates.add(new OptionScope(directory, options.toRealPath()));
                    }
                }
            }
            candidates.sort(Comparator.comparing(candidate -> candidate.directory().toString()));
            return List.copyOf(candidates);
        }

        private static ConfigurationException ambiguousOptions(String tool, Path effective, List<OptionScope> candidates,
                boolean workingDirectoryOption) {
            var message = new StringBuilder("Multiple ")
                    .append(tool)
                    .append(" option scopes are contained by ")
                    .append(effective)
                    .append(workingDirectoryOption ? "; select one with -C:" : "; run from within one of:");
            Path root = optionsRoot(effective);
            for (var candidate : candidates) {
                message.append("\n  ");
                if (workingDirectoryOption) {
                    message.append("-C ");
                }
                message.append(displayPath(root, candidate.directory()));
            }
            return new ConfigurationException(message.toString());
        }

        private static Path optionsRoot(Path effective) {
            for (Path directory = effective;
                 directory != null;
                 directory = directory.getParent()) {
                if (Files.isDirectory(directory.resolve(OPTIONS_DIRECTORY))) {
                    return directory;
                }
            }
            return effective;
        }

        private static Path normalizeDirectory(Path directory) throws IOException {
            Path normalized = directory.toAbsolutePath().normalize();
            if (!Files.isDirectory(normalized)) {
                throw new ConfigurationException("Option directory is not a directory: " + normalized);
            }
            return normalized.toRealPath();
        }

        private static String displayPath(Path root, Path path) {
            if (root != null && path.startsWith(root)) {
                Path relative = root.relativize(path);
                return relative.getNameCount() == 0 ? "." : relative.toString();
            }
            return path.toString();
        }

        private record OptionScope(Path directory, Path options) {}
    }

    private static final class JavaArgumentFiles {
        private JavaArgumentFiles() {}

        static List<String> expand(List<String> arguments) throws IOException {
            var expanded = new ArrayList<String>();
            for (String argument : arguments) {
                if (argument.length() > 1 && argument.charAt(0) == '@') {
                    if (argument.charAt(1) == '@') {
                        expanded.add(argument.substring(1));
                    } else {
                        expanded.addAll(read(Path.of(argument.substring(1))));
                    }
                } else {
                    expanded.add(argument);
                }
            }
            return List.copyOf(expanded);
        }

        static List<String> read(Path file) throws IOException {
            try (Reader reader = Files.newBufferedReader(file, Charset.defaultCharset())) {
                var tokenizer = new Tokenizer(reader);
                var arguments = new ArrayList<String>();
                String argument;
                while ((argument = tokenizer.next()) != null) {
                    arguments.add(argument);
                }
                return List.copyOf(arguments);
            } catch (IOException e) {
                throw new IOException("Cannot read argument file " + file + ": " + e.getMessage(), e);
            }
        }

        private static final class Tokenizer {
            private final Reader reader;
            private int character;

            private Tokenizer(Reader reader) throws IOException {
                this.reader = reader;
                character = reader.read();
            }

            String next() throws IOException {
                skipWhitespace();
                if (character == -1) {
                    return null;
                }
                var value = new StringBuilder();
                char quote = 0;
                while (character != -1) {
                    switch (character) {
                        case ' ', '\t', '\f' -> {
                            if (quote == 0) {
                                return value.toString();
                            }
                            value.append((char) character);
                        }
                        case '\n', '\r' -> {
                            return value.toString();
                        }
                        case '\'', '"' -> {
                            if (quote == 0) {
                                quote = (char) character;
                            } else if (quote == character) {
                                quote = 0;
                            } else {
                                value.append((char) character);
                            }
                        }
                        case '\\' -> {
                            if (quote != 0) {
                                character = reader.read();
                                switch (character) {
                                    case '\n', '\r' -> {
                                        do {
                                            character = reader.read();
                                        } while (isWhitespace(character));
                                        continue;
                                    }
                                    case 'n' -> character = '\n';
                                    case 'r' -> character = '\r';
                                    case 't' -> character = '\t';
                                    case 'f' -> character = '\f';
                                    default -> {}
                                }
                            }
                            value.append((char) character);
                        }
                        default -> value.append((char) character);
                    }
                    character = reader.read();
                }
                return value.toString();
            }

            private void skipWhitespace() throws IOException {
                while (character != -1) {
                    if (isWhitespace(character)) {
                        character = reader.read();
                    } else if (character == '#') {
                        do {
                            character = reader.read();
                        } while (character != '\n' && character != '\r' && character != -1);
                    } else {
                        return;
                    }
                }
            }

            private static boolean isWhitespace(int character) {
                return character == ' '
                        || character == '\t'
                        || character == '\n'
                        || character == '\r'
                        || character == '\f';
            }
        }
    }

    /** A command-line preparation failure. */
    public static final class ConfigurationException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private ConfigurationException(String message) {
            super(message);
        }

        private ConfigurationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Builds a command-line description. */
    public static final class Builder {
        private String description = "";
        private final List<ToolOption> options = new ArrayList<>();
        private final List<ToolOption.Group> optionGroups = new ArrayList<>();
        private final List<Subcommand> commands = new ArrayList<>();
        private Operand operand;
        private boolean workingDirectory;
        private boolean argumentFiles;
        private boolean javaToolOptions;
        private Module versionModule;

        private Builder() {}

        public Builder description(String value) {
            description = Objects.requireNonNull(value);
            return this;
        }

        public Builder option(ToolOption option) {
            options.add(Objects.requireNonNull(option));
            return this;
        }

        public Builder options(ToolOption... declared) {
            for (ToolOption option : declared) {
                option(option);
            }
            return this;
        }

        public Builder options(ToolOption.Group... groups) {
            for (ToolOption.Group group : groups) {
                var declared = Objects.requireNonNull(group);
                optionGroups.add(declared);
                options.addAll(declared.options());
            }
            return this;
        }

        public Builder command(String name, String description, CommandLine commandLine) {
            commands.add(new Subcommand(name, description, commandLine));
            return this;
        }

        public Builder completion() {
            if (options.stream()
                    .flatMap(option -> option.names().stream())
                    .anyMatch(COMPLETION_COMMAND::equals)) {
                throw new IllegalStateException("Completion is already enabled");
            }
            return option(COMPLETION);
        }

        public Builder version(Module module) {
            if (versionModule != null) {
                throw new IllegalStateException("Version reporting is already enabled");
            }
            versionModule = Objects.requireNonNull(module);
            return option(VERSION);
        }

        public Builder argumentFiles() {
            if (argumentFiles) {
                throw new IllegalStateException("Argument file expansion is already enabled");
            }
            argumentFiles = true;
            return this;
        }

        public Builder javaToolOptions() {
            if (javaToolOptions) {
                throw new IllegalStateException(".java-tool-options are already enabled");
            }
            javaToolOptions = true;
            return this;
        }

        public Builder workingDirectory() {
            if (workingDirectory) {
                throw new IllegalStateException("The working directory convention is already enabled");
            }
            workingDirectory = true;
            option(ToolOption.option("-C", "DIRECTORY", "Run in the specified directory"));
            return this;
        }

        public Builder operand(String name, String description, Cardinality cardinality) {
            if (operand != null) {
                throw new IllegalStateException("An operand is already declared");
            }
            operand = new Operand(name, description, cardinality, false);
            return this;
        }

        public Builder remainder(String name, String description) {
            if (operand != null) {
                throw new IllegalStateException("An operand is already declared");
            }
            operand = new Operand(name, description, Cardinality.ZERO_OR_MORE, true);
            return this;
        }

        public CommandLine build() {
            return new CommandLine(description, options, optionGroups, commands, operand, workingDirectory,
                    argumentFiles, javaToolOptions, versionModule);
        }
    }
}
