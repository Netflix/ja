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
 * @addExports jdk.javadoc/jdk.javadoc.internal.doclets.formats.html=com.netflix.tools.jdocserver
 * @addExports jdk.javadoc/jdk.javadoc.internal.api=com.netflix.tools.jdocserver
 * @addExports jdk.javadoc/jdk.javadoc.internal.doclets.toolkit.util=com.netflix.tools.jdocserver
 * @addExports jdk.javadoc/jdk.javadoc.internal.tool=com.netflix.tools.jdocserver
 * @addExports jdk.compiler/com.sun.tools.javac.api=com.netflix.tools.jdocserver
 * @addExports jdk.compiler/com.sun.tools.javac.main=com.netflix.tools.jdocserver
 * @addExports jdk.compiler/com.sun.tools.javac.util=com.netflix.tools.jdocserver
 */
open module com.netflix.tools.ja.test {
    requires com.netflix.tools.cli;
    requires com.netflix.tools.ja;
    requires com.netflix.tools.jig;
    requires com.netflix.tools.launcher;
    requires com.netflix.tools.jdocserver;
    requires java.compiler;
    requires org.junit.jupiter; // @6.1.3

    uses java.util.spi.ToolProvider;
    uses javax.tools.Tool;
}
