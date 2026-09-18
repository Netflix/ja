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

/**
 * @mainClass com.netflix.tools.ja.Ja
 * @enableNativeAccess com.netflix.tools.ja
 */
module com.netflix.tools.ja {
    exports com.netflix.tools.ja to com.netflix.tools.ja.test;

    requires com.netflix.tools.cli;
    requires com.netflix.tools.jig; // @0.16.3
    requires com.netflix.tools.launcher;
    requires java.compiler;
    requires java.xml;
    requires jdk.compiler;
    requires jdk.zipfs;
    requires static com.netflix.tools.jfmt; // @0.7.4
    requires static com.netflix.tools.jist; // @0.5.2
    requires static com.netflix.tools.jdocserver; // @0.4.3

    uses java.util.spi.ToolProvider;
    uses javax.tools.JavaCompiler;
    uses javax.tools.Tool;

    provides javax.tools.Tool with com.netflix.tools.ja.JaTool;
}
