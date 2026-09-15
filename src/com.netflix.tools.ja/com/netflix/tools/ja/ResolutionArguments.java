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

/** Transforms the root selection in arguments submitted to Jig. */
final class ResolutionArguments {
    private ResolutionArguments() {}

    static List<String> withRoots(List<String> arguments, List<String> roots) {
        var result = withoutRoots(arguments);
        for (String root : roots) {
            result.add("-m");
            result.add(root);
        }
        return List.copyOf(result);
    }

    static List<String> withAddedModules(List<String> arguments, List<String> modules) {
        var result = new ArrayList<String>();
        for (int i = 0; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            if (argument.equals("--add-modules")) {
                operand(arguments, ++i, argument);
            } else if (!argument.startsWith("--add-modules=")) {
                result.add(argument);
            }
        }
        if (!modules.isEmpty()) {
            result.add("--add-modules");
            result.add(String.join(",", modules));
        }
        return List.copyOf(result);
    }

    static List<String> rootsAsAddedModules(List<String> arguments) {
        var result = new ArrayList<String>();
        for (int i = 0; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            if (argument.equals("-m") || argument.equals("--module")) {
                result.add("--add-modules");
                result.add(operand(arguments, ++i, argument));
            } else if (argument.startsWith("--module=")) {
                result.add("--add-modules=" + argument.substring("--module=".length()));
            } else {
                result.add(argument);
            }
        }
        return List.copyOf(result);
    }

    private static ArrayList<String> withoutRoots(List<String> arguments) {
        var result = new ArrayList<String>();
        for (int i = 0; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            if (argument.equals("-m") || argument.equals("--module")) {
                operand(arguments, ++i, argument);
            } else if (!argument.startsWith("--module=")) {
                result.add(argument);
            }
        }
        return result;
    }

    private static String operand(List<String> arguments, int index, String option) {
        if (index >= arguments.size()) {
            throw new IllegalArgumentException(option + " requires an argument");
        }
        return arguments.get(index);
    }
}
