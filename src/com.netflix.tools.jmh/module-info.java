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

module com.netflix.tools.jmh {
    opens com.netflix.tools.jmh to org.junit.platform.commons;

    requires org.openjdk.jmh.core; // @1.37
    requires org.openjdk.jmh.generator.asm; // @1.37
    requires org.openjdk.jmh.generator.bytecode; // @1.37
    requires org.openjdk.jmh.generator.reflection; // @1.37
    requires jopt.simple; // @5.0.4
    requires org.apache.commons.math3; // @3.6.1
    requires org.objectweb.asm; // @9.0
    requires java.compiler;
    requires static org.junit.jupiter; // @6.1.3

    provides java.util.spi.ToolProvider with com.netflix.tools.jmh.JmhToolProvider;
}
