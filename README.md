# ja

[![Maven Central](https://img.shields.io/maven-central/v/com.netflix/com.netflix.tools.ja)](https://central.sonatype.com/artifact/com.netflix/com.netflix.tools.ja)
![JDK 25+](https://img.shields.io/badge/JDK-25%2B-blue)

Java has [paved the on-ramp](https://openjdk.org/projects/amber/design-notes/on-ramp) for people learning Java and writing simple programs. `ja` lets those programs grow naturally into a module with dependencies, and later into multiple modules on a module source path, without requiring a complex build system.

`ja` uses the Java module descriptor as the source of truth throughout development. Start with one module in the current directory, add dependencies without changing the tools, and move the module beneath `src` when it enters source control or develops additional module boundaries.

- Declare dependencies directly in `module-info.java`
- Build, test, and distribute modules and applications from the same module declarations
- Preserve module boundaries and integrity from development through distribution
- Format source, find declarations and usages, browse APIs, and reuse compilation work across tools
- Keep output concise so coding agents can work effectively without losing important errors

> [!IMPORTANT]
> This project and its bundled tools are currently in preview. We are collecting feedback for all of the tools together in [Discussions](https://github.com/Netflix/ja/discussions).

## Installation

> [!NOTE]
> Netflix engineers should use the internally bundled toolchain rather than installing `ja` or its tools separately.

Make a JDK 25 or later available through `JAVA_HOME`, `PATH`, or an environment manager such as [jenv](https://www.jenv.be/).

On macOS and Linux:

```sh
curl -fsSL https://raw.githubusercontent.com/Netflix/ja/main/install.sh | bash
```

On Windows PowerShell:

```powershell
irm https://raw.githubusercontent.com/Netflix/ja/main/install.ps1 | iex
```

The default installation is discoverable by common development tools. On macOS it is a user JDK bundle under `~/Library/Java/JavaVirtualMachines`. On Linux and Windows it is under `~/.jdks`, following the IntelliJ IDEA convention also recognized by Gradle.

The installer does not modify shell configuration. It prints activation steps matching the way the source JDK was selected, including jenv, SDKMAN!, `JAVA_HOME`, or `PATH`. The steps also add `~/.local/bin` when needed so commands created by `ja install` are available, and show how to enable completions for `ja` and the bundled tools.

Pass an output directory to use a custom location instead:

```sh
curl -fsSL https://raw.githubusercontent.com/Netflix/ja/main/install.sh |
  bash -s -- /path/to/output
```

```powershell
& ([scriptblock]::Create((irm https://raw.githubusercontent.com/Netflix/ja/main/install.ps1))) `
  -Output C:\path\to\output
```

Set `JA_VERSION` on macOS and Linux, or pass `-JaVersion` on Windows, to install an exact version instead of the latest release:

```sh
curl -fsSL https://raw.githubusercontent.com/Netflix/ja/main/install.sh |
  JA_VERSION=0.17.0 bash
```

```powershell
& ([scriptblock]::Create((irm https://raw.githubusercontent.com/Netflix/ja/main/install.ps1))) `
  -JaVersion 0.17.0
```

Take `ja` for a spin by installing an application:

```console
$ ja install com.github.ricksbrown.cowsay@1.1.0
$ cowsay 'Holy cow, Java modules!'
 _________________________
< Holy cow, Java modules! >
 -------------------------
        \   ^__^
         \  (oo)\_______
            (__)\       )\/\
                ||----w |
                ||     ||
```

## Quick start

Initialize the current directory as a module and declare its main class:

```sh
ja init --main-class com.example.hello.Main com.example.hello
```

This creates `module-info.java` in the current directory without moving existing source:

```java
/**
 * @mainClass com.example.hello.Main
 */
module com.example.hello {
}
```

Create `com/example/hello/Main.java`:

```java
package com.example.hello;

public final class Main {
    public static void main(String[] arguments) {
        System.out.println("Hello");
    }
}
```

Run your shiny new modular application:

```sh
ja run
```

The working directory determines which modules are in scope. `ja` supplies each command or tool with the standard Java arguments it accepts for those modules:

```sh
ja tool javap com.example.hello.Main
```

When moving to source control move to a module source path layout, `src/com.example.hello`. With that layout, `ja init` creates new modules alongside it under `src`.

While `com.example.hello` is fine for this example, for a module you intend to publish, choose a globally meaningful reverse-domain name following [Sonatype's namespace conventions](https://central.sonatype.org/register/namespace/), using a domain you own or a namespace you can verify, such as `io.github.owner.application`.

## Continuous Integration

Refer to the [minimal bootstrap script](.github/actions/setup-ja/link.sh) for the steps to produce a `ja` development JDK for continuous integration. It resolves the `ja` modules and passes the resulting argument file directly to the source JDK's `jlink`. Packaged JMODs from the source JDK and resolved modules are retained so subsequent links can replace bundled modules.

### GitHub Actions

The setup action links and caches a development JDK, selects it through `JAVA_HOME`, and adds its commands to `PATH`:

```yaml
steps:
  - uses: actions/checkout@v6

  - name: Set up Zulu JDK
    uses: actions/setup-java@v5
    with:
      distribution: zulu
      java-version-file: .java-version

  - name: Set up ja
    uses: Netflix/ja/.github/actions/setup-ja@v0.17.7
```

A versioned action reference selects the same distribution version, so no separate version input is needed. When pinning the action to a branch or commit instead, pass `ja-version` explicitly in a `with` block.

## Module layout

A source module is a directory containing `module-info.java`, with Java source and resources arranged beneath it by package:

```text
 -- module-info.java
`-- com/example/application/
    |-- Application.java
    `-- messages.properties
```

There is no required separation between production and test sources and resources. Colocating tests and benchmarks with the implementation is recommended when they need access to package-private code. Tools define [class and package name suffix conventions](#tool-metadata) that identify content to filter out when packaging a module.

Dedicated test and benchmark modules with ordinary requirements are a better fit when they use only public APIs. A [multi-release layout](#resources-and-multi-release-modules) is also supported for release-specific source and resources.

### Module source paths

For a layout more suitable for source control or to support multiple modules, place each module using the module source path convention in `src`:

```text
src/
|-- com.example.api/
|   `-- module-info.java
|-- com.example.application/
|   `-- module-info.java
`-- com.example.application.test/
    `-- module-info.java
```

From the repository root, from `src` itself, or from anywhere inside an existing module source path, `ja init` creates the new module beneath `src`:

```sh
ja init com.example.application
```

A directory named `src` is treated as a module source path when it is empty or contains the standard `<module>/module-info.java` structure. An unrelated layout such as `src/main/java` is left alone. The directory structure and module declarations are enough to discover the modules; there is no additional build file to maintain.

The working directory continues to select scope: run from the repository root or `src` to include every module, and from a module directory or one of its descendants to select that module. Use `-C` to make the same choice without first changing directories:

```sh
ja compile
ja -C src/com.example.application compile
```

### Tests and benchmarks

For white-box tests, you can keep tests and their resources alongside the implementation:

```text
com.example.application/
|-- module-info.java
`-- com/example/application/
    |-- Application.java
    |-- ApplicationTest.java
    `-- test/
        |-- Fixture.java
        `-- fixture.properties
```

Add JUnit as a static requirement so it is available when compiling and testing, but is not required when the application runs:

```sh
ja require --static org.junit.jupiter
```

When the module is packaged, the [class and package suffixes declared by the tool](#tool-metadata) determine which supporting content is left out.

For black-box tests against exported APIs, use a dedicated module with ordinary requirements. Making JUnit a regular requirement ensures that tests and resources remain in the packaged module:

```java
module com.example.application.test {
    requires com.example.application;
    requires org.junit.jupiter; // @6.1.3
}
```

Benchmarks follow the same pattern. Add JMH as a static requirement when benchmarks live with the implementation:

```sh
ja require --static org.openjdk.jmh.core
```

The requirement activates the benchmark runner. Use an ordinary requirement on `org.openjdk.jmh.core` in a dedicated benchmark module.

### JMOD layout

Use the standard JMOD sections for modules that contain more than classes and resources:

```text
com.example.tool/
|-- bin/
|-- classes/
|   |-- module-info.java
|   `-- com/example/tool/Tool.java
|-- conf/
|-- include/
|-- legal/
|-- lib/
`-- man/
```

Java source and resources go beneath `classes`. Only the sections used by the module need to be present. Exporting a module with this layout produces a `jmod` in addition to its `jar`.

### Resources and multi-release modules

Keep resources alongside the Java source that uses them. Their module-relative paths are preserved when the module is packaged.

Use `META-INF/versions/<release>` for release-specific source and resources:

```text
com.example.application/
|-- META-INF/MANIFEST.MF
|-- META-INF/versions/21/com/example/application/
|   |-- Application.java
|   `-- messages.properties
|-- module-info.java
`-- com/example/application/
    |-- Application.java
    `-- messages.properties
```

Set `Multi-Release: true` in the manifest. For the selected release, files beneath `META-INF/versions/<release>` override matching files in the base module.

## Add and update dependencies

Add a dependency by Java module name or Maven package URL:

```sh
ja require org.slf4j
ja require 'pkg:maven/org.slf4j/slf4j-api'
```

Both forms add a standard `requires` directive using the dependency's Java module name. When no version is given, `ja` selects the latest stable semantic version across all major versions. If the repository has no semantic versions, it selects the latest version reported by the repository.

Add `@version` to either form when you need an exact release. Explicit prerelease and non-semantic versions are supported:

```sh
ja require org.slf4j@2.0.17
ja require 'pkg:maven/org.slf4j/slf4j-api@2.0.17'
```

The requested version is recorded as a comment on the module declaration:

```java
module com.example.hello {
    requires org.slf4j; // @2.0.17
}
```

Requirement modifiers are available through the command line:

```sh
ja require --static org.junit.jupiter@6.1.3
ja require --transitive com.example.api@1.4.0
```

A static dependency is available when compiling but is not required when the application runs. A transitive dependency is also made available to modules that depend on yours.

Update every direct dependency, or name the ones to update:

```sh
ja require --update
ja require --update org.slf4j
ja require --update=patch org.slf4j
ja require --update=major
```

By default, updates select compatible minor and patch releases. Versions before `1.0.0` may advance through `1.x`; later versions remain on their current major release. Use `patch` to remain on the current major and minor release, or `major` to allow any newer stable semantic version. Dependencies with only non-semantic versions advance to the latest version reported by the repository.

Hashes of resolved dependencies in `module-info.hash` are verified when resolving later. It stops if the content of an existing JPMS module coordinate changes.

## Generate checked-in sources

Annotation processor generated code is treated as ordinary source files. Review, maintain, and check them in alongside handwritten source. A clean checkout can then compile without running the generator first, and ensures that annotation processors aren't opaque.

Add an annotation processor as a static dependency, or provide it from another source module, then select it with `@processWith`:

```java
/** @processWith com.example.generator */
module com.example.model {
    requires static com.example.generator; // @1.2.3
}
```

Run the processor to update the generated files:

```sh
ja generate
```

Run `ja generate` again whenever the generated files need updating. Compilation and tests use the files already in the source tree.

## Compile and run

Compile the selected modules and their dependencies:

```sh
ja compile
```

`compile` reports retained and fresh compilation diagnostics. Other commands compile source modules silently as needed. Use `ja compile --recompile` to compile without reusing prior output.

Pass `--verbose` before the command to log resolution and each tool or Java invocation to standard error. The switch is also passed to each underlying tool that supports it:

```sh
ja --verbose compile
```

Run the application, optionally passing it arguments:

```sh
ja run
ja run one two three
```

When several application modules are available, choose one with `-m` or `--module`:

```sh
ja run -m com.example.application one two three
```

To run a class other than the module's declared entry point, add its name after `/`:

```sh
ja run --module com.example.application/com.example.application.Alternate
```

## Run tests

First, add JUnit to the module containing the tests. This is a one-time setup step:

```sh
ja require --static org.junit.jupiter
```

The requirement activates the test runner, so tests can then be run directly:

```sh
ja test
ja test com.example.application.ApplicationTest
ja test com.example.application.ApplicationTest.starts
ja test --all
ja test -t fast
ja test --tag fast --tag unit
```

Use an ordinary `ja require org.junit.jupiter` instead for a dedicated test module. The resolved root modules define the test discovery scope. Name one or more classes or methods to select them.

A bare `ja test` discovers tests in the resolved root modules and reuses successful ordinary Jupiter `@Test` methods while their test classes, reachable code, and module inputs are unchanged. Parameterized, repeated, dynamic, template, and other tests execute on every run.

`--all` runs all tests without reuse. Explicit selectors, `-t`, and `--tag` run the selected tests without reuse; either tag option may be repeated. Use the standalone `junit` tool for other Console options. The summary distinguishes JUnit containers and tests from test methods satisfied by the cache.

Test runner output uses a compact summary by default and includes failure details when tests fail. Standard output and error produced by tests are captured in structured reports instead of written to the console; reports from failed runs are retained and linked from the failure output.

## Run benchmarks

First, add JMH to the module containing the benchmarks. This is a one-time setup step:

```sh
ja require --static org.openjdk.jmh.core
```

The requirement activates the benchmark runner:

```sh
ja bench
ja bench com.example.application.ApplicationBenchmark
ja bench com.example.application.ApplicationBenchmark.starts
```

Use an ordinary `ja require org.openjdk.jmh.core` instead for a dedicated benchmark module. The tool discovers benchmarks from activated root modules, generates the benchmark harness, and runs it without requiring a separate generated-source build. Name one or more classes or methods to select them. Use `ja tool jmh` for additional options and native selector syntax.

## Format source

Format source in the selected modules:

```sh
ja fmt
```

## Explore APIs

Look up APIs from the selected modules, their dependencies, and the JDK without leaving the terminal:

```sh
ja doc java.lang.String.isEmpty
```

Show the source appropriate to a symbol. Top-level types show their complete source file, nested types and methods show their definitions, and other declarations show their signatures:

```sh
ja source java.lang.String.isEmpty
```

When attached source is unavailable, the command warns and shows the compiled declaration instead.

Open the complete API documentation in a browser, optionally starting at a particular type:

```sh
ja doc --browse
ja doc --browse java.lang.String
```

## Declare runtime access

Consistent with [Integrity by Default](https://openjdk.org/jeps/8305968), access that can weaken module integrity must be authorized by the consuming module. Access that cannot be expressed with standard module directives can be declared using [`jig` runtime access attributes](https://github.com/Netflix/jig#runtime-access-options):

```java
/**
 * @enableNativeAccess com.example.nativebinding
 * @enableFinalFieldMutation com.example.model
 * @addExports jdk.compiler/com.sun.tools.javac.tree=com.example.processor
 * @addOpens java.base/java.lang=com.example.framework
 */
module com.example.application {
}
```

Pass the corresponding runtime option to `require` to add the authorization with a dependency:

```sh
ja require com.example.nativebinding@1.2.3 \
  --enable-native-access com.example.nativebinding
```

The application or tool selected as the root must authorize all runtime access needed by its dependencies. A dependency can record the access it requires, but cannot authorize itself, and authorization is not inherited through `requires` directives.

Adding a dependency with `require`, or installing an application, requires every runtime access requirement in the resulting module graph to be satisfied. Resolution fails when the selected root does not authorize one of those requirements.

These declarations are validated and applied automatically. Installed tools carry the same settings in their runtime images, so users do not need to maintain JVM flags in wrapper scripts.

## Assemble modules

Assemble the selected source modules for distribution:

```sh
ja assemble --module-version 1.2.3 build/artifacts
```

The destination must not already exist. Each module receives binary, source, and Javadoc JARs. Modules that use the standard [JMOD layout](#jmod-layout) also receive a JMOD, while `--jmod` requests one for every selected module:

```sh
ja assemble --jmod --module-version 1.2.3 build/artifacts
```

Artifacts use their Java module names and form a flat directory. The version is recorded in each binary module descriptor. A `META-INF/com.netflix.tools.ja/maven/deploy.pom` resource is preserved in the assembled module and copied beside the matching main JAR as `<module-name>.pom`. When a module requires an automatic module, its binary JAR is also made automatic and records its name in `Automatic-Module-Name`; a JMOD is not created for that module.

## Export a Maven project

Export the selected source modules as a Maven reactor for IDE import and other tools that understand the Maven project model:

```sh
ja maven export
```

The command exports to the selected working directory, which must be a common ancestor of every selected source module. It updates the root `pom.xml`, writes `module-info.pom` beside each selected module descriptor, and configures `.mvn/maven.config` to make resolved dependencies available to Maven. The generated `module-info.pom` is an export intermediary and is not included when assembling the module.

## Install and deploy modules

Install the selected source modules in the configured local Maven repository:

```sh
ja maven install --module-version 1.2.3
```

Deploy them to a selected release repository:

```sh
ja maven deploy \
  --module-version 1.2.3 \
  --repository releases=https://repository.example/releases
```

Deploy to a filesystem repository by passing its path:

```sh
ja maven deploy \
  --module-version 1.2.3 \
  --repository build/repository
```

Maven repositories expose project information alongside a module so consumers can understand its purpose, ownership, licensing, and source. Provide this information in `META-INF/com.netflix.tools.ja/maven/deploy.pom` within each source module:

- Use Maven model version `4.0.0`.
- Give the project a name and description.
- Link to the project website.
- Identify its license.
- Identify its developers.
- Link to its source control repository.

Add `--sign` when the selected repository requires OpenPGP signatures.

Deploy a release to Maven Central with:

```sh
ja maven deploy-central --module-version 1.2.3
```

Every selected module must provide a deployment POM with the project information required by Central. Store the Central user token under the `central` server in `~/.m2/settings.xml`, or set both `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD`. Signing reads `MAVEN_GPG_KEY`, with optional `MAVEN_GPG_KEY_FINGERPRINT` and `MAVEN_GPG_PASSPHRASE`, from the environment. Add `--manual` to leave the validated deployment awaiting approval in the Central Portal.

## Link runtime images

Run the JDK's `jlink` tool directly to create a runtime image for the current source application. `ja` supplies the selected modules and their module path:

```sh
ja tool jlink --output /path/to/image
```

Standard `jlink` options are accepted. For example, include every observable module:

```sh
ja tool jlink \
  --add-modules ALL-MODULE-PATH \
  --output /path/to/image
```

## Install commands

Install the current source module as a command:

```sh
ja install
```

Install a command from a released module by module name or Maven package URL:

```sh
ja install com.example.application
ja install com.example.application@1.2.3
ja install 'pkg:maven/com.example/example-application'
ja install 'pkg:maven/com.example/example-application@1.2.3'
```

Without a version, the latest stable semantic version is selected across all major versions. If the repository has no semantic versions, the latest version reported by the repository is selected.

Installation creates a dedicated runtime containing the application and its dependencies, and makes its command available in the user executable directory. The final component of the module name is used as the command name by default:

```sh
ja install --name example com.example.application@1.2.3
```

An existing installation of the same version is not overwritten. Use `--force` to replace it:

```sh
ja install --force com.example.application@1.2.3
```

Use `--output` to create a standalone runtime image without installing a command:

```sh
ja install --output /path/to/image com.example.application@1.2.3
```

Static requirements are omitted by default. Include them with `--include-static`:

```sh
ja install --include-static com.example.application@1.2.3
```

An application containing automatic modules requires `java.se` in its runtime image, which can increase the installed size.

## Tools

Modules make Java tools available through the standard [`Tool`](https://docs.oracle.com/en/java/javase/25/docs/api/java.compiler/javax/tools/Tool.html) and [`ToolProvider`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/spi/ToolProvider.html) service interfaces. `ja` runs them in the module context selected by the current directory. It provides the built-in `fmt`, `test`, and `bench` workflows; `ja tool` runs a named tool directly.

Module-aware tools declare the standard Java arguments they accept, allowing `ja` to supply only the module paths, selected modules, and runtime access they need. The [native launcher](#native-launcher) packages service-provided tools as ordinary commands without wrapper scripts or Java launch syntax.

### Running tools

Run `ja tool` without a name to list the JDK and module-provided tools available to the selected modules:

```sh
ja tool
```

Pass a tool name followed by its own arguments to run it:

```sh
ja tool jshell
ja tool jdeps
ja tool javap com.example.application.Main
ja tool junit
```

The working directory or `-C` selects the module context. Module-aware tools receive the module paths, selected modules, and other standard Java arguments described by their [tool metadata](#tool-metadata). Tools that do not accept module context run in the current directory.

### Bundled tools

A `ja`-enabled JDK bundles compatible versions of these tools:

- [jig](https://github.com/Netflix/jig) — resolves module dependencies from Maven repositories
- [jfmt](https://github.com/Netflix/jfmt) — formats Java source through `ja fmt` or the `jfmt` command
- [jist](https://github.com/Netflix/jist) — finds Java declarations and source for `ja doc` and `ja source`
- [jdocserver](https://github.com/Netflix/jdocserver) — serves browsable API documentation for `ja doc --browse`

The bundled tools remain ordinary commands and share the same module and dependency context.

### Providing tools from a module

A module provides a tool through a standard [`Tool`](https://docs.oracle.com/en/java/javase/25/docs/api/java.compiler/javax/tools/Tool.html) or [`ToolProvider`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/spi/ToolProvider.html) service:

```java
module com.example.checks {
    provides java.util.spi.ToolProvider with com.example.checks.Check;
}
```

The tool is immediately available from the source tree:

```sh
ja tool check
```

The service declaration is enough for a basic tool. Implement [`OptionChecker`](https://docs.oracle.com/en/java/javase/25/docs/api/java.compiler/javax/tools/OptionChecker.html) when `ja` should infer the standard Java options accepted by the tool. Provide [tool metadata](#tool-metadata) when option spelling and arity do not fully describe the tool's module contract.

### Tool metadata

Tool metadata is the command-line contract between a tool and `ja`. It lives at `META-INF/com.netflix.tools/tools/<name>.properties`. Metadata is unnecessary when a tool needs no module context.

When `options` is omitted, `ja` uses an `OptionChecker` implemented by the provider to determine which standard Java arguments the tool accepts. Declare `options` to replace that inference with a precise contract, listing option names without the leading `--`:

```properties
options=module-path,module=list,release,enable-preview,add-exports
```

`ja` resolves and supplies only the declared arguments. Use one module form within the `options` list:

- `module=single` when the tool accepts exactly one selected module
- `module=list` when the tool accepts a comma-separated module list
- `module=main` when the tool expects `module/main-class`
- `module=roots` when the tool accepts one root through `--module` and multiple roots through `--add-modules`

`module=roots` must be accompanied by `add-modules`, and bare `module` is an alias for `module=single`. The forms are mutually exclusive. Metadata supplies this operand-shape information because `OptionChecker` can report only whether `--module` is supported and how many arguments it consumes.

The fixed `junit` tool used by `ja test` demonstrates activation and Java launching:

```properties
launch=java
activation=org.junit.platform.engine
module=org.junit.platform.console
defaults=execute --details=none --disable-banner --disable-ansi-colors
class-suffix=Test
package-suffix=test
```

This metadata makes `junit` applicable when `org.junit.platform.engine` is present in the selected module graph. The `module` property identifies the module containing the tool, while `launch=java` runs that module in a child JVM instead of invoking an in-process provider. `defaults` supplies arguments before those passed by the user. Provider launch is the default for other tools.

`class-suffix` and `package-suffix` identify tool-specific content when packaging a module. If the activation dependency is static, matching classes, packages, and resources are left out of `jar`, `jmod`, and Maven artifacts. An ordinary requirement keeps that content in the artifacts.

Include `verbose` in `options` when the tool accepts `--verbose`. `ja` passes its global `--verbose` switch to tools that declare this support or expose it through `OptionChecker`.

Use `provider` when the service name differs from the metadata name. Append `@version` to `module` to select a fixed provider version; otherwise the version of the activation module is used.

### Native launcher

A native launcher turns a service-provided tool into an ordinary command without a wrapper script, class path, or Java launch syntax. Tools bundled with a `ja`-enabled JDK use these launchers.

Exporting a module that provides `Tool` or `ToolProvider` services produces platform-specific `jmod` artifacts containing their native launchers. Linking one of those artifacts into a JDK image makes its tools available as ordinary commands.

Launcher configuration carries the target module and the runtime arguments required by the tool. Preview features and native, final-field, exported-package, and open-package access are preserved when tools are packaged and runtime images are linked. Users do not need to reproduce those settings as JVM flags.

### Installed application launchers

`ja install` gives every application a native launcher, even when the application only declares a main class and does not provide a `Tool` or `ToolProvider` service.

For an application module named `<module>`, installation generates a companion module named `<module>.launcher`. That name is reserved for the installed command adapter. The companion module requires the application and `com.netflix.tools.launcher`, and it becomes the root used to launch the command.

When the application already provides a tool whose name matches the installed command, the companion module anchors that service in the runtime image. Otherwise, it provides a generated `Tool` adapter that invokes the application's declared main class. The main package is opened only to the companion launcher module.

Every installed application receives `--` passthrough and launcher options. Version reporting, argument files, and working-directory handling are tool-level conventions rather than native-launcher behavior. Installation also preserves preview and runtime-access configuration in the generated command. Tools that provide [startup and warmup](#startup-and-warmup) metadata additionally receive launcher-managed AOT caching.

### Startup and warmup

A service-provided tool can declare arguments that exercise its startup path:

```properties
warmup=--aot-warmup
```

A native launcher packaged for the tool uses these arguments to create an AOT cache before running the requested invocation. Later launches reuse the cache, which is rebuilt when the runtime image or launcher configuration changes. The JDK installer can create these caches while building a development image.

AOT caching is automatic on supported Java 25 or later HotSpot images and on OpenJ9 images. Launcher options can override it:

- `-L-aot=auto` uses or creates a cache, falling back to a normal launch if warmup is unavailable
- `-L-aot=create` creates or refreshes the cache and reports any warmup failure
- `-L-aot=off` runs without the launcher-managed cache

HotSpot uses a user cache and stores warmup output and VM logs beside it as `out` and `log`. Automatic creation is attempted once for each runtime and launcher configuration. If it fails, an `attempted` marker prevents later launches from repeating the warmup; the launcher reports the marker path so it can be removed to retry.

OpenJ9 uses one shared class cache under `lib/ja/sharedclasses` in the runtime image. Warmup opens it for updates and ordinary launches reuse it read-only. The launcher resolves the cache from the runtime image so it continues to work when the image moves. A failed automatic warmup leaves `lib/ja/sharedclasses.attempted`; remove it or use `-L-aot=create` to retry.

### Launcher arguments

Launcher options such as `-L-aot=off` are interpreted before the launched command's arguments. Use `--` to stop launcher option processing when the command needs to receive an argument that looks like a launcher option:

```sh
some-command -- -L-aot=off
```

The native launcher passes `@file`, `-C`, and `--version` through unchanged. Tools may opt into Java-style argument files, working-directory handling, and module-version reporting as part of their described command-line contract; tools that do not opt in receive those arguments literally.

## Acknowledgements

`ja` builds on work by:

- The [OpenJDK](https://openjdk.org/) project
- The [Maven](https://maven.apache.org/) project and Aether contributors
- [Sonatype](https://central.sonatype.org/), which operates Maven Central and established its [namespace conventions](https://www.sonatype.com/blog/why-namespacing-matters-in-public-open-source-repositories)
- The [JUnit team](https://junit.org/) — [support the development of JUnit](https://github.com/sponsors/junit-team)
- The [google-java-format](https://github.com/google/google-java-format) and [Error Prone](https://github.com/google/error-prone) projects
