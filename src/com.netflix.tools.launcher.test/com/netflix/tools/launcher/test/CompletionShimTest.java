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

package com.netflix.tools.launcher.test;

import java.util.Map;

import com.netflix.tools.launcher.CompletionBundle;
import com.netflix.tools.launcher.CompletionShell;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompletionShimTest {
    @Test
    void rendersShellShimsThatDelegateToTheHiddenCompletionOption() {
        var markers = Map.of(
        "bash", "complete -F",
        "zsh", "compdef",
        "fish", "complete -c",
        "powershell", "Register-ArgumentCompleter");

        markers.forEach(
                (shell, marker) -> {
                    String shim = CompletionBundle.builder()
                            .add("probe")
                            .build()
                            .render(CompletionShell.parse(shell));
                    assertTrue(shim.contains(marker), shim);
                    assertTrue(shim.contains("probe"), shim);
                    assertTrue(shim.contains("__complete"), shim);
                });
    }

    @Test
    void zshDisplaysCandidateValuesAlongsideDescriptions() {
        String shim = CompletionBundle.builder()
                .add("probe")
                .build()
                .render(CompletionShell.ZSH);

        assertTrue(shim.contains("displays+=(\"$value -- $description\")"), shim);
        assertTrue(shim.contains("compadd -d displays -- \"${values[@]}\""), shim);
    }

    @Test
    void composesDynamicRegistrationsByCommandName() {
        String shim = CompletionBundle.builder()
                .add("first")
                .add("second")
                .build()
                .render(CompletionShell.POWERSHELL);

        assertTrue(shim.contains("-CommandName 'first'"), shim);
        assertTrue(shim.contains("-CommandName 'second'"), shim);
        assertEquals(2, shim.split("__complete", -1).length - 1, shim);
    }

    @Test
    void coalescesDuplicateCommands() {
        String shim = CompletionBundle.builder()
                .add("probe")
                .add("probe")
                .build()
                .render(CompletionShell.BASH);

        assertEquals(1, shim.split("complete -F", -1).length - 1, shim);
    }

    @Test
    void rejectsBlankCommandsAndUnknownShells() {
        var command = assertThrows(IllegalArgumentException.class,
                () -> CompletionBundle.builder().add(" "));
        assertEquals("Tool name is blank", command.getMessage());

        var shell = assertThrows(IllegalArgumentException.class, () -> CompletionShell.parse("other"));
        assertEquals("Unknown completion shell: other", shell.getMessage());
    }
}
