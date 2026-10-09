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
import java.util.List;

/**
 * Parses the argument-file syntax used to exchange command lines with {@code
 * jig}.
 */
public final class ArgumentFiles {
    private ArgumentFiles() {}

    public static List<String> parse(String content) {
        var arguments = new ArrayList<String>();
        var current = new StringBuilder();
        boolean quoted = false;
        boolean escaped = false;
        boolean started = false;

        for (int i = 0; i < content.length(); i++) {
            char character = content.charAt(i);
            if (escaped) {
                current.append(character);
                escaped = false;
                started = true;
            } else if (character == '\\' && quoted) {
                escaped = true;
                started = true;
            } else if (character == '"') {
                quoted = !quoted;
                started = true;
            } else if (Character.isWhitespace(character) && !quoted) {
                if (started) {
                    arguments.add(current.toString());
                    current.setLength(0);
                    started = false;
                }
            } else {
                current.append(character);
                started = true;
            }
        }
        if (escaped || quoted) {
            throw new IllegalArgumentException("Malformed argument file");
        }
        if (started) {
            arguments.add(current.toString());
        }
        return List.copyOf(arguments);
    }
}
