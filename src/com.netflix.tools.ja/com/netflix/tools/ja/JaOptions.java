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

import java.util.List;

import com.netflix.tools.cli.CommandLine.ToolOption;
import com.netflix.tools.cli.CommandLine.ToolOption.Group;

final class JaOptions {
    private JaOptions() {}

    static ToolOption help() {
        return ToolOption.flag("--help", "Print help", "-h");
    }

    static ToolOption verbose() {
        return ToolOption.flag("--verbose", "Enable verbose output");
    }

    static ToolOption recompile() {
        return ToolOption.flag("--recompile", "Compile without reusing prior output");
    }

    static ToolOption browse() {
        return ToolOption.flag("--browse", "Browse API documentation");
    }

    static Group initOptions() {
        return group(
                ToolOption.option("--release", "RELEASE", "Compile for the specified Java release"),
                ToolOption.option("--main-class", "CLASS", "Set the main class"),
                ToolOption.flag("--enable-preview", "Enable preview language features"),
                enableNativeAccess(),
                enableFinalFieldMutation(),
                addOpens(),
                addExports());
    }

    static Group requireOptions() {
        return group(
                ToolOption.flag("--static", "Add a static requirement"),
                ToolOption.flag("--transitive", "Add a transitive requirement"),
                ToolOption.builder("--update")
                        .alias("-u")
                        .optionalArgument("POLICY")
                        .choices("patch", "minor", "major")
                        .description("Update dependencies")
                        .build(),
                enableNativeAccess(),
                enableFinalFieldMutation(),
                addOpens(),
                addExports());
    }

    static Group testOptions() {
        return group(
                ToolOption.flag("--all", "Run all discovered tests, including successful executions"),
                ToolOption.builder("--tag")
                        .alias("-t")
                        .argument("TAG")
                        .description("Run tests with TAG without using cached results")
                        .build());
    }

    static Group installOptions() {
        return group(ToolOption.option("--name", "NAME", "Set the installed command name"), ToolOption.flag("--force", "Replace an existing command"), ToolOption.flag("--include-static", "Include static requirements"),
                ToolOption.option("--output", "PATH", "Write the runtime image to PATH"));
    }

    static Group assembleOptions() {
        return group(ToolOption.option("--module-version", "VERSION", "Set the module version"),
                ToolOption.flag("--jmod", "Also assemble JMOD artifacts"), ToolOption.option("--target-platform", "TARGET", "Set the JMOD target platform"));
    }

    static Group mavenInstallOptions() {
        return group(ToolOption.option("--module-version", "VERSION", "Set the module version"),
                ToolOption.flag("--jmod", "Also install JMOD artifacts"), ToolOption.option("--target-platform", "TARGET", "Set the JMOD target platform"));
    }

    static Group mavenDeployOptions() {
        return group(
                ToolOption.option("--module-version", "VERSION", "Set the module version"),
                ToolOption.flag("--jmod", "Also deploy JMOD artifacts"),
                ToolOption.option("--target-platform", "TARGET", "Set the JMOD target platform"),
                ToolOption.option("--merge-consumer-pom", "PATH", "Merge metadata into consumer POMs"),
                ToolOption.option("--repository", "ID=URI|PATH", "Select the deployment repository"),
                ToolOption.flag("--sign", "Sign deployed artifacts"));
    }

    static Group mavenCentralOptions() {
        return group(
                ToolOption.option("--module-version", "VERSION", "Set the module version"),
                ToolOption.flag("--jmod", "Also deploy JMOD artifacts"),
                ToolOption.option("--target-platform", "TARGET", "Set the JMOD target platform"),
                ToolOption.option("--merge-consumer-pom", "PATH", "Merge metadata into consumer POMs"),
                ToolOption.option("--name", "NAME", "Set the Central deployment name"),
                ToolOption.flag("--manual", "Wait for manual approval after validation"));
    }

    static Group mainModule() {
        return group(ToolOption.option("--module", "MODULE[/MAIN-CLASS]", "Select the module", "-m"));
    }

    static Group singleModule() {
        return group(ToolOption.option("--module", "MODULE", "Select the module", "-m"));
    }

    private static ToolOption enableNativeAccess() {
        return ToolOption.option("--enable-native-access", "MODULE[,MODULE...]", "Allow restricted native access");
    }

    private static ToolOption enableFinalFieldMutation() {
        return ToolOption.option("--enable-final-field-mutation", "MODULE[,MODULE...]", "Allow final field mutation");
    }

    private static ToolOption addOpens() {
        return ToolOption.option("--add-opens", "MODULE/PACKAGE=TARGET", "Open a package to another module");
    }

    private static ToolOption addExports() {
        return ToolOption.option("--add-exports", "MODULE/PACKAGE=TARGET", "Export a package to another module");
    }

    private static Group group(ToolOption... options) {
        List<ToolOption> declared = List.of(options);
        return () -> declared;
    }
}
