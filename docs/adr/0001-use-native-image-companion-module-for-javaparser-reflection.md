# Use a Native-Image Companion Module for JavaParser Reflection

**Status:** Accepted

## Context

frmtr's native executable needs GraalVM reflection registration for JavaParser internals because JavaParser validates parsed trees through metamodel-driven reflective access to AST node fields.

## Decision

We will use a dedicated native-image companion module that contributes a GraalVM Feature for this registration and is included only on native-image build paths, not in the normal formatter runtime or build integration dependency graph.

The Feature will register all declared fields for every JavaParser AST node type exposed by `JavaParserMetaModel.getNodeMetaModels()`. The companion module must be guarded by fast JVM tests for its JavaParser metamodel coverage and by explicit native compatibility coverage in the CLI so JavaParser upgrades cannot miss native-image reflection failures. Native compatibility coverage belongs in native-image CI and release workflows rather than the default JVM `check` lifecycle.

Implementation uses `:frmtr-native-image-support` and `dev.lanwen.frmtr.nativeimage.JavaParserReflectionFeature`. `:frmtr-cli` wires that module through `nativeImageCompileOnly` and `nativeImageTestCompileOnly`; it must not be added to normal `implementation` dependencies.

## Consequences

This keeps GraalVM hosted APIs out of ordinary frmtr modules while avoiding a large static reachability metadata file that can drift when JavaParser adds or changes AST node types.
