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

package com.netflix.tools.ja.project;

public sealed interface ProjectScope {
    String qualifier();

    boolean isDefault();

    record Module() implements ProjectScope {
        public String qualifier() { return ""; }
        public boolean isDefault() { return true; }
    }

    record Maven(String scope) implements ProjectScope {
        public String qualifier() { return scope; }
        public boolean isDefault() { return scope.equals("compile"); }
    }

    record Gradle(String sourceSet, String classpath) implements ProjectScope {
        public String qualifier() { return sourceSet + "/" + classpath; }
        public boolean isDefault() { return sourceSet.equals("main") && classpath.equals("compile"); }
    }
}
