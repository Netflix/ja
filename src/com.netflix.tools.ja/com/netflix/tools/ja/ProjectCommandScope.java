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

import java.io.IOException;

import com.netflix.tools.ja.Command.Builtin;
import com.netflix.tools.ja.Command.Category;
import com.netflix.tools.ja.project.ProjectContext;
import com.netflix.tools.ja.project.ProjectType;

final class ProjectCommandScope {
    private ProjectCommandScope() {}

    static JaInvocation apply(JaInvocation invocation, ProjectContext context) throws IOException {
        requireProjectType(invocation.command(), context);
        if (context.catalog().anchor().type() == ProjectType.MODULE
                && !invocation.command().equals(new Builtin(BuiltinCommand.PROJECTS))) {
            return ModuleProjectScope.apply(invocation, context);
        }
        return invocation.withProjectContext(context);
    }

    static void requireProjectType(Command command, ProjectContext context) {
        var type = context.catalog().anchor().type();
        if (command.category() == Category.MODULE && type != ProjectType.MODULE) {
            throw new IllegalArgumentException(command.commandName() + " requires a module project; the selected project is " + type);
        }
    }
}
