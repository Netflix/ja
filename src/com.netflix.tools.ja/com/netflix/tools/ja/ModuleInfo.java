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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ServiceLoader;
import java.util.regex.Pattern;
import javax.tools.Diagnostic.Kind;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;

import com.netflix.tools.ja.RequireRequest.Dependency;
import com.netflix.tools.ja.RequireRequest.RuntimeAccess;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.DirectiveTree;
import com.sun.source.tree.ModuleTree;
import com.sun.source.tree.RequiresTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;

/**
 * Reads and edits source module descriptors while preserving the surrounding
 * source text.
 */
final class ModuleInfo {
    private ModuleInfo() {}

    record VersionedRequirement(String moduleName, String version) {}

    static String name(Path descriptor) throws IOException {
        return parse(descriptor).module()
                                .getName()
                                .toString();
    }

    static List<VersionedRequirement> versionedRequirements(Path descriptor) throws IOException {
        var parsed = parse(descriptor);
        var source = Files.readString(descriptor);
        var requirements = new ArrayList<VersionedRequirement>();
        for (var directive : parsed.module().getDirectives()) {
            if (!(directive instanceof RequiresTree requires)) {
                continue;
            }
            var end = position(parsed.positions()
                    .getEndPosition(parsed.unit(), requires),
                    descriptor);
            var lineEnd = source.indexOf('\n', end);
            if (lineEnd < 0) {
                lineEnd = source.length();
            }
            var trailing = source.substring(end, lineEnd).stripLeading();
            if (!trailing.startsWith("// @")) {
                continue;
            }
            var version = trailing.substring("// @".length()).strip();
            if (!version.isEmpty()) {
                requirements.add(new VersionedRequirement(requires.getModuleName()
                        .toString(),
                                version));
            }
        }
        return List.copyOf(requirements);
    }

    static void require(Path descriptor, RequireRequest request) throws IOException {
        Parsed parsed = parse(descriptor);
        String source = Files.readString(descriptor);
        var existing = new LinkedHashMap<String, RequiresTree>();
        for (DirectiveTree directive : parsed.module().getDirectives()) {
            if (directive instanceof RequiresTree requires) {
                existing.put(requires.getModuleName()
                                     .toString(),
                        requires);
            }
        }

        var edits = new ArrayList<Edit>();
        var additions = new ArrayList<Dependency>();
        for (Dependency dependency : request.dependencies()) {
            RequiresTree requires = existing.get(dependency.moduleName());
            if (requires == null) {
                additions.add(dependency);
                continue;
            }
            int start = position(parsed.positions()
                    .getStartPosition(parsed.unit(), requires),
                    descriptor);
            int end = position(parsed.positions()
                    .getEndPosition(parsed.unit(), requires),
                    descriptor);
            int lineEnd = source.indexOf('\n', end);
            if (lineEnd < 0) {
                lineEnd = source.length();
            }
            String trailing = source.substring(end, lineEnd).stripLeading();
            if (!trailing.isEmpty() && !trailing.startsWith("// @")) {
                throw new IllegalArgumentException("Cannot update requires " + dependency.moduleName() + " with a trailing comment in " + descriptor);
            }
            boolean staticPhase = requires.isStatic() || request.staticPhase();
            boolean transitive = requires.isTransitive() || request.transitive();
            edits.add(new Edit(start, trailing.startsWith("// @") ? lineEnd : end,
                    directive(dependency, staticPhase, transitive)));
        }

        if (!additions.isEmpty()) {
            edits.add(insertion(parsed, source, descriptor, additions, request));
        }
        edits.sort(Comparator.comparingInt(Edit::start)
                .reversed());
        var edited = new StringBuilder(source);
        for (Edit edit : edits) {
            edited.replace(edit.start(), edit.end(), edit.content());
        }
        Files.writeString(descriptor, edited);
    }

    static void authorize(Path descriptor, List<RuntimeAccess> runtimeAccess) throws IOException {
        if (runtimeAccess.isEmpty()) {
            return;
        }
        Parsed parsed = parse(descriptor);
        String source = Files.readString(descriptor);
        int moduleStart = position(parsed.positions().getStartPosition(parsed.unit(), parsed.module()),
                descriptor);
        int commentEnd = source.lastIndexOf("*/", moduleStart);
        int commentStart = commentEnd < 0 ? -1 : source.lastIndexOf("/**", commentEnd);
        boolean attached = commentStart >= 0 && source.substring(commentEnd + 2, moduleStart).isBlank();

        var missing = new ArrayList<RuntimeAccess>();
        if (attached) {
            String comment = source.substring(commentStart, commentEnd + 2);
            for (var access : runtimeAccess) {
                var declaration = Pattern.compile("@"
                        + Pattern.quote(access.tag())
                        + "\\s+"
                        + Pattern.quote(access.value())
                        + "(?=\\s|\\*/)");
                if (!declaration.matcher(comment).find()) {
                    missing.add(access);
                }
            }
        } else {
            missing.addAll(runtimeAccess);
        }
        if (missing.isEmpty()) {
            return;
        }

        String indent = indentation(source, moduleStart);
        var tags = new StringBuilder();
        for (var access : missing) {
            tags.append(indent)
                .append(" * @")
                .append(access.tag())
                .append(' ')
                .append(access.value())
                .append('\n');
        }
        if (!attached) {
            String documentation = "/**\n" + tags + indent + " */\n" + indent;
            Files.writeString(descriptor, source.substring(0, moduleStart) + documentation + source.substring(moduleStart));
            return;
        }

        String comment = source.substring(commentStart, commentEnd + 2);
        if (comment.indexOf('\n') < 0) {
            String content = comment.substring(3, comment.length() - 2).strip();
            var documentation = new StringBuilder("/**\n");
            if (!content.isEmpty()) {
                documentation.append(indent)
                             .append(" * ")
                             .append(content)
                             .append('\n');
            }
            documentation.append(tags)
                         .append(indent)
                         .append(" */");
            Files.writeString(descriptor, source.substring(0, commentStart) + documentation + source.substring(commentEnd + 2));
            return;
        }

        int closingLine = lineStart(source, commentEnd);
        Files.writeString(descriptor, source.substring(0, closingLine) + tags + source.substring(closingLine));
    }

    private static Edit insertion(Parsed parsed, String source, Path descriptor,
            List<Dependency> additions, RequireRequest request) {
        List<? extends DirectiveTree> directives = parsed.module().getDirectives();
        String indent = "    ";
        int insertion;
        String prefix = "";

        RequiresTree lastRequires = null;
        for (DirectiveTree directive : directives) {
            if (directive instanceof RequiresTree requires) {
                lastRequires = requires;
            }
        }
        if (lastRequires != null) {
            int end = position(parsed.positions()
                    .getEndPosition(parsed.unit(), lastRequires),
                    descriptor);
            int lineEnd = source.indexOf('\n', end);
            insertion = lineEnd < 0 ? source.length() : lineEnd + 1;
            indent = indentation(source,
                    position(parsed.positions().getStartPosition(parsed.unit(), lastRequires), descriptor));
            if (lineEnd < 0) {
                prefix = "\n";
            }
        } else if (!directives.isEmpty()) {
            int start = position(parsed.positions().getStartPosition(parsed.unit(), directives.getFirst()),
                    descriptor);
            insertion = lineStart(source, start);
            indent = indentation(source, start);
        } else {
            int moduleStart = position(parsed.positions().getStartPosition(parsed.unit(), parsed.module()),
                    descriptor);
            int moduleEnd = position(parsed.positions().getEndPosition(parsed.unit(), parsed.module()),
                    descriptor);
            int open = source.indexOf('{', moduleStart);
            int close = source.lastIndexOf('}', moduleEnd - 1);
            if (open < 0 || close < open) {
                throw new IllegalArgumentException("Could not locate module body in " + descriptor);
            }
            String body = source.substring(open + 1, close);
            if (body.isBlank()) {
                String closingPrefix = indentation(source, close);
                String closingIndent = closingPrefix.isBlank() ? closingPrefix : "";
                indent = closingIndent + indent;
                return new Edit(open + 1, close, "\n" + directives(additions, request, indent) + closingIndent);
            }
            insertion = lineStart(source, close);
        }
        return new Edit(insertion, insertion, prefix + directives(additions, request, indent));
    }

    private static String directives(List<Dependency> dependencies, RequireRequest request, String indent) {
        var content = new StringBuilder();
        for (Dependency dependency : dependencies) {
            content.append(indent)
                   .append(directive(dependency, request.staticPhase(), request.transitive()))
                   .append('\n');
        }
        return content.toString();
    }

    private static String directive(Dependency dependency, boolean staticPhase, boolean transitive) {
        var content = new StringBuilder("requires ");
        if (staticPhase) {
            content.append("static ");
        }
        if (transitive) {
            content.append("transitive ");
        }
        return content.append(dependency.moduleName())
                      .append("; // @")
                      .append(dependency.version())
                      .toString();
    }

    private static String indentation(String source, int position) {
        return source.substring(lineStart(source, position), position);
    }

    private static int lineStart(String source, int position) {
        int newline = source.lastIndexOf('\n', Math.max(0, position - 1));
        return newline < 0 ? 0 : newline + 1;
    }

    private static int position(long position, Path descriptor) {
        if (position < 0 || position > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Could not locate a directive in " + descriptor);
        }
        return (int) position;
    }

    private static Parsed parse(Path descriptor) throws IOException {
        JavaCompiler compiler = javaCompiler();

        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, null)) {
            Iterable<? extends JavaFileObject> units = files.getJavaFileObjects(descriptor);
            JavacTask task = (JavacTask) compiler.getTask(null, files, diagnostics, List.of("-proc:none"), null,
                    units);
            CompilationUnitTree unit = task.parse()
                    .iterator()
                    .next();
            ModuleTree module = unit.getModule();
            if (module == null) {
                throw new IllegalArgumentException(descriptor + " does not declare a module");
            }
            var errors = diagnostics.getDiagnostics().stream()
                    .filter(diagnostic -> diagnostic.getKind() == Kind.ERROR)
                    .toList();
            if (!errors.isEmpty()) {
                throw new IllegalArgumentException("Could not parse " + descriptor + ": " + errors.getFirst());
            }
            return new Parsed(unit, module,
                    Trees.instance(task).getSourcePositions());
        }
    }

    private static JavaCompiler javaCompiler() {
        var layer = ModuleInfo.class.getModule().getLayer();
        if (layer == null) {
            throw new IllegalStateException("A JDK is required to read a module descriptor");
        }
        return ServiceLoader.load(layer, JavaCompiler.class)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("A JDK is required to read a module descriptor"));
    }

    private record Parsed(CompilationUnitTree unit, ModuleTree module, SourcePositions positions) {}

    private record Edit(int start, int end, String content) {}
}
