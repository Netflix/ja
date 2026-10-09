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

import java.util.List;

import com.netflix.tools.ja.ArgumentFiles;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArgumentFilesTest {
    @Test
    void parsesJigArgumentFilesSyntax() {
        assertEquals(
                List.of("--module-path", "/path with spaces", "--add-modules", "a,b", "#value"),
                ArgumentFiles.parse("--module-path\n\"/path with spaces\"\n--add-modules\na,b\n\"#value\"\n"));
    }

    @Test
    void preservesBackslashesInUnquotedWindowsPaths() {
        assertEquals(
                List.of("--module-path", "C:\\Users\\example\\modules"),
                ArgumentFiles.parse("--module-path\nC:\\Users\\example\\modules\n"));
    }

    @Test
    void parsesEscapedQuotesAndBackslashes() {
        assertEquals(List.of("a\\b\"c"), ArgumentFiles.parse("\"a\\\\b\\\"c\"\n"));
    }

    @Test
    void rejectsUnclosedQuote() {
        assertThrows(IllegalArgumentException.class, () -> ArgumentFiles.parse("\"value"));
    }
}
