# frmtr

`frmtr` is a fast, opinionated Java formatter built on JavaParser.

The formatter parses Java source, adapts the parsed tree into formatter-owned syntax views, prints a compact document IR, and renders that IR with width-aware line breaking.

```bash
./gradlew build
./gradlew :frmtr-cli:run --args='--check frmtr-core/src/main/java'
```

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
