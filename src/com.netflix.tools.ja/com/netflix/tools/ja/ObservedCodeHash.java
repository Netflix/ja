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

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Map;

/** Hashes the methods and class structure observed during a test execution. */
final class ObservedCodeHash {
    private record Component(String name, byte[] hash) {}

    private final Map<MethodModel, Component> methodHashes = new IdentityHashMap<>();
    private final Map<ClassModel, Component> classHashes = new IdentityHashMap<>();

    String hash(
            Collection<MethodModel> methods,
            Collection<ClassModel> classes,
            ClassHierarchyResolver hierarchy) {
        var classFile = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(hierarchy));
        var digest = new Sha256();
        methods.stream()
                .distinct()
                .map(method -> methodHashes.computeIfAbsent(method, candidate -> methodHash(classFile, candidate)))
                .sorted(Comparator.comparing(Component::name))
                .forEach(component -> add(digest, "method", component));
        classes.stream()
                .distinct()
                .map(model -> classHashes.computeIfAbsent(model, candidate -> classHash(classFile, candidate)))
                .sorted(Comparator.comparing(Component::name))
                .forEach(component -> add(digest, "class", component));
        return digest.hex();
    }

    private static Component methodHash(ClassFile classFile, MethodModel method) {
        var content = classFile.build(
                method.parent()
                        .orElseThrow()
                        .thisClass()
                        .asSymbol(),
                builder -> builder.transformMethod(method, MethodTransform.ACCEPT_ALL));
        return new Component(methodName(method), Sha256.hashBytes(content));
    }

    private static Component classHash(ClassFile classFile, ClassModel model) {
        var content = classFile.build(
                model.thisClass().asSymbol(),
                builder -> builder.transform(model,
                        ClassTransform.transformingMethods(MethodTransform.dropping(CodeModel.class::isInstance))));
        return new Component(className(model), Sha256.hashBytes(content));
    }

    private static String methodName(MethodModel method) {
        return className(method.parent()
                               .orElseThrow())
                + "."
                + method.methodName().stringValue()
                + method.methodType().stringValue();
    }

    private static String className(ClassModel model) {
        return model.thisClass().asInternalName();
    }

    private static void add(Sha256 digest, String kind, Component component) {
        digest.add(kind)
              .add(component.name())
              .add(component.hash());
    }
}
