# Speed up CLI file discovery with lazy `.gitignore` loading

Status: Implemented for CLI discovery; ignored-directory pruning remains a guarded follow-up.

## Summary

`frmtr-cli --check` currently pays a fixed project-root scan before it knows which selectors will be checked. On a large
external checkout, that means an explicit single-file selector still walks the whole project to find `.gitignore` files
before formatting one path. Prior measurements showed:

- Warm full-project check before cleanup: roughly 14.2s.
- Warm full-project check after cleanup: roughly 10.2s.
- Summary stayed stable for the same external benchmark revision.
- Single explicit Java file selector: roughly 1.8-3.8s.
- Equivalent stdin check: roughly 0.3-0.5s.
- The measured checkout has roughly 143k filesystem entries and 3 `.gitignore` files.

The proposed change is to remove the unconditional full-root `.gitignore` walk from CLI discovery. `FileDiscovery` should
load ignore rules lazily for the selector scope it is actually evaluating:

- Explicit Java file selector: stat that selector and load only `.gitignore` files on the root-to-parent ancestor chain.
- Directory selector: traverse the selected directory once and load ancestor plus encountered `.gitignore` files as the
  traversal reaches those directories.
- Glob selector: derive the same selected base as today, traverse that base once, apply the existing glob matchers, and
  load ignore rules only for ancestor and encountered directories.

This should remove the root-size fixed cost for explicit-file selectors and avoid a separate root-wide ignore scan for
directory and glob selectors. It keeps JGit `IgnoreNode`, preserves `--exclude` precedence over `.gitignore`, preserves
deterministic output ordering, and treats ignored-directory pruning as a separate optimization guarded by tests for
negation semantics.

## Current Hot Path

The CLI calls discovery after stdin and option validation in `Main.call()`. For non-stdin runs it chooses default
selectors when no selectors are passed, then calls:

```java
new FileDiscovery(workingDirectory).discover(usingDefaultSelectors ? DEFAULT_SELECTORS : selectors, excludes)
```

Current discovery behavior is concentrated in `frmtr-cli/src/main/java/dev/lanwen/frmtr/cli/FileDiscovery.java`:

- `FileDiscovery(Path root)` normalizes the working directory to an absolute root.
- `discover(...)` creates the result sets and immediately constructs `new GitIgnoreMatcher(root)` before selector parsing
  decides whether the run is a single file, directory, or glob.
- `GitIgnoreMatcher(Path root)` immediately calls `loadRules(root)`.
- `loadRules(root)` uses `Files.walk(root)`, filters every regular file named `.gitignore`, parses each file into a JGit
  `IgnoreNode`, and sorts rules by directory depth.
- Only after that eager root walk does `discover(...)` iterate selectors.
- Explicit-file selectors still call `Files.isRegularFile(path)`, check `--exclude`, and then call
  `ignores.isIgnored(path, false)`, but the expensive root ignore scan already happened.
- Directory selectors call `Files.walk(directory)`, collect regular `.java` candidates, then classify each candidate
  through `selectCandidates(...)`.
- Glob selectors derive `globBase(selector)`, call `Files.walk(base)`, filter regular `.java` candidates by path matcher,
  then classify each candidate through `selectCandidates(...)`.
- `selectCandidates(...)` intentionally applies `ExcludeMatcher` before `.gitignore`; this is why a file that matches
  both is counted as excluded, not ignored.
- Result files, ignored files, and excluded files are sorted before returning, so traversal order does not leak into CLI
  output.

That means a large project can be walked twice for a default `--check .` style run: once to discover `.gitignore` files
and once to discover Java candidates. It also means a single explicit Java file selector pays the same root ignore scan as
a full-project selector.

Current tests cover selector and summary behavior through `MainTest`, including:

- Path and glob excludes during discovery.
- Write summary counts for excluded Java files.
- Unknown extensions and unmatched globs.
- A root `.gitignore` directory rule during check.
- Write summary counts for ignored Java files.

They do not yet cover nested `.gitignore` files, root-to-selected-base ancestor rules, explicit-file ignore fast paths,
negation behavior, ignored-directory traversal decisions, or discovery performance characteristics.

## Proposed Design

### Replace eager ignore loading with a lazy matcher

Change `GitIgnoreMatcher` from an eager root scanner into a lazy, cache-backed matcher:

- Constructor stores `root.toAbsolutePath().normalize()` and does no filesystem walking.
- Maintain a cache keyed by absolute normalized directory:
  - no `.gitignore` present;
  - parsed `IgnoreRules(directory, IgnoreNode)`;
  - optional failed load state if the implementation wants repeated calls to preserve the same exception.
- A helper such as `rulesFor(Path absolute, EntryKind kind)` loads only the directories whose rules can apply to that
  path.
- For a file, applicable rule directories are `root` through the file's parent directory.
- For a directory being tested as an entry, applicable rule directories are `root` through the directory's parent
  directory; the directory's own `.gitignore` applies to its contents, not to the directory entry itself.
- Continue using JGit `IgnoreNode.isIgnored(relative, directory)` so pattern syntax stays delegated to the same library.
- Evaluate each nested `IgnoreNode` with the candidate path relative to that `.gitignore` file's owning directory, not
  always relative to the project root. Anchored nested rules such as `/OnlyHere.java` must stay directory-local.
- Preserve the current root-to-leaf override order: later, deeper applicable rules can override earlier results when
  JGit returns a non-`CHECK_PARENT` result.

This keeps the important semantic boundary: ignore rules are loaded because the selector needs them, not because they
exist somewhere under the working directory.

### Make explicit files a true narrow path

The explicit-file branch should stay direct:

1. Resolve and normalize `root.resolve(selector)`.
2. Preserve the existing missing explicit `.java` selector error.
3. Check `Files.isRegularFile(path)` and `.java` suffix.
4. Apply `ExcludeMatcher` first.
5. Apply lazy `.gitignore` matching for that path only.
6. Return the same `Selection` shape as today.

This path should probe at most the selected file plus `.gitignore` files along the root-to-parent chain. It should not
walk unrelated directories, fixture corpora, build outputs, or external projects.

### Fuse directory/glob traversal with ignore loading

Replace the two-step "walk candidates, then classify" shape with a traversal that classifies candidates as it walks:

- Use a file visitor or equivalent traversal from the selected base.
- For directory selectors, the selected base is the selector directory.
- For glob selectors, keep `globBase(selector)` and `globMatchers(selector)` so matching semantics remain familiar.
- Before visiting entries inside a directory, ask the lazy matcher to load that directory's `.gitignore` into the cache so
  child entries see it.
- For every regular `.java` candidate that also matches the glob when a glob is active:
  1. normalize the absolute path;
  2. apply `ExcludeMatcher`;
  3. apply `GitIgnoreMatcher`;
  4. append to the selected, excluded, or ignored result list.
- Keep final sorting in `Result` so output remains deterministic even if the traversal implementation changes order.

This removes the separate `Files.walk(root)` used only for ignore discovery. For a full-project default check, the tree
still needs to be traversed to find Java files, but the traversal should happen once. For narrower directory or glob
selectors, unrelated `.gitignore` files outside the selected base should not be loaded except for ancestor rules needed
to evaluate the selected base.

### Preserve `--exclude` over `.gitignore`

The current implementation classifies candidates with excludes first. Keep that rule unchanged:

- If a Java file matches `--exclude`, count it as excluded even if it would also be ignored.
- Do not use `.gitignore` to suppress excluded counting before the exclude matcher sees the file.
- If a future optimization prunes directories, it must prove that excluded counts and exclude precedence are still
  preserved for files under that directory or explicitly accept a mode-specific count tradeoff.

This matters because check summaries already report excluded file counts, and write/print summaries report both ignored
and excluded counts.

### Treat ignored-directory pruning as a guarded follow-up

The safest first implementation should not prune ignored directories. It should walk the selected base once, lazily load
encountered `.gitignore` files, and classify Java candidates exactly. That alone removes the unconditional root-wide
ignore scan and should address the explicit-file regression.

Directory pruning can be a follow-up once tests pin the desired semantics. The tricky cases are:

- root or parent `.gitignore` rules that ignore a directory;
- negation rules that re-include files or subdirectories;
- nested `.gitignore` files inside directories that would otherwise be ignored;
- exact ignored/excluded summary counts in `--write`, print mode, and empty-result branches;
- `--exclude` patterns that match files inside ignored directories.

If pruning is added, prefer a conservative predicate:

- Only skip an ignored directory when no already-loaded applicable rules can re-include descendants and when the current
  CLI mode does not require exact ignored-file counts under that directory.
- Keep check mode, write mode, and print mode requirements explicit instead of baking a hidden mode-specific shortcut
  into the matcher.
- Document any count tradeoff before accepting it. The initial recommendation is no count tradeoff.

### Keep the change private to CLI discovery

This is CLI selector discovery work, not formatter policy work:

- Keep it under `frmtr-cli/src/main/java/dev/lanwen/frmtr/cli`.
- Keep `frmtr-core`, `frmtr-tooling`, Gradle plugin source selection, and formatting behavior unchanged.
- Do not change test fixtures or external projects.
- Update `ARCHITECTURE.md` in the eventual implementation PR only if the CLI discovery shape, public CLI behavior, or
  testing strategy changes. This proposal task intentionally does not edit `ARCHITECTURE.md`.

## Implementation Steps

1. Add an internal `EntryKind` or boolean-equivalent helper to make file-vs-directory ignore checks explicit at call
   sites.
2. Change `GitIgnoreMatcher` construction so it performs no filesystem walk.
3. Add an ignore-rule cache keyed by normalized absolute directory.
4. Implement a small loader that checks only `directory.resolve(".gitignore")`, parses it with JGit `IgnoreNode`, and
   stores either parsed rules or an empty result in the cache.
5. Implement ancestor-chain resolution from `root` to a target path:
   - for files, include the parent directory;
   - for directories as entries, stop at the parent directory;
   - for directory contents, load the directory before child classification.
6. Rework `isIgnored(...)` to evaluate only applicable loaded/cached rules in root-to-leaf order.
7. Keep explicit-file discovery narrow: no directory traversal, no root scan, and unchanged missing-selector behavior.
8. Replace directory and glob `Files.walk(...)` pipelines with a traversal that can load per-directory ignore rules before
   classifying children.
9. Keep `ExcludeMatcher` unchanged unless the traversal needs a helper to ask whether an exclude pattern can match
   descendants. Do not change exclude precedence.
10. Keep `Result` sorting and de-duplication through `LinkedHashSet` plus natural-order final lists.
11. Add focused tests before or alongside the implementation.
12. Run the existing CLI test suite and the measurement plan below.
13. In the implementation PR, update `ARCHITECTURE.md` if the final design changes documented CLI discovery behavior or
   testing strategy.

## Test Plan

Do not change existing test fixtures, fixture corpora, or external projects. Add small temp-directory tests only.

Focused behavior tests to add or run:

- Existing `MainTest` coverage for excludes, ignored files, default selectors, unmatched globs, missing explicit Java
  files, and deterministic output.
- Explicit file under a root `.gitignore` rule is counted as ignored in write mode without requiring a directory selector.
- Explicit file with a same-directory `.gitignore` rule is ignored.
- Explicit file in an unignored directory is checked normally even when an unrelated sibling subtree contains a
  `.gitignore`.
- Directory selector loads parent `.gitignore` rules above the selected directory.
- Directory selector loads nested `.gitignore` rules encountered below the selected directory.
- Glob selector loads parent rules above `globBase(selector)` and nested rules below the base.
- Nested `.gitignore` negation: a local rule ignores `*.java` and a later `!Keep.java` re-includes one file.
- Nested anchored pattern scope: a nested `.gitignore` containing `/OnlyHere.java` ignores only that directory's
  immediate file, while an unanchored nested pattern applies below that directory according to JGit semantics.
- Directory-ignore negation behavior is pinned explicitly before directory pruning is introduced.
- Excludes still win over ignores when a Java file matches both `--exclude` and `.gitignore`.
- Excluded and ignored summary counts remain stable in check, write, and print modes.
- Duplicate selectors still de-duplicate results and final output remains naturally sorted.
- Symlink behavior remains unchanged from `Files.walk`: discovery does not follow symlinked directories by default.
- IO behavior is explicit for selected scope: unreadable `.gitignore` files in selected or ancestor directories still
  surface, while unreadable ignore files outside the selected scope are not touched by narrow selectors.

Performance and regression checks to run:

```bash
./gradlew :frmtr-cli:test
./gradlew test
sem diff --format json --from origin/main --to HEAD
```

Measurement may use external projects only as read-only benchmark targets. Do not edit, clean, or rewrite those
checkouts during measurement, and do not record their names in durable project documentation.

## Pros

- Removes the fixed root-wide `.gitignore` scan from explicit-file selectors.
- Reduces default and directory/glob discovery from "ignore scan plus candidate scan" to one selected traversal.
- Keeps JGit `IgnoreNode` as the source of ignore-pattern semantics.
- Keeps `--exclude` precedence and deterministic result ordering.
- Narrows I/O to selector-relevant paths, which should make failures and latency scale with the user's request.
- Creates a clearer internal boundary between selector planning, traversal, exclude matching, and ignore matching.

## Cons

- More stateful discovery code: cache invalidation, ancestor-chain loading, and traversal timing need careful tests.
- A custom traversal is more verbose than the current `Files.walk(...).filter(...).toList()` pipelines.
- Exact ignored/excluded summary counts limit how aggressively ignored or excluded directories can be pruned.
- Some behavior may become intentionally narrower: unreadable `.gitignore` files outside selected scopes would no longer
  affect a narrow selector run.
- Without a benchmark harness, performance can regress silently even if behavior tests pass.

## Risks

- `.gitignore` negation is easy to mishandle, especially around ignored directories and nested `.gitignore` files.
- Loading a directory's own `.gitignore` too early could incorrectly apply it to the directory entry itself.
- Loading it too late could miss rules for immediate children.
- Pruning ignored directories can break write/print ignored counts or `--exclude` precedence.
- Glob base calculation must remain compatible with existing `**/` matcher behavior.
- Symlink behavior should not change accidentally; the current `Files.walk` behavior does not follow links by default.
- Path normalization must keep working for selectors containing backslashes.
- IO exception timing may change because ignore files outside selected scopes are no longer touched.

## Rollout/Measurement Plan

1. Capture baseline from the current branch before implementation:
   - warm full-project `frmtr-cli --check --color never .` against a read-only external checkout;
   - warm single explicit Java file selector;
   - equivalent stdin check for the same file;
   - summary line for the full-project check.
2. Implement lazy ignore loading behind the existing CLI behavior with no user-facing flag.
3. Run focused CLI tests and full tests.
4. Re-run the same warm measurements after implementation.
5. Compare:
   - full-project summary remains unchanged for the same external benchmark revision;
   - explicit file selector should lose the root-size dependency and move much closer to stdin timing;
   - full-project check should not regress and should ideally improve by avoiding the duplicate root walk;
   - excluded and ignored summary counts stay stable on small controlled tests.
6. Use `sem diff --format json --from origin/main --to HEAD` before publishing the implementation so reviewers can inspect
   the structural scope.

Use multiple warm runs, or `hyperfine` if available, because JVM/native startup, filesystem cache state, and formatter work
can dominate small samples. Report non-routine measurement results in the implementation PR only if they materially
explain the change; omit routine test/format/lint verification per repository PR guidance.

## Non-goals

- Do not implement the code in this proposal-only change.
- Do not edit source code, tests, test fixtures, `ARCHITECTURE.md`, build files, or external projects as part of this
  proposal task.
- Do not replace JGit `IgnoreNode` or reimplement `.gitignore` pattern parsing.
- Do not change formatter output, parser options, check/write/print output formats, or exit-code behavior.
- Do not change Gradle plugin source selection.
- Do not introduce a persistent discovery cache across CLI invocations.
- Do not add broad ignored-directory pruning until negation and summary-count semantics are covered by focused tests.
