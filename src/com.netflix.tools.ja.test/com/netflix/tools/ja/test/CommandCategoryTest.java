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

package com.netflix.tools.ja.test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import com.netflix.tools.ja.BuiltinCommand;
import com.netflix.tools.ja.Command;
import com.netflix.tools.ja.Command.Category;
import com.netflix.tools.ja.JaInvocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandCategoryTest {
    @Test
    void everyBuiltinHasAnExplicitCategory() {
        var development = Set.of(BuiltinCommand.PROJECTS, BuiltinCommand.FMT, BuiltinCommand.DOC, BuiltinCommand.SOURCE, BuiltinCommand.DESCRIBE, BuiltinCommand.FIND);
        for (var builtin : BuiltinCommand.values()) {
            assertEquals(development.contains(builtin) ? Category.DEVELOPMENT : Category.MODULE,
                    new Command.Builtin(builtin).category(), builtin.name());
        }
    }

    @Test
    void parsedRequestsKeepTheirBuiltinCategoryAndName() throws Exception {
        for (var arguments : List.of(List.of("init", "example.app"), List.of("require", "-u"), List.of("install"), List.of("run"))) {
            var command = JaInvocation.parse(Path.of(""), arguments.toArray(String[]::new)).command();
            assertEquals(Category.MODULE, command.category());
            assertEquals(arguments.getFirst(), command.commandName());
        }
        for (var arguments : List.of(List.of("source", "example.App"), List.of("doc", "example.App"), List.of("doc", "--browse"))) {
            var command = JaInvocation.parse(Path.of(""), arguments.toArray(String[]::new)).command();
            assertEquals(Category.DEVELOPMENT, command.category());
            assertEquals(arguments.getFirst(), command.commandName());
        }
    }

    @Test
    void toolListingAndInvocationAreDevelopmentCommands() {
        var listing = new Command.Tools();
        var invocation = new Command.Tool("javac");
        assertEquals(Category.DEVELOPMENT, listing.category());
        assertEquals(Category.DEVELOPMENT, invocation.category());
        assertEquals("tool", listing.commandName());
        assertEquals("tool javac", invocation.commandName());
    }
}
