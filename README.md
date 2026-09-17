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

The default installation is discoverable by common development tools. On macOS it is a user JDK bundle under `~/Library/Java/JavaVirtualMachines`; on Linux and Windows it is under `~/.jdks`, following the IntelliJ IDEA convention also recognized by Gradle.

On macOS and Linux, we recommend using [jenv](https://www.jenv.be/) to select both the source JDK and the installed JDK. Running `jenv local` records the selection in `.java-version`, allowing each repository to select its `ja`-enabled JDK and compatible bundled toolchain automatically.

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

## Documentation

The [wiki](https://github.com/Netflix/ja/wiki) covers:

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
