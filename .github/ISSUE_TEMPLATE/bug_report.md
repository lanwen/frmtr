---
name: Bug report
about: Report incorrect or unexpected formatter behavior
title: "[Bug]: "
labels: needs-triage
assignees: ''
---

## Description

A clear and concise description of what the bug is.

## Input

The Java source (or minimal snippet) that triggers the problem. A small, self-contained reproducer is ideal.

```java
// paste the input here
```

## Command

How you invoked `frmtr` (CLI flags, Gradle task, or plugin configuration).

```bash
# e.g. ./gradlew :frmtr-cli:run --args='--check Example.java'
```

## Expected output

What you expected the formatted output to look like.

## Actual output

What `frmtr` actually produced. Include diffs or `--explain` output if helpful.

## Environment

- `frmtr` version / commit:
- Java version (`java -version`):
- OS:
- Invocation: CLI / Gradle plugin / self-format task

## Additional context

Add any other context, stack traces (`--stacktrace`), or screenshots here.
