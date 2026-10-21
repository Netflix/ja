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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.netflix.tools.cli.CommandLine;
import com.netflix.tools.cli.CommandLine.Builder;
import com.netflix.tools.cli.CommandLine.Cardinality;
import com.netflix.tools.cli.CommandLine.Completion;
import com.netflix.tools.cli.CommandLine.CompletionRequest;
import com.netflix.tools.cli.CommandLine.ToolInvocation;
import com.netflix.tools.ja.Command.Category;
import com.netflix.tools.ja.project.ProjectLocator;
import com.netflix.tools.launcher.CompletionBundle;
import com.netflix.tools.launcher.CompletionShell;

/** Provides the {@code ja} command-line entry point and top-level help. */
public final class Ja {
    private static final CommandLine COMMAND_LINE = createCommandLine();

    private Ja() {}

    public static void main(String[] args) throws Exception {
        int exitCode = run(System.in, System.out, System.err, Path.of(""), args);
        System.exit(exitCode);
    }

    static int run(InputStream in, PrintStream out, PrintStream err,
                   Path workingDirectory, String... args)
            throws IOException {
        try {
            var completion = COMMAND_LINE.runCompletion(new PrintWriter(out, true), new PrintWriter(err, true), Ja::complete,
                    new ToolInvocation(workingDirectory, Arrays.asList(args)));
            if (completion.isPresent()) {
                return completion.orElseThrow();
            }
            var invocation = COMMAND_LINE.prepare(new ToolInvocation(workingDirectory, Arrays.asList(args)));
            workingDirectory = invocation.workingDirectory();
            args = invocation.arguments().toArray(String[]::new);
            int commandIndex = JaInvocation.commandIndex(Arrays.asList(args));
            var versionArguments = args.length == commandIndex + 1 && args[commandIndex].equals("--version")
                    ? new String[] {"--version"}
                    : args;
            var version = COMMAND_LINE.runVersion("ja", new PrintWriter(out, true), versionArguments);
            if (version.isPresent()) {
                return version.orElseThrow();
            }
            if (args.length == 1 && args[0].equals("--aot-warmup")) {
                return AotWarmup.run(err);
            }
            if (args.length == commandIndex || args[commandIndex].equals("-h") || args[commandIndex].equals("--help")) {
                var layer = Ja.class.getModule().getLayer();
                help(out, ToolRuntime.load(layer), ToolCatalog.load(layer));
                return 0;
            }
            var commandHelp = commandHelp(args, commandIndex);
            if (commandHelp.isPresent()) {
                var command = commandHelp.orElseThrow();
                out.print(command.commandLine()
                                 .help("ja " + command.name()));
                return 0;
            }
            if (args[commandIndex].equals("completion")) {
                return printCompletions(out, args, commandIndex);
            }
            var commandLine = JaInvocation.parse(workingDirectory, args);
            COMMAND_LINE.parse(new ToolInvocation(workingDirectory, Arrays.asList(args)));
            return new CommandRunner(Ja.class
                    .getModule()
                    .getLayer())
                    .run(commandLine, in, out, err);
        } catch (ToolExecutionException e) {
            return e.exitCode();
        } catch (IllegalArgumentException | IllegalStateException e) {
            err.println("ja: " + e.getMessage());
            if (args.length > 0 && args[0].equals("--verbose")) {
                e.printStackTrace(err);
            }
            return 2;
        }
    }

    public static void help(PrintStream out, ToolRuntime tools, ToolCatalog catalog) {
        out.println("Usage: ja [OPTIONS] <command> [command-arguments]");
        out.println("       ja [OPTIONS] tool [<name> [tool-arguments]]");
        out.println();

        var unavailable = new ArrayList<BuiltinCommand>();
        for (Category category : Category.values()) {
            var available = new ArrayList<BuiltinCommand>();
            for (BuiltinCommand command : BuiltinCommand.values()) {
                if (command.category() != category) {
                    continue;
                }
                if (CommandAvailability.missingTools(command, tools, catalog).isEmpty()) {
                    available.add(command);
                } else {
                    unavailable.add(command);
                }
            }
            if (available.isEmpty()) {
                continue;
            }
            out.printf("%s:%n", category.heading());
            for (BuiltinCommand command : available) {
                out.printf("  %-12s %s%n", command.commandName(), command.description());
            }
            if (category == Category.DEVELOPMENT) {
                out.printf("  %-12s %s%n", "tool", "List or run tools");
                out.printf("  %-12s %s%n", "completion", "Print completion shims for all completion-aware tools");
            }
            out.println();
        }
        if (!unavailable.isEmpty()) {
            out.println("Unavailable commands:");
            for (BuiltinCommand command : unavailable) {
                out.printf("  %s%n", CommandAvailability.message(command.commandName(), CommandAvailability.missingTools(command, tools, catalog)));
            }
            out.println();
        }
        out.print(COMMAND_LINE.optionsHelp());
        out.println();
        out.println("Run 'ja <command> --help' for command-specific help.");
    }

    private record CommandHelp(String name, CommandLine commandLine) {}

    private static Optional<CommandHelp> commandHelp(String[] arguments, int commandIndex) {
        var commands = COMMAND_LINE.commands();
        CommandHelp selected = null;
        int index = commandIndex;
        while (index < arguments.length) {
            String name = arguments[index];
            var command = commands.stream().filter(candidate -> candidate.name().equals(name)).findFirst();
            if (command.isEmpty()) break;
            var current = command.orElseThrow();
            selected = new CommandHelp(selected == null ? current.name() : selected.name() + " " + current.name(), current.commandLine());
            commands = current.commandLine().commands();
            index++;
        }
        if (selected == null) return Optional.empty();
        if (index == arguments.length && !commands.isEmpty()) return Optional.of(selected);
        for (; index < arguments.length; index++) {
            if (arguments[index].equals("--")) return Optional.empty();
            if (arguments[index].equals("-h") || arguments[index].equals("--help")) return Optional.of(selected);
        }
        return Optional.empty();
    }

    private static int printCompletions(PrintStream out, String[] arguments, int commandIndex) {
        if (arguments.length > commandIndex + 2) {
            throw new IllegalArgumentException("Usage: ja completion [SHELL]");
        }
        CompletionShell shell = arguments.length == commandIndex + 2 ? CompletionShell.parse(arguments[commandIndex + 1]) : CompletionShell.detect().orElseThrow(() -> new IllegalArgumentException("Cannot determine completion shell; use ja completion bash|zsh|fish|powershell"));
        var tools = ToolRuntime.load(Ja.class
                .getModule()
                .getLayer());
        var completions = CompletionBundle.builder().add("ja");
        tools.contributeCompletions(completions);
        out.print(completions.build()
                             .render(shell));
        return 0;
    }

    static CommandLine commandLine() {
        return COMMAND_LINE;
    }

    static List<Completion> complete(CompletionRequest request) {
        return complete(request, ToolRuntime.load(Ja.class
                .getModule()
                .getLayer()));
    }

    public static List<Completion> complete(CompletionRequest request, ToolRuntime tools) {
        var prepared = COMMAND_LINE.prepare(request.invocation());
        var arguments = prepared.arguments();
        if (!arguments.isEmpty() && (arguments.getLast().equals("-p") || arguments.getLast().equals("--project"))) {
            return completeProjects(prepared.workingDirectory(), request.current(), tools);
        }
        if (request.current().startsWith("--project=")) {
            String prefix = "--project=";
            return completeProjects(prepared.workingDirectory(), request.current().substring(prefix.length()), tools)
                    .stream()
                    .map(completion -> new Completion(prefix + completion.value(), completion.description()))
                    .toList();
        }
        int commandIndex = JaInvocation.commandIndex(arguments);
        if (arguments.size() > commandIndex && arguments.get(commandIndex).equals("tool")) {
            if (arguments.size() == commandIndex + 1) {
                return tools.names().stream()
                        .filter(name -> name.startsWith(request.current()))
                        .sorted()
                        .map(name -> new Completion(name, "Java tool"))
                        .toList();
            }
            String name = arguments.get(commandIndex + 1);
            var toolArguments = new ToolInvocation(prepared.workingDirectory(),
                    arguments.subList(commandIndex + 2, arguments.size()));
            return tools.complete(name, new CompletionRequest(toolArguments, request.current()));
        }
        if (!request.current().startsWith("-") && arguments.size() > commandIndex) {
            String command = arguments.get(commandIndex);
            var commandArguments = arguments.subList(commandIndex + 1, arguments.size());
            var selectorCommand = BuiltinCommand.find(command).filter(candidate -> candidate == BuiltinCommand.TEST || candidate == BuiltinCommand.BENCH);
            if (selectorCommand.isPresent() && !completesTagValue(selectorCommand.orElseThrow(), commandArguments)) {
                return SelectorCompletion.complete(selectorCommand.orElseThrow(), prepared.workingDirectory(), request.current(),
                        arguments.subList(0, commandIndex));
            }
            if (commandArguments.size() == 1 && command.equals("describe")) {
                for (var subject : DescribeRequest.Subject.values()) {
                    if (subject.command().equals(commandArguments.getFirst())) {
                        return subject == DescribeRequest.Subject.FILE
                                ? CodeCompletion.paths(prepared.workingDirectory(), request.current())
                                : CodeCompletion.symbols(tools, prepared.workingDirectory(), arguments.subList(0, commandIndex),
                                        subject.completionArguments(), request.current());
                    }
                }
            } else if (commandArguments.size() == 1 && command.equals("find")) {
                for (var question : FindRequest.Question.values()) {
                    if (question.command().equals(commandArguments.getFirst())) {
                        return CodeCompletion.symbols(tools, prepared.workingDirectory(), arguments.subList(0, commandIndex),
                                question.completionArguments(), request.current());
                    }
                }
            }
            Optional<ToolInvocation> delegated = Optional.empty();
            if (command.equals("source") && commandArguments.isEmpty()) {
                delegated = Optional.of(new ToolInvocation(prepared.workingDirectory(), List.of("--source", "symbol")));
            } else if (command.equals("doc") && commandArguments.isEmpty()) {
                delegated = Optional.of(new ToolInvocation(prepared.workingDirectory(), List.of("--source", "doc", "--break", "--no-line-number")));
            } else if (command.equals("doc") && commandArguments.equals(List.of("--browse"))) {
                delegated = Optional.of(new ToolInvocation(prepared.workingDirectory(), List.of()));
            }
            if (delegated.isPresent()) {
                return tools.complete("jist",
                        new CompletionRequest(delegated.orElseThrow(), request.current()));
            }
        }
        return COMMAND_LINE.complete(new CompletionRequest(prepared, request.current())).stream()
                .filter(completion -> !completion.value().equals("completion"))
                .toList();
    }

    private static List<Completion> completeProjects(Path workingDirectory, String current, ToolRuntime tools) {
        try {
            var errors = new ByteArrayOutputStream();
            var context = new ProjectLocator(tools, true).locate(workingDirectory, List.of(), InputStream.nullInputStream(),
                    new PrintStream(errors));
            return context.map(
                    value -> value.matchingTargets(current)
                                  .stream()
                                  .map(target -> new Completion(target.selector(), target.project().directory().toString()))
                                  .toList())
                          .orElseGet(List::of);
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot discover projects for completion", failure);
        }
    }

    private static boolean completesTagValue(BuiltinCommand command, List<String> arguments) {
        return command == BuiltinCommand.TEST
                && !arguments.isEmpty()
                && (arguments.getLast().equals("-t") || arguments.getLast().equals("--tag"));
    }

    private static CommandLine createCommandLine() {
        var commandLine = CommandLine.builder()
                .description("Develop, explore, build, and run Java projects")
                .options(JaOptions.verbose(), JaOptions.project(), JaOptions.help())
                .version(Ja.class.getModule())
                .completion()
                .workingDirectory();
        for (BuiltinCommand command : BuiltinCommand.values()) {
            commandLine.command(command.commandName(), command.description(), commandLine(command));
        }
        commandLine.command("tool", "List or run tools",
                CommandLine.builder()
                        .option(JaOptions.help())
                        .remainder("TOOL-ARGUMENTS", "A tool name followed by its arguments")
                        .build());
        commandLine.command("completion", "Print completion shims for all completion-aware tools",
                CommandLine.builder()
                        .option(JaOptions.help())
                        .operand("SHELL", "Shell to generate completions for", Cardinality.ZERO_OR_ONE)
                        .build());
        return commandLine.build();
    }

    static CommandLine commandLine(BuiltinCommand command) {
        var commandLine = CommandLine.builder()
                .description(command.description())
                .option(JaOptions.help());
        return switch (command) {
            case INIT ->
                    commandLine.options(JaOptions.initOptions())
                               .operand("MODULE", "Java module name", Cardinality.EXACTLY_ONE)
                               .build();
            case REQUIRE ->
                    commandLine.options(JaOptions.requireOptions())
                               .operand("MODULE|PACKAGE-URL", "Module, versioned module, or package URL", Cardinality.ZERO_OR_MORE)
                               .build();
            case COMPILE -> commandLine.option(JaOptions.recompile()).build();
            case RUN ->
                    commandLine.options(JaOptions.mainModule())
                               .remainder("ARGUMENTS", "Arguments passed to the application")
                               .build();
            case TEST ->
                    commandLine.options(JaOptions.testOptions())
                               .operand("CLASS[.METHOD]", "Select test classes or methods", Cardinality.ZERO_OR_MORE)
                               .build();
            case DESCRIBE -> {
                for (var subject : DescribeRequest.Subject.values()) {
                    commandLine.command(subject.command(), subject.description(),
                            CommandLine.builder().option(JaOptions.help()).operand("SUBJECT", subject.description(), Cardinality.EXACTLY_ONE).build());
                }
                yield commandLine.build();
            }
            case FIND -> {
                for (var question : FindRequest.Question.values()) {
                    commandLine.command(question.command(), question.description(),
                            CommandLine.builder().option(JaOptions.help()).operand("SUBJECT", "Java type or member", Cardinality.EXACTLY_ONE).build());
                }
                yield commandLine.build();
            }
            case DOC ->
                    commandLine.option(JaOptions.browse())
                               .operand("SYMBOL|TYPE", "Java source symbol or type", Cardinality.ZERO_OR_ONE)
                               .build();
            case SOURCE -> commandLine.operand("SYMBOL", "Java package, type, member, or source symbol", Cardinality.EXACTLY_ONE).build();
            case INSTALL ->
                    commandLine.options(JaOptions.installOptions())
                               .operand("MODULE|PACKAGE-URL", "Module or package reference", Cardinality.ZERO_OR_ONE)
                               .build();
            case BENCH -> commandLine.operand("CLASS[.METHOD]", "Select benchmark classes or methods", Cardinality.ZERO_OR_MORE).build();
            case ASSEMBLE ->
                    commandLine.options(JaOptions.assembleOptions())
                               .operand("DIRECTORY", "Artifact directory", Cardinality.EXACTLY_ONE)
                               .build();
            case GENERATE, FMT, PROJECTS, LIST -> commandLine.build();
            case MAVEN -> mavenCommandLine(commandLine);
        };
    }

    private static CommandLine mavenCommandLine(Builder commandLine) {
        return commandLine.command("export", "Generate Maven build POMs",
                                  CommandLine.builder().build())
                          .command("install", "Install modules in the local Maven repository",
                                  CommandLine.builder()
                                          .options(JaOptions.mavenInstallOptions())
                                          .build())
                          .command("deploy", "Deploy modules to a Maven repository",
                                  CommandLine.builder()
                                          .options(JaOptions.mavenDeployOptions())
                                          .build())
                          .command("deploy-central", "Deploy modules to Maven Central",
                                  CommandLine.builder()
                                          .options(JaOptions.mavenCentralOptions())
                                          .build())
                          .build();
    }
}
