# ja

[![Maven Central](https://img.shields.io/maven-central/v/com.netflix/com.netflix.tools.ja)](https://central.sonatype.com/artifact/com.netflix/com.netflix.tools.ja)
![JDK 25+](https://img.shields.io/badge/JDK-25%2B-blue)

Java developers have long been exceptionally well served by IDEs. Formatting source, navigating declarations and usages, reading API documentation, and exploring dependency sources and references are all part of the experience. In the terminal, those same tasks often mean searching dependency caches, locating source archives, and assembling tool arguments by hand. Coding agents encounter these gaps constantly.

`ja` is a terminal development environment for Java, bringing those capabilities to the command line through small, composable tools. The goal is to make common Java development tasks as accessible to terminal users and coding agents as they are to someone working in an IDE.

It works with module, Maven, and Gradle projects. The working directory selects the build and provides a useful focus; Java insight commands search across the applicable project contexts and can be restricted with `-p`. Relationship searches inspect project source by default, add external application code with `--external`, and include system modules with `--all`. When selected project contexts use distinct system images, system results use module-qualified stack-trace form to preserve their versions. Each tool gets the sources, dependencies, and Java options it needs automatically. That includes standard JDK tools such as `javac`, `jdeps`, `jlink`, and `jshell`.

For module projects, the environment also supports building, testing, and distributing applications directly from `module-info.java`. A simple program can grow into one module with dependencies, then multiple modules, without introducing a separate build configuration.

> [!IMPORTANT]
> This project and its bundled tools are currently in preview. We are collecting feedback for all of the tools together in [Discussions](https://github.com/Netflix/ja/discussions).

## Installation

> [!NOTE]
> Netflix engineers should use the internally bundled toolchain rather than installing `ja` or its tools separately.

Make a JDK 25 or later available through `JAVA_HOME`, `PATH`, or an environment manager such as [jenv](https://www.jenv.be/). The installer supports two first-class installation styles.

### Development JDK

The default installation links `ja` and its tools into a development JDK. Selecting that JDK selects the complete toolchain together, which works naturally with environment managers, IDE JDK discovery, `JAVA_HOME`, and `PATH`.

On macOS and Linux:

```sh
curl -fsSL https://raw.githubusercontent.com/Netflix/ja/main/install.sh | bash
```

On Windows PowerShell:

```powershell
irm https://raw.githubusercontent.com/Netflix/ja/main/install.ps1 | iex
```

On macOS the JDK is installed under `~/Library/Java/JavaVirtualMachines`. On Linux and Windows it is installed under `~/.jdks`. The source JDK is not modified.

### Standalone distribution

Use the standalone installation when you want to add only the `ja` command and continue using an existing JDK unchanged:

```sh
curl -fsSL https://raw.githubusercontent.com/Netflix/ja/main/install.sh |
  bash -s -- --standalone
```

```powershell
& ([scriptblock]::Create((irm https://raw.githubusercontent.com/Netflix/ja/main/install.ps1))) `
  -Installation Standalone
```

The installer selects the correct platform archive, installs it alongside applications managed by `ja install`, and activates `ja` through the same user command directory. The launcher uses `JAVA_HOME` when set, then falls back to `java` on `PATH`. The archive contains the development tools, but not a JDK. Use a plain JDK: the launcher rejects system modules whose names overlap the bundled application modules, rather than silently using the JDK's copies. When the selected runtime does not contain `java.se`, `ja` keeps development commands available subject to the modules in that runtime and reports module development commands as unavailable.

Neither installation modifies shell configuration. The installer prints the activation and completion commands to add to your shell profile. See the [installation guide](https://github.com/Netflix/ja/wiki/Installation) for default locations, custom locations, version pinning, shell setup, and environment-manager guidance.

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

## Explore Java code

`ja describe` separates the symbol you select from how much source you read. Start with a member's signature and Javadoc, then keep that member selected while expanding to its implementation, enclosing type, or entire compilation unit:

```sh
ja describe member example.Store.save
ja describe member example.Store.save --source definition
ja describe member example.Store.save --source type
ja describe member example.Store.save --source unit
```

This works for project code and attached dependency sources. `--source` (`-s`) is available on `describe module`, `type`, `member`, and `file`, with the same scopes as `jist`: `none`, `body`, `signature`, `doc`, `definition`, `symbol`, `type`, and `unit`. The defaults are `definition` for modules, `doc` for members, and `signature` for types and files. `definition` includes the selected declaration's Javadoc and implementation; `type` expands to the outermost enclosing type; `unit` includes imports and sibling top-level types.

Use `ja find declarations <name>` to discover declarations, `ja describe types <type-or-file>` for referenced type identities, and `ja describe origin <symbol>` to locate resolved source or artifacts. Run `ja skill ja` for the complete navigation guide.

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

## Benchmarks

For modules requiring `org.openjdk.jmh.core`, `ja bench` discovers and runs JMH benchmarks. Run all benchmarks or select literal class/method names; shell completion offers discovered classes and methods. Fully qualified names and unambiguous short class names such as `ExampleBenchmark` or `ExampleBenchmark.answer` are accepted. Unknown or ambiguous selectors fail before benchmarks run.

```sh
ja bench
ja bench com.example.ExampleBenchmark.answer
ja bench --check ExampleBenchmark
```

Normal runs respect benchmark annotations and JMH defaults. `--check` checks benchmark execution, not performance: one fork, no warmup, and one 100 ms measurement iteration per benchmark configuration. Successful checks report the benchmark configurations that ran without timing scores. Failed checks retain diagnostics. Setup and execution failures produce a nonzero exit status.

Use `ja tool jmh` for detailed measurement settings, regex selection, profilers, and other JMH options.

## Bundled Tools

Several bundled tools underpin the command implementation:

- [jig](https://github.com/Netflix/jig) performs module version resolution, compilation and assembly, outputting standard module system arguments for use with other tools. It is also the bridge to and from Maven repositories providing a standalone module proxy and publishing commands
- [jfmt](https://github.com/Netflix/jfmt) formats source using the Code Conventions for the Java Programming Language, adapted for the modern Java language. Avoids the very common whitespace, indentation, import ordering and qualified class references introduced in agent written code
- [jist](https://github.com/Netflix/jist) provides source aware symbol search, providing a grep style interface for understanding class files and their associated sources. Gives coding agents access to symbols and sources without indexing, LSPs or MCPs while interoperating with other build tools via an argument file contract
- [jdocserver](https://github.com/Netflix/jdocserver) serves locally browsable API documentation

## Agent Skills

`ja skill` lists tools in the current installation that publish an [Agent Skill](https://agentskills.io/). `ja skill <tool>` prints the version-matched `SKILL.md` supplied by that tool:

```sh
ja skill
ja skill ja
```

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
