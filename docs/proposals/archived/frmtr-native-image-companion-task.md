> **Status: Implemented.** Landed as the `frmtr-native-image-support` module (`JavaParserReflectionFeature`), wired into `frmtr-cli` native build/test only and recorded in `docs/adr/0001-use-native-image-companion-module-for-javaparser-reflection.md`. Archived 2026-07-14; retained as a provenance record.

# Implement GraalVM native-image companion module for JavaParser reflection

## Background

The `frmtr` native binary currently fails in JavaParser validation with:

```text
java.lang.NoSuchFieldError: variables
    at com.github.javaparser.metamodel.PropertyMetaModel.getValue(PropertyMetaModel.java:263)
    at com.github.javaparser.ast.validator.language_level_validations.chunks.CommonValidators.lambda$new$7(CommonValidators.java:78)
```

Root cause: JavaParser's language validators use metamodel-driven reflection over AST node fields. On the JVM, private fields such as `FieldDeclaration.variables` and `VariableDeclarationExpr.variables` are visible through normal reflection. In a GraalVM native image, they are not visible unless registered through reachability metadata or a native-image Feature.

The CLI has been updated separately to report this as an internal formatter error without dumping a stacktrace by default. This task is the real compatibility fix so the native binary formats JavaParser-validated source instead of reporting the internal failure.

## Decisions Already Made

- Use a dedicated native-image companion module.
- The companion module is build-time support only. It belongs on native-image build paths and must not be part of the normal formatter runtime or Gradle plugin dependency graph.
- Prefer a GraalVM `Feature` over static JSON reachability metadata because the registration can be derived from JavaParser's own metamodel and is less likely to drift when JavaParser upgrades.
- Register all declared fields for every JavaParser AST node type exposed by `JavaParserMetaModel.getNodeMetaModels()`.
- Add two layers of tests:
  - Fast JVM tests in the companion module for JavaParser metamodel coverage and known-risk fields such as `variables`.
  - Explicit native compatibility coverage in the CLI for a Java sample containing a field declaration plus modern switch/yield syntax.
- Native compatibility coverage should be explicit/native-only, not part of the default JVM `check` lifecycle.

These decisions are documented in:

- `CONTEXT.md` terms: `Native-image companion module`, `Native compatibility fixture`
- `docs/adr/0001-use-native-image-companion-module-for-javaparser-reflection.md`

## Implementation Scope

1. Add a new Gradle module, likely `frmtr-native-image-support`.
2. Implement a GraalVM Feature, likely `dev.lanwen.frmtr.nativeimage.JavaParserReflectionFeature`.
3. In the Feature, iterate `JavaParserMetaModel.getNodeMetaModels()` and register every declared field for each node type with GraalVM's hosted reflection API.
4. Wire the module into `frmtr-cli` only for native-image build/test tasks. Do not add it as a normal `implementation` dependency.
5. Pass the Feature to both the main native image and native test image.
6. Add JVM tests for the companion module that prove coverage of all JavaParser metamodel node types and known reflective fields.
7. Add native CLI compatibility coverage with a fixture that would previously hit `NoSuchFieldError: variables`.
8. Update `ARCHITECTURE.md` in the same change because this affects build shape, module layout, native binary behavior, and testing strategy.

## Acceptance Criteria

- Normal JVM runtime classpaths for `frmtr-cli`, `frmtr-tooling`, `frmtr-core`, and `frmtr-gradle-plugin` do not include the native-image companion module or GraalVM hosted APIs.
- `./gradlew test` passes.
- `./gradlew :frmtr-cli:nativeTest` or the chosen explicit native compatibility task passes with GraalVM.
- `./gradlew :frmtr-cli:nativeCompile` succeeds with the SDKMAN GraalVM JDK.
- The rebuilt native binary no longer reports `NoSuchFieldError: variables` for a Java sample with fields and switch/yield syntax.
- `ARCHITECTURE.md` documents the new module and native-image testing shape.

## Useful Commands

```bash
./gradlew test

source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk use java 25.0.3-graal
./gradlew :frmtr-cli:nativeTest
./gradlew :frmtr-cli:nativeCompile

./frmtr-cli/build/native/nativeCompile/frmtr --check path/to/fixture.java
sem diff --format json --from origin/main --to HEAD
```

## Constraints

- Use AssertJ for assertions.
- Keep changes scoped to the native-image compatibility work.
- Do not make the companion module a default runtime dependency.
- In PR descriptions, omit routine verification such as tests, formatting, linting, or diff checks unless there is a meaningful non-routine result.
