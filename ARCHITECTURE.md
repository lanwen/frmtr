# Architecture

`frmtr` is a Java formatter with a small public API, thin CLI and Gradle adapters, and a formatter engine built around JavaParser plus an internal document IR. The project is split into focused Gradle modules so formatter internals and build-tool integrations can evolve behind explicit dependency boundaries.

## Build

The project is a Gradle multi-module build:

- Group: `dev.lanwen.frmtr`
- Java toolchain: Java 25
- Root project: aggregator only; it does not produce the formatter library or CLI artifact.
- `:frmtr-core`: formatter library and engine.
- `:frmtr-tooling`: reusable file-oriented runner, result summaries, and diff rendering for adapters.
- `:frmtr-cli`: Picocli application and native executable entrypoint that depends on `:frmtr-core` and `:frmtr-tooling`.
- `:frmtr-gradle-plugin`: Gradle plugin with project-local formatting tasks that depends on `:frmtr-core` and `:frmtr-tooling`.
- `:frmtr-native-image-support`: GraalVM native-image build-time companion module for JavaParser reflection metadata. It is wired only through native-image configurations, not normal JVM runtime classpaths.

Shared subproject conventions configure Java 25, UTF-8 compilation, `-Xlint:all`, JUnit Platform, and JaCoCo. External dependency versions and the GraalVM Native Build Tools plugin are managed through the Gradle version catalog in `gradle/libs.versions.toml`.

`:frmtr-cli` generates a small `BuildInfo` source file during compilation. It embeds the project version, current Git commit SHA, and build timestamp so JVM and native CLI binaries report the same build identity through `--version`.

## Package Layout

- `frmtr-core/src/main/java/dev/lanwen/frmtr`: public API and configuration.
- `frmtr-core/src/main/java/dev/lanwen/frmtr/doc`: formatter document IR and renderer.
- `frmtr-core/src/main/java/dev/lanwen/frmtr/java`: JavaParser-backed parser, syntax view, comment handling, formatter pragma state, raw source text helpers, and Java-specific printer.
- `frmtr-tooling/src/main/java/dev/lanwen/frmtr/tooling`: reusable file-oriented check/write runner, run summaries, per-file results, and unified diff rendering shared by adapters.
- `frmtr-cli/src/main/java/dev/lanwen/frmtr/cli`: Picocli command-line adapter, selector discovery, ignore handling, and output modes.
- `frmtr-gradle-plugin/src/main/java/dev/lanwen/frmtr/gradle`: Gradle extension, Java source-set integration, and formatter tasks.
- `frmtr-native-image-support/src/main/java/dev/lanwen/frmtr/nativeimage`: native-image feature code that registers JavaParser AST node fields for hosted reflection.

## Formatting Pipeline

Single-source formatting starts at `Frmtr.format(...)`.

1. `Frmtr` applies default or caller-provided `FormatterOptions`.
2. If `FormatterOptions.requirePragma` is enabled, `JavaFormatter` first checks the leading Javadoc comment for `@format` or `@prettier` and returns source unchanged when neither pragma is present.
3. `JavaFormatter` parses source with JavaParser using stored tokens and attributed comments.
4. Syntactically invalid Java is rejected with `FormatterException`; v1 formats parseable compilation units only.
5. The parsed tree is adapted into `SyntaxNodeView` to keep formatter-owned syntax metadata separate from JavaParser APIs.
6. `JavaPrinter` walks JavaParser declarations and statements and emits `Doc` values.
7. `DocRenderer` renders the document IR using line width, indentation, line ending, and trailing-newline options.

JavaParser printers are not the formatter engine. They may be useful as references, but final formatting is owned by the `Doc` pipeline.

## Document IR

`Doc` is the intermediate representation between Java syntax rules and text output. It models formatting decisions instead of building strings directly:

- `Text` emits literal text.
- `Concat` joins documents.
- `Line`, `SoftLine`, and `HardLine` express break opportunities and required breaks.
- `Indent` increases indentation after line breaks.
- `Group` attempts flat rendering first and breaks when content does not fit.
- `IfBreak` selects different output for flat versus broken groups.

`DocRenderer` is language-agnostic. Java-specific choices belong in `JavaPrinter`, not in the renderer.

## File-Oriented Runs

`:frmtr-tooling` provides reusable file-oriented support for adapters that need to check or write many source files:

- `FormatterRunner.check(...)` formats selected files in memory and returns a `FormatRunResult` with per-file results and aggregate status helpers.
- `FormatterRunner.write(...)` writes changed formatter output back to disk, continues after per-file failures, distinguishes write-step failures as partially written results, and reports the full run summary.
- `UnifiedDiffRenderer` renders the same unified diff format for CLI and Gradle check output.

The runner owns deterministic path ordering and de-duplication for file lists supplied by adapters. Source discovery remains adapter-specific: the CLI uses selectors and `.gitignore`; the Gradle plugin builds one canonical file collection from Java source sets and Gradle-style source filters, then uses that same collection for task inputs and task actions.

## Java Formatter

`JavaFormatter` owns JavaParser configuration, pragma gating, and parse-error handling. It enables token storage and comment attribution because formatter rules need syntax-adjacent trivia. `FormatterOptions.JavaLanguageLevel` is the public parser-level setting; `JavaFormatter` converts it to JavaParser's own language-level enum internally. The default is `LATEST_AVAILABLE`, which resolves through JavaParser's latest available stable alias at runtime, while `UNSET` deliberately selects JavaParser raw mode. `FormatterOptions.requirePragma` is an opt-in API setting that mirrors Prettier's require-pragma behavior by formatting only files whose leading Javadoc comment contains `@format` or `@prettier`. `FormatterOptions.LambdaArrowParens` controls whether single-parameter lambdas preserve source parentheses, avoid parentheses when Java syntax allows it, or always emit parentheses. `FormatterOptions.BinaryOperatorPosition` controls whether broken binary continuation lines keep operators at the end of the previous line or move operators to the start of continuation lines. Parse failures are reported with nearby source lines and a caret marker at JavaParser's reported line and column; lexical failures that only include line and column in the parser message use that message position for the same source-context rendering.

The public `Frmtr` API wraps recoverable internal formatter failures, including parser dependency linkage failures and assertions, as `FormatterException.internal(...)` so adapters can report concise failures without treating them as VM-level crashes.

`JavaPrinter` contains the current Java formatting rules for common type declarations, fields, methods, constructors, and expression dispatch. It keeps the v1 style deliberately opinionated and sparse on options. `JavaPrinter` also keeps the outer statement pragma/raw gate and routes `SwitchStmt` through `SwitchPrinter` only after applying statement-level raw output, leading comments, and trailing line comments. `StatementPrinter` renders structured non-switch statement bodies, including simple semicolon statements, if/else chains, loops, synchronized blocks, and try/catch/finally, while delegating expression formatting, local variable declarations, declaration bodies, and block rendering back to existing owners. `SwitchPrinter` renders switch statements and switch expressions as one switch grammar slice: selector line comments, empty versus non-empty switch blocks, default and pattern labels, record-pattern label wrapping, guards, rule entries, statement-group entries, source-only raw single-line rule entries, and switch entry bodies. It deliberately delegates nested statement rendering, expression rendering, ordinary block rendering, statement separators, compact source text, compact type text, modifiers, and width calculations back through `JavaPrinter`, `BlockPrinter`, `RawSource`, and `TypePrinter` callbacks. `ConditionalExpressionPrinter` renders conditional expressions and owns the ternary-specific decision tree for assignment values, variable initializers, comments around `?` and `:`, nested conditional branches, and binary condition wrapping while leaving general expression dispatch, assignment dispatch, field declaration layout, raw source handling, and binary-expression continuation policy with `JavaPrinter`, `BinaryExpressionPrinter`, and existing collaborators. `LambdaExpressionPrinter` renders lambda expressions and owns the lambda-specific decision tree for parameter parentheses, commented parameter reconstruction, expression versus block bodies, parenthesized lambdas, broken logical bodies, and lambda arguments that can be hugged by method calls or object creation. It deliberately delegates broad expression dispatch, statement and block rendering, object-creation layout, call-chain layout, range predicates, compact source text, and binary-expression continuation policy back through `JavaPrinter`, `BlockPrinter`, `BinaryExpressionPrinter`, `MethodCallPrinter`, and raw-source callbacks. `MethodCallPrinter` renders method calls and method-call chains once expression dispatch selects a `MethodCallExpr`: auto versus forced chain breaks, compact-root broken-final-segment calls, mixed field/method chains, name comments on chain segments, empty argument comments, text-block arguments, single binary arguments, and method-call suffixes on enclosed scopes. It deliberately delegates broad expression dispatch, lambda argument rendering, object creation, binary-expression continuation policy, method-reference suffixes, compact source text, type-argument text, and width calculations back through `JavaPrinter`, `LambdaExpressionPrinter`, `BinaryExpressionPrinter`, and `TypePrinter` callbacks. `BinaryExpressionPrinter` renders binary expressions and shared binary continuation lines: same-operator flattening, start/end operator placement, line comments between operands, precedence parentheses, end-position method-call operand breaks, and cast-division continuation decisions while delegating ordinary expression and method-call docs back through `JavaPrinter` and `MethodCallPrinter` callbacks. `CompilationUnitPrinter` sequences whole-file layout for source-leading package comments, orphan comments, package declarations, sorted static and ordinary import blocks, optional module declarations, top-level declarations, compact unnamed-class member expansion, and trailing orphan comments while delegating module and body rendering back to `JavaPrinter` collaborators. `PackageDeclarationPrinter` renders package declarations and source-leading package comments while leaving compilation-unit ordering, orphan comments, import rendering, module declarations, and top-level declaration dispatch in `CompilationUnitPrinter`. `ImportDeclarationPrinter` renders individual import declarations while leaving import ordering, static-versus-normal import block separation, package/import/module sequencing, and module declarations in `CompilationUnitPrinter`. `ModuleDeclarationPrinter` renders module declaration headers, chooses between raw commented module reconstruction and the structured header path, and delegates annotation rendering, compact module names, and brace-delimited module body rendering to collaborators. `ModuleBlockPrinter` prints normal structured module blocks and directives while leaving module header assembly and raw commented module fallback handling to `ModuleDeclarationPrinter` and `CommentedModulePrinter`. `TypePrinter` centralizes shared type-clause rendering, declaration type-parameter flat text, compact type-list joining, and breakable generic type bodies while delegating compact type text back to `JavaPrinter` because that text still depends on raw-source normalization and expression compacting policy. `ClassOrInterfaceDeclarationPrinter` renders class and interface declaration headers, chooses the raw commented-interface fallback versus the structured path, and owns flat-versus-broken clause layout while delegating shared type-clause rendering to `TypePrinter` and member sequencing back to `JavaPrinter` collaborators. `RecordDeclarationPrinter` renders record headers, component lists, implements clauses, and same-line-versus-broken body starts while delegating shared flat type-parameter and compact type-list text to `TypePrinter` and member sequencing back to `MemberBlockPrinter`. `EnumDeclarationPrinter` renders enum headers, constant lists, source-sensitive enum semicolons, body orphan comments, and enum constant argument layout while delegating shared implements-clause rendering to `TypePrinter` and ordinary member rendering back to `JavaPrinter`. `AnnotationDeclarationPrinter` renders annotation type headers, annotation member blocks, and annotation member default values while delegating member declarations back through `JavaPrinter`. `FieldDeclarationPrinter` renders field declarations, comma-separated field variables, and variable-initializer break decisions while delegating shared expression, type, method-call, binary-expression, object-creation, conditional-expression, and lambda rendering back to `JavaPrinter`, `MethodCallPrinter`, `BinaryExpressionPrinter`, `ConditionalExpressionPrinter`, `LambdaExpressionPrinter`, and `TypePrinter` callbacks. `ConstructorDeclarationPrinter` renders normal and compact constructor headers while delegating parameter lists to `CallableSignaturePrinter`, throws wrapping to `JavaPrinter`, and body rendering to `BlockPrinter`. `MethodDeclarationPrinter` renders structured method headers, body-versus-semicolon suffixes, and the raw commented-method fallback handoff while delegating parameter lists to `CallableSignaturePrinter`, commented signature reconstruction to `CommentedMethodSignaturePrinter`, throws wrapping to `JavaPrinter`, and body rendering to `BlockPrinter`. `InitializerDeclarationPrinter` renders static and instance initializer declarations while delegating initializer body rendering to `BlockPrinter`. `MemberBlockPrinter` sequences already-rendered type members with orphan comments, opening-brace line comments, and source-range-sensitive blank lines without deciding how declarations render. `BlockPrinter` sequences already-rendered statements inside block bodies with orphan comments, printable empty statements, formatter-pragma separator rules, and source-range-sensitive blank lines without deciding how statements render. `CallableSignaturePrinter` renders callable receiver parameters, ordinary parameters, and declaration type-parameter lists while leaving method, constructor, class, and record header assembly to their declaration printers and breakable type bodies to `TypePrinter`. `CommentedModulePrinter` owns the raw-source escape hatch for module headers and module directives whose inline comments are not exposed by JavaParser in a structured form useful to normal module directive printing. `CommentedMethodSignaturePrinter` owns the raw-source escape hatch for method signatures whose comments are not exposed by JavaParser in a structured form useful to normal method-signature printing. `CommentedInterfacePrinter` owns the raw-source escape hatch for interface headers and abstract method signatures whose inline comments are not exposed by JavaParser in a structured form useful to normal interface declaration printing. `FormatterPragmas` tracks formatter off/on and ignore pragmas for printer dispatch without deciding how declarations or statements render. `RawSource` centralizes JavaParser token-range text access and whitespace normalization used by printer rules when formatting requires raw source text or compact source-derived text. `CommentedTokenText` centralizes the small comment-aware tokenization and token-line text helpers used by raw-source fallback formatting.

`CommentTracker` preserves comments currently exposed by JavaParser as leading or orphan comments. Comment handling is expected to become more precise as the formatter grows.

## CLI

The CLI is an adapter over the public formatter API:

- No selectors: discover `./**/*.java` and check formatting by default.
- `--stdin`: read Java source from stdin and write formatted source to stdout; when combined with `--check` or `--diff`, compare stdin against formatter output using `stdin` as the display path. This mode is separate from file selectors and `--write`.
- `--check`: report each checked Java file with a status marker and exit non-zero when changes are needed. `✓` means already formatted, `✗` means formatting would change, and `!` means parsing or reading failed. File check runs end with a concise stdout summary counting unchanged, would-change, and failed files.
- `--diff`: in check mode, print unified diffs for sources marked `✗`; passed sources and parse/read failures do not produce diff blocks. With no selectors or `--stdin`, `--diff` implies check mode. Diff output is followed by the same check summary.
- `--write`: rewrite files in place and print a concise stdout processed summary counting formatted, failed, ignored, and unchanged files. Ignored files are `.java` files excluded by `.gitignore` during selector discovery.
- `--version`: print the project version, Git commit SHA, and build timestamp.
- `--java-level`: select the core Java parser language level; accepts enum names such as `LATEST_AVAILABLE` and `UNSET`, plus release shorthands such as `21` or `JAVA_21`.
- `--stacktrace`: include formatter or I/O stack traces in failure output; default CLI failures stay concise. Internal formatter failures are reported as internal bugs with the original failure summary and a stacktrace hint.
- Selectors may be repeated, comma-separated, files, directories, or glob patterns.
- Directory and glob traversal formats `.java` files, skips unknown extensions silently, and respects `.gitignore`.
- Missing explicit `.java` file selectors are tool errors reported on stderr with exit code 2. Empty glob or directory matches are not tool errors, but the CLI reports `No Java files matched.` on stderr instead of exiting silently.
- Multiple matched files without `--write` or `--check` are printed to stdout with filename headers. Because stdout is formatted source in this mode, the final processed summary is printed to stderr and counts printed, failed, and ignored files.

CLI behavior should not own formatting policy. New formatting behavior belongs in the API and Java formatter pipeline first.

The CLI module owns application packaging and Gradle `run` wiring. Local execution uses `./gradlew :frmtr-cli:run --args='...'`; the `run` task uses the root project as its working directory and forwards `System.in` so selectors, default discovery, and `--stdin` behave like the native binary during local development.

## Gradle Plugin

The Gradle plugin ID is `dev.lanwen.frmtr`. Applying it creates project-local aggregate tasks:

- `frmtrFormat`: mutating formatter task aggregate, never wired into lifecycle tasks.
- `frmtrCheck`: verification aggregate wired into Gradle's `check` lifecycle.

When the Gradle Java plugin is present, Java formatting is enabled by convention without requiring a `frmtr {}` block. The plugin registers:

- `frmtrJavaFormat`: formats selected Java source-set files in place.
- `frmtrJavaCheck`: checks selected Java source-set files, suppresses unchanged-file output by default, prints `✗` and `!` status lines, and prints unified diffs for changed files by default.

The plugin is project-local. Applying it to a root project does not automatically reach into subprojects; users should apply it in each project or through a convention plugin.

Default Java source selection covers all Java source sets, de-duplicates files by normalized path, and excludes files under the project's Gradle build directory. `frmtr { java { include(...) exclude(...) } }` narrows source-set selection using source-root-relative Gradle patterns. Formatter tasks do not depend on source-generation tasks by default.

Gradle exposes parser language level as semantic DSL choices instead of mirroring every core Java release. `AUTO` infers from the Java toolchain language version first, then `sourceCompatibility`, and otherwise falls back to `LATEST_AVAILABLE`. `LATEST_AVAILABLE` explicitly ignores the Gradle project target and uses the newest stable JavaParser level. `UNDEFINED` maps to the core `UNSET` raw parser mode. Gradle stack trace output is controlled by Gradle's native `--stacktrace`; the plugin does not define a formatter-specific stacktrace switch.

## Native Binary

`frmtr-cli` applies GraalVM Native Build Tools and configures the native executable name as `frmtr`. It adds `:frmtr-native-image-support` to `nativeImageCompileOnly` and `nativeImageTestCompileOnly` so the companion module is visible to native-image builds and native tests without becoming part of `implementation` or ordinary JVM runtime classpaths.

Picocli's annotation processor generates CLI reflection and resource metadata during `:frmtr-cli:compileJava`. Proxy metadata generation is disabled because the CLI does not require dynamic proxy entries and GraalVM 25 deprecates `proxy-config.json` files discovered under `META-INF/native-image`.

`:frmtr-native-image-support` contributes `dev.lanwen.frmtr.nativeimage.JavaParserReflectionFeature` through native-image metadata. The feature iterates `JavaParserMetaModel.getNodeMetaModels()` and registers every declared field on each JavaParser AST node type with GraalVM hosted reflection APIs.

Docker is the default Linux native build path. `Dockerfile.native` builds inside `ghcr.io/graalvm/native-image-community:25` and emits a glibc-linked Linux binary. Docker on macOS still produces a Linux binary because native-image targets the build operating system and toolchain.

Local host-native builds use SDKMAN-managed GraalVM from `.sdkmanrc`, then `./gradlew :frmtr-cli:nativeCompile`.

## Tests

The test suite covers:

- `:frmtr-core`: Doc rendering behavior, formatter output, idempotence, reparse validity, comments, parse errors, and fixture corpus checks.
- `:frmtr-tooling`: file-oriented run summaries, deterministic ordering, de-duplication, diffs, write behavior, and per-file failure handling.
- `:frmtr-cli`: CLI selector parsing, glob/directory discovery, ignore handling, stdout/write/check behavior, end-of-run summaries, explicit no-file diagnostics, option validation, and exit codes.
- `:frmtr-gradle-plugin`: TestKit functional coverage for task registration, zero-configuration Java defaults, `check` lifecycle wiring, no-op non-Java projects, Gradle and source-set source filters, build-directory exclusion, check diff output, Java language-level inference, and explicit Gradle language-level overrides.
- `:frmtr-native-image-support`: JavaParser metamodel coverage for native-image reflection registration, including known-risk AST fields used by field and variable declarations.
- `:frmtr-cli:nativeTest`: native-image compatibility coverage for JavaParser reflection-sensitive syntax. It is explicit native coverage and is not wired into the default JVM `check` lifecycle.
- Golden resources under `frmtr-core/src/test/resources/format`, using directory-local companion files such as `input.java` and `frmtr.output.java`.
- The adopted upstream `prettier-java` fixture set under `frmtr-core/src/test/resources/format/prettier-java`, with verbatim upstream `input.java` and `prettier.output.java` files, checked-in `frmtr.output.java` snapshots for fixtures whose upstream syntax JavaParser can parse, and an explicit Prettier-compatible subset compared directly against `prettier.output.java` using Prettier-style 80-column, two-space options, raw trailing-whitespace preservation for ignored regions, require-pragma mode for require-pragma fixtures, and per-fixture options such as lambda arrow-parens mode where Prettier fixtures encode option-specific behavior. Fixtures using upstream syntax unsupported by the bundled JavaParser dependency stay in the adopted tree, are explicitly enumerated in tests, and are skipped by formatter assertions until parser support exists. `frmtr-output-examples` preserves formatter snapshots from earlier parseable adaptations of unsupported fixtures as examples only.

New formatter rules should include golden coverage plus idempotence and reparse checks where practical.
