---
name: ja
description: Develop and understand Java projects with the `ja` command-line environment. Use for build-aware Java code navigation, source and API lookup, Gradle or Maven project contexts, module dependencies, formatting, compilation, tests, benchmarks, packaging, and direct use of JDK tools.
---

# ja

`ja` is a terminal development environment for Java. It discovers Java module, Gradle, and Maven projects and supplies commands and tools with the sources, dependencies, class paths, module paths, and Java options for the selected project context.

The working directory selects the build and provides a useful focus. Java insight commands search every discovered Java context when run at the build root, narrow to an enclosing project or source file when possible, and merge duplicate results. Use `-C <directory>` to change the working directory before project discovery, or `-p <selector>` to focus a command on an explicit project context. Run `ja <command> --help` for complete command syntax.

## Select the project context

When the automatic selection is not obvious, start with:

```bash
ja projects
```

The output groups discovered projects by directory and marks the current working-directory or requested selection. Each Gradle project lists its source sets and compile or runtime class paths; each Maven project lists its available scopes. Use the logical project selector with `-p`, followed by a source set and optional class path for Gradle or a scope for Maven, when a question should be restricted to one context.

Gradle selectors can identify a project, source set, and class path. Compile is the default class path, so the source-set form is shorthand for its compile context:

```bash
ja -p :app/main describe file src/main/java/example/App.java
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
ja describe module <module>
ja describe file <source-file>
ja describe type <type>
ja describe member <member>
ja describe types <type-or-source-file>
ja describe origin <symbol>
ja doc <symbol>
ja find declarations <name-query>
ja find usages <type-or-member>
ja find callers <type-or-method>
ja find callees <type-or-method>
ja find impl <type-or-method>
ja find supertypes <type>
```

Choose the command according to the question:

- `describe module` shows a module declaration.
- `describe file` lists declarations in one source or class file.
- `describe type` shows a type and its API.
- `describe member` shows a method or field's signature and Javadoc by default.
- `describe types` lists compiler-resolved type identities referenced by a type or source file; use it to resolve names appearing in that code.
- `describe origin` shows where a selected symbol resolves: its source, module, or dependency artifact.
- `doc` finds local API documentation; `doc --browse` opens the documentation browser.
- `find declarations` searches named type and member declarations. Use repeatable `--kind` options to restrict results to `module`, `package`, `type`, `class`, `interface`, `enum`, `record`, `annotation`, `method`, or `field`; `type` includes all type declarations.
- `find usages` finds semantic references to a type or member.
- `find callers` finds calls to a method, or calls to executables declared by a type.
- `find callees` finds calls made by a method, or by executables declared by a type.
- `find impl` finds subtypes of a type or overriding methods.
- `find supertypes` finds parent types.

Choose the narrowest symbol and source scope that answer the question. Selection and source scope are independent: keep the same subject and change `--source` (`-s`) when you need its implementation or surrounding code. This works for project and attached dependency sources alike:

```bash
ja describe member example.Store.save
ja describe member example.Store.save --source definition
ja describe member example.Store.save --source type
ja describe member example.Store.save --source unit
```

`describe module`, `type`, `member`, and `file` accept these scopes:

| Scope | Source shown |
| --- | --- |
| `none` | Symbols without source text |
| `signature` | Declaration signatures |
| `doc` | Javadoc and declaration signatures |
| `body` | Method source lines identified by compiled line tables |
| `definition` | Complete selected declarations, including Javadoc and implementation |
| `symbol` | Natural scope: compilation unit for a top-level type, definition for a nested type or executable, signature for other symbols |
| `type` | Outermost enclosing type, including Javadoc |
| `unit` | Entire compilation unit, including imports and sibling top-level types |

Defaults are `definition` for modules, `doc` for members, and `signature` for types and files. Describing a member already includes its Javadoc; select `--source definition` when the question is about implementation behavior.

Named dependency and JDK symbols resolve through `describe` and `doc` using the selected context's inputs, without a widening flag. If you do not know a declaration's qualified name, use `find declarations` to discover it, then use the returned name in follow-up queries. Use a fully qualified type or member when a simple name is ambiguous. Command completion can supply symbols and project selectors when shell completions are installed.

At the build root, `find` searches the project-source contexts reported by `ja projects`, not just one source set. From within a project or with `-p`, it searches the marked current or requested context. The `find` options `--external` and `--all` widen the code searched, not the symbols available for resolution: `--external` adds dependency and other external application code; `--all` also adds JDK system-module code:

```bash
ja find declarations --kind type Queue
ja find callers --external example.Library.run
ja find callers --all java.lang.String.repeat
```

Each `find` invocation performs a fresh search; reuse its output when counting, filtering, or presenting multiple views of the same results.

Declaration locations use `path:start-end of total` to report the declaration range and the source file's total line count. Start file reads with the reported declaration range and use the total line count to judge how far to expand. Alternatively, select a source scope with `describe` to read the declaration or surrounding code. A successful semantic query provides evidence for the declarations or relationships it reports within the selected contexts. Ordinary text search and file reads complement this for lexical questions, arbitrary line ranges, comments, strings, non-Java files, and other languages.

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
