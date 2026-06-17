# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Open-source community files: GitHub issue/PR templates.
- Gradle plugin root-project registration for Java subprojects, inherited module configuration, and module-level
  `frmtr { enabled = false }` opt-out.
- Gradle check build caching and incremental source selection for `frmtrJavaCheck`.

### Changed

- Published JVM runtime artifacts now target Java 21 while native CLI builds continue to use GraalVM/JDK 25.

[Unreleased]: https://github.com/lanwen/frmtr/commits/main
