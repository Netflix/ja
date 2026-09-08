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
import java.util.Optional;
import java.util.Set;

import com.netflix.tools.ja.BuiltinCommand.Execution.LaunchJava;
import com.netflix.tools.ja.BuiltinCommand.Execution.Materialize;
import com.netflix.tools.ja.BuiltinCommand.Execution.ToolBacked;
import com.netflix.tools.ja.BuiltinCommand.Execution.Workflow;
import com.netflix.tools.ja.BuiltinCommand.ToolRequirement.Provider;
import com.netflix.tools.ja.Command.Builtin;
import com.netflix.tools.ja.Command.Doc;
import com.netflix.tools.ja.Command.Init;
import com.netflix.tools.ja.Command.Install;
import com.netflix.tools.ja.Command.Require;
import com.netflix.tools.ja.Command.Run;
import com.netflix.tools.ja.Command.Source;
import com.netflix.tools.ja.Command.Tools;
import com.netflix.tools.ja.ModuleResolver.Projection;

/** A stable, ergonomic command in ja's base surface. */
public enum BuiltinCommand {
    INIT("init", HelpGroup.DEVELOP, "Initialize a source module", workflow(),
            project()),
    REQUIRE("require", HelpGroup.DEVELOP, "Add or update dependencies", workflow(),
            project("module-source-path")),
    GENERATE("generate", HelpGroup.DEVELOP, "Update generated source", workflow(requiresProvider("javac")),
            javac()),
    FMT("fmt", HelpGroup.DEVELOP, "Format source", new ToolBacked("jfmt"),
            sourceTool(false)),
    COMPILE("compile", HelpGroup.DEVELOP, "Compile source modules", new Materialize(),
            project("module-path", "upgrade-module-path").withCompileDiagnostics()),
    RUN("run", HelpGroup.DEVELOP, "Run a module", new LaunchJava(),
            javaLauncher()),
    TEST("test", HelpGroup.DEVELOP, "Run tests", new ToolBacked("junit"),
            ToolProjections.COMPLETE_RUNTIME_WITH_ACCESS.withCompileTime(true)),
    BENCH("bench", HelpGroup.DEVELOP, "Run benchmarks", new ToolBacked("jmh"),
            runtimeTool(false)),

    LIST("list", HelpGroup.EXPLORE, "List observable modules", new LaunchJava(),
            project("module-path", "upgrade-module-path", "add-modules")),
    DESCRIBE("describe", HelpGroup.EXPLORE, "Describe a module", new LaunchJava(),
            project("module-path", "upgrade-module-path", "describe-module")),
    DOC(
            "doc",
            HelpGroup.EXPLORE,
            "Find or browse Java APIs",
            workflow(requiresProvider("jist"), requiresProvider("jdocserver")),
            sourceTool(true)),
    SOURCE("source", HelpGroup.EXPLORE, "Show Java source", workflow(requiresProvider("jist")),
            sourceTool(true)),

    ASSEMBLE(
            "assemble",
            HelpGroup.BUILD,
            "Assemble module artifacts",
            workflow(requiresProvider("jar"), requiresProvider("javadoc")),
            Projection.EMPTY),
    INSTALL("install", HelpGroup.BUILD, "Install a module as a command",
            workflow(requiresProvider("jlink")), ToolProjections.COMPLETE_JAVA),
    MAVEN(
            "maven",
            HelpGroup.BUILD,
            "Export Maven projects or install and deploy modules",
            workflow(requiresProvider("jar"), requiresProvider("javadoc")),
            Projection.EMPTY);

    private final String commandName;
    private final HelpGroup helpGroup;
    private final String description;
    private final Execution execution;
    private final Projection projection;

    BuiltinCommand(String commandName, HelpGroup helpGroup, String description,
                   Execution execution, Projection projection) {
        this.commandName = commandName;
        this.helpGroup = helpGroup;
        this.description = description;
        this.execution = execution;
        this.projection = projection;
    }

    String commandName() {
        return commandName;
    }

    HelpGroup helpGroup() {
        return helpGroup;
    }

    String description() {
        return description;
    }

    Execution execution() {
        return execution;
    }

    public Set<String> options() {
        return projection.options();
    }

    Projection projection() {
        return projection;
    }

    List<ToolRequirement> requiredTools() {
        return switch (execution) {
            case ToolBacked(var tool) -> List.of(new ToolRequirement.Tool(tool));
            case Workflow(var requirements) -> requirements;
            case LaunchJava _ -> List.of();
            case Materialize _ -> List.of();
        };
    }

    static Optional<BuiltinCommand> from(Command command) {
        return switch (command) {
            case Builtin(var builtin) -> Optional.of(builtin);
            case Init _ -> Optional.of(INIT);
            case Require _ -> Optional.of(REQUIRE);
            case Install _ -> Optional.of(INSTALL);
            case Run _ -> Optional.of(RUN);
            case Doc _ -> Optional.of(DOC);
            case Source _ -> Optional.of(SOURCE);
            case Command.Tool _ -> Optional.empty();
            case Tools _ -> Optional.empty();
        };
    }

    static Optional<BuiltinCommand> find(String name) {
        return Arrays.stream(values())
                .filter(command -> command.commandName.equals(name))
                .findFirst();
    }

    private static Workflow workflow(ToolRequirement... requirements) {
        return new Workflow(List.of(requirements));
    }

    private static ToolRequirement requiresProvider(String name) {
        return new Provider(name);
    }

    private static Projection project(String... options) {
        return new Projection(Set.of(options), false, false);
    }

    private static Projection javac() {
        return ToolProjections.JAVAC;
    }

    private static Projection sourceTool(boolean compileTime) {
        return ToolProjections.sourceList(compileTime);
    }

    private static Projection javaLauncher() {
        return ToolProjections.JAVA;
    }

    private static Projection runtimeTool(boolean compileTime) {
        return ToolProjections.runtimeWithAccess(compileTime);
    }

    sealed interface ToolRequirement {
        record Provider(String name) implements ToolRequirement {}

        record Tool(String name) implements ToolRequirement {}
    }

    sealed interface Execution {
        record Workflow(List<ToolRequirement> requirements) implements Execution {
            public Workflow {
                requirements = List.copyOf(requirements);
            }
        }

        record Materialize() implements Execution {}

        record LaunchJava() implements Execution {}

        record ToolBacked(String tool) implements Execution {}
    }

    enum HelpGroup {
        DEVELOP("Develop"),
        EXPLORE("Explore"),
        BUILD("Build and distribute");

        private final String heading;

        HelpGroup(String heading) {
            this.heading = heading;
        }

        String heading() {
            return heading;
        }
    }
}
