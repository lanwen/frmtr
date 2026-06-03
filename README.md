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
        languageLevel.set(FrmtrJavaLanguageLevel.JAVA_21)
    }
    check {
        print {
            diffs.set(true)
        }
    }
}
```

Java source files under the Gradle build directory are excluded by default. Check output prints changed and failed files; unified diffs for changed files are enabled by default.

## CLI

Format stdin to stdout:

```bash
./gradlew :frmtr-cli:run --args='' < Example.java
```

Format selectors in place:

```bash
./gradlew :frmtr-cli:run --args='--write "src/**/*.java,examples/*.java"'
```

Selectors can be repeated, comma-separated, files, directories, or glob patterns. The CLI formats `.java` files, skips unknown extensions silently, and respects `.gitignore`.

`--check` prints `✓` for files that are already formatted, `✗` for files that need formatting, and `!` for files that failed to parse or could not be read. Add `--diff` to render unified diffs for files marked `✗`. Failure output is concise by default; add `--stacktrace` when debugging formatter or I/O failures.

Use `--java-level` to choose the parser language level. The default is `LATEST_AVAILABLE`, which uses the newest stable level exposed by the bundled JavaParser dependency. Use `UNSET` for JavaParser raw mode, or a release value such as `17`, `JAVA_21`, or `JAVA_25`.

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
