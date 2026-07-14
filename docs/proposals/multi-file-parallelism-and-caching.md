# Multi-file parallelism and content-addressed caching

Status: Partially implemented — runner-level bounded parallelism, CLI progress reporting, and
Gradle incremental/cache behavior landed; any Gradle-native progress/logging follow-up remains
proposed.

> **Remaining actionable work:** only the optional **Gradle-native progress/logging** follow-up (surface per-file
> progress through Gradle's logging/progress APIs, if the plugin needs the visibility the CLI already has). A persistent
> CLI results cache stays out of scope (deliberate non-goal). Everything below is the design record of the shipped
> runner parallelism, CLI progress side-channel, and Gradle `@CacheableTask` / `InputChanges` behavior.

## Summary

`frmtr` originally formatted files one at a time. The first implementation slice replaced the shared
tooling runner's sequential `stream().map(...)` with bounded, order-preserving parallel processing
for `check` and `write`. The Gradle plugin now avoids compounding unchanged-file work:
`frmtrJavaCheck` has a deterministic success marker, is `@CacheableTask`, and uses `InputChanges`
on Gradle's `@SkipWhenEmpty` source file input so changed-source runs process only added or
modified files. `frmtrJavaFormat` remains deliberately
non-cacheable because it mutates source files in place and does not declare a synthetic marker
output.

The generated-file hang finding makes this more than a throughput problem. `check` and `write` still
preserve ordered final status, diagnostics, diffs, and summary output, but the CLI now also renders a
stderr progress side channel while the runner is in flight. Gradle-native progress/logging remains a
separate follow-up if the plugin needs comparable visibility.

This proposal tracks two independent, composable speedups, building on the lazy-discovery work in
`cli-discovery-lazy-ignore.md` (which made *finding* files cheap; this makes *processing* them
cheap):

1. **Parallel file processing** in `FormatterRunner` — **implemented for `check` and `write`**.
   Each file is independent, and `Frmtr.format` is provably safe to call concurrently (see
   thread-safety analysis), so the per-file work can run on a bounded thread pool while results stay
   deterministically ordered.
2. **Skip-unchanged caching**, keyed by `(content-hash, options, formatter-version)`:
   - In **Gradle**, the idiomatic and durable form is now implemented for verification: declare a
     cacheable check task with a deterministic marker output and use incremental inputs so unchanged
     files are skipped and warm re-runs are `UP-TO-DATE` / `FROM-CACHE`. The mutating format task
     stays non-cacheable and does not declare a synthetic output marker.
   - In the **CLI**, a results cache is discussed but recommended **out of scope** for now, to
     respect the explicit non-goal in `cli-discovery-lazy-ignore.md`: *"Do not introduce a
     persistent discovery cache across CLI invocations."* Parallelism alone is the CLI win.

On a monorepo the implemented runner slice turns cold multi-file runs from sequential into
core-parallel, and the implemented Gradle slice turns a warm successful `check` from "re-format
everything" into `UP-TO-DATE` or `FROM-CACHE`.

## Original sequential behavior (grounded)

### The shared runner was sequential before the first implementation slice

`FormatterRunner.check(...)` and `FormatterRunner.write(...)`
(`frmtr-tooling/src/main/java/dev/lanwen/frmtr/tooling/FormatterRunner.java`) both do:

```java
return new FormatRunResult(selectedFiles(displayRoot, files).stream()
        .map(file -> checkFile(displayRoot, file, options, includeDiffs, diffRenderMode))
        .toList());
```

- `selectedFiles(...)` normalizes, de-duplicates (`LinkedHashSet`), and **sorts** by display path,
  so the input list is already in a deterministic order before any formatting.
- `checkFile(...)` / `writeFile(...)` each: read the file (`Files.readString`), call
  `Frmtr.format(original, options)`, compare, and produce a `FormatFileResult`
  (`UNCHANGED` / `CHANGED` / `WRITTEN` / `WRITTEN_PARTIALLY` / `FAILED`). Per-file failures are
  captured into the result (not thrown), so one bad file never aborts the run.
- This was a plain sequential `Stream` — **not** `.parallel()`. One CPU core did all formatting.
- `FormatRunResult` is materialized only after every selected file returns, preserving ordered final
  result output. The CLI now subscribes to the runner progress side channel and emits stderr progress
  while work is in flight; Gradle callers still need a Gradle-native progress/logging follow-up if
  they require the same visibility.

### The CLI drives it (and `printFiles` is its own sequential loop)

`frmtr-cli/.../Main.java`:

- `checkFiles(...)` → `FormatterRunner.check(...)`, then iterates `run.results()` to print status
  lines and optional diffs. Non-stacktrace diagnostics for failed checked files currently go to
  **stdout** beside status output; stacktraces are deferred to the run-failure path on stderr.
- `writeFiles(...)` → `FormatterRunner.write(...)`, then prints failures to **stderr** and the final
  summary to stdout.
- `printFiles(...)` does **not** go through the runner — it has its own
  `for (int i = 0; i < files.size(); i++)` loop calling `Frmtr.format` and printing as it goes,
  using the index for `==> path <==` headers and blank-line separators. Formatted source goes to
  stdout; failures and the summary go to stderr.
- Discovery (`FileDiscovery`) already returns files in sorted, de-duplicated order; the lazy-ignore
  proposal preserved that. Discovery counters (`ignored`, `excluded`) stay outside the runner: check
  summaries report excluded files only, while write/print summaries report ignored and excluded.

`checkFiles(...)` and `writeFiles(...)` therefore have worse responsiveness than `printFiles(...)`:
they do not emit anything until the whole run finishes. `printFiles(...)` at least prints each file
after formatting it, but it is still sequential and a pathological file blocks all later output.

### The Gradle plugin uses native incremental and cache state

`frmtr-gradle-plugin/src/main/java/dev/lanwen/frmtr/gradle/`:

- `AbstractFrmtrJavaTask` declares Gradle-native incremental inputs:
  - `@SkipWhenEmpty @InputFiles @PathSensitive(RELATIVE)` `getSourceFiles()`
  - `@Input` for `includes`, `excludes`, `lineWidth`, `javaLanguageLevel`
  - `@Internal` `projectDirectory`
- `FrmtrJavaCheckTask` is `@CacheableTask` and declares an `@OutputFile` success marker under the
  project's build directory. The marker is cleared when the task executes and written only after a
  successful check with no formatter failures and no unformatted files. Successful checks can
  therefore be `UP-TO-DATE` or restored `FROM-CACHE` with `--build-cache`.
- `FrmtrJavaFormatTask` remains `@DisableCachingByDefault` because it rewrites sources in place.
  It does not declare a marker output or use Gradle's incremental task API, so Gradle still executes
  the mutating task instead of restoring or skipping source rewrites through synthetic output state.
- The check task action accepts `InputChanges`: non-incremental runs process the whole selected
  source set, while incremental runs pass only added/modified file changes to `FormatterRunner` and
  ignore removed files for formatting work.
- `frmtrCheck` is wired into the lifecycle `check` task via `dependsOn`.
- `FrmtrGradlePlugin.configureJava(...)` registers both tasks and feeds them a lazily-computed
  `sourceFiles` provider derived from `sourceSet.getAllJava()` (build dir excluded).

So on a 5,000-file module where one source file changed after a successful run, `frmtrJavaCheck`
processes the changed source file instead of re-reading and re-formatting all 5,000.

## Thread-safety analysis: is `Frmtr.format` safe to call concurrently? — **Yes.**

Evidence, from `frmtr-core`:

- **`Frmtr.format`** (`Frmtr.java`) is a `static` method on a final class with a private
  constructor and **no instance or mutable static fields**. Its body is
  `return new JavaFormatter(options).format(source);` wrapped in exception normalization. Every call
  constructs a **fresh** `JavaFormatter`.
- **`JavaFormatter`** (`JavaFormatter.java`) holds only `private final FormatterOptions options` and
  `private final JavaParser parser`, both built in the constructor per instance. Its single
  `static` field is `TRANSFORMS` — a `private static final JavaTransformPipeline`. That pipeline
  (`JavaTransformPipeline`) holds one `private final List<JavaFormatTransform> transforms`
  (`List.copyOf(...)`, immutable) and its `transform(unit)` method only reads the transforms and
  threads a per-call `CompilationUnit` through them; it stores nothing. The transform
  (`ImportSortTransform`) exposes only a `private static final Comparator` — stateless. All other
  `static` members in `JavaFormatter` are pure helper *methods*, not fields.
- **`JavaPrinter`** builds a brand-new `JavaFormatContext` per construction
  (`this.context = new JavaFormatContext(options, sourceText, recoverParseProblems)`), and all the
  per-domain printer composers (`ExpressionPrinters`, `DeclarationPrinters`, `StatementPrinters`,
  `TypePrinter`) are constructed per `JavaPrinter`.
- **`JavaFormatContext`** is the run-local mutable state hub: every field
  (`comments`, `sourceShape`, `rawSource`, `layoutWidth`, `commentPlacement`, …) is `final` and
  freshly constructed in its constructor. It is created once per `format` call and never shared.
- The parsed `CompilationUnit` AST that flows parse → transform → print is allocated inside each
  `format` call. No AST instance is shared between calls.

**Conclusion:** there is no shared mutable state across `format` calls. Two threads each calling
`Frmtr.format(differentSource, options)` touch only their own object graphs plus immutable shared
constants. Formatting is a pure function of `(source, options)`. Concurrent calls across different
files are safe with no locking. (The only cross-cutting caveats are *I/O*, not the formatter:
concurrent `Files.writeString` must target distinct paths — which the de-duplicated file list
guarantees — and any shared `PrintWriter` output must be ordered/serialized, addressed below.)

## Proposed design

### (a) Parallel file processing — implemented for `check` / `write` in `FormatterRunner`

`FormatterRunner`'s public surface (`check` / `write`, returning `FormatRunResult`) stays
unchanged. Internally, the runner now uses a bounded parallel map that preserves order.

**Thread pool sizing.** The implementation uses a fixed, bounded pool sized to
`Math.max(1, Runtime.getRuntime().availableProcessors())`, capped at `files.size()` so tiny runs
don't spin up idle threads. The work is CPU-bound (parse + render dominate; I/O per file is a single
read/write), so an unbounded `parallelStream` on the common ForkJoinPool is discouraged — it would
contend with, and be contended by, other JVM work (especially inside Gradle workers). The explicit
`ExecutorService` is owned by the call and shut down in a `finally`, with `Future`s collected in
input order. A configurable override (CLI flag / Gradle
`frmtr { java { maxParallelism = N } }`) is a follow-up, not required for v1.

**Ordering / output determinism.** Results MUST come back in the same order as the already-sorted
input list. The map is *embarrassingly parallel but order-preserving*: collect into an
index-addressed array (`results[i] = process(files.get(i))`) or use an ordered stream collector, so
`FormatRunResult.results()` is byte-for-byte identical to the sequential version regardless of
completion order. Because each `FormatFileResult` is fully computed (including its rendered diff
text) before assembly, no formatting output interleaves. `FormatRunResult` itself is already an
immutable record built from a final list, so once assembled it is safe to read from one thread.

**Progress vs. result output.** Do not print status lines, diffs, or formatted source directly from
worker threads. The ordered `FormatRunResult` remains the final truth. If v1 is expected to address
the generated-file "silent hang" behavior, add an explicit progress path alongside the ordered
result path:

- CLI progress should be a side channel (stderr and preferably interactive/TTY-oriented), not mixed
  into stdout status/diff/formatted-source output that scripts may consume.
- Gradle progress should use Gradle logging/progress APIs rather than worker-thread `println`s.
- Completion can be tracked in completion order for counters such as `37/500 files processed`, while
  final result printing stays in deterministic input order.
- If the implementation instead streams result output in input order as each contiguous prefix
  completes, document that a slow first file can still block visible results even while later worker
  tasks finish.

Without this progress path, bounded parallelism is still valuable for throughput, but it must not be
claimed to fix a true single-file hang that prevents the run from returning.

**`Main.printFiles` (CLI print mode).** This path prints *as it formats* and uses the loop index for
headers/separators. It cannot reuse `FormatFileResult` as-is because that result carries status,
diff text, and failure, but not formatted source. Two safe options:

1. Add a print-specific result type that carries either the formatted source or the failure, collect
   those results in input order, then emit stdout/stderr sequentially. This preserves the exact
   `==> path <==` headers, blank-line separators, failure reporting, and summary, but can hold many
   formatted files in memory at once.
2. Leave print mode sequential in v1. It is the least common mode and its stdout stream is the most
   sensitive API surface.

Recommendation: leave print mode sequential for the first runner-parallelism change unless the work
also adds a bounded ordered-drain design. A bounded drain may emit the contiguous completed prefix
from index `0..n`, but it must document that a slow earlier file still blocks later stdout output
even if later worker tasks finish.

**Per-file error handling.** Unchanged semantics: each task catches `FormatterException | IOException`
and returns a `FAILED` (or `WRITTEN_PARTIALLY`) `FormatFileResult` — exactly as `checkFile` /
`writeFile` do today. One failing file must never poison sibling tasks or the pool. Unexpected
`RuntimeException`/`Error` are already normalized to `FormatterException.internal` inside
`Frmtr.format`. The executor must be shut down even on early exit. Preserve the existing status
semantics: `WRITTEN_PARTIALLY` is both changed and failed, check mode returns `2` if any result
failed and `1` only for clean "would change" results, and write/print return `2` for any failure.

### (b) Content-addressed CLI results cache — discussed, recommended OUT of scope for v1

The cache **key** is the same triple everywhere: `(content-hash, options, formatter-version)`.

- `content-hash`: a hash (e.g. SHA-256) of the file's UTF-8 bytes. Hashing then formatting is
  net-positive only when the cache hit rate is high.
- `options`: `FormatterOptions` is a `record`, so it already has value-based `equals`/`hashCode` —
  it can be hashed/serialized directly into the key with no extra work.
- `formatter-version`: a stable identifier of formatter behavior. `BuildInfo.VERSION` /
  `BuildInfo.COMMIT_SHA` exist in the CLI (`Main.BuildVersionProvider`) and are the natural source.
  Crucially, the key must change whenever *output* could change, so a release version alone is too
  coarse for local development; use the build commit (or a dedicated "format algorithm version"
  constant bumped on output-affecting changes).

**Why out of scope for the CLI:** `cli-discovery-lazy-ignore.md` lists as an explicit non-goal:
*"Do not introduce a persistent discovery cache across CLI invocations."* That ruling was about
discovery, but the same spirit applies to a persistent *results* cache: it introduces an on-disk
store, invalidation rules, a location/ownership question (`.frmtr-cache`? XDG cache dir? per-repo?),
and cross-invocation state that the CLI has so far deliberately avoided. A CLI process is short-lived
and usually formats what the user explicitly asked for, so the warm-rerun win is mostly a *build
tool* concern — which Gradle solves natively (below). **Recommendation:** ship parallelism for the
CLI now; treat a persistent CLI results cache as a separate, later proposal that must first decide
cache location, eviction, and corruption handling, and must explicitly revisit the lazy-ignore
non-goal. (An in-memory per-process cache would be pointless: a single CLI run visits each path
once.)

### (c) Gradle incremental inputs + `@CacheableTask` + build-cache relocatability — implemented

This is where the content-addressed idea pays off durably, because Gradle already implements
content hashing, an incremental input change set, and a (optionally remote) build cache. We just
have to declare the tasks correctly. The implemented Gradle slice follows this design.

**Declare outputs only where the task semantics are cacheable.**

- `frmtrJavaCheck` is annotated with `@CacheableTask` and has a deterministic success marker output.
  `frmtrJavaFormat` stays non-cacheable unless its in-place source mutation is redesigned around a
  safe output model.
- `getSourceFiles()` already has `@PathSensitive(PathSensitivity.RELATIVE)` — correct for
  relocatability (cache entries don't depend on absolute checkout paths). Keep it.
- The current `@Internal projectDirectory` is used only to compute display paths; keep it
  `@Internal` so it does not break relocatability. Display-path differences must not be part of the
  cache key.
- **Check task** declares a small `@OutputFile` verification marker written on success. With inputs
  unchanged and the marker present, Gradle marks the task `UP-TO-DATE`; with build cache enabled,
  the marker is restored `FROM-CACHE`. This is the standard pattern for verification tasks (mirrors
  how `test` uses its results dir). Because Gradle does not cache failed task executions, a check
  run that finds unformatted files or formatter failures does not write the success marker and will
  still re-run until it passes.
- **Format task** rewrites sources in place, so it is not remotely cacheable and does not declare a
  synthetic output marker. The implemented conservative model leaves `frmtrJavaFormat` as a
  non-cacheable mutating task while `frmtrJavaCheck` is fully `@CacheableTask`.

**Incremental check action via `InputChanges`.** The check task action injects `InputChanges` and
uses `getSourceFiles()` as the incremental file input. In the action, it asks
`inputChanges.getFileChanges(getSourceFiles())` for added/modified files and passes **only those**
to `FormatterRunner` when the run is incremental. Removed files are ignored for formatting work but
still allow the task marker/snapshot to update. On a non-incremental run (first run, or an
`@Input` like `lineWidth`, `javaLanguageLevel`, `includes`, or `excludes` changed), process
everything — a change to formatting options correctly busts the whole task because options are
tracked `@Input`s and feed the cache key. This makes "one file changed" cost one file, not the whole
module.

**Relationship to the content-addressed key.** With these annotations, Gradle's own input
fingerprint *is* `(content-hash of sources, @Input options, task classpath)`. The
**formatter-version** dimension is supplied implicitly by the frmtr jar on the task's classpath:
when frmtr is upgraded, the classpath fingerprint changes and all cache entries invalidate. To make
this robust against same-version output changes during development, optionally add an explicit
`@Input` "format algorithm version" property so we control invalidation independently of the jar
coordinate. This means we do **not** need to hand-roll a content-addressed store for Gradle — we
declare inputs/outputs correctly and let Gradle's build cache be the content-addressed store.

## Determinism and output-ordering guarantees

- **Result order is input order.** `selectedFiles(...)` (runner) and `FileDiscovery` / plugin
  `selectedFiles()` already produce sorted, de-duplicated lists. The parallel map writes results
  into index-addressed slots, so `FormatRunResult.results()` is identical to the sequential output
  byte-for-byte.
- **Diffs are precomputed.** Each `FormatFileResult` carries its fully-rendered `diffText` before
  assembly, so no diff fragments interleave across threads. The runner should continue returning
  plain diff text; CLI colorization, including `--render-line-width` ruler coloring, stays in
  `Main` after ordered assembly.
- **CLI/Gradle result printing happens after assembly** (except print mode, addressed in (a)), so
  final output ordering is unaffected by scheduling. CLI progress is separate stderr output emitted
  while the runner is in flight.
- **Progress output is separate from result output.** Progress counters or "currently processing"
  diagnostics may be completion-ordered and ephemeral, but status lines, diffs, formatted source,
  and summaries must remain deterministic and script-compatible.
- **Discovery counters remain discovery-owned.** Ignored/excluded files are not processed by the
  runner and must not be counted as worker successes, failures, or progress completions. Preserve the
  current summary split: check reports processed selected files plus excluded count; write/print
  report selected + ignored + excluded.
- **Write safety.** Distinct, normalized, de-duplicated paths mean concurrent `Files.writeString`
  calls never target the same file.
- **Gradle incremental subset must not change reported semantics** for the *changed* files;
  unchanged files are legitimately skipped, which is the intended behavior, and the summary should
  make clear when a run was incremental.

## Risks

- **Nondeterministic logs / interleaving.** If any future code logs from within the per-file task,
  ordering could leak nondeterminism into output. Mitigation: keep all rendering inside the
  per-file result object; print only after the ordered assembly.
- **Progress output can accidentally become API output.** The generated-file hang evidence argues
  for visible progress, but CLI stdout already carries status lines, diffs, and formatted source.
  Mitigation: keep progress on stderr / Gradle progress APIs, make it clearly non-result output,
  and test that stdout remains byte-stable.
- **Print-mode memory pressure.** Parallelizing print mode by collecting all formatted source before
  printing can retain much more text than check/write, especially for generated files. Mitigation:
  keep print sequential in v1 or implement a bounded ordered drain instead of an unbounded
  all-results buffer.
- **Cache invalidation correctness (the classic hard problem).** The cache key MUST capture
  everything that affects output. `options` is safe (record `equals`). The dangerous dimension is
  **formatter-version**: a behavior change that does *not* bump the key produces stale "skips" and
  silently wrong (un-reformatted) files. Mitigation: tie the key to build commit and/or an explicit
  algorithm-version `@Input`, and bump it on any output-affecting change; cover with a test that a
  version bump re-processes files.
- **Failed check runs are not cache hits.** `frmtrJavaCheck` can be cacheable only for successful
  verification. If it finds unformatted files, Gradle fails the task and will not restore that
  failed result from cache on the next run. This is correct, but warm-run success metrics must be
  measured on already-formatted inputs.
- **In-place format outputs == inputs** complicates remote build-cache for `frmtrJavaFormat`.
  Mitigation: `frmtrJavaCheck` is fully cacheable; `frmtrJavaFormat` remains non-cacheable and does
  not use a synthetic marker to model source mutation.
- **Thread-pool placement inside Gradle workers.** Using the common ForkJoinPool could contend with
  Gradle's own parallelism, and one pool per parallel Gradle task can multiply total threads.
  Mitigation: own an explicit, bounded `ExecutorService` per run, keep the default conservative
  inside Gradle, and shut it down.
- **Hashing overhead.** For the CLI (if a cache were added) and even for Gradle's fingerprinting,
  hashing every file has a cost; it only wins on high warm-hit-rate workloads. Gradle already pays
  this and amortizes it, which is another reason to lean on Gradle rather than a bespoke CLI store.
- **Memory.** Parallelism means up to N files' source + AST + Doc live simultaneously. Bounding the
  pool bounds peak memory.
- **`PathSensitivity` / relocatability mistakes.** Accidentally tracking the absolute
  `projectDirectory` as an input would break cache sharing across machines/checkouts. Mitigation:
  keep it `@Internal`; only relative-path-sensitive source files feed the key.

## Success metrics

Tie measurement to **M1** (the benchmark-discipline item, `docs/proposals/README.md` §M1): M1 is a
prerequisite for *claiming* any speedup, and this work is exactly the kind of change M1's macro run
and CI regression gates exist to validate. Specifically:

- **Cold-run wall-clock on a large repo** (sequential vs parallel), reported as speedup and
  scaling vs core count. Expect near-linear up to memory/I/O limits on CPU-bound formatting.
- **Time to first visible progress** on a synthetic slow/generated-file repro: CLI runs should show
  completed counts or active-file diagnostics on stderr while other worker tasks continue. Gradle
  runs should either use a Gradle-native progress/logging follow-up or explicitly measure throughput
  only.
- **Warm-run wall-clock and `% files skipped`** for Gradle `frmtrJavaCheck`/`frmtrJavaFormat`:
  - first run (cold cache): 0% skipped;
  - re-run with no changes: ~100% skipped → task `UP-TO-DATE`/`FROM-CACHE`, near-instant;
  - re-run with one changed file: only that file processed (incremental subset of 1).
- **Determinism check:** parallel output is byte-identical to sequential output across repeated runs
  on the corpus (a golden-stability assertion, not just a timing number).
- **Progress-output compatibility:** CLI stdout remains byte-identical to the sequential baseline
  for `check`, `write`, and `print`; any progress appears only on the intended side channel. Gradle
  logs remain ordered enough to diagnose changed/failed files without diff interleaving.
- **Failure compatibility:** exit codes and failure streams remain byte-compatible for check/write/
  print, including `--stacktrace`, `WRITTEN_PARTIALLY`, and check-mode failed-file diagnostics.
- **No regression** on small runs: parallel overhead must not make a 1–5 file run slower (hence the
  `min(cores, files)` cap).

Use the same measurement hygiene `cli-discovery-lazy-ignore.md` established — multiple warm runs /
`hyperfine`, read-only external checkouts, no durable recording of benchmark-target names.

## Relationship to other proposals

- **`cli-discovery-lazy-ignore.md` (Implemented for CLI discovery).** That work removed the eager
  full-root `.gitignore` walk and made discovery load ignore rules lazily for the selector scope,
  cutting the *file-finding* cost (and keeping output deterministically sorted). It deliberately
  scoped itself to CLI discovery and left formatting/runner/Gradle selection unchanged, and it
  forbade a persistent discovery cache. **This proposal picks up where that left off:** discovery is
  now cheap, so the next bottleneck is the sequential *formatting* of discovered files and Gradle's
  reformat-everything behavior. We honor its non-goal by keeping a persistent CLI cache out of
  scope and leaning on Gradle's native build cache instead. The sorted, de-duplicated file lists it
  preserved are exactly what makes order-preserving parallelism trivial here.
- **M1 (benchmark discipline).** Must be used to measure this: cold parallel speedup, warm
  skip-rate, and a determinism/regression gate. Do not publish "faster" claims without M1 numbers.
- **M2 (linear-time renderer).** Orthogonal but complementary: M2 reduces per-file CPU; this reduces
  total files processed and parallelizes what remains. M1 will reveal whether parse or render
  dominates, directing whether M2 or this item yields more on a given workload.

## Non-goals

- Do not implement Gradle-native progress/logging in the Gradle incremental/cache slice.
- Do not introduce a persistent CLI results cache in v1 (respect `cli-discovery-lazy-ignore.md`).
- Do not change formatter output, `FormatRunResult` shape, CLI output formats, exit codes, or
  summary semantics for processed files.
- Do not add a per-file timeout as a substitute for diagnosing formatter hangs. Progress makes the
  run observable; core hangs still need root-cause fixes.
- Do not change `FileDiscovery` or the plugin's source-set selection logic.
- Do not add user-facing parallelism/version-key configuration in v1 beyond safe defaults
  (follow-up).
