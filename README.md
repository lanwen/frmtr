# frmtr

`frmtr` is a fast, opinionated Java formatter built on JavaParser.

The formatter parses Java source, adapts the parsed tree into formatter-owned syntax views, prints a compact document IR, and renders that IR with width-aware line breaking.

```bash
./gradlew build
./gradlew :frmtr-cli:run --args='--check frmtr-core/src/main/java'
```

## Gradle Plugin

Apply the Gradle plugin to a Java project to check formatting during `check` and format source files on demand:

```kotlin
plugins {
    java
    id("dev.lanwen.frmtr")
}
```

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

Optional configuration narrows Java source-set selection and check output:

```kotlin
import dev.lanwen.frmtr.gradle.FrmtrJavaLanguageLevel

frmtr {
    java {
        include("**/*.java")
        exclude("**/generated/**")
        lineWidth.set(140)
        languageLevel.set(FrmtrJavaLanguageLevel.LATEST_AVAILABLE)
    }
    check {
        print {
            diffs.set(true)
        }
    }
}
```

Java source files under the Gradle build directory are excluded by default. The Gradle parser language level defaults to `AUTO`, which uses the Java toolchain first, then `sourceCompatibility`, and otherwise falls back to `LATEST_AVAILABLE`. Set `LATEST_AVAILABLE` to ignore the Gradle project target and use JavaParser's bleeding-edge parser mode, or `UNDEFINED` for JavaParser raw mode. Check output prints changed and failed files; unified diffs for changed files are enabled by default. When files fail, Gradle renders outlined failure blocks with JavaParser source context when available before failing the task.

## CLI

Check all Java files under the current directory:

```bash
./gradlew :frmtr-cli:run
```

Format stdin to stdout:

```bash
./gradlew :frmtr-cli:run --args='--stdin' < Example.java
```

Format selectors in place:

```bash
./gradlew :frmtr-cli:run --args='--write "src/**/*.java,examples/*.java"'
```

Selectors can be repeated, comma-separated, files, directories, or glob patterns. The CLI formats `.java` files, skips unknown extensions silently, and respects `.gitignore`. Missing explicit `.java` file selectors are reported as tool errors; empty glob or directory matches report that no Java files matched without failing the run.

With no selectors, the CLI uses `./**/*.java` and checks formatting by default. Pass `--stdin` to read Java source from stdin and write formatted source to stdout, or combine `--stdin` with `--check` or `--diff` to compare piped source against formatter output.

`--check` prints `✓` for files that are already formatted, `✗` for files that need formatting, and `!` for files that failed to parse or could not be read, followed by a concise summary. Check-mode failure diagnostics are printed immediately after the failed file's `!` status line, so they stay grouped with that file when `--diff` output is present. `--write` ends with a processed summary that counts files formatted, failed, and ignored by `.gitignore`. For multi-file runs, `--check` and `--write` continue after formatter failures and render outlined diagnostics with line-numbered JavaParser source context when available. Add `--diff` to render unified diffs for files marked `✗`, or `--stacktrace` when debugging formatter or I/O failures.

Use `--java-level` to choose the parser language level. The default is `LATEST_AVAILABLE`, which uses JavaParser's bleeding-edge parser mode. Use `UNSET` for JavaParser raw mode, or a release value such as `17`, `JAVA_21`, or `JAVA_25` when you need a strict release gate.

The formatter-wide default line width is 140 columns. Use `--line-width` in the CLI or `frmtr { java { lineWidth.set(...) } }` in Gradle to override it.

## Native Binary

The default native binary build is Linux via Docker:

```bash
docker build -f Dockerfile.native --output type=local,dest=build/native-linux .
```

On Linux, run the exported binary directly:

```bash
./build/native-linux/frmtr --help
```

On macOS, Docker still produces a Linux binary. Copy it to a Linux host or verify it inside a Linux container. To build a local macOS binary, use SDKMAN-managed GraalVM:

```bash
sdk env install
sdk env use
./gradlew :frmtr-cli:nativeCompile
./frmtr-cli/build/native/nativeCompile/frmtr --help
```
