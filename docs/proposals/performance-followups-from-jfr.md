# Performance Follow-ups From M2a JFR Sampling

Status: Proposed

## Summary

M2a removed the targeted `DocWidths.Measurement` record allocation path, but a paired macro run did
not show a clear whole-CLI wall-clock win. The denser JFR allocation recording did, however, confirm
that the exact `DocWidths$Budget` and `DocWidths$MeasureResult` allocations disappeared. That makes
M2a a valid internal-overhead cleanup, but not a performance story by itself.

The useful outcome is a ranked backlog of broader performance seams. The JFR samples point away from
the renderer-width path and toward raw-source normalization, comment-query scans, parser/formatter
lifecycle, stream-heavy method-chain analysis, startup, and file discovery. Each item below is scoped
so it can be investigated independently.

All corpus references in this proposal are intentionally anonymous. The benchmark target was a large
external Java corpus used as a read-only macro workload; proposal docs should keep using corpus size
and behavior numbers instead of repository names or paths.

## Measurement snapshot

The macro command shape was:

```text
frmtr-cli --check --progress=never --color=never <large-external-java-corpus>
```

The corpus contained 631 checked Java files. Check mode exited `1` in every pass because the corpus
had existing formatter drift:

```text
Checked 631 files: 237 unchanged, 394 would change.
```

That exit status was stable across both branches and is useful for timing as long as the run stays
read-only and no `--write` pass mutates the corpus.

### Paired wall-clock run

The paired run used one warmup pass per branch, then 20 alternating pairs. Pair order alternated
(`M2a -> main`, then `main -> M2a`) to reduce order bias. Times are seconds.

| Build | n | min | median | mean | p95 | max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| M2a | 20 | 3.21 | 4.50 | 4.97 | 6.78 | 7.11 |
| main | 20 | 3.51 | 4.26 | 4.79 | 9.14 | 9.25 |

Paired deltas use `M2a - main`:

| Metric | Value |
| --- | ---: |
| mean delta | +0.18s |
| median delta | -0.09s |
| min delta | -3.65s |
| p95 delta | +2.28s |
| max delta | +3.06s |
| M2a faster | 10 / 20 pairs |
| M2a slower | 10 / 20 pairs |

Interpretation: whole-CLI timing is noise-level for M2a. It should not be described as a macro
speedup.

### Allocation evidence

A normal profile JFR did not reliably surface exact `DocWidths` classes, so a second run used denser
allocation sampling (`allocation-profiling=maximum`). Exact allocation-class matches:

| Build | Class | Samples | Sampled weight |
| --- | --- | ---: | ---: |
| main | `dev.lanwen.frmtr.doc.DocWidths$Budget` | 19 | 6496.3 kB |
| main | `dev.lanwen.frmtr.doc.DocWidths$MeasureResult` | 20 | 7193.9 kB |
| M2a | exact `DocWidths$Budget` / `DocWidths$MeasureResult` matches | 0 | 0 kB |

Interpretation: M2a removes the targeted allocation shape, but those allocations are too small a
slice of the full check run to move macro timing reliably.

### Broader JFR shape

The samples were short, but the recurring signals were consistent enough to guide follow-up work:

- Top allocation classes were dominated by `byte[]` (about 28-29%), `Object[]` (about 10%),
  stream pipeline objects, `String`, `LinkedList` / `ArrayList`, `Optional`, primitive arrays, and
  JavaParser `Token` / `Range` / `Position`.
- Top allocation sites included array allocation/copying, stream setup/filtering, JavaParser
  `Node.getAllContainedComments`, `Optional.ofNullable`, regex compilation/matching, `Range.range`,
  and `Token.newToken`.
- Execution samples pointed first at JavaParser tokenization/lookahead, then formatter paths:
  `RawSource.normalizeWhitespace`, `JavaCommentPlacementPolicy.lineCommentsInRange`,
  `CommentedExpressionListPrinter`, `MethodCallChain*`, `CompactSourceText`, and
  `Doc.concat` / `flattenConcat`.
- JavaParser control-flow exceptions (`LookaheadSuccess`) and parser-buffer `IOException` paths were
  visible in both branches, so parser cost remains a large baseline component.
- Startup/classpath work was visible in fresh-process samples: Picocli reflection, class loading,
  jar verification/signature work, and first-use lambda/linkage.
- Default recordings saw 27 young GCs on main and 31 on M2a over roughly one check process. Pauses
  were generally single-digit milliseconds, so GC pause time was not the obvious bottleneck, but
  allocation volume is still high.

## Investigation lane 1: raw-source whitespace churn

**Priority:** medium. **Confidence:** high.

### State

`stripTrailingHorizontalWhitespace` is a single-pass scan: its per-op allocation scales linearly with input, and it
is out of the top allocation tier on the macro corpus. `normalizeWhitespace` is the remaining churn — it applies
several regex passes (`WHITESPACE` twice, `ASSIGN_EQUALS`, `SPACE_AFTER_OPENER`, `SPACE_BEFORE_CLOSER`) to each
non-literal region and stays a top formatter-owned CPU path (about 7% of execution samples), with modest allocation.

The `:frmtr-bench` JMH module measures these helpers directly against the real code; pair it with an
`allocation-profiling=maximum` JFR macro run to attribute pipeline-level share.

### Question

Can compact whitespace normalization avoid repeated regex matching and replacement without changing output?

Sub-questions:

- Can `flushOutside` collapse whitespace, apply the `=` spacing rule, and fix opener/closer interior spacing in one
  hand-written scan instead of five `Matcher`/`replaceAll` passes?
- Can compact raw text be cached per original node within one formatting run so callers do not normalize the same
  token range repeatedly? Such a cache must live for one run and never cross source strings or AST instances.

### Code area

- `RawSource.normalizeWhitespace` / `RawSource.flushOutside`
- `CompactSourceText.compact`

### Proposed experiment

1. Extend `RawSourceNormalizationBenchmark` with the normalization inputs under study.
2. Prototype a single-scan `flushOutside` behind the existing method.
3. Run the full fixture suite with AST-equivalence enabled, plus a differential property test against the current
   regex output.
4. Re-run the paired macro check and an `allocmax` JFR pass; confirm `normalizeWhitespace` leaves the top CPU path.

### Success metrics

- Byte-identical formatter output across fixtures and the macro corpus summary.
- `normalizeWhitespace` allocation and CPU samples drop.
- No regression in paired macro median beyond noise.

### Risks

- The `=` spacing and opener/closer rules are subtle; a hand-written scan must preserve spacing around
  assignment-like tokens and never rewrite inside string/character/text-block literals or line comments.
- A cache can retain cloned/detached nodes if it is not scoped to original parse trees.

## Investigation lane 2: comment-query indexing

**Priority:** high. **Confidence:** medium-high.

### Signal

JFR repeatedly shows `Node.getAllContainedComments`, `lineCommentsInRange`, trailing argument comment queries, and argument-gap comment checks.
`JavaCommentMap` computes recursive containment bottom-up, and original-tree boolean gates read the index.
Remaining direct calls need filtered lists, identity accounting, compatibility checks, or clone-local facts.

### Question

Can comment placement answer common range/line/containment questions from one run index instead of
recursive JavaParser scans and repeated stream filtering?

Sub-questions:

- Which remaining full-list and identity callers are on the hot print path?
- Which repeated filter shapes deserve intent-specific policy queries?
- Can `JavaCommentPlacementPolicy` expose source-line indexes for common range queries without
  turning into a service locator?

### Code area

- `JavaCommentMap`
- `JavaCommentPlacementPolicy`
- `CommentIndex`
- `CommentedExpressionListPrinter`
- full-list filtering and identity-accounting callers that still ask JavaParser directly

### Proposed experiment

1. Add temporary counters for comment queries per formatted file:
   - contained-comments full-list reads,
   - indexed boolean containment gates,
   - line-range queries,
   - stream filters over contained comments.
2. Pick one narrow family first: argument-list trailing/gap comments.
3. Add policy methods for the exact query shape and migrate only that family.
4. Verify fixtures and AST-equivalence, then compare allocation-by-site and macro timing.

### Success metrics

- Direct JavaParser contained-comment scans drop for the migrated family.
- Allocation-by-site shows fewer stream/list allocations around comment queries.
- No fixture output changes unless a pre-existing inconsistency is explicitly reviewed.
- Query methods remain named by intent, not by low-level data structure.

### Risks

- JavaParser own/orphan/contained comment semantics are identity-sensitive. The index must preserve
  raw `Comment` identity through `JavaCommentTrivia`.
- Detached clones may not exist in the run index; unknown nodes must not silently look like
  comment-free original nodes.
- This overlaps B1/B2 source-shape work, so each migration should own one query family.

## Investigation lane 3: worker-local formatter/parser reuse

**Status:** narrow reuse slice landed. **Priority:** high. **Confidence:** medium.

### Signal

Fresh `Frmtr.format(source, options)` constructs a new `JavaFormatter`, and each `JavaFormatter`
constructs a new `JavaParser` and `ParserConfiguration`. JFR showed parser configuration,
language-level validation, JavaParser tokenization/lookahead, `Token.newToken`, `Range.range`, and
`Position` allocation across worker stacks.

### Question

Can file-oriented runners reuse one formatter/parser per worker thread for identical
`FormatterOptions` while keeping public `Frmtr.format(...)` pure and thread-safe?

The current implementation landed that narrow slice: `FrmtrSession` exposes a public sequential
session API for callers that intentionally reuse formatter state, and the file-oriented runner keeps
session reuse worker-local. The static `Frmtr.format(...)` entry points remain pure one-shot calls.

### Code area

- `Frmtr.format`
- `FrmtrSession`
- `JavaFormatter`
- `FormatterRunner`

### Landed slice

1. Kept public `Frmtr.format(...)` unchanged as a one-shot static API.
2. Added `FrmtrSession` as an explicit sequential reuse API.
3. Updated the file-oriented runner to reuse one session per worker for that worker's files.
4. Kept formatter/session instances worker-local rather than shared across threads.

### Remaining follow-up

1. Run paired macro timing on a large external Java corpus.
2. Add JFR comparison focused on parser setup and token/range allocation.
3. Use those measurements to decide whether additional parser/session reuse work is justified.

### Success metrics

- No behavior/output changes.
- Runner parallelism remains deterministic and order-preserving.
- Parser setup samples decrease in the file-runner path.
- Macro timing improves or at least parser setup allocation drops enough to justify any additional
  runner or API surface.

### Risks

- Reusing parser objects may retain state in JavaParser internals. Reuse should remain sequential or
  worker-local unless JavaParser thread-safety is proven.
- The public API now includes both static one-shot entry points and explicit sequential sessions;
  future optimization should preserve that distinction.
- Verify-mode reparse uses the same parser configuration and must stay semantically equivalent.

## Investigation lane 4: method-chain and comment-analysis stream flattening

**Priority:** medium-high. **Confidence:** medium.

### Signal

Allocation classes included stream pipeline objects, and stack samples pointed at method-chain
planning and comment analysis: `MethodCallChainSourcePlanner.analyze`, trailing line comments before
chain segments, argument comment gaps, repeated `anyMatch`, and `toList`.

### Question

Can chain traits and chain-comment predicates be computed once in simple loops and reused through the
printer path?

### Code area

- `MethodCallChainSourcePlanner`
- `MethodCallChainPrinter`
- `CommentedExpressionListPrinter`

### Proposed experiment

1. Add counters for chain-analysis calls and comment predicate calls per method chain.
2. Pick one chain-heavy fixture subset and one corpus slice.
3. Replace repeated stream predicates with one local analysis object only for that slice.
4. Compare fixture output, allocation-by-site, and `--explain` output where chain decisions are
   labeled.

### Success metrics

- Fewer stream pipeline allocation samples around method-chain analysis.
- Chain-analysis code becomes more explicit instead of adding another boolean web.
- No formatter output changes without fixture review.

### Risks

- Method-chain layout already has many source-shape exceptions. A performance pass can easily make
  the code harder to reason about if it only inlines streams into more conditionals.
- This lane should be paired with B1's source-shape policy work when source layout probes are the
  reason for repeated analysis.

## Investigation lane 5: startup vs warmed formatting

**Priority:** medium. **Confidence:** medium.

### Signal

Fresh-process JFR samples included Picocli reflection, class loading, jar verification/signature
work, service/console setup, and first-use lambda/linkage. These costs are mixed into every CLI
macro pass, so formatter-internal wins can disappear in process startup noise.

### Question

How much of user-visible `frmtr-cli --check` latency is startup and packaging rather than file
formatting?

### Code area

- `frmtr-cli` `Main`
- distribution packaging
- native image / CDS build paths

### Proposed experiment

1. Measure `frmtr-cli --version` or an empty/no-file command as a startup baseline.
2. Compare installed JVM CLI, warmed in-process runner harness, and native image if available.
3. Use the same large corpus for full runs, but report startup-only and warmed-formatting numbers
   separately.

### Success metrics

- A published split: startup-only, discovery-only, parse/format/check-only.
- Evidence for whether native image, AppCDS, or packaging changes are worth prioritizing.
- Macro comparisons stop conflating startup with formatter pipeline changes.

### Risks

- Startup-focused wins may not help Gradle/plugin/editor integrations if those already run inside a
  warm JVM.
- Native-image comparisons need separate compatibility checks and should not block core formatter
  work.

## Investigation lane 6: discovery and ignore matching

**Priority:** medium-low. **Confidence:** medium.

### Signal

JFR samples included file discovery, ignore matching, path normalization/relativization, and native
directory stat/read operations. Prior measurements suggested discovery was not the long pole, but it
is still visible in fresh-process runs.

### Question

After formatter-owned hot paths improve, does discovery become material again on large trees?

### Code area

- `FileDiscovery`
- ignore-rule matching and relative-path handling

### Proposed experiment

1. Add a discovery-only timing mode or temporary harness that returns the selected Java files without
   formatting them.
2. Measure selector scopes separately: explicit files, one directory, repository root, and excluded
   fixture/resource trees.
3. Try caching relative paths or ignore-rule lookup only if discovery-only timing justifies it.

### Success metrics

- Discovery-only timing is reported independently from formatting.
- Any optimization preserves sorted deterministic output and existing exclude precedence.
- No persistent CLI cache is introduced unless a separate proposal accepts it.

### Risks

- Discovery optimizations can introduce subtle include/exclude regressions.
- The existing lazy-ignore work already removed the largest known discovery issue; this lane should
  stay behind formatter-side wins unless new measurements say otherwise.

## Suggested sequencing

1. **Raw-source `normalizeWhitespace`**: the residual formatter-owned CPU target now that the trailing-whitespace
   strip is single-pass.
2. **Comment-query indexing**: likely large, but should be split by query family.
3. **Worker-local formatter/parser reuse**: narrow session reuse landed; measure before widening it.
4. **Method-chain/comment stream flattening**: useful after the source-shape/comment policy surface
   is clearer.
5. **Startup split**: run in parallel with performance claims so macro numbers are interpretable.
6. **Discovery isolation**: revisit after formatter and startup costs are separated.

## Measurement hygiene for follow-ups

- Use anonymized corpus labels and stable counts; do not record repository names or paths in docs.
- Prefer paired/interleaved runs with warmups over separate 20-pass blocks.
- Keep check-mode read-only; never use `--write` for macro timing.
- Report exit status and summary line so timing data is interpretable.
- Keep JFR settings in the report because allocation sampling settings can perturb the run.
- Treat short JFR recordings as hypotheses. Require fixture/AST-equivalence checks plus repeated
  paired timing before claiming a speedup.
