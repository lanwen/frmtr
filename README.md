# frmtr

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Build: Gradle](https://img.shields.io/badge/build-Gradle-02303A.svg?logo=gradle)](build.gradle.kts)

`frmtr` is a fast (supposed to be), opinionated Java formatter built on JavaParser.

The formatter parses Java source, adapts the parsed tree into formatter-owned syntax views, prints a compact document IR, and renders that IR with width-aware line breaking.

## Installation

`frmtr` is pre-release software. Use the published Gradle plugin or native binary, or build it from source.

Prerequisites:

- JDK 21 for JVM runtime artifacts and tests. The Gradle build compiles published/runtime Java artifacts with
  `--release 21` so the Gradle plugin and JVM CLI can run in Java 21 builds.
- GraalVM/JDK 25 with `native-image` for `:frmtr-cli:nativeCompile`. The repo ships an `.sdkmanrc` pinned to
  GraalVM 25 for that native build path, and Gradle selects a Java 21 toolchain separately for JVM bytecode.
- No global Gradle install required — use the bundled `./gradlew` wrapper.

```bash
./gradlew build
```

For a standalone executable, see [Native Binary](#native-binary) below.

## Usage

Build the project and check formatting over a source tree:

```bash
./gradlew :frmtr-cli:run --args='--check frmtr-core/src/main/java'
```

The sections below cover the Gradle plugin, the CLI, the documentation site, and native binaries in detail.

## Gradle Plugin

Apply the Gradle plugin to a Java project to check formatting during `check` and format source files on demand:

```kotlin
plugins {
    java
    id("dev.lanwen.frmtr") version "0.2.1"
}
```

Snapshot builds are published from `main`. Current snapshot: `0.2.2-SNAPSHOT`; see
[Consuming Snapshots](PUBLISHING.md#consuming-snapshots) for the repository setup.

The plugin follows Java source-set defaults with no required `frmtr {}` block:

```bash
./gradlew frmtrCheck
./gradlew frmtrFormat
```

Available tasks:

- `frmtrCheck`: checks project-local sources and is wired into `check`.
- `frmtrFormat`: formats project-local sources in place.
- `frmtrJavaCheck`: checks Java source-set files.
- `frmtrJavaFormat`: formats Java source-set files.

`frmtrJavaCheck` is cacheable and uses Gradle incremental source changes. `frmtrJavaFormat` remains
non-cacheable because it rewrites source files in place.

In a multi-project build, apply frmtr to each project that should run it. A root `apply false` declaration can keep the
plugin version central, while normal Gradle plugin application controls which modules participate:

```kotlin
plugins {
    id("dev.lanwen.frmtr") version "0.2.1" apply false
}

subprojects {
    pluginManager.withPlugin("java") {
        pluginManager.apply("dev.lanwen.frmtr")
    }
}
```

To set shared frmtr defaults, apply the plugin to the root project too. Subprojects that apply frmtr after the root
extension exists inherit root `frmtr {}` values as conventions:

```kotlin
plugins {
    id("dev.lanwen.frmtr") version "0.2.1"
}

frmtr {
    java {
        exclude("**/generated/**")
    }
}

subprojects {
    pluginManager.withPlugin("java") {
        pluginManager.apply("dev.lanwen.frmtr")
    }
}
```

Each participating project gets its own local `frmtrCheck` and `frmtrFormat` tasks. From the root, Gradle task selectors
such as `./gradlew frmtrCheck` run matching tasks across projects; use `./gradlew frmtrCheck --continue` to check
independent modules even after one module fails. A module can override inherited conventions or use
`frmtr { enabled = false }` to opt out.

Inherited include and exclude filters are conventions, not merged base lists. If a module defines its own `include(...)`,
that module's include list replaces the inherited include list; `exclude(...)` works the same way independently. For
example, if the root has:

```kotlin
frmtr {
    java {
        include("**/api/**/*.java")
        exclude("**/generated/**")
    }
}
```

and a module has:

```kotlin
frmtr {
    java {
        include("**/service/**/*.java")
        exclude("**/legacy/**")
    }
}
```

then that module checks only `**/service/**/*.java` and excludes only `**/legacy/**`. To keep both root and module
filters, repeat both:

```kotlin
frmtr {
    java {
        include("**/api/**/*.java", "**/service/**/*.java")
        exclude("**/generated/**", "**/legacy/**")
    }
}
```

Explicit module application is idempotent when the subproject hook also applies frmtr, and useful when module-specific
Kotlin DSL configuration needs type-safe accessors. The module `frmtr {}` block overrides inherited root conventions:

```kotlin
plugins {
    java
    id("dev.lanwen.frmtr") version "0.2.1"
}

frmtr {
    java {
        exclude("**/generated/**")
    }
}
```

Optional configuration narrows Java source-set selection and check output:

```kotlin
import dev.lanwen.frmtr.gradle.FrmtrJavaLanguageLevel

frmtr {
    enabled = true
    java {
        include("**/api/**/*.java", "**/service/**/*.java")
        exclude("**/generated/**", "**/legacy/**")
        languageLevel = FrmtrJavaLanguageLevel.LATEST_AVAILABLE
    }
    check {
        print {
            diffs = true
        }
    }
}
```

Java source include and exclude patterns use Gradle source-set filtering, so paths are relative to Java source roots.
Java source files under the Gradle build directory are excluded by default. The Gradle parser language level defaults to `AUTO`, which uses the Java toolchain first, then `sourceCompatibility`, and otherwise falls back to `LATEST_AVAILABLE`. Set `LATEST_AVAILABLE` to ignore the Gradle project target and use JavaParser's bleeding-edge parser mode, or `UNDEFINED` for JavaParser raw mode. Check output prints changed and failed files; unified diffs for changed files are enabled by default and label sides as `origin` and `frmtr`. When files fail, Gradle renders outlined failure blocks with JavaParser source context when available before failing the task.

To format this checkout with the current formatter implementation, use the root CLI wrapper tasks:

```bash
./gradlew frmtrSelfCheck
./gradlew frmtrSelfFormat
```

These tasks run `:frmtr-cli` from the current checkout in a single Gradle invocation. They dogfood the formatter engine, tooling runner, and CLI over this checkout while excluding `frmtr-core/src/test/resources/format`; `frmtrSelfCheck` prints unified diffs for changed files, and `frmtrSelfFormat` uses `--write --verify` so self-formatting refuses non-equivalent rewrites. Gradle plugin behavior stays covered by `:frmtr-gradle-plugin` functional tests.

Review the produced Java diff and run tests before committing it; the formatter source contains embedded Java fixtures, so self-formatting can expose formatter bugs rather than producing a purely mechanical style diff.

## Site

The onboarding site is a JBake static site under `site/src/jbake`.

```bash
./gradlew :site:bake
```

The generated GitHub Pages artifact is written to `site/build/jbake`. The root `siteBuild` task is a convenience alias
for the same bake task.

## CLI

Run the CLI from this checkout with `./gradlew :frmtr-cli:run --args='...'`. When using a standalone binary, replace
that prefix with `frmtr`.

### CLI Recipes

Check all Java files under the current directory. With no selectors, the CLI checks `./**/*.java`:

```bash
./gradlew :frmtr-cli:run
```

Read Java source from stdin and write formatted source to stdout:

```bash
./gradlew :frmtr-cli:run --args='--stdin' < Example.java
```

Format selectors in place:

```bash
./gradlew :frmtr-cli:run --args='--write "src/**/*.java,examples/*.java"'
```

Format selectors in place with the write-time AST-equivalence safety valve:

```bash
./gradlew :frmtr-cli:run --args='--write --verify "src/**/*.java,examples/*.java"'
```

Assert AST-equivalence read-only — format each file in memory and verify, writing nothing (exit 3 on a violation):

```bash
./gradlew :frmtr-cli:run --args='--check --verify "src/**/*.java,examples/*.java"'
```

Exclude generated or fixture sources from a broad selector:

```bash
./gradlew :frmtr-cli:run --args='--check --exclude "src/generated,fixtures/**/*.java" .'
```

### File Selection

- Selectors and `--exclude` patterns can be repeated or comma-separated.
- Selectors can be files, directories, or glob patterns.
- Directory excludes apply recursively.
- The CLI formats `.java` files, skips unknown extensions silently, and respects `.gitignore`.
- Missing explicit `.java` file selectors are reported as tool errors.
- Empty glob or directory matches report that no Java files matched without failing the run.

### Run Modes

| Mode | Behavior |
| --- | --- |
| `--check` | Checks formatting without changing files. This is the default mode. |
| `--write` | Formats files in place and prints a processed summary. |
| `--write --verify` | Like `--write`, but re-parses each formatted file and refuses to overwrite it when the result is not AST-equivalent to the input. |
| `--check --verify` | Like `--check`, but also re-parses each in-memory formatted result and asserts AST-equivalence. Read-only: it reports would-change and writes nothing, exiting 3 if any file's output is not AST-equivalent. Additionally emits informational stderr warnings for breakable output lines that still exceed the configured line width (does not affect the exit code). |
| `<selector>` (no `--check`/`--write`) | Prints formatted output to stdout without changing files. With multiple matched files, each is preceded by a `==> path <==` header (stdout aggregate mode). |
| `--stdin` | Reads Java source from stdin and writes formatted source to stdout. |
| `--stdin --check` | Compares piped source against formatter output. |
| `--stdin --diff` | Prints a unified diff between piped source and formatter output. |
| `--render-indentation` | Visualizes the leading indentation of printed source so you can see how far and why each line is indented: block indents show the columns they add as middle-dots (`·`), continuation indents (broken chains, assignment/return wraps) show a vertical ellipsis (`⋮`) plus dots. A display aid for printed source only (default print or `--stdin`); the formatting is unchanged and it cannot combine with `--write`, `--check`, `--diff`, `--render-line-width`, or `--explain`. |

For multi-file runs, `--check` and `--write` continue after formatter failures and render outlined diagnostics with
line-numbered JavaParser source context when available.

`--verify` is an opt-in safety valve, off by default and valid with `--write` or `--check`; the CLI rejects it in stdin,
explain, and print modes (and standalone, without `--write` or `--check`). When enabled, each file's formatted output is
re-parsed and compared structurally to the input. Under `--write`, a non-AST-equivalent result leaves that file
untouched and reports it as a failure instead of overwriting it. Under `--check`, formatting happens in memory only:
nothing is ever written, the file is reported as would-change like a normal check, and a non-AST-equivalent result is a
verify violation (exit 3). Verification doubles parse cost, which is why it stays off by default. The Gradle plugin does
not yet have an equivalent flag.

`--check --verify` also scans each file's formatted output and prints informational warnings to stderr for lines that
both exceed the configured line width and still contain a breakable construct the formatter could have split further
(binary/ternary operators, comma-heavy argument lists, fluent call chains, multi-type `throws` clauses, casts, or
lambdas). Operators that live inside string, char, or text-block literals or comments are masked first, so prose
comments and atomic over-long literals never warn. These warnings are advisory only — stdout (status lines and diffs)
stays machine-readable, and the warnings never change the exit code.

### Exit Codes

| Code | Meaning |
| --- | --- |
| `0` | Success: all files clean / written / verified, or no files matched. |
| `1` | Would change (check modes only): files need formatting, with no failures. |
| `2` | Parse failure, IO error, or usage/config error: the run could not be completed. |
| `3` | Verify violation: a cleanly-parsed file's formatted output was not AST-equivalent to the input (or did not re-parse) — a formatter bug. |

When a run produces a mix of outcomes, the highest-severity code wins: `3 > 2 > 1 > 0`. Usage and configuration errors
stay `2`; there is no separate usage code. The breakable over-width-line warnings emitted under `--check --verify` are
purely informational and never change the exit code.

### Check Output

Check mode prints one marker per processed file, followed by a concise summary:

| Marker | Meaning |
| --- | --- |
| `✓` | The file is already formatted. |
| `✗` | The file needs formatting. |
| `!` | The file failed to parse or could not be read. |

Failure diagnostics are printed immediately after the failed file's `!` status line, so they stay grouped with that file
when `--diff` output is present.

`--write` ends with a processed summary that counts files formatted, failed, ignored by `.gitignore`, and excluded by
`--exclude`.

### Diffs and Rendering

- `--diff` renders unified diffs for files marked `✗`, with `origin` and `frmtr` side labels.
- `--render-line-width` prints terminal-only diff output with a dotted width guide near the configured line width.
- `--render-indentation` prints formatted source with each line's leading indentation visualized so you can see how far
  and _why_ each line is indented, distinguishing the two kinds of indentation plain text renders identically. A
  **block** indent (opening a new brace-delimited body) shows only the columns it _adds_ over the line above as
  middle-dots (`·`); the indentation it shares with that line stays blank, and a dedent renders as plain spaces. A
  **continuation** indent (a wrap aligned to a logical parent — a broken method-chain selector, an assignment or return
  continuation) shows a vertical ellipsis (`⋮`) at the enclosing statement's indent followed by dots for the rest of its
  offset, on every continuation line. It applies only when source is printed (default print mode or `--stdin`), never
  rewrites files, and is off by default so plain output is byte-for-byte the formatter result. It replaces only leading
  whitespace — mid-line spaces and whitespace inside string literals are untouched — so the substitution shifts no
  columns. (Interior lines of a multi-line text block carry literal indentation, not layout, so their leading whitespace
  is shown as uniform dots and left out of the block/continuation scheme.)
- `--color=auto|always|never` controls ANSI coloring for status markers and diff output; formatted source output stays
  plain.

### Progress and Streams

Live progress includes a counter line and, while files are active, one active display path:

```text
Processed [240/823 files, 7 would change, 0 failed].
(⠋) src/generated/Huge.java
```

Multi-file check and write progress is rendered to stderr as an in-place status when `--progress=auto` detects a console
or `--progress=always` is set. Traversal starts with `Discovering Java files...`, then processing starts at
`Processed [0/N files, 0 would change, 0 failed].` for check mode or
`Processed [0/N files, 0 formatted, 0 failed].` for write mode.

Use `--progress=never` to keep stderr append-only for logs and scripts. stdout remains reserved for status, diffs,
formatted source, and final summaries.

### Diagnostics and Configuration

`--explain` helps you understand *why* a file is laid out the way it is. Run it on one file or `--stdin`; it prints the
formatted result plus a "why it wrapped" report with readable construct names, the real
`flat width N > W available` arithmetic behind each break, and a pruned decision tree of the rules involved.

Add `-v`/`--verbose` for raw rule labels and every group in the tree. `--explain` is its own mode, cannot be combined
with `--check`, `--write`, or `--diff`, and never changes the formatted output; it only observes the render.

Use `--stacktrace` when debugging formatter or I/O failures.

Use `--java-level` to choose the parser language level. The default is `LATEST_AVAILABLE`, which uses JavaParser's
bleeding-edge parser mode. Use `UNSET` for JavaParser raw mode, or a release value such as `17`, `JAVA_21`, or
`JAVA_25` when you need a strict release gate.

Use `--parse-error-behavior` to control how the formatter handles JavaParser parse problems. The default is `RECOVER`,
which formats the valid parts of a partially-parseable file while preserving the broken regions; use `FAIL` for strict,
all-or-nothing behavior that aborts as soon as JavaParser reports any parse problem.

The formatter-wide default line width is 120 columns. Use `--line-width` in the CLI or
`frmtr { java { lineWidth = ... } }` in Gradle to override it.

The formatter-wide default indentation is four spaces. Use `--indent-width` in the CLI to choose a different number of
spaces per indentation level.

## Native Binary

Install the published native CLI through the [Homebrew tap](https://github.com/lanwen/homebrew-tap):

```bash
brew install lanwen/tap/frmtr
```

Platform archives are also attached to the [latest GitHub release](https://github.com/lanwen/frmtr/releases/latest).

The default native binary build is Linux via Docker:

```bash
docker build -f Dockerfile.native --output type=local,dest=build/native-linux .
```

On Linux, run the exported binary directly:

```bash
./build/native-linux/frmtr --help
```

On macOS, Docker still produces a Linux binary. Copy it to a Linux host or verify it inside a Linux container. To build a local macOS binary, use SDKMAN-managed GraalVM 25:

```bash
sdk env install
sdk env use
./gradlew :frmtr-cli:nativeCompile
./frmtr-cli/build/native/nativeCompile/frmtr --help
```

`nativeCompile` invokes `native-image` with a native-image-capable Java 25 launcher while the CLI classes on its
classpath remain Java 21 bytecode.

## License

`frmtr` is released under the MIT License. See [LICENSE](LICENSE).
