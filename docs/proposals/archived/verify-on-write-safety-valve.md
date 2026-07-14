> **Status: Implemented.** Landed on `main`: `--write --verify` (and `--check --verify`) → `Frmtr.formatVerified` → `JavaFormatter.assertOutputEquivalentOrThrow`, dispatched via `FormatterRunner.writeVerified`. Archived 2026-07-14; retained as a provenance record. The executor steps below are historical; see [Outcome](#outcome).

# Opt-In `--verify` Safety Valve: Refuse to Overwrite Non-Equivalent Output

**Status:** Implemented (roadmap S8) — opt-in `--write --verify` CLI flag backed by `Frmtr.formatVerified` → `JavaFormatter.formatVerified` (decision seam `assertOutputEquivalentOrThrow`); see [Outcome](#outcome) · Category: correctness / UX · Effort: M · Risk: MED (new public API + CLI behavior)
**Planned at:** commit `1237c475`, 2026-06-20

> **Executor instructions**: Follow step by step; run every verification command. This plan adds public API surface and
> a CLI flag — keep the new behavior strictly **opt-in and off by default** (see "Relationship to B3"). If anything
> under "STOP conditions" occurs, stop and report.
>
> **Drift check (run first)**:
> `git diff 1237c475..HEAD -- frmtr-core/src/main/java/dev/lanwen/frmtr/Frmtr.java frmtr-core/src/main/java/dev/lanwen/frmtr/FrmtrSession.java frmtr-core/src/main/java/dev/lanwen/frmtr/java/JavaFormatter.java frmtr-tooling/src/main/java/dev/lanwen/frmtr/tooling/FormatterRunner.java frmtr-cli/src/main/java/dev/lanwen/frmtr/cli/Main.java`
> If any changed, re-read it and confirm the "Current state" excerpts before proceeding.

## Why this matters

`frmtr`'s headline promise is that it never changes program meaning. The project already built the check that proves it
— AST-equivalence verify (`AstEquivalence` + `FormatterGuardrails.assertAstEquivalent`, re-parses the output and
compares structurally). But that check is reachable **only** as a debug/test system property
(`dev.lanwen.frmtr.debug.verify`), and when it does fire its failure is an `AssertionError` that
`FrmtrSession.formatterCall` wraps into `FormatterException.internal(...)` — i.e. it surfaces to a user as "Internal
formatter error. This is a bug in frmtr…", never as "I declined to overwrite your file because the result wasn't
equivalent." So an end user running the destructive `--write` path has **no first-class, graceful way** to say "format
my files, but if a formatting result would change meaning, refuse to write that file and tell me" — the safety net is
test-only and its failure mode is mislabeled as an internal crash.

This proposal adds exactly that valve: an **opt-in** `--verify` flag for `--write` that, per file, re-parses the
formatted output, and **only writes when it is AST-equivalent to the input**; otherwise it leaves the original file
untouched and reports a clear, non-internal diagnostic. The verification machinery already exists; this plan exposes it
through the public API with honest failure semantics and wires it to the one path where the cost is justified by the
stakes (overwriting source).

## Relationship to B3 (read this before implementing)

The B3 safety-net proposal (`docs/proposals/semantic-preservation-safety-net.md`) deliberately keeps verify **off by
default in the shipped CLI/Gradle hot path** (its Non-goals, line 440: "Turning any new check on by default in the
shipped CLI / Gradle plugin hot path"; and the performance note, lines 188-197: re-parsing doubles parse cost, so verify
must be opt-in). **This plan does not contradict that** — it is additive and stays within the decision:

- The new `--verify` flag **defaults to off**. Default `--write` behavior is byte-for-byte unchanged and pays nothing.
- Verification runs only when the user explicitly opts in, accepting the re-parse cost — exactly the "available to
  developers / opt-in" usage B3 endorses (it currently endorses it only via the `-D` debug property; this makes it a
  documented, ergonomic flag with a *graceful refusal* instead of an "internal error").

If, while implementing, you find a maintainer decision that says the CLI must **not** expose verify even opt-in, STOP
and report — that would supersede this proposal.

## Current state

- `frmtr-core/.../java/JavaFormatter.java`:
  - `format(String)` (lines 63-72): parses, prints, renders, then `verifyAstEquivalent(parseResult, formatted)`.
  - `verifyAstEquivalent(...)` (lines 83-95): **no-op unless `FormatterGuardrails.verifyEnabled()`** and skipped for
    recovered inputs (`inputResult.hasParseProblems()`); re-parses output; throws `AssertionError` if the output fails
    to parse or `FormatterGuardrails.assertAstEquivalent(inputCU, outputCU)` finds a difference.
  - `AstEquivalence` (same package `dev.lanwen.frmtr.java`) exposes `describeDifference(CompilationUnit, CompilationUnit)
    : Optional<String>` (and `equivalent(...)`) — usable directly from `JavaFormatter`.
- `frmtr-core/.../FrmtrSession.java`: `format(String)` (lines 31-33) calls `formatterCall(() -> formatter.format(source))`.
  `formatterCall` (49-57) **passes through `FormatterException`** but wraps `RuntimeException | LinkageError |
  AssertionError` into `FormatterException.internal(...)`. So a *typed `FormatterException`* thrown from the formatter
  reaches callers unwrapped and non-internal.
- `frmtr-core/.../Frmtr.java`: thin static facade — `format`, `session`, `debugDoc`, `explain` (lines 7-53).
- `frmtr-core/.../FormatterException.java`: public constructors `(message)`, `(message, cause)` set `internal=false`
  (lines 24-34); only the `internal(Throwable)` factory (42-51) sets `internal=true`. A clean user-facing failure is
  `new FormatterException(message)` / `(message, cause)`.
- `frmtr-tooling/.../FormatterRunner.java`: `write(Path displayRoot, List<Path> files, FormatterOptions options,
  FormatRunProgress progress)` (lines 63-78) → `writeFile(Path, Path, Supplier<FrmtrSession>)` (lines 265-282), which
  formats with `formatter.get().format(original)` and writes. `FormatFileResult` is constructed as
  `new FormatFileResult(file, displayPath, FormatFileStatus.X, "", exceptionOrNull)`.
- `frmtr-cli/.../Main.java`: options block (49-133) includes `--write` (61-62), `--line-width`, `--indent-width`,
  `--java-level`, `--parse-error-behavior`. `call()` (193-259) routes `write` to
  `writeFiles(files, options, ignored, excluded, progress)` (line 255). `formatterOptions()` (261-272) builds options
  from the flags.
- Repo conventions: AssertJ + JUnit 5; CLI option combination errors print to `err` and `return 2`
  (e.g. `--check and --write cannot be used together`, line 216-218); public methods get Javadoc.

## Commands you will need

| Purpose | Command | Expected |
|---|---|---|
| Core compile/test | `./gradlew :frmtr-core:test` | `BUILD SUCCESSFUL` |
| Tooling test | `./gradlew :frmtr-tooling:test` | `BUILD SUCCESSFUL` |
| CLI test | `./gradlew :frmtr-cli:test` | `BUILD SUCCESSFUL` |
| Full suite (before push) | `./gradlew test` | `BUILD SUCCESSFUL` |
| Manual smoke (optional) | `./gradlew :frmtr-cli:run --args='--write --verify <file>'` | file formatted; no "internal error" |

## Scope

**In scope:**
- `frmtr-core/.../java/JavaFormatter.java` — add a verified-format path + a testable equivalence seam.
- `frmtr-core/.../FrmtrSession.java` — `formatVerified(String)`.
- `frmtr-core/.../Frmtr.java` — `formatVerified(String)` and `formatVerified(String, FormatterOptions)`.
- `frmtr-tooling/.../FormatterRunner.java` — a `write(..., boolean verify)` overload + `writeFile` branch.
- `frmtr-cli/.../Main.java` — `--verify` flag (default false), wiring, and a combination guard.
- Tests in the matching `*Test` files; `README.md` + `ARCHITECTURE.md` docs.

**Out of scope:**
- **Do NOT add a field to the `FormatterOptions` record.** Its canonical constructor is called positionally in several
  places (e.g. `FrmtrTest`), so adding a field is a wide, error-prone ripple. Thread verification as a method/flag
  instead, as below.
- **Do NOT enable verify by default** anywhere (honor B3).
- **Gradle plugin** `--verify` equivalent — deferred follow-up (note it in docs), to keep this change bounded.
- Changing the existing `dev.lanwen.frmtr.debug.verify` test toggle or `AstEquivalence` comparison rules.

## Steps

### Step 1: Add a testable equivalence seam + verified format in `JavaFormatter`

Add a package-private method that converts an AST difference into a **non-internal** `FormatterException` (so it is unit-
testable without round-tripping the whole formatter):

```java
/** Throws a non-internal {@link FormatterException} when {@code formatted} is not AST-equivalent to {@code inputUnit}. */
void assertOutputEquivalentOrThrow(CompilationUnit inputUnit, String formatted) {
    JavaParseResult outputResult = parse(formatted);
    if (outputResult.hasParseProblems()) {
        throw new FormatterException("frmtr verify: formatted output did not parse under the input's parser configuration");
    }
    AstEquivalence.describeDifference(inputUnit, outputResult.compilationUnit()).ifPresent(difference -> {
        throw new FormatterException("frmtr verify: formatted output is not AST-equivalent to the input — " + difference);
    });
}
```

Add `public String formatVerified(String source)` mirroring `format(String)` but always verifying when the input parsed
cleanly:

```java
public String formatVerified(String source) {
    if (options.requirePragma() && !hasFormatPragma(source)) {
        return source;
    }
    JavaParseResult parseResult = parse(source);
    Doc doc = printDoc(source, parseResult);
    String formatted = new DocRenderer(options).render(doc);
    if (!parseResult.hasParseProblems()) {
        assertOutputEquivalentOrThrow(parseResult.compilationUnit(), formatted);
    }
    return formatted;
}
```

Notes the executor must respect: verification is **skipped for recovered (partially-parsed) inputs** — AST-equivalence
is ill-defined there (same rule the existing `verifyAstEquivalent` uses); `formatVerified` then returns the formatted
recovered output without a guarantee. Keep `assertOutputEquivalentOrThrow` package-private for the seam test. Add
Javadoc explaining the method always verifies (independent of the debug toggle) and throws a non-internal
`FormatterException` on mismatch.

**Verify**: `./gradlew :frmtr-core:compileJava` → `BUILD SUCCESSFUL`.

### Step 2: Expose it through `FrmtrSession` and `Frmtr`

- `FrmtrSession`: add `public String formatVerified(String source) { return formatterCall(() -> formatter.formatVerified(source)); }`.
  Because the mismatch throws a plain `FormatterException` (not internal), `formatterCall` passes it through unchanged.
- `Frmtr`: add `public static String formatVerified(String source, FormatterOptions options) { return session(options).formatVerified(source); }`
  and a `formatVerified(String)` defaulting to `FormatterOptions.defaults()`, mirroring `format`. Javadoc each.

**Verify**: `./gradlew :frmtr-core:compileJava` → `BUILD SUCCESSFUL`.

### Step 3: Add a verify overload to `FormatterRunner.write`

- Keep the existing `write(displayRoot, files, options, progress)` and add an overload
  `write(displayRoot, files, options, progress, boolean verify)`; make the 4-arg delegate with `verify = false` so all
  current callers are unaffected.
- Thread `verify` to `writeFile`. In `writeFile`, choose the formatter call:
  `String formatted = verify ? formatter.get().formatVerified(original) : formatter.get().format(original);`
  The existing `try/catch (FormatterException | IOException) -> FAILED` arm already handles the verify-mismatch
  exception: when verification fails, **no write is attempted** (the throw happens during formatting, before
  `writeAtomically`/`Files.writeString`), so the original file is left intact and the result is `FAILED` carrying the
  non-internal exception whose message states why. Confirm by code-reading that the format call precedes the write.

**Verify**: `./gradlew :frmtr-tooling:test` → `BUILD SUCCESSFUL`.

### Step 4: Add the `--verify` CLI flag and wire it

- Add near `--write`:
  ```java
  @Option(names = "--verify",
      description = "With --write, re-parse each formatted file and refuse to overwrite it if the result is not "
          + "AST-equivalent to the input. Off by default; doubles parse cost.")
  boolean verify;
  ```
- In `call()`, add a combination guard (match the style at lines 216-218): `--verify` is only meaningful with `--write`;
  if `verify && !write` (and not stdin/explain), print `"--verify requires --write"` to `err` and `return 2`.
- Change the `writeFiles(...)` call (line 255) and method to thread `verify` into `FormatterRunner.write(workingDirectory,
  files, options, <progress>, verify)`. Match the existing progress argument exactly (drift-check the current
  `writeFiles` body and the tooling `write` signature first).

**Verify**: `./gradlew :frmtr-cli:test` → `BUILD SUCCESSFUL`.

### Step 5: Tests

- **Core seam (negative path):** in `frmtr-core/.../java/JavaFormatterTest` (create if absent, or add to an existing
  core test) — build two **deliberately non-equivalent** trees by formatting/parsing different sources and assert
  `assertOutputEquivalentOrThrow` throws a `FormatterException` whose `internal()` is `false` and whose message contains
  the difference. (Model the divergent-pair construction on `AstEquivalenceTest`, which already parses non-equivalent
  sources.) This is the test that proves the refusal logic and the non-internal failure type, since the real formatter
  won't produce non-equivalent output on its own.
- **Core happy path:** assert `Frmtr.formatVerified(src, opts).equals(Frmtr.format(src, opts))` for several
  representative sources (a method chain, a record, an enum, a comment-dense class), and that it does **not** throw.
- **Tooling happy path:** in `FormatterRunnerTest`, a `@TempDir` test that `write(root, List.of(changed), defaults,
  progress, /*verify*/ true)` still writes a normal changed file (status `WRITTEN`, content equals formatted) —
  verifying the opt-in path does not break correct formatting. (An end-to-end *refusal* can't be triggered while the
  formatter is correct; the seam test in core covers refusal.)
- **CLI:** a test that `--verify` without `--write` exits 2 with the guard message, and that `--write --verify` on an
  already-correct temp file behaves like `--write` (model on existing `MainTest` write cases).

**Verify**: `./gradlew test` → all pass, new tests included.

### Step 6: Docs

- `README.md` CLI section: document `--verify` (opt-in; with `--write`, refuses to overwrite a file whose formatted
  output is not AST-equivalent to the input; off by default; doubles parse cost; Gradle equivalent is a follow-up).
- `ARCHITECTURE.md` CLI section: one or two sentences — `--verify` exposes the AST-equivalence check (otherwise a
  test/debug toggle) as an opt-in write-time safety valve that fails closed (does not write) with a non-internal
  diagnostic; default behavior and the `dev.lanwen.frmtr.debug.verify` toggle are unchanged.

**Verify**: `grep -n "\-\-verify" README.md ARCHITECTURE.md` → matches.

## Done criteria

ALL must hold:

- [ ] `./gradlew test` exits 0 with the new tests present and passing.
- [ ] `Frmtr.formatVerified`, `FrmtrSession.formatVerified`, `JavaFormatter.formatVerified` exist; the mismatch path
      throws a `FormatterException` with `internal() == false` (asserted by a test).
- [ ] `--write` **without** `--verify` produces byte-identical output and writes to the same files as before (no
      behavior change off the opt-in path) — confirmed by the unchanged existing write tests.
- [ ] `grep -n "verify" frmtr-cli/src/main/java/dev/lanwen/frmtr/cli/Main.java` shows the flag defaulting to false.
- [ ] No field was added to the `FormatterOptions` record (`git diff -- frmtr-core/.../FormatterOptions.java` empty).
- [ ] `README.md` and `ARCHITECTURE.md` document `--verify`.

## STOP conditions

- A maintainer decision (in a doc/ADR/issue) forbids exposing verify in the CLI even opt-in → STOP.
- Implementing the seam requires touching `AstEquivalence`'s comparison semantics → STOP (out of scope; reuse
  `describeDifference` as-is).
- Threading `verify` forces a `FormatterOptions` record field (you cannot avoid it) → STOP and report; the no-field
  constraint is load-bearing for keeping this change bounded.
- The verify path changes default `--write` output or makes the default path pay the re-parse cost → STOP (must be
  opt-in only).

## Maintenance notes

- A reviewer should confirm: (1) verification is gated by the `--verify`/`formatVerified` opt-in and nothing else turned
  it on by default; (2) the mismatch exception is non-internal so it renders as a plain refusal, not an "internal bug";
  (3) on refusal the file is genuinely not written (the throw precedes any write); (4) recovered inputs are skipped, not
  falsely refused.
- Follow-ups deliberately deferred: a Gradle `frmtr { verify = true }` option; a distinct `FormatFileStatus` (e.g.
  `SKIPPED_NOT_EQUIVALENT`) and CLI marker so a safety refusal reads differently from a parse `FAILED` (this plan reuses
  `FAILED` with a clear message to avoid enum/exhaustive-switch ripple); and pairing `--verify` with the atomic-write
  change (`atomic-in-place-writes.md`) so "refuse" and "can't corrupt" together make `--write` fully safe.
- This is the most speculative of the three audit proposals and partially overlaps B3's surface; if B3 Layer 3 or a
  future "safe mode" design absorbs it, mark this stale rather than duplicating.

## Outcome

Implemented on `main` end-to-end as an opt-in valve. The CLI exposes `--verify` (`frmtr-cli/.../Main.java`, option at
~line 65), which requires `--write` (the run rejects `--verify` without `--write` at ~line 202) and dispatches the
formatting run to `FormatterRunner.writeVerified` (Main.java ~line 426). `writeVerified` formats each file through
`FrmtrSession::formatVerified` (`frmtr-tooling/.../FormatterRunner.java`), which calls the new public API
`Frmtr.formatVerified` → `FrmtrSession.formatVerified` → `JavaFormatter.formatVerified`. The decision seam is the
package-private `JavaFormatter.assertOutputEquivalentOrThrow`: it re-parses the output and, when it does not parse or is not AST-equivalent (reusing
`AstEquivalence.describeDifference`), throws a **non-internal** `FormatterException` with a `"frmtr verify: …"` message
*before* any write, so the original file is left intact; recovered (partially-parsed) inputs are skipped, not refused.
As the plan deferred, the refusal reuses the existing `FAILED` status rather than a dedicated `SKIPPED_NOT_EQUIVALENT`,
and verification here is independent of the `dev.lanwen.frmtr.debug.verify` debug toggle. The deferred Gradle
`frmtr { verify = true }` option did not land.
