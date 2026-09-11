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

package com.netflix.tools.launcher;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

final class CompletionShims {
    private static final String RESOURCE_ROOT = "META-INF/com.netflix.tools/completion/delegated/";

    private CompletionShims() {}

    static String render(CompletionShell shell, String command) {
        String template = resource(RESOURCE_ROOT, shell.option());
        return template.replace("@COMMAND@", quote(command, shell)).replace("@FUNCTION@", functionName(command));
    }

    private static String resource(String root, String shell) {
        String name = root + shell;
        try (var input = CompletionShims.class.getModule().getResourceAsStream(name)) {
            if (input == null) {
                throw new IllegalStateException("Missing completion shim resource: " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String quote(String value, CompletionShell shell) {
        return switch (shell) {
            case BASH, ZSH, FISH -> "'" + value.replace("'", "'\\''") + "'";
            case POWERSHELL -> "'" + value.replace("'", "''") + "'";
        };
    }

    private static String functionName(String command) {
        var name = new StringBuilder("_java_tool_completion_");
        for (int i = 0; i < command.length(); i++) {
            char character = command.charAt(i);
            name.append(Character.isLetterOrDigit(character) || character == '_'
                    ? character
                    : '_');
        }
        return name.toString();
    }
}
