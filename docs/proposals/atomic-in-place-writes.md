# Atomic In-Place Writes for `--write` / `frmtrFormat`

**Status:** Implemented (roadmap S6) — atomic temp-file + rename write-out in `BestEffortAtomicFileWriter`, used by `FormatterRunner.writeFile`; see [Outcome](#outcome) · Category: correctness / data-loss · Effort: S · Risk: LOW
**Planned at:** commit `1237c475`, 2026-06-20

> **Executor instructions**: Follow this plan step by step. Run every verification command and confirm the expected
> result before moving on. If anything under "STOP conditions" occurs, stop and report — do not improvise.
>
> **Drift check (run first)**: `git diff --stat 1237c475..HEAD -- frmtr-tooling/src/main/java/dev/lanwen/frmtr/tooling/FormatterRunner.java`
> If `FormatterRunner.java` changed since `1237c475`, compare the "Current state" excerpt below against the live code
> before proceeding; on a meaningful mismatch (the `writeFile` body no longer matches), treat it as a STOP condition.

## Why this matters

`frmtr --write` and `frmtrFormat` overwrite the user's `.java` source files in place. The write is **not atomic**:
`FormatterRunner.writeFile` calls `Files.writeString(file, formatted, UTF_8)`, whose default open options are
`CREATE, TRUNCATE_EXISTING, WRITE` — it truncates the file to zero length and *then* streams the formatted text. If the
process is killed (`SIGTERM`, `SIGKILL`, IDE/CI cancel), the machine loses power, or the disk fills (`ENOSPC`) **between
the truncate and the completed write**, the source file is left truncated or empty, with no backup. For a tool whose one
job is to rewrite source, silently destroying a source file is the worst possible failure.

The code already anticipates partial writes — there is a dedicated `FormatFileStatus.WRITTEN_PARTIALLY` for "writing
failed; the file may have been partially updated" — but nothing prevents the data loss; it only labels it after the
fact. The standard fix (write a sibling temp file, then atomically rename it over the original) makes a write
all-or-nothing: either the old complete content or the new complete content is on disk, never a truncated mix. This is
the same technique `gofmt` and most formatters use.

The M3 parallelism work made the runner write multiple files concurrently on a worker pool
(`README.md` M3: "`frmtrJavaFormat` ... mutates source files in place"), so the in-place truncate now runs on several
files at once — the same risk, wider.

## Current state

- `frmtr-tooling/src/main/java/dev/lanwen/frmtr/tooling/FormatterRunner.java` — the file-oriented check/write runner.
  `write(...)` (lines 63-78) fans files out across a bounded worker pool via `formatSelectedFiles` (lines 80-98); each
  file is handled by `writeFile`. The data-loss site is the bare `Files.writeString` at **line 274**:

```java
private static FormatFileResult writeFile(Path displayRoot, Path file, Supplier<FrmtrSession> formatter) {
    Path displayPath = displayPath(displayRoot, file);
    try {
        String original = Files.readString(file, StandardCharsets.UTF_8);
        String formatted = formatter.get().format(original);
        if (formatted.equals(original)) {
            return new FormatFileResult(file, displayPath, FormatFileStatus.UNCHANGED, "", null);
        }
        try {
            Files.writeString(file, formatted, StandardCharsets.UTF_8);   // <-- TRUNCATE_EXISTING then write: non-atomic
        } catch (IOException exception) {
            return new FormatFileResult(file, displayPath, FormatFileStatus.WRITTEN_PARTIALLY, "", exception);
        }
        return new FormatFileResult(file, displayPath, FormatFileStatus.WRITTEN, "", null);
    } catch (FormatterException | IOException exception) {
        return new FormatFileResult(file, displayPath, FormatFileStatus.FAILED, "", exception);
    }
}
```

- Imports already present in the file include `java.nio.file.Files`, `java.nio.file.Path`,
  `java.nio.charset.StandardCharsets`, `java.io.IOException`. You will add `java.nio.file.StandardCopyOption` and a few
  `java.nio.file.attribute.*` types.
- Existing write tests live in `frmtr-tooling/src/test/java/dev/lanwen/frmtr/tooling/FormatterRunnerTest.java`:
  - `writesChangedFilesAndContinuesAfterFailures` (`@TempDir`) — formats `Changed.java`, leaves `Broken.java` failing.
  - `reportsPartialWriteWhenChangedFileCannotBeWritten` — marks a **read-only file** writable=false and asserts
    `WRITTEN_PARTIALLY`. **This test's premise changes** with atomic writes (see Step 4) and must be updated.
  - Helper `write(Path, String)` at the bottom writes fixture files with `Files.writeString`.

- Repo conventions: assertions use **AssertJ** (`org.assertj.core.api.Assertions.assertThat`); tests are JUnit 5 with
  `@org.junit.jupiter.api.io.TempDir`. Match the style already in `FormatterRunnerTest.java`. The `:frmtr-tooling`
  module has no dependency on JavaParser; do not add one.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Compile + test the module | `./gradlew :frmtr-tooling:test` | `BUILD SUCCESSFUL`, exit 0 |
| Full suite (before any push) | `./gradlew test` | `BUILD SUCCESSFUL`, exit 0 |
| Confirm no other module references the write path | `grep -rn "FormatterRunner.write" frmtr-cli frmtr-gradle-plugin` | only adapter call sites |

## Scope

**In scope** (the only files you should modify):
- `frmtr-tooling/src/main/java/dev/lanwen/frmtr/tooling/FormatterRunner.java`
- `frmtr-tooling/src/test/java/dev/lanwen/frmtr/tooling/FormatterRunnerTest.java`

**Out of scope** (do NOT touch):
- `FormatFileStatus.java` — keep the enum as-is. `WRITTEN_PARTIALLY` stays valid; with atomic moves it now means "the
  replace step failed and the original is intact" rather than "half-written." Repurposing or renaming it is a separate
  change with CLI/Gradle status-marker ripple.
- `frmtr-cli` / `frmtr-gradle-plugin` — adapters consume `FormatFileResult`; no adapter change is needed.
- The `check(...)` read-only path — it never writes.

## Steps

### Step 1: Add a private atomic-write helper to `FormatterRunner`

Add a `static void writeAtomically(Path file, String contents) throws IOException` helper that:

1. Resolves the real target so a symlinked `.java` is rewritten through to its target (preserving today's
   write-through-symlink behavior): `Path target = Files.exists(file) ? file.toRealPath() : file;`
2. Creates a temp file **in the same directory as `target`** (so the later move is a same-filesystem rename, which is
   what makes `ATOMIC_MOVE` possible): `Path dir = target.getParent();`
   `Path tmp = Files.createTempFile(dir, target.getFileName().toString(), ".frmtr.tmp");`
3. In a `try` that deletes `tmp` on **any** failure (`Files.deleteIfExists(tmp)` in a `catch`/rethrow):
   - Write bytes: `Files.writeString(tmp, contents, StandardCharsets.UTF_8);`
   - **Preserve POSIX mode** when the filesystem supports it, so the formatted file keeps the original's permissions
     (a fresh temp file is created `rw-------`): if
     `Files.getFileStore(target).supportsFileAttributeView(PosixFileAttributeView.class)` and `Files.exists(target)`,
     copy `Files.getPosixFilePermissions(target)` onto `tmp` via `Files.setPosixFilePermissions(tmp, perms)`. Skip
     silently on non-POSIX filesystems (Windows).
   - Move into place, preferring atomicity, falling back when the platform/filesystem cannot do an atomic rename:
     ```java
     try {
         Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
     } catch (AtomicMoveNotSupportedException unsupported) {
         Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
     }
     ```

Add a method-level Javadoc in the same spirit as the rest of the module: state that this helper owns crash-safe
replacement (temp-in-same-dir + atomic rename), why (an interrupted in-place write would truncate user source), and what
it intentionally leaves to the caller (status mapping, threading). New imports needed:
`java.nio.file.StandardCopyOption`, `java.nio.file.AtomicMoveNotSupportedException`,
`java.nio.file.attribute.PosixFileAttributeView`, `java.nio.file.attribute.PosixFilePermission`, `java.util.Set`.

**Verify**: `./gradlew :frmtr-tooling:compileJava` → `BUILD SUCCESSFUL`.

### Step 2: Call the helper from `writeFile`

Replace the bare `Files.writeString(file, formatted, StandardCharsets.UTF_8);` at line 274 with
`writeAtomically(file, formatted);`. Keep the surrounding `try`/`catch (IOException)` → `WRITTEN_PARTIALLY` mapping
exactly as-is: if the temp write or the move fails, the helper has already deleted the temp and (because the move is the
last step and is atomic) the original is untouched, so `WRITTEN_PARTIALLY` now correctly means "not written; original
intact." Do not change the outer `catch (FormatterException | IOException)` → `FAILED` arm.

**Verify**: `./gradlew :frmtr-tooling:compileJava` → `BUILD SUCCESSFUL`.

### Step 3: Add a regression test proving the original survives a failed replace and no temp leaks

In `FormatterRunnerTest.java`, add a `@TempDir` test `keepsOriginalIntactAndLeavesNoTempWhenReplaceFails`:

- Write a changed source file inside a subdirectory: `Path dir = root.resolve("src"); ... Path changed =
  write(dir.resolve("Changed.java"), "class Changed{int value;}");` and record `String before =
  Files.readString(changed, UTF_8);`.
- Force the replace to fail without corrupting the original by making the **parent directory** non-writable
  (`assertThat(dir.toFile().setWritable(false)).isTrue();`) inside a `try`/`finally` that restores writability.
- Run `FormatterRunner.write(root, List.of(changed), FormatterOptions.defaults(), FormatRunProgress...)` — use the same
  `FormatRunProgress` argument the other tests in this file pass (copy their call shape exactly; the runner's `write`
  signature takes a progress argument).
- Assert: the single result status is `WRITTEN_PARTIALLY` **or** `FAILED` (accept either — the point is it did not
  silently succeed); `Files.readString(changed, UTF_8)` still equals `before` (original intact); and no leftover temp
  remains: `try (var entries = Files.list(dir)) { assertThat(entries).noneMatch(p -> p.getFileName().toString().endsWith(".frmtr.tmp")); }`.

> If a non-writable parent directory does not make the move fail in the executor's environment (some platforms/roots let
> root or the test user bypass the bit), switch the failure injection to a read-only file inside a read-only directory,
> or use a temp file whose name collides such that the move target's directory is unwritable. The assertions
> (original intact + no temp leak) stay the same.

**Verify**: `./gradlew :frmtr-tooling:test --tests '*FormatterRunnerTest'` → all pass including the new test.

### Step 4: Update the now-stale read-only-file test

`reportsPartialWriteWhenChangedFileCannotBeWritten` marks a **file** read-only and expects `WRITTEN_PARTIALLY`. With
temp-in-same-dir + replace, a read-only *file* in a *writable* directory can now be replaced (the rename does not need
the old file to be writable), so the file may legitimately become `WRITTEN`. Update this test so its expectation matches
the atomic-write contract:

- Either re-point it at a read-only *directory* (so the replace genuinely cannot happen → `WRITTEN_PARTIALLY`/`FAILED`,
  original intact), folding it into / aligning it with the Step 3 test; or
- Keep the read-only-file setup but assert the new, correct outcome (`WRITTEN`, file content equals the formatted
  output) and rename the test to reflect that a read-only file in a writable directory is now reformatted.

Pick the first option (read-only directory) so the suite still covers the "cannot write" path. Leave a one-line comment
explaining the behavior change.

**Verify**: `./gradlew :frmtr-tooling:test` → `BUILD SUCCESSFUL`.

### Step 5: Update docs

Add one line to `ARCHITECTURE.md` in the "File-Oriented Runs" section noting that `FormatterRunner.write` replaces files
atomically (temp file in the same directory + atomic rename, POSIX mode preserved) so an interrupted write cannot
truncate source. Keep it to the existing prose style. (AGENTS.md requires architecture updates in the same change as the
behavior they describe.)

**Verify**: `grep -n "atomic" ARCHITECTURE.md` → matches the new sentence.

## Test plan

- New test: `keepsOriginalIntactAndLeavesNoTempWhenReplaceFails` (Step 3) — proves the failure path preserves the
  original and leaks no temp file. This is the core regression for the data-loss bug.
- Updated test: `reportsPartialWriteWhenChangedFileCannotBeWritten` (Step 4) — re-aligned to the atomic contract.
- Keep `writesChangedFilesAndContinuesAfterFailures` passing unchanged (proves the happy path still writes correct
  content and still continues past a failing file).
- Model new tests on the existing `@TempDir` tests in the same file; use AssertJ.

## Done criteria

ALL must hold:

- [ ] `./gradlew :frmtr-tooling:test` exits 0 with the new test present and passing.
- [ ] `./gradlew test` exits 0 (full suite green).
- [ ] `grep -n "Files.writeString(file" frmtr-tooling/src/main/java/dev/lanwen/frmtr/tooling/FormatterRunner.java`
      returns nothing (the direct in-place write is gone; writes go through `writeAtomically`).
- [ ] `grep -n "ATOMIC_MOVE" frmtr-tooling/src/main/java/dev/lanwen/frmtr/tooling/FormatterRunner.java` matches.
- [ ] `ARCHITECTURE.md` mentions atomic write replacement.
- [ ] `git status` shows only the two in-scope source/test files and `ARCHITECTURE.md` modified.

## STOP conditions

Stop and report (do not improvise) if:

- `FormatterRunner.writeFile` no longer matches the "Current state" excerpt (drift since `1237c475`).
- The full suite fails for a reason that looks related to write ordering or worker threading (the move must stay
  per-file; do not introduce shared state across workers).
- Preserving POSIX permissions requires platform-specific code beyond `PosixFileAttributeView` detection, or the target
  filesystem semantics make a same-directory atomic rename impossible — report what you found rather than weakening the
  atomicity guarantee.

## Maintenance notes

- A reviewer should confirm: temp files are created in the **same directory** as the target (cross-directory moves are
  not atomic and may even fail across mount points), the temp is deleted on every failure branch, and POSIX permissions
  are copied before the move (otherwise reformatted files silently become `rw-------`).
- Symlinked `.java` inputs: this plan resolves `toRealPath()` so the link target is rewritten (matching today's
  behavior). If a future change wants to *replace* the symlink with a regular file, that is a deliberate behavior change
  to call out.
- Owner/group of the rewritten file becomes the running user (inherent to create-temp-then-rename); this matches what
  most formatters do and is acceptable. Note it if a use case needs owner preservation.
- Follow-up (not in this plan): `FormatFileStatus.WRITTEN_PARTIALLY` is now a slight misnomer (atomic moves can't half-
  write). Consider renaming to `WRITE_FAILED` in a separate change that also updates the CLI/Gradle status markers.

## Outcome

Implemented on `main`. The atomic-write choreography shipped as a dedicated helper rather than the inline
`FormatterRunner.writeAtomically` the plan sketched: `frmtr-tooling/src/main/java/dev/lanwen/frmtr/tooling/BestEffortAtomicFileWriter.java`
(`writeString`) writes the formatted contents to a sibling `*.frmtr.tmp` file in the target directory, copies the
target's POSIX mode when the filesystem supports it (`copyPosixModeIfAvailable`), then renames it over the original via
`Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)`, falling back to a plain `REPLACE_EXISTING` move on
`AtomicMoveNotSupportedException`; the temp file is deleted on any failure. Symlinked inputs are resolved with
`toRealPath()` as planned. `FormatterRunner.writeFile` (`frmtr-tooling/.../FormatterRunner.java`) calls it in place of
the old bare `Files.writeString`, keeping the `IOException → WRITTEN_PARTIALLY` mapping. As the plan flagged,
`FormatFileStatus.WRITTEN_PARTIALLY` was deliberately **kept** (the deferred rename to `WRITE_FAILED` did not land), so
that status now means "replace failed, original intact."
