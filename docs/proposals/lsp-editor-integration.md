# Editor integration via an LSP server, with range formatting

Status: Proposed

## Summary

`frmtr` ships as a CLI (`frmtr-cli`) and a Gradle plugin (`frmtr-gradle-plugin`) today. Both are
batch-shaped: they discover files, format whole documents, and write or diff them. There is no way
for an editor to ask "format this buffer on save" or "format just this selection," which is the
single most common path users hit a formatter through.

This proposal adds a small Language Server that speaks the two formatting requests editors care
about — `textDocument/formatting` (whole document) and `textDocument/rangeFormatting` (a selected
region) — backed by the existing native binary for near-instant startup. The whole-document request
maps cleanly onto today's `Frmtr.format(String, FormatterOptions)`. The range request does not: the
public API only formats whole documents, so range formatting is the real engineering work here, and
it must be designed in `frmtr-core` rather than faked in the server.

The work splits cleanly into two shippable phases: (1) a `frmtr-lsp` module that does whole-document
format-on-save and returns a single full-document `TextEdit`; (2) a `Frmtr.formatRange(...)` core
capability plus `rangeFormatting` wiring on top of it. Phase 1 alone unlocks format-on-save in
VS Code, IntelliJ, and Neovim.

## Why LSP (adoption)

The roadmap (`docs/proposals/README.md`, item **M4**) names editor integration as
"the single biggest driver of adoption" and rates it medium-large effort, user-friendly, with reach.
The reasoning:

- **Format-on-save is where formatters earn trust.** Most developers never run `frmtr-cli` by hand;
  they configure their editor to format on save and forget the tool exists. Until `frmtr` can do
  that, it competes only on CI/Gradle ground that google-java-format, palantir-java-format, and
  Spotless already own.
- **One protocol, three editors.** A conformant LSP server gives VS Code, IntelliJ
  (External Formatters / LSP4IJ), and Neovim (`none-ls` / native LSP) the same formatting behavior
  with thin per-editor glue, instead of three bespoke plugins.
- **Range formatting is table stakes for IDEs.** IntelliJ's "Reformat selection" and VS Code's
  "Format Selection" both issue `rangeFormatting`. Without it, the server can only no-op or silently
  reformat the whole file, both of which feel broken.
- **The native binary makes per-keystroke-class latency realistic.** `frmtr-cli` already compiles to
  a GraalVM native image (`Dockerfile.native`, `:frmtr-cli:nativeCompile`). A long-lived LSP process
  with a warm parser amortizes even the JVM cost away; a native LSP removes cold-start entirely.

## Gap analysis

### The public API is whole-document only

`frmtr-core/src/main/java/dev/lanwen/frmtr/Frmtr.java` exposes exactly:

- `format(String source)` — defaults.
- `format(String source, FormatterOptions options)` — explicit options.
- `debugDoc(...)` — debug document tree, not user-facing.

Every entry point takes a full source string and returns a full formatted string. There is **no**
offset-, range-, or node-scoped formatting. `JavaFormatter.format(String)`
(`frmtr-core/.../java/JavaFormatter.java`) parses the whole source into a `CompilationUnit`, runs
transforms, prints a `Doc`, and renders the entire document. **This is the core gap for
`rangeFormatting`** — it must be closed in `frmtr-core`, not in the LSP layer.

### Output is a whole-string replacement, not edits

`Frmtr.format` returns the entire formatted document. The CLI/tooling layer
(`frmtr-tooling/.../FormatterRunner.java`, `UnifiedDiffRenderer.java`, `FormatFileResult.java`)
diffs old-vs-new only for *display* (unified diff, status counts). It never emits structured edits.
LSP wants `TextEdit[]`. The simplest correct mapping for whole-document formatting is a **single
full-document `TextEdit`** that replaces the entire range `[0,0]–[lastLine,lastCol]` with the
formatted text; the editor computes its own minimal diff. Range formatting needs **sub-document**
edits, which today's pipeline cannot produce.

### Options surface is close to LSP `FormattingOptions`, but not 1:1

`FormatterOptions` (`frmtr-core/.../FormatterOptions.java`) is a record with: `lineWidth`,
`indentStyle` (`SPACE`/`TAB`), `indentWidth`, `lineEnding` (`LF`/`CRLF`), `trailingNewline`,
`preserveRawTrailingWhitespace`, `requirePragma`, `lambdaArrowParens`, `binaryOperatorPosition`,
`parseErrorBehavior` (`RECOVER`/`FAIL`), and `javaLanguageLevel`. Convenient withers exist
(`withIndentStyle`, `withIndentWidth`, `withLineWidth`, etc.) plus `defaults()`.

LSP `FormattingOptions` carries `tabSize`, `insertSpaces`, and optionally
`trimTrailingWhitespace` / `insertFinalNewline` / `trimFinalNewlines`. The mapping is direct:

| LSP `FormattingOptions`     | `FormatterOptions`                                        |
| --------------------------- | --------------------------------------------------------- |
| `insertSpaces: true`        | `indentStyle = SPACE`                                     |
| `insertSpaces: false`       | `indentStyle = TAB`                                       |
| `tabSize`                   | `indentWidth` (used as the space width; ignored for TAB)  |
| `insertFinalNewline`        | `trailingNewline`                                         |
| (no LSP equivalent)         | `lineWidth` — server/workspace setting, default `120`     |
| (no LSP equivalent)         | `lambdaArrowParens`, `binaryOperatorPosition`, `javaLanguageLevel` — server config |
| (always)                    | `parseErrorBehavior = RECOVER` (editors send dirty buffers) |

Note `Main.formatterOptions()` today hardcodes `SPACE`, `DEFAULT_INDENT_WIDTH`, and `LF` and only
exposes `--line-width`, `--java-level`, `--parse-error-behavior`. The LSP server should drive
`FormatterOptions` from the request's `FormattingOptions` plus a workspace config block, so it is
strictly more configurable than the CLI here — no core change needed for the mapping itself.

### Recovery already targets editor flows — but recovers a bounded node set

`ParseErrorBehavior.RECOVER` is documented as the mode "for editor and local formatting flows where
valid surrounding code should still be formatted while broken source is preserved," and it is the
default in `FormatterOptions.defaults()`. `JavaFormatter` only accepts a recovered unit when every
recovered node is one of a closed set of supported list shapes (block-statement lists, class/
interface/record member lists, import lists, top-level declaration lists, module-directive lists,
switch-entry lists, enum-constant lists, annotation-member lists — see
`JavaFormatter.isSupportedRecovery`). Anything else throws a `FormatterException` with a recovery
reason. So the LSP server inherits a real but **bounded** dirty-buffer story: many mid-edit buffers
recover, but some throw. The server must degrade gracefully (return zero edits) rather than surface
an error popup on every keystroke-class save.

## Proposed architecture

### (a) New `frmtr-lsp` module *(proposed-new)*

Add `include("frmtr-lsp")` to `settings.gradle.kts` *(existing file)* and a module that
`implementation(project(":frmtr-core"))` *(existing dependency target)*. It does **not** depend on
`frmtr-cli` or `frmtr-tooling`; the LSP server and the CLI are sibling adapters over the same core,
mirroring how `frmtr-cli` and `frmtr-gradle-plugin` already sit over `frmtr-core` /
`frmtr-tooling`.

Two implementation options for the JSON-RPC/LSP plumbing:

- **LSP4J** (Eclipse, the de-facto JVM LSP library). Pros: complete, well-tested, handles framing,
  lifecycle, capabilities, and the `TextDocument` types. Cons: it leans on reflection and dynamic
  proxies for the JSON-RPC dispatch, which is the classic GraalVM native-image friction point (see
  Risks). The repo already has the machinery to handle exactly this kind of reflection config — see
  (c).
- **Minimal hand-rolled JSON-RPC server** *(proposed-new)*. Pros: native-image-friendly by
  construction (no dynamic proxies, explicit method dispatch, only the handful of messages we
  implement). Cons: we own framing, `Content-Length` parsing, and the initialize/shutdown/exit
  lifecycle.

**Recommendation:** start on **LSP4J** for Phase 1 to move fast and get the lifecycle right, and
treat a minimal JSON-RPC fallback as the contingency if native-image reflection config for LSP4J
proves brittle (the surface we actually use — `initialize`, `textDocument/didOpen|didChange|
didClose`, `textDocument/formatting`, `textDocument/rangeFormatting`, `shutdown`, `exit` — is small
enough that hand-rolling is genuinely viable).

Server responsibilities:

1. Advertise `documentFormattingProvider` and (Phase 2) `documentRangeFormattingProvider` in the
   `initialize` capabilities.
2. Track open buffers from `didOpen`/`didChange`/`didClose` (in-memory text; editors send dirty,
   unsaved content).
3. On `textDocument/formatting`: build `FormatterOptions` from `FormattingOptions` + workspace
   config, call `Frmtr.format(buffer, options)`, and return a **single full-document `TextEdit`**.
4. On `textDocument/rangeFormatting`: call the new `Frmtr.formatRange(...)` (see (b)) and return the
   edits it produces.
5. On any `FormatterException` (unrecoverable parse, unsupported recovery), return an **empty
   `TextEdit[]`** and log; do not reformat partially and do not raise a user-facing error per save.

`FormatterException` already distinguishes parse failures from internal failures (`Frmtr.format`
rethrows `FormatterException` and wraps other throwables via `FormatterException.internal`), so the
server can log internal failures at a higher severity while treating parse failures as routine
no-ops.

### (b) Range formatting design in `frmtr-core` — the hard part *(proposed-new)*

The core only knows how to format a whole document, so `rangeFormatting` needs a new core
capability. The contract: given source, a requested `[start,end)` region, and options, produce edits
that bring the **enclosing formattable construct(s)** into formatted shape while leaving everything
outside untouched (or at most a deterministic superset of the selection, which is LSP-legal — the
spec lets a server widen the formatted range).

Three candidate strategies:

**Option 1 — Enclosing-node expansion (format a sub-AST in place).**
Map the requested offsets to AST nodes via `SourceText`/`SourceRegion`
(`frmtr-core/.../java/SourceText.java`, `SourceRegion.java`) — these already convert JavaParser
one-based line/column ranges to half-open character offsets. Find the smallest enclosing
statement/member/declaration that fully contains the selection, print *just that node's* `Doc` at
the correct base indentation, and emit a single `TextEdit` replacing that node's source region.
- Pros: edits are tightly scoped; surrounding code is provably untouched; output is small.
- Cons: the printers are built to print a whole `CompilationUnit` from the top; printing a node in
  isolation requires re-establishing its indentation context, surrounding-blank-line policy, and the
  source-shape signals threaded through the printers (the same coupling B1 flags). Significant new
  core surface, and the most correctness-risky.

**Option 2 — Format whole file, emit only edits overlapping the range.**
Run the existing whole-document `Frmtr.format`, diff old-vs-new to a list of hunks (the tooling
layer already diffs in `UnifiedDiffRenderer`), and **keep only the hunks that intersect the
requested range**, discarding the rest.
- Pros: reuses the entire, battle-tested whole-document pipeline; correctness of the *formatting* is
  identical to format-on-save; small, well-contained new code (offset math + hunk filtering).
- Cons: requires the whole buffer to parse/recover (a broken region outside the selection can still
  abort the format); does redundant work formatting the whole file; a reformat far from the
  selection that *touches* the boundary line could leak in. The leak is bounded by clamping kept
  hunks to the requested range's line span.

**Option 3 — Hybrid: enclosing-node region + whole-file format + clamp.**
Expand the selection to the smallest enclosing top-level-ish member via `SourceText`, format the
whole file, then keep only diff hunks within that expanded region. Combines Option 1's scoping with
Option 2's reuse.

**Recommendation: Option 2 for Phase 2, with Option 3's clamping.** It reuses the existing whole-
document formatter verbatim, so range-format output can never disagree with format-on-save output
(a property users will absolutely notice), and it needs only offset/diff plumbing rather than a new
"print a node in isolation" capability in the printers. Concretely, add
`Frmtr.formatRange(String source, int startOffset, int endOffset, FormatterOptions options)` that
returns a structured list of `(region, replacement)` edits, implemented as: format whole document →
compute line-aligned diff hunks → drop hunks that do not overlap `[startOffset,endOffset)` →
return the survivors. Expand the kept range to whole lines (editors expect line-granular range
formatting) and to the enclosing statement/member boundary where cheap, so partial-line selections
behave sanely. Revisit Option 1 only if profiling shows whole-file formatting per range request is
too slow on very large files — unlikely with a warm native process, and **M2** (linear-time
renderer) further de-risks it.

This keeps the **format quality** decision in one place (the whole-document pipeline) and makes range
formatting a pure *scoping* concern, which is much easier to test and reason about.

### (c) Native-image startup story *(extends existing native support)*

`frmtr-cli` already produces a native image: `Dockerfile.native` runs
`./gradlew :frmtr-cli:nativeCompile` against `frmtr-core` + `frmtr-cli` and copies the `frmtr`
binary out. The native build plumbing exists in `frmtr-cli/build.gradle.kts`
(`alias(libs.plugins.graalvm.native)`, `graalvmNative { binaries { named("main") { imageName =
"frmtr" } } }`) and the `frmtr-native-image-support` module already supplies the **JavaParser AST
reflection config** that native image needs (`JavaParserAstReflection`,
`JavaParserReflectionFeature`, plus a `native-image.properties`). The CLI also uses picocli's AOT
processor to generate native-image config.

For the LSP server, mirror this:

- Add a native binary target to `frmtr-lsp` (its own `graalvmNative` block, `imageName = "frmtr-lsp"`
  or a subcommand of the existing `frmtr` binary) reusing
  `nativeImageCompileOnly(project(":frmtr-native-image-support"))` so JavaParser reflection is
  already covered.
- A native LSP keeps cold-start in the tens of milliseconds and the process is long-lived anyway, so
  per-request latency is dominated by parse+format, not startup. This is the property that makes
  format-on-save feel instant.
- The native image must also cover whatever reflection the JSON-RPC layer needs. With a **minimal
  hand-rolled server** this is essentially nothing. With **LSP4J**, its proxy/reflection usage will
  need reachability metadata — generate it with the GraalVM tracing agent during an LSP test session
  and check it in alongside the existing native-image resources, or fall back to the minimal server.

A JVM-mode LSP jar should also ship as a fallback for platforms without a published native binary;
the long-lived process hides the JVM warmup after the first request.

### (d) Editor distribution *(proposed-new, mostly out-of-repo glue)*

- **VS Code extension** — a thin TypeScript extension that launches the `frmtr-lsp` binary and
  registers it as the document/range formatting provider for Java. The bulk of the behavior lives in
  the server; the extension is launch + config plumbing.
- **IntelliJ** — register via the LSP API (LSP4IJ plugin or the platform's LSP support) pointing at
  the same binary, or expose it as an External Formatter. Range formatting maps to IntelliJ's
  "Reformat selection."
- **Neovim** — no plugin needed; document it as an `nvim-lspconfig` / `none-ls` entry that points at
  the `frmtr-lsp` binary. This is the lowest-cost editor to support and good for early dogfooding.

Per-editor packaging can land incrementally; the protocol contract is the stable interface.

## Incremental rollout

1. **Phase 1 — whole-document format-on-save.** `frmtr-lsp` module + LSP4J server, `initialize`/
   lifecycle, buffer tracking, `textDocument/formatting` → single full-document `TextEdit` over
   `Frmtr.format`, `FormattingOptions`→`FormatterOptions` mapping, empty-edits on
   `FormatterException`. Ship JVM jar first, then native binary. Validate end-to-end in Neovim, then
   VS Code. This phase needs **zero `frmtr-core` changes**.
2. **Phase 2 — range formatting.** Add `Frmtr.formatRange(...)` (Option 2 + clamp) in `frmtr-core`,
   advertise `documentRangeFormattingProvider`, wire `textDocument/rangeFormatting`. Add IntelliJ
   registration.
3. **Phase 3 — polish.** Workspace config (line width, lambda/operator/Java-level), incremental
   document sync, optional formatting diagnostics, packaged VS Code extension.

## Risks

- **Dirty-buffer parsing.** Editors send incomplete/invalid source constantly. `RECOVER` handles a
  bounded set of node shapes (`JavaFormatter.isSupportedRecovery`); outside that set `Frmtr.format`
  throws. The server must treat this as a routine no-op (empty `TextEdit[]`), never an error popup.
  Widening the recoverable node set is a `frmtr-core` follow-up, related to **B1**.
- **Partial-format correctness.** Range formatting must never (a) change code outside the user's
  selection beyond a deterministic, line-aligned widening, or (b) produce output that disagrees with
  whole-document formatting. Option 2 structurally guarantees (b); (a) is enforced by clamping kept
  hunks to the requested line span. This needs heavy property testing (overlapping ranges,
  whole-file == union of full-range edits, idempotence) — directly dependent on **B3**.
- **Native-image + LSP4J reflection.** LSP4J's dynamic proxies are the canonical native-image
  failure mode. Mitigations: GraalVM tracing-agent-generated metadata checked in next to the
  existing `frmtr-native-image-support` config, or the minimal hand-rolled JSON-RPC server which
  sidesteps the problem. JavaParser reflection is already solved by `frmtr-native-image-support`.
- **Behavioral divergence from CLI.** If the server and CLI map options differently, users get
  different output in-editor vs in-CI. Mitigation: both adapters must build `FormatterOptions` from
  the same documented mapping; consider lifting the mapping into a shared helper.
- **Document sync correctness.** Off-by-one in LSP `Position` (line/character, UTF-16 code units) vs
  `frmtr`'s character offsets. `SourceText` already does one-based-line/column ↔ offset conversion,
  but LSP positions are zero-based and UTF-16-counted; the conversion needs its own focused tests.

## Success metrics

- Format-on-save works in VS Code, IntelliJ, and Neovim against the same `frmtr-lsp` binary.
- p50 whole-document format latency on a warm native server is dominated by parse+format, not
  startup or RPC; cold start of the native binary is in the tens of milliseconds.
- Range-format output is a strict subset of whole-document format output for the same buffer
  (verified by property test: applying full-range range-formatting equals whole-document
  formatting).
- Dirty-buffer saves never surface a user-facing error; unrecoverable buffers cleanly no-op.
- Adoption signal: published VS Code extension installs / Neovim config usage trending up after
  release.

## Relationship to other roadmap items

- **M3 (parallelism + content-addressed caching).** Shares the same `frmtr-core` entry points. M3's
  `(content-hash, options, formatter-version)` cache key concept maps onto the LSP server too: a
  long-lived process can cache the last formatted result per `(buffer-version, options)` and skip
  re-formatting unchanged saves. Both items want the core to stay the single source of formatting
  truth.
- **B3 (correctness safety net).** Range-format correctness *needs* B3's net. The "range-format ⊆
  whole-format" and idempotence properties this proposal relies on are exactly the AST-equivalence /
  idempotence / corpus machinery B3 builds. Phase 2 should land on top of, or alongside, B3 layer 1.
- **B1 (formatter-owned source view).** The bounded recovery set and the difficulty of printing a
  node in isolation (Option 1) both trace back to source-shape coupling B1 targets; B1 progress
  widens what the LSP server can recover and would make Option 1 viable if it is ever needed.
- **M2 (linear-time renderer).** De-risks Option 2's "format the whole file per range request" cost
  on large files.

## Non-goals

- Do not implement the code in this proposal-only change.
- Do not change `frmtr-core` formatting behavior, the CLI, the Gradle plugin, or existing native
  build config as part of this proposal.
- Do not add semantic editor features (completion, diagnostics beyond format failures, code actions);
  this server is formatting-only.
- Do not replace the whole-document pipeline; range formatting is layered on top of it.
