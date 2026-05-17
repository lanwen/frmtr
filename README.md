# frmtr

`frmtr` is a fast, opinionated Java formatter built on JavaParser.

The formatter parses Java source, adapts the parsed tree into formatter-owned syntax views, prints a compact document IR, and renders that IR with width-aware line breaking.

```bash
./gradlew test
./gradlew run --args='--check src/main/java'
```
