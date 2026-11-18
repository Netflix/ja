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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.spi.ToolProvider;
import java.util.stream.Stream;

import com.netflix.tools.ja.CommandRunner;
import com.netflix.tools.ja.JaInvocation;
import com.netflix.tools.ja.ToolCatalog;
import com.netflix.tools.ja.ToolRuntime;
import com.netflix.tools.ja.project.Project;
import com.netflix.tools.ja.project.ProjectAnchor;
import com.netflix.tools.ja.project.ProjectCatalog;
import com.netflix.tools.ja.project.ProjectContext;
import com.netflix.tools.ja.project.ProjectScope;
import com.netflix.tools.ja.project.ProjectSelection;
import com.netflix.tools.ja.project.ProjectSelection.BuildScope;
import com.netflix.tools.ja.project.ProjectSelection.SelectedProjects;
import com.netflix.tools.ja.project.ProjectTarget;
import com.netflix.tools.ja.project.ProjectType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InsightSemanticTest {
    private static final Path ROOT = Path.of("build/test-insight-semantics").toAbsolutePath();
    private static final Path MAIN = ROOT.resolve("main");
    private static final Path TEST = ROOT.resolve("test");
    private static final Path DEPENDENCIES = ROOT.resolve("dependency-classes");
    private static final Path DEPENDENCY_SOURCES = ROOT.resolve("dependency-sources.jar");

    @BeforeAll
    static void sources() throws Exception {
        Files.createDirectories(MAIN.resolve("sample"));
        Files.createDirectories(TEST.resolve("sample"));
        Files.writeString(MAIN.resolve("sample/SelectionBase.java"), """
                package sample;
                public interface SelectionBase { void perform(); }
                """);
        Files.writeString(MAIN.resolve("sample/SelectionSubject.java"), """
                package sample;
                /** Subject documentation. */
                public class SelectionSubject implements SelectionBase {
                    public static int VALUE;
                    /** Perform documentation. */
                    public void perform() { System.out.println(VALUE); }
                    public static class NestedSelection { public void nestedWork() {} }
                }
                """);
        Files.writeString(MAIN.resolve("sample/HierarchyLeaf.java"), """
                package sample;
                public interface HierarchyLeaf extends HierarchyMiddle {}
                interface HierarchyMiddle extends HierarchyRoot {}
                interface HierarchyRoot {}
                """);
        Files.writeString(MAIN.resolve("sample/MessageEnvelope.java"), """
                package sample;
                public class MessageEnvelope {
                    public record Payload(String value) {}
                    public static class PayloadBuilder {}
                }
                """);
        Files.writeString(MAIN.resolve("sample/SelectionConsumer.java"), """
                package sample;
                public class SelectionConsumer {
                    public void invoke(SelectionSubject subject) { subject.perform(); int value = SelectionSubject.VALUE; }
                }
                """);
        Files.writeString(TEST.resolve("sample/SelectionTest.java"), """
                package sample;
                public class SelectionTest {
                    public void verify(SelectionSubject subject) { subject.perform(); }
                }
                """);
        var dependency = ROOT.resolve("dependency-source/sample/dependency/DependencySource.java");
        Files.createDirectories(dependency.getParent());
        Files.createDirectories(DEPENDENCIES);
        Files.writeString(dependency, """
                package sample.dependency;
                public class DependencySource {
                    public DependencySource() {}
                    public static class NestedSource {
                        /** Read the nested value. */
                        public String nestedValue() { return "nested"; }
                    }
                    private record ImplicitSource(String implicitValue) {}
                    public record ExplicitSource(String explicitValue) {
                        /** Read the explicit value. */
                        public String explicitValue() { return explicitValue; }
                    }
                }
                """);
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-g", "-parameters", "-d", DEPENDENCIES.toString(), dependency.toString()));
        try (var jar = new JarOutputStream(Files.newOutputStream(DEPENDENCY_SOURCES))) {
            jar.putNextEntry(new JarEntry("sample/dependency/DependencySource.java"));
            Files.copy(dependency, jar);
            jar.closeEntry();
        }
        for (var namespace : List.of("alpha", "beta")) {
            Files.createDirectories(MAIN.resolve(namespace));
            Files.writeString(MAIN.resolve(namespace + "/AmbiguousSelection.java"),
                    "package " + namespace + "; public class AmbiguousSelection { public void perform() {} }\n");
        }
    }

    static Stream<Arguments> queries() {
        var cases = List.of(
                List.of("describe", "type", "SelectionSubject", "perform", "VALUE", "NestedSelection"),
                List.of("describe", "type", "SelectionSubject.NestedSelection", "nestedWork"),
                List.of("describe", "member", "SelectionSubject.NestedSelection.nestedWork", "nestedWork"),
                List.of("describe", "origin", "SelectionSubject.NestedSelection", "NestedSelection"),
                List.of("find", "usages", "SelectionSubject.perform", "SelectionConsumer"),
                List.of("describe", "member", "SelectionSubject.perform", "Perform documentation.", "perform"),
                List.of("describe", "member", "SelectionSubject.VALUE", "VALUE"),
                List.of("describe", "types", "SelectionSubject", "sample.SelectionBase"),
                List.of("describe", "origin", "SelectionSubject", "SelectionSubject.java"),
                List.of("describe", "origin", "SelectionSubject.VALUE", "VALUE"),
                List.of("find", "usages", "SelectionSubject", "SelectionConsumer"),
                List.of("find", "usages", "SelectionSubject.VALUE", "SelectionConsumer"),
                List.of("find", "callers", "SelectionSubject.perform", "SelectionConsumer"),
                List.of("find", "callees", "SelectionSubject.perform", "println"),
                List.of("find", "impl", "SelectionBase", "SelectionSubject"),
                List.of("find", "supertypes", "SelectionSubject", "SelectionBase"));
        var standard = cases.stream().flatMap(query -> Stream.of(false, true).map(focused -> Arguments.of(
                focused, query.subList(0, 2), query.get(2), query.subList(3, query.size()))));
        var scopes = Stream.of("none", "signature", "doc", "body", "definition", "symbol", "type", "unit")
                .flatMap(scope -> Stream.of(false, true).map(focused -> Arguments.of(focused,
                        List.of("describe", "type", "--source", scope), "SelectionSubject",
                        scope.equals("body") ? List.of() : List.of("perform", "VALUE"))));
        var documentation = Stream.of(false, true).flatMap(focused -> Stream.of(
                Arguments.of(focused, List.of("doc"), "SelectionSubject", List.of("Subject documentation.", "perform", "VALUE")),
                Arguments.of(focused, List.of("doc"), "SelectionSubject.perform", List.of("Perform documentation."))));
        return Stream.concat(Stream.concat(standard, scopes), documentation);
    }

    @ParameterizedTest
    @MethodSource("queries")
    void concreteInsightHasTheSameMeaningForSimpleAndQualifiedSubjects(boolean focused,
            List<String> command, String simple, List<String> expected) throws Exception {
        var exact = run(focused, command, "sample." + simple);
        assertEquals(0, exact.status(), exact.error());
        for (var fragment : expected) assertTrue(exact.output().contains(fragment), fragment + "\n" + exact.output());
        var discovered = run(focused, command, simple);
        assertEquals(0, discovered.status(), discovered.error());
        assertEquals(exact.output(), discovered.output());
        if (!focused && command.equals(List.of("find", "callers"))) {
            assertTrue(discovered.output().contains("SelectionTest"), discovered.output());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void declarationNameSearchReportsTheSelectedType(boolean focused) throws Exception {
        var result = run(focused, List.of("find", "declarations", "--kind", "type"), "SelectionSubject");
        assertEquals(0, result.status(), result.error());
        assertTrue(result.output().contains("class sample.SelectionSubject"), result.output());
        if (!focused) assertTrue(result.output().contains("main/sample/SelectionSubject.java:3-8 of 8"), result.output());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void declarationNameDiscoverySupportsCaseAndExpressions(boolean focused) throws Exception {
        var query = run(focused, List.of("find", "declarations", "-i"), "selectionsubject");
        assertEquals(0, query.status(), query.error());
        assertEquals(1, query.output().lines().count(), query.output());
        assertTrue(query.output().contains("class sample.SelectionSubject"), query.output());

        var regex = run(focused, List.of("find", "declarations", "-e", "^perform$"), "sample.SelectionSubject");
        assertEquals(0, regex.status(), regex.error());
        assertEquals(1, regex.output().lines().count(), regex.output());
        assertTrue(regex.output().contains("method sample.SelectionSubject.perform()"), regex.output());

        var unscoped = run(focused, List.of("find", "declarations", "-e"), "nestedWork");
        assertEquals(0, unscoped.status(), unscoped.error());
        assertEquals(1, unscoped.output().lines().count(), unscoped.output());
        assertTrue(unscoped.output().contains("method sample.SelectionSubject.NestedSelection.nestedWork()"), unscoped.output());

        var union = run(focused, List.of("find", "declarations", "-e", "^perform$", "-e", "^VALUE$"),
                "sample.SelectionSubject");
        assertEquals(0, union.status(), union.error());
        assertEquals(2, union.output().lines().count(), union.output());
        assertTrue(union.output().contains("field sample.SelectionSubject.VALUE"), union.output());
        assertTrue(union.output().contains("method sample.SelectionSubject.perform()"), union.output());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void supertypeSearchIncludesDirectAndIndirectParents(boolean focused) throws Exception {
        var result = run(focused, List.of("find", "supertypes"), "HierarchyLeaf");
        assertEquals(0, result.status(), result.error());
        assertEquals(2, result.output().lines().count(), result.output());
        assertTrue(result.output().contains("interface HierarchyMiddle"), result.output());
        assertTrue(result.output().contains("interface HierarchyRoot"), result.output());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void declarationDiscoveryFindsNestedTypesWithoutTheirOwner(boolean focused) throws Exception {
        var types = run(focused, List.of("find", "declarations", "--kind", "type"), "Payload");
        assertEquals(0, types.status(), types.error());
        assertEquals(2, types.output().lines().count(), types.output());
        assertTrue(types.output().contains("record sample.MessageEnvelope.Payload"), types.output());
        assertTrue(types.output().contains("class sample.MessageEnvelope.PayloadBuilder"), types.output());

        var records = run(focused, List.of("find", "declarations", "--kind", "record"), "Payload");
        assertEquals(0, records.status(), records.error());
        assertEquals(1, records.output().lines().count(), records.output());
        assertTrue(records.output().contains("record sample.MessageEnvelope.Payload"), records.output());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void attachedNestedMemberSourceSurvivesInsightComposition(boolean focused) throws Exception {
        for (var member : List.of("NestedSource.nestedValue", "ExplicitSource.explicitValue")) {
            var result = run(focused, List.of("describe", "member"),
                    "sample.dependency.DependencySource." + member);
            assertEquals(0, result.status(), result.error());
            assertEquals("", result.error());
            assertTrue(result.output().contains("(DependencySource.java:"), result.output());
            assertTrue(result.output().contains("/** Read the "), result.output());
            assertTrue(result.output().contains(member.substring(member.indexOf('.') + 1) + "()"), result.output());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void implicitRecordMemberFallbackIsQuietThroughInsightComposition(boolean focused) throws Exception {
        var result = run(focused, List.of("describe", "member"),
                "sample.dependency.DependencySource.ImplicitSource.implicitValue");
        assertEquals(0, result.status(), result.error());
        assertEquals("", result.error());
        assertTrue(result.output().contains("implicitValue()"), result.output());
        assertFalse(result.output().contains("(DependencySource.java:"), result.output());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void declarationFragmentsAreLiteralSimpleNames(boolean focused) throws Exception {
        var result = run(focused, List.of("find", "declarations"), "SelectionSubject.perform");
        assertEquals(0, result.status(), result.error());
        assertEquals("", result.output());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ambiguousTypeDescriptionsContainTheCompleteApiOfEachMatch(boolean focused) throws Exception {
        var expected = new TreeSet<String>();
        for (var namespace : List.of("alpha", "beta")) {
            var exact = run(focused, List.of("describe", "type"), namespace + ".AmbiguousSelection");
            assertEquals(0, exact.status(), exact.error());
            assertTrue(exact.output().contains("perform"), exact.output());
            expected.addAll(exact.output().lines().toList());
        }
        var result = run(focused, List.of("describe", "type"), "AmbiguousSelection");
        assertEquals(0, result.status(), result.error());
        assertEquals(expected, new TreeSet<>(result.output().lines().toList()));
        assertEquals(expected.size(), result.output().lines().count());
    }

    private static Result run(boolean focused, List<String> command, String target) throws Exception {
        var arguments = new ArrayList<String>();
        if (focused) arguments.addAll(List.of("-p", ":app/main/compile"));
        arguments.addAll(command);
        arguments.add(target);
        var parsed = JaInvocation.parse(ROOT, arguments.toArray(String[]::new));
        var main = new ProjectScope.Gradle("main", "compile");
        var project = new Project(":app", ROOT, List.of(main, new ProjectScope.Gradle("test", "compile")));
        var selection = focused
                ? new SelectedProjects(List.of(new ProjectTarget(project, Optional.of(main))), ProjectSelection.Origin.REQUESTED)
                : new BuildScope();
        var context = new ProjectContext(new ProjectCatalog(new ProjectAnchor(ProjectType.GRADLE, ROOT),
                List.of(project)), selection);
        var invocation = new JaInvocation(parsed.workingDirectory(), parsed.verbose(), parsed.command(),
                parsed.moduleSourcePath(), parsed.rootModules(), parsed.resolutionArguments(), parsed.toolArguments(),
                parsed.requestedProjects(), Optional.of(context));
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();
        var runtime = ToolRuntime.of(new FixtureJig(), ToolProvider.findFirst("jist").orElseThrow());
        int status = new CommandRunner(ModuleLayer.boot(), runtime, new ToolCatalog(List.of()), () -> null)
                .run(invocation, InputStream.nullInputStream(), new PrintStream(output), new PrintStream(error));
        return new Result(status, output.toString(), error.toString());
    }

    private static final class FixtureJig implements ToolProvider {
        @Override public String name() { return "jig"; }
        @Override public int run(PrintWriter out, PrintWriter err, String... args) {
            var arguments = List.of(args);
            boolean tests = arguments.get(arguments.indexOf("--source-set") + 1).equals("test");
            out.println("--class-path");
            out.println(DEPENDENCIES);
            out.println("--source-path");
            out.println((tests ? TEST + File.pathSeparator + MAIN : MAIN.toString())
                    + File.pathSeparator + DEPENDENCY_SOURCES);
            return 0;
        }
    }

    private record Result(int status, String output, String error) {}
}
