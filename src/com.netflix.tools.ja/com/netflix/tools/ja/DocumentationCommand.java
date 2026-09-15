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
import java.io.InputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import com.netflix.tools.ja.DocRequest.Browse;
import com.netflix.tools.ja.DocRequest.Terminal;
import com.netflix.tools.jdocserver.DocumentationHandler;
import com.netflix.tools.launcher.ModuleOptions;

/** Runs terminal or browser documentation using the resolved module context. */
final class DocumentationCommand {
    private final ToolServices tools;
    private final DocumentationBrowser browser;

    DocumentationCommand(ToolServices tools, DocumentationBrowser browser) {
        this.tools = tools;
        this.browser = browser;
    }

    int run(DocRequest request, List<String> resolvedArguments, InputStream in,
            PrintStream out, PrintStream err)
            throws IOException {
        return switch (request) {
            case Terminal(var symbol) -> {
                var arguments = new ArrayList<>(resolvedArguments);
                arguments.addAll(List.of("--source", "doc", "--break", "--no-line-number", symbol));
                yield tools.run("jist", in, out, err, arguments.toArray(String[]::new));
            }
            case Browse(var type) -> browser.browse(ToolArguments.select(resolvedArguments, ModuleOptions.checker(ResolutionOptions.JAVAC_OPTIONS), DocumentationHandler.optionChecker()),
                    type);
        };
    }
}
