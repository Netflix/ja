# ja

[![Maven Central](https://img.shields.io/maven-central/v/com.netflix/com.netflix.tools.ja)](https://central.sonatype.com/artifact/com.netflix/com.netflix.tools.ja)
![JDK 25+](https://img.shields.io/badge/JDK-25%2B-blue)

Java has [paved the on-ramp](https://openjdk.org/projects/amber/design-notes/on-ramp) for people learning Java and writing simple programs. `ja` lets those programs grow naturally into a module with dependencies, and later into multiple modules on a module source path, without requiring a complex build system.

`ja` uses the Java module descriptor as the source of truth throughout development. Start with one module in the current directory, add dependencies without changing the tools, and move the module beneath `src` when it enters source control or develops additional module boundaries.

- Install Java applications from Maven repositories as ordinary commands with dedicated runtimes
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

Make a JDK 25 or later available through `JAVA_HOME`, `PATH`, or an environment manager such as [jenv](https://www.jenv.be/). The installer creates a `ja`-enabled copy without modifying the source JDK.

On macOS and Linux:

```sh
curl -fsSL https://raw.githubusercontent.com/Netflix/ja/main/install.sh | bash
```

On Windows PowerShell:

```powershell
irm https://raw.githubusercontent.com/Netflix/ja/main/install.ps1 | iex
```

The installer prints the steps needed to activate `ja`. See the [installation guide](https://github.com/Netflix/ja/wiki/Installation) for default locations, shell setup and completions, custom locations, and version pinning.

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

Initialize the current directory as a module, declare its main class, and add a dependency by Java module name:

```sh
ja init --main-class com.example.hello.Main com.example.hello
ja require org.apache.commons.text@1.13.1
```

These commands create `module-info.java` in the current directory without moving existing source. `ja` records the dependency version alongside the standard `requires` directive:

```java
/**
 * @mainClass com.example.hello.Main
 */
module com.example.hello {
    requires org.apache.commons.text; // @1.13.1
}
```

Create `com/example/hello/Main.java` and use the dependency normally:

```java
package com.example.hello;

import java.util.Map;

import org.apache.commons.text.StringSubstitutor;

public final class Main {
    public static void main(String[] arguments) {
        var values = Map.of("name", "modules");
        System.out.println(StringSubstitutor.replace("Hello, ${name}!", values));
    }
}
```

Run your shiny new modular application:

```sh
ja run
```

```text
Hello, modules!
```

The working directory determines which modules are in scope. `ja` supplies each command or tool with the standard Java arguments it accepts for those modules:

```sh
ja tool jdeps
```

When moving to source control move to a module source path layout, `src/com.example.hello`. With that layout, `ja init` creates new modules alongside it under `src`.

While `com.example.hello` is fine for this example, for a module you intend to publish, choose a globally meaningful reverse-domain name following [Sonatype's namespace conventions](https://central.sonatype.org/register/namespace/), using a domain you own or a namespace you can verify, such as `io.github.owner.application`.

## Bundled Tools

Several bundled tools underpin the command implementation:

- [jig](https://github.com/Netflix/jig) performs module version resolution, compilation and assembly, outputting standard module system arguments for use with other tools. It is also the bridge to and from Maven repositories providing a standalone module proxy and publishing commands
- [jfmt](https://github.com/Netflix/jfmt) formats source using the Code Conventions for the Java Programming Language, adapted for the modern Java language. Avoids the very common whitespace, indentation, import ordering and qualified class references introduced in agent written code
- [jist](https://github.com/Netflix/jist) provides source aware symbol search, providing a grep style interface for understanding class files and their associated sources. Gives coding agents access to symbols and sources without indexing, LSPs or MCPs while interoperating with other build tools via an argument file contract
- [jdocserver](https://github.com/Netflix/jdocserver) serves locally browsable API documentation

## Documentation

The [wiki](https://github.com/Netflix/ja/wiki) covers:

- [Installation](https://github.com/Netflix/ja/wiki/Installation)
- [Module layout](https://github.com/Netflix/ja/wiki/Module-Layout)
- [Dependencies](https://github.com/Netflix/ja/wiki/Dependencies)
- [Development](https://github.com/Netflix/ja/wiki/Development)
- [Runtime access](https://github.com/Netflix/ja/wiki/Runtime-Access)
- [Packaging and distribution](https://github.com/Netflix/ja/wiki/Packaging-and-Distribution)
- [Tools](https://github.com/Netflix/ja/wiki/Tools)
- [Continuous Integration](https://github.com/Netflix/ja/wiki/Continuous-Integration)

## Acknowledgements

`ja` builds on work by:

- The [OpenJDK](https://openjdk.org/) project
- The [Maven](https://maven.apache.org/) project and Aether contributors
- [Sonatype](https://central.sonatype.org/), which operates Maven Central and established its [namespace conventions](https://www.sonatype.com/blog/why-namespacing-matters-in-public-open-source-repositories)
- The [JUnit team](https://junit.org/) — [support the development of JUnit](https://github.com/sponsors/junit-team)
- The [google-java-format](https://github.com/google/google-java-format) project
