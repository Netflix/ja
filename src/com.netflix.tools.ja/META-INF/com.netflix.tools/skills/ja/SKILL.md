---
name: ja
description: Develop and understand Java projects with the `ja` command-line environment. Use for build-aware Java code navigation, source and API lookup, Gradle or Maven project contexts, module dependencies, formatting, compilation, tests, benchmarks, packaging, and direct use of JDK tools.
---

# ja

`ja` is a terminal development environment for Java. It discovers Java module, Gradle, and Maven projects and supplies commands and tools with the sources, dependencies, class paths, module paths, and Java options for the selected project context.

The working directory of each `ja` invocation selects the project and its default context. Run from the project or source directory being investigated. Use `-C <directory>` to change the working directory before project discovery, or `-p <selector>` to explicitly select another project context. Run `ja <command> --help` for complete command syntax.

## Select the project context

When the automatic selection is not obvious, start with:

```bash
ja projects
```

The output lists every discovered project context and marks the current selection. Use the selector from the first column with `-p`.

Gradle selectors can identify a project, source set, and class path:

```bash
ja -p :app/main/compile describe file src/main/java/example/App.java
ja -p :app/test/runtime find callers example.App.start
```

Maven selectors can identify a reactor project and scope:

```bash
ja -p example:app/compile describe type example.App
ja -p example:app/test find usages example.App
```

Use a compile context for questions about compile-time visibility. Use a runtime context for whole-program relationships when runtime-only dependencies may contribute callers, implementations, or types. Select the source set containing the source under investigation.

Do not construct class paths or module paths by hand when `ja` has discovered the project. The selected context is applied to `ja` commands and compatible tools automatically.

## Understand Java code

Use `ja` semantic commands for questions about Java declarations, resolved types, origins, and relationships:

```bash
ja -p <context> describe module <module>
ja -p <context> describe file <source-file>
ja -p <context> describe type <type>
ja -p <context> describe member <member>
ja -p <context> describe types <type-or-source-file>
ja -p <context> describe origin <symbol>
ja -p <context> source <symbol>
ja -p <context> doc <symbol>
ja -p <context> find usages <type-or-member>
ja -p <context> find callers <type-or-method>
ja -p <context> find callees <type-or-method>
ja -p <context> find impl <type-or-method>
ja -p <context> find supertypes <type>
```

Choose the command according to the question:

- `describe module` shows a module declaration.
- `describe file` lists declarations in one source or class file.
- `describe type` shows a type and its API.
- `describe member` shows a method or field.
- `describe types` lists compiler-resolved type identities referenced by a type or source file.
- `describe origin` shows the source, module, or dependency artifact from which a symbol resolves.
- `source` prints the implementation source for a package, type, or member.
- `doc` finds local API documentation; `doc --browse` opens the documentation browser.
- `find usages` finds semantic references to a type or member.
- `find callers` finds calls to a method, or calls to executables declared by a type.
- `find callees` finds calls made by a method, or by executables declared by a type.
- `find impl` finds subtypes of a type or overriding methods.
- `find supertypes` finds parent types.

Use a fully qualified type or member when a simple name is ambiguous. Command completion can supply symbols and project selectors when shell completions are installed.

These queries use the selected project's resolved Java inputs. A successful semantic query is sufficient evidence for the declarations or relationships it reports. Use ordinary text search for lexical questions, arbitrary line ranges, comments, strings, non-Java files, and languages other than Java; do not repeat semantic queries with text search merely to reconstruct the same result.

## Use packaged Java tools

List and run packaged Java tools:

```bash
ja tool
ja tool javac --version
ja tool jdeps
```

`ja tool <name>` runs the selected Java tool with compatible project options supplied automatically. Pass tool-specific arguments after the name. Use `ja skill` to list tools that publish Agent Skills and `ja skill <tool>` to print one.

## Work with module projects

`ja` can directly manage projects defined by `module-info.java` without a separate build configuration:

```bash
ja init --main-class example.Main com.example.app
ja require org.apache.commons.text@1.13.1
ja fmt
ja compile
ja run
ja test
ja bench
```

Other module workflows include:

- `ja generate` updates generated source.
- `ja list` lists observable modules.
- `ja assemble <directory>` creates module and application artifacts.
- `ja install` installs a module as a command.
- `ja maven export` generates Maven build POMs.
- `ja maven install` installs modules into the local Maven repository.
- `ja maven deploy` and `ja maven deploy-central` publish module artifacts.

Use these module commands for `ja` module projects. In Gradle and Maven projects, do not choose or replace the project's formatting and build workflow merely because `ja` provides a command. Follow the project's conventions and the user's intent, using `ja` for project-aware insight and compatible tools.
