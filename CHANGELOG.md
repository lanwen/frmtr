# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.2.1] - 2026-07-15

### Merged Pull Requests

- `fix` fix(core): preserve chain comments stranded by a width-driven root-to-first-selector join ([#347](https://github.com/lanwen/frmtr/pull/347))
- `docs` docs: require present-state comments; archive the actioned comment-handling-findings ([#348](https://github.com/lanwen/frmtr/pull/348))
- `fix` fix(gradle): surface formatter progress ([#351](https://github.com/lanwen/frmtr/pull/351))
- `perf` perf(core): single-pass raw-source trailing-whitespace strip (+ frmtr-bench JMH module) ([#352](https://github.com/lanwen/frmtr/pull/352))
  Rewrites the raw-source trailing-whitespace strip as a single linear pass, eliminating the quadratic string concatenation that was the formatter pipeline's largest allocation source (~49% → ~0.5% of sampled allocation on a large macro corpus; ~46% less total allocation). Output is byte-identical. Adds a `:frmtr-bench` JMH module for measuring formatter hot paths.
- `perf` perf(core): index comment-presence gates ([#353](https://github.com/lanwen/frmtr/pull/353))
- `docs` docs: mark B3 corpus harness (Layer 3) as shipped and archive the proposal ([#354](https://github.com/lanwen/frmtr/pull/354))
  Docs-only: reconcile the B3 correctness-net roadmap to present-state (Layer 3 corpus harness has shipped as `corpus.yml`); no formatter, CLI, or build behavior changes.
- `perf` perf(core): single-scan raw-source whitespace normalization ([#355](https://github.com/lanwen/frmtr/pull/355))
  Replaces `RawSource`'s five-regex compact-source whitespace normalization with a single byte-identical hand scan, removing the `Pattern` machinery (notably the `=`-spacing lookbehind/lookahead backtracking) from the hot path. ~7× faster and 3.6–12.7× less allocation on the `:frmtr-bench` normalize benchmarks; output is unchanged, pinned by a differential property test over 200k randomized inputs.

## [0.2.0] - 2026-07-14

### Merged Pull Requests

- `docs` docs(proposals): convergence redesign — source-neutral fan-out + opener-attachment ranking ([#238](https://github.com/lanwen/frmtr/pull/238))
- `feat` feat(doc): add opener-attachment priority to BestFitting ranking (convergence slice 1) ([#239](https://github.com/lanwen/frmtr/pull/239))
- `refactor` refactor(core): extract a source-neutral chainFanOut builder (convergence slice 2) ([#240](https://github.com/lanwen/frmtr/pull/240))
- `feat` feat(core): route the initializer fan-out convergence through the ranked engine (#191) ([#241](https://github.com/lanwen/frmtr/pull/241))
- `feat` feat(core): dot-split an initializer single-simple-arg tail instead of opening it (#221) ([#242](https://github.com/lanwen/frmtr/pull/242))
- `feat` feat(core): fan an over-width lambda-body chain by dots, dedenting the close (#221 Case A) ([#243](https://github.com/lanwen/frmtr/pull/243))
- `feat` feat(cli): add --render-indentation to render indentation as middle-dots (#201) ([#244](https://github.com/lanwen/frmtr/pull/244))
- `docs` docs(proposals): chain-path unification plan (route all chain layout through the ranked engine) ([#245](https://github.com/lanwen/frmtr/pull/245))
- `refactor` refactor(core): retire transitional ternary/unary/return width closures (#200) ([#246](https://github.com/lanwen/frmtr/pull/246))
- `refactor` refactor(core): route the multi-segment chain fan-out through chainFanOut (chain-unify U1) ([#247](https://github.com/lanwen/frmtr/pull/247))
- `feat` feat(cli): render-indentation delta dots + continuation ellipsis ([#249](https://github.com/lanwen/frmtr/pull/249))
- `refactor` refactor(core): route the return multi-segment chain through bestFitting (chain-unify U2) ([#250](https://github.com/lanwen/frmtr/pull/250))
- `refactor` refactor(core): activate leftEdgePrefix for statement and argument chain callers (chain-unify U3) ([#252](https://github.com/lanwen/frmtr/pull/252))
- `feat` feat(core): canonical fan for multi-link method chains (End-state A) ([#256](https://github.com/lanwen/frmtr/pull/256))
- `docs` docs: propose reprint-by-default with structural break rules ([#258](https://github.com/lanwen/frmtr/pull/258))
- `refactor` refactor(core): BreakRule model + canonical fan as the first named rule (break-rules Stage 0) ([#259](https://github.com/lanwen/frmtr/pull/259))
- `refactor` refactor(core): extract chainFanOut sub-shapes into named BreakRules (break-rules Stage 1a) ([#260](https://github.com/lanwen/frmtr/pull/260))
- `feat` feat(core): closed SourceShapeException set + ratchet (break-rules Stage 2) ([#261](https://github.com/lanwen/frmtr/pull/261))
- `feat` feat(core): parameter lists and throws clauses reflow by width, not source shape (reprint-by-default) ([#262](https://github.com/lanwen/frmtr/pull/262))
- `feat` feat(core): control conditions reflow logical breaks by width, not source shape (retire sourceMultilineLogicalCondition + methodCallOperandSpansMultipleLines) ([#263](https://github.com/lanwen/frmtr/pull/263))
  Control-condition logical breaks are now width-driven and source-shape-independent: a `&&`/`||` `if`/`while`/`do`/`switch` condition that fits collapses to one line, and an overflowing one breaks in the standard precedence-grouped binary shape. Retires two `SourceShapePolicy` reads (`sourceMultilineLogicalCondition`, `methodCallOperandSpansMultipleLines`).
- `feat` feat(core): multi-arg if-condition arguments reflow by width, not source shape (retire methodCallFirstArgumentStartsAfterName) ([#264](https://github.com/lanwen/frmtr/pull/264))
  Multi-argument method-call `if` conditions now break their argument list by rendered width (overflow, or a complex argument near the budget) rather than by whether the author wrapped the arguments across source lines. A near-boundary condition with only simple arguments collapses to one line. Retires the `methodCallFirstArgumentStartsAfterName` `SourceShapePolicy` read.
- `feat` feat(core): return binary/string-concat continuations reflow by width, not source shape (retire containsSourceMultilineMethodCallArgument) ([#265](https://github.com/lanwen/frmtr/pull/265))
  Direct binary and `+` string-concatenation `return` continuations now reflow by rendered width rather than preserving the author's source line breaks around a wrapped method call. A concatenation that opened one argument mid-line now breaks operand-per-line; a source-multiline binary return that was pushed into `return (...)` grouping now stays un-parenthesized. Retires the `containsSourceMultilineMethodCallArgument` `SourceShapePolicy` read.
- `feat` feat(core): ternary, enclosed-binary-operand and try-resource layouts reflow by width, not source shape ([#266](https://github.com/lanwen/frmtr/pull/266))
  Ternary, enclosed-binary-operand, and try-resource layout now reflow by rendered width instead of preserving the author's source line breaks: a fitting multiline ternary/operand/resource collapses to one line; a wide one breaks by width. Comment-bearing ternaries still break to keep their line comments.
- `docs` docs(proposals): non-reverting atomic-rewrite plan for the canonicalization hub ([#267](https://github.com/lanwen/frmtr/pull/267))
- `chore` chore(tooling): corpus-check harness and source-read tripwire (D0) ([#268](https://github.com/lanwen/frmtr/pull/268))
- `refactor` refactor(core): inter-segment-comment fan gate as comment-safety residue (D2a, byte-identical) ([#269](https://github.com/lanwen/frmtr/pull/269))
- `refactor` refactor(core): thread LayoutContext into statement/field single-segment chain flat-gates (D1e plumbing, byte-identical) ([#270](https://github.com/lanwen/frmtr/pull/270))
- `refactor` refactor(core): thread LayoutContext into the breakable-argument seam (D1a plumbing, byte-identical) ([#271](https://github.com/lanwen/frmtr/pull/271))
- `refactor` refactor(core): thread LayoutContext into the expression-lambda hug admission plan (D1g plumbing, byte-identical) ([#272](https://github.com/lanwen/frmtr/pull/272))
- `docs` docs(proposals): D3 atomic-flip map — per-read consumer/replacement guide for the hub ([#273](https://github.com/lanwen/frmtr/pull/273))
- `refactor` refactor(core): address thermo-nuclear review of hub-canonicalization scaffolding (byte-identical) ([#274](https://github.com/lanwen/frmtr/pull/274))
- `docs` docs(proposals): record D3 first-attempt empirical findings + refined worklist ([#275](https://github.com/lanwen/frmtr/pull/275))
- `refactor` refactor(core): thread the true segment column into expression-lambda hug seams (D3 keystone, byte-identical) ([#276](https://github.com/lanwen/frmtr/pull/276))
- `docs` docs(proposals): record validated D2b/c width-driven fan design + prototype evidence ([#277](https://github.com/lanwen/frmtr/pull/277))
- `fix` fix(scripts): harden sign-prs timeout handling and preserve signed commits ([#278](https://github.com/lanwen/frmtr/pull/278))
- `feat` feat(core): retire the method-call/chain/object-creation/lambda hub source-shape reads (D3 atomic flip) ([#279](https://github.com/lanwen/frmtr/pull/279))
- `chore` chore(scripts): cool down between sign-prs signatures to dodge the macOS Touch-ID throttle ([#280](https://github.com/lanwen/frmtr/pull/280))
- `refactor` refactor(core): split MethodCallChainPrinter into break-rules Stage 1b helper classes ([#281](https://github.com/lanwen/frmtr/pull/281))
- `feat` feat(core): width-driven wrap for type-use annotations and class literals (retire satellite source-shape reads) ([#282](https://github.com/lanwen/frmtr/pull/282))
- `fix` fix(scripts): make sign-prs.sh portable to macOS bash 3.2 (replace mapfile) ([#283](https://github.com/lanwen/frmtr/pull/283))
- `fix` fix(core): fan lambda-body chains by width, not source arrow shape (#190 keystone) ([#284](https://github.com/lanwen/frmtr/pull/284))
- `refactor` refactor(core): measure statement/argument chain roots at the rendered column (U3 floor-drop) ([#285](https://github.com/lanwen/frmtr/pull/285))
- `fix` fix(core): render comment-bearing object-root chains at their stable exploded shape (D4) ([#286](https://github.com/lanwen/frmtr/pull/286))
- `refactor` refactor(core): retire the transitional LayoutContext.widthBudget field (U9 field-retire) ([#287](https://github.com/lanwen/frmtr/pull/287))
- `refactor` refactor(core): retire the LayoutWidth.LineBudget enum — measure width at the true rendered column (C10) ([#288](https://github.com/lanwen/frmtr/pull/288))
- `fix` fix(core): converge single-selector fan tail to one-pass idempotence (testcontainers RegistryAuthLocator regression) ([#289](https://github.com/lanwen/frmtr/pull/289))
- `refactor` refactor(core): sweep dead source-shape stubs and the inert lambda-attach subsystem (byte-identical) ([#290](https://github.com/lanwen/frmtr/pull/290))
- `refactor` refactor(core): retire sourceMultilineChain and enforce the closed source-shape exception set (governance G2) ([#291](https://github.com/lanwen/frmtr/pull/291))
- `fix` fix(core): align fanned nested-lambda closers and hug bare-name-receiver lambda selectors (#279 review follow-ups) ([#292](https://github.com/lanwen/frmtr/pull/292))
- `refactor` refactor(core): retire 5 of the 7 enumerated inline source-shape reads (governance G3) ([#293](https://github.com/lanwen/frmtr/pull/293))
- `refactor` refactor(core): extract the try-statement family from StatementPrinter into TryStatementLayout ([#296](https://github.com/lanwen/frmtr/pull/296))
- `refactor` refactor(core): extract the if- and loop-statement families from StatementPrinter ([#297](https://github.com/lanwen/frmtr/pull/297))
- `fix` fix(core): hug the opener when a fanned lambda body call takes a bare object-creation argument ([#298](https://github.com/lanwen/frmtr/pull/298))
  Fixes an over-width layout where a fanned chain selector's expression-lambda body — a call whose argument is a freshly-constructed object — dropped the lambda flat onto one continuation line instead of hugging the call opener and fanning its arguments. Object-creation-rooted-chain and multi-selector-chain arguments are unaffected (they still route through the chain fan).
- `fix` fix(core): hug the constructor opener for a fanned lambda body that is a bare object creation ([#299](https://github.com/lanwen/frmtr/pull/299))
  Fixes an over-width layout where a fanned chain selector's expression-lambda body — a bare `new Type(args)` object creation — dropped the lambda flat onto one continuation line instead of hugging the constructor opener and fanning its arguments. Object-creation-rooted-chain and multi-selector-chain constructor arguments still route through the chain fan.
- `docs` docs(proposals): printer-contract-inversion — ranked candidate sets to dissolve the callback mesh ([#300](https://github.com/lanwen/frmtr/pull/300))
- `refactor` refactor(core): bundle nested child-expression render callbacks into one facade ([#301](https://github.com/lanwen/frmtr/pull/301))
  Internal refactor only; no formatting behavior change.
- `refactor` refactor(core): make an owner's comment re-claim idempotent ([#302](https://github.com/lanwen/frmtr/pull/302))
  Internal enabler; no formatting behavior change.
- `refactor` refactor(core): add inert comments-as-pure-content primitives ([#303](https://github.com/lanwen/frmtr/pull/303))
  Internal scaffolding; inert, no formatting behavior change.
- `refactor` refactor(core): re-anchor the do-while trailing comment onto the layout-independent anchor ([#304](https://github.com/lanwen/frmtr/pull/304))
  Internal refactor; no formatting behavior change.
- `refactor` refactor(core): re-anchor the local var-decl trailing comment onto the anchor ([#305](https://github.com/lanwen/frmtr/pull/305))
  Internal refactor; no formatting behavior change.
- `refactor` refactor(core): collapse return-chain shape-selection into the chain printer ([#306](https://github.com/lanwen/frmtr/pull/306))
  Internal refactor; no formatting behavior change. Consumer printers stop taking method-call/chain shape callbacks; the chain printer owns shape selection keyed by context.
- `refactor` refactor(core): source FieldDeclarationPrinter's own services from JavaFormatContext ([#307](https://github.com/lanwen/frmtr/pull/307))
  Internal refactor; no formatting behavior change.
- `refactor` refactor(core): convert order-independent if-cascades to switch expressions ([#308](https://github.com/lanwen/frmtr/pull/308))
  Internal refactor; no formatting behavior change.
- `refactor` refactor(core): extract ChainCommentLayout + ChainSegmentWidthLayout from the chain printer ([#309](https://github.com/lanwen/frmtr/pull/309))
  Internal refactor; no formatting behavior change.
- `refactor` refactor(core): extract ChainSegmentPaddingLayout from the chain printer ([#310](https://github.com/lanwen/frmtr/pull/310))
  Internal refactor; no formatting behavior change.
- `refactor` refactor(core): extract InitializerObjectCreationLayout from the initializer printer ([#311](https://github.com/lanwen/frmtr/pull/311))
  Internal refactor; no formatting behavior change.
- `refactor` refactor(core): extract SwitchCaseLabelLayout from SwitchPrinter ([#312](https://github.com/lanwen/frmtr/pull/312))
  Internal refactor; no formatting behavior change.
- `refactor` refactor(core): extract NestedBinaryParenthesesLayout from BinaryExpressionPrinter ([#313](https://github.com/lanwen/frmtr/pull/313))
  Internal refactor; no formatting behavior change.
- `refactor` refactor(core): extract LambdaBodyChainFanLayout from ExpressionLambdaArgumentLayout ([#314](https://github.com/lanwen/frmtr/pull/314))
  Internal refactor; no formatting behavior change.
- `refactor` refactor(core): extract BlockLambdaArgumentLayout from LambdaExpressionPrinter ([#315](https://github.com/lanwen/frmtr/pull/315))
  Internal refactor; no formatting behavior change.
- `refactor` refactor(core): extract EnumConstantLayout from EnumDeclarationPrinter ([#316](https://github.com/lanwen/frmtr/pull/316))
  Internal refactor; no formatting behavior change.
- `refactor` refactor(core): extract ConditionalCommentLayout from ConditionalExpressionPrinter ([#317](https://github.com/lanwen/frmtr/pull/317))
  Internal refactor; no formatting behavior change.
- `refactor` refactor(core): extract ParameterTrailingBlockCommentLayout from CallableSignaturePrinter ([#318](https://github.com/lanwen/frmtr/pull/318))
  Internal refactor; no formatting behavior change.
- `refactor` refactor(core): extract ControlConditionCommentLayout from ControlConditionPrinter ([#319](https://github.com/lanwen/frmtr/pull/319))
  Internal refactor; no formatting behavior change.
- `refactor` refactor(core): extract TopLevelDeclarationLayout from CompilationUnitPrinter ([#320](https://github.com/lanwen/frmtr/pull/320))
  Internal refactor; no formatting behavior change.
- `refactor` refactor(core): extract AssignmentStatementCommentLayout from AssignmentExpressionPrinter ([#321](https://github.com/lanwen/frmtr/pull/321))
  Internal refactor; no formatting behavior change.
- `refactor` refactor(core): extract MemberBlockBraceLayout from MemberBlockPrinter ([#322](https://github.com/lanwen/frmtr/pull/322))
  Internal refactor; no formatting behavior change.
- `refactor` refactor(core): extract InitializerTrailingCommentLayout from VariableInitializerLayout ([#323](https://github.com/lanwen/frmtr/pull/323))
  Internal refactor; no formatting behavior change.
- `refactor` refactor(core): account comments by recorded ownership, not build-time printed set ([#324](https://github.com/lanwen/frmtr/pull/324))
  Internal refactor; no formatting behavior change. First step of the comment-ownership cutover that unblocks ranking comment-bearing layouts.
- `refactor` refactor(core): anchor array-element trailing comments by ownership, not isPrinted ([#325](https://github.com/lanwen/frmtr/pull/325))
  Internal refactor; no formatting behavior change.
- `refactor` refactor(core): anchor detached-condition line comments by ownership, not isPrinted ([#326](https://github.com/lanwen/frmtr/pull/326))
  Internal refactor; no formatting behavior change.
- `refactor` refactor(core): anchor assignment value-tail line comment by ownership, not isPrinted ([#327](https://github.com/lanwen/frmtr/pull/327))
  Internal refactor; no formatting behavior change.
- `refactor` refactor(core): anchor chain final-trailing line comment by ownership, not isPrinted ([#328](https://github.com/lanwen/frmtr/pull/328))
  Internal refactor; no formatting behavior change.
- `refactor` refactor(core): anchor try-resource opener line comments by ownership, not isPrinted ([#329](https://github.com/lanwen/frmtr/pull/329))
  Internal refactor; no formatting behavior change.
- `fix` fix(core): repair chain-array-element trailing-comment double-render; anchor gap comments to container ([#330](https://github.com/lanwen/frmtr/pull/330))
  Fixes a non-idempotent double-render of an inline trailing comment on a method-chain array element, introduced by the interaction of two earlier comment-ownership re-keys.
- `refactor` refactor(core): migrate leading/adjacent/own/trailing comment families to the claim-neutral ownedComment rail (Phase B core) ([#331](https://github.com/lanwen/frmtr/pull/331))
  Internal refactor; no formatting behavior change. Migrates the core comment families to ownership-pure rendering.
- `refactor` refactor(core): complete Phase A comment re-keys (chain-before-segment, empty-block, list shape-predicate) ([#332](https://github.com/lanwen/frmtr/pull/332))
  Internal refactor; no formatting behavior change.
- `refactor` refactor(core): migrate orphan/gap/trailing-recovery comment families to the ownedComment rail (Phase B families) ([#333](https://github.com/lanwen/frmtr/pull/333))
  Internal refactor; no formatting behavior change.
- `refactor` refactor(core): migrate comment() interleaved family to ownedComment — complete Phase B claim-neutrality ([#334](https://github.com/lanwen/frmtr/pull/334))
  Internal refactor; no formatting behavior change. Completes the migration of all comment rendering onto the claim-neutral ownership rail.
- `refactor` refactor(core): retire CommentTracker.speculatively — redundant post-claim-neutrality (Phase C) ([#335](https://github.com/lanwen/frmtr/pull/335))
  Internal refactor; no formatting behavior change. Removes the speculative comment-claim rollback machinery, now dead after comment rendering became ownership-pure.
- `feat` feat(core): comment-bearing method-call chains rank via bestFitting (Phase D) ([#336](https://github.com/lanwen/frmtr/pull/336))
  Comment-bearing method-call chains now participate in width ranking; one shape improves (a trivial-receiver two-selector chain with a trailing comment now attaches its first selector to the receiver line, matching the comment-free shape).
- `docs` docs(architecture): reflect the comment-claim enabler (Phases A-D) ([#337](https://github.com/lanwen/frmtr/pull/337))
  Documentation only.
- `test` test(core): lock comment-bearing non-overflowing nested logical sub-chain parens (Phase D boundary) ([#338](https://github.com/lanwen/frmtr/pull/338))
  Test-only: adds a regression fixture; no formatting behavior change.
- `chore` chore(core): retire orphaned comment-claim machinery + refresh enabler docs ([#339](https://github.com/lanwen/frmtr/pull/339))
  Internal cleanup + docs; no formatting behavior change.
- `fix` fix(core): preserve text-block argument gap comments in the hug layout (remove hasLineComments bail) ([#340](https://github.com/lanwen/frmtr/pull/340))
  Fixes a dropped `//` comment adjacent to a text-block method-call argument under whitespace-perturbed input; such calls now keep the compact hug layout with their comments preserved.
- `refactor` refactor(core): unify the broken-binary operand builder; drop the :137 comment preempt ([#341](https://github.com/lanwen/frmtr/pull/341))
  Internal refactor; no formatting behavior change. Removes a comment-bearing-binary special-case by unifying the broken-binary line builder.
- `docs` docs(architecture): rewrite ARCHITECTURE.md as a high-level overview ([#342](https://github.com/lanwen/frmtr/pull/342))
- `docs` docs(proposals): archive shipped proposals and rebuild the roadmap ([#343](https://github.com/lanwen/frmtr/pull/343))
- `docs` docs: refresh formatter internals/coverage/recovery maps and add a docs index ([#344](https://github.com/lanwen/frmtr/pull/344))
- `refactor` refactor(core): tighten source comments and apply small code cleanups ([#345](https://github.com/lanwen/frmtr/pull/345))

## [0.1.0] - 2026-06-30

### Merged Pull Requests

- `change` Regression fixtures from build-#27 Camel verification (BUG 2 fixed; BUG 4 tracked) ([#25](https://github.com/lanwen/frmtr/pull/25))
- `change` Fix #23: comment-aware body-brace detection for commented method signatures ([#26](https://github.com/lanwen/frmtr/pull/26))
- `change` Fix #24: break nested mixed-operator boolean sub-chain in commented conditions ([#27](https://github.com/lanwen/frmtr/pull/27))
- `change` Recover trailing string-concat initializer comment before the semicolon ([#28](https://github.com/lanwen/frmtr/pull/28))
- `change` Preserve trailing line comment on an empty labeled for-loop body (drains last KNOWN_DROPS) ([#29](https://github.com/lanwen/frmtr/pull/29))
- `change` Fix silent string-literal corruption: make = normalization literal-aware ([#30](https://github.com/lanwen/frmtr/pull/30))
- `change` Fix non-idempotent object-creation chain initializer wrap (converge on argument break) ([#32](https://github.com/lanwen/frmtr/pull/32))
- `change` Fix non-idempotent object-creation block-lambda chain initializer with body comment ([#33](https://github.com/lanwen/frmtr/pull/33))
- `change` Make compact() AST-complete for literal-bearing expressions (no raw-text = spacing) ([#34](https://github.com/lanwen/frmtr/pull/34))
- `change` Add release-triggered corpus correctness check via CLI ([#35](https://github.com/lanwen/frmtr/pull/35))
- `change` CLI: read-only --check --verify + distinct exit codes (parse-failure 2 vs verify-violation 3) ([#36](https://github.com/lanwen/frmtr/pull/36))
- `change` site: landing polish — shimmer wordmark, growable code card, sliding tab bar ([#37](https://github.com/lanwen/frmtr/pull/37))
- `change` Close B1 + B2 ownership consolidation Stage 1 (trailing family) ([#38](https://github.com/lanwen/frmtr/pull/38))
- `change` B2: enable strict-claims (each comment claimed at most once) as a CI gate ([#39](https://github.com/lanwen/frmtr/pull/39))
- `change` Fix compound-assignment operator split (`^=` → `^ =`) in raw =-spacing normalization ([#40](https://github.com/lanwen/frmtr/pull/40))
- `change` Fix C-style array declarator emitting non-compiling `Type name[] name;` ([#41](https://github.com/lanwen/frmtr/pull/41))
- `change` Fix dropped `final`/annotations on for-loop init variable declaration ([#42](https://github.com/lanwen/frmtr/pull/42))
- `change` Fix unbounded comment duplication before package declaration (package-info idempotence) ([#43](https://github.com/lanwen/frmtr/pull/43))
- `change` Fix silent string-literal whitespace collapse in raw normalization (make whitespace collapse literal-aware) ([#44](https://github.com/lanwen/frmtr/pull/44))
- `change` Fix dropped license/file header comment before package when preceded by a blank line ([#45](https://github.com/lanwen/frmtr/pull/45))
- `change` Fix dropped between-operand line comments on flat binary expressions ([#46](https://github.com/lanwen/frmtr/pull/46))
- `change` Fix dropped duplicate-text and empty chain-link line comments (identity dedup) ([#47](https://github.com/lanwen/frmtr/pull/47))
- `change` Fix non-idempotent over-break of trailing method-chain call argument ([#48](https://github.com/lanwen/frmtr/pull/48))
- `change` Fix dropped comments around switch case labels ([#49](https://github.com/lanwen/frmtr/pull/49))
- `change` Fix dropped leading line comment on a method/constructor parameter ([#50](https://github.com/lanwen/frmtr/pull/50))
- `change` Fix dropped block comments in argument lists ([#51](https://github.com/lanwen/frmtr/pull/51))
- `change` Fix dropped comment between lambda arrow and expression body ([#52](https://github.com/lanwen/frmtr/pull/52))
- `change` Fix block-comment data loss: preserve text on the line sharing the closing */ ([#53](https://github.com/lanwen/frmtr/pull/53))
- `change` Fix dropped =-gap block comment and trailing // on a collapsed field initializer ([#54](https://github.com/lanwen/frmtr/pull/54))
- `change` Fix dropped trailing // after the final operand of a multi-line return value ([#55](https://github.com/lanwen/frmtr/pull/55))
- `change` Fix mutated banner Javadoc dividers (preserve asterisk-art verbatim) ([#56](https://github.com/lanwen/frmtr/pull/56))
- `change` Fix split/duplicated multi-line // header before package declaration ([#57](https://github.com/lanwen/frmtr/pull/57))
- `chore` chore(release): automate release workflow ([#58](https://github.com/lanwen/frmtr/pull/58))
- `change` Harden binary comment guard: identity not value equality (duplicate-text residual) ([#59](https://github.com/lanwen/frmtr/pull/59))
- `change` Fix dropped block/Javadoc comment interspersed between method-chain links ([#60](https://github.com/lanwen/frmtr/pull/60))
- `change` Fix non-idempotent field-init wrap flip for single-call type-like roots ([#61](https://github.com/lanwen/frmtr/pull/61))
- `change` Fix dropped first comment in a nested block of a lambda-block initializer ([#62](https://github.com/lanwen/frmtr/pull/62))
- `change` Fix dropped trailing comment on a multi-line ternary then-branch ([#63](https://github.com/lanwen/frmtr/pull/63))
- `change` Recover interspersed comment before empty-arg chain selector; fix `=` double-space in comment-bearing assignment chains ([#64](https://github.com/lanwen/frmtr/pull/64))
- `test` test(core): add fixtures for nested-binary parens, parenthesized-conditional suffix break, breaking-parameter throws ([#65](https://github.com/lanwen/frmtr/pull/65))
  Test-only change: adds formatter fixtures covering nested-binary clarifying parentheses, the parenthesized-conditional trailing-break-before-suffix layout, and methods/constructors whose parameter list breaks alongside a throws clause. No formatter behavior change.
- `refactor` refactor(core): remove unreachable throws-clause overloads and params-break helper ([#66](https://github.com/lanwen/frmtr/pull/66))
- `test` test(core): add fixtures for uncovered lambda break/hug layout paths ([#67](https://github.com/lanwen/frmtr/pull/67))
  Test-only change: adds fixtures covering broken/hugged expression-lambda bodies, assorted lambda parameter shapes, and lambda-as-argument layouts. No formatter behavior change.
- `test` test(core): add fixtures for uncovered variable-initializer layout paths ([#68](https://github.com/lanwen/frmtr/pull/68))
  Test-only change: adds fixtures covering object-creation array initializers, broken array-creation initializers, array-access initializers with a broken enclosed name, and overflowing cast/array-access initializer values. No formatter behavior change.
- `refactor` refactor(core): remove 28 unreachable methods surfaced by coverage sweep ([#69](https://github.com/lanwen/frmtr/pull/69))
  Internal cleanup: removes 28 unreachable private/package-private methods left over from earlier refactors. No public API or formatter behavior change.
- `refactor` refactor(core): remove unreachable public dead code (pre-publish cleanup) ([#71](https://github.com/lanwen/frmtr/pull/71))
  Internal cleanup: removes unused public methods (DocExplanation.ruleName accessors and OverWidthLines.Scanner.inRawRegion) with no callers. Pre-publication, so no compatibility impact.
- `fix` fix(core): break generic array-creation type args only when they overflow ([#72](https://github.com/lanwen/frmtr/pull/72))
  Generic array creations (new Type<...>[] {...}) no longer split their type arguments across lines when they fit; an overflowing one breaks at = or at the initializer braces instead, and only a type too long to fit on its own continuation line still wraps its type arguments.
- `fix` fix(release): update site version during release prep ([#73](https://github.com/lanwen/frmtr/pull/73))
- `fix` fix(core): wrap throws clause for empty-parameter methods and constructors ([#74](https://github.com/lanwen/frmtr/pull/74))
  A throws clause on a method or constructor with empty parameters now wraps onto its own line when the signature would otherwise exceed the line width, instead of staying on one over-width line.
- `fix` fix(core): normalize interior spacing for compact object creations and method references ([#75](https://github.com/lanwen/frmtr/pull/75))
  The compact one-line rendering of object creations and method references no longer preserves stray interior spaces from the source (e.g. new Foo( a, b ) is now new Foo(a, b)), matching how method calls are already normalized. Comment-bearing nodes are unaffected.
- `fix` fix(core): break object-creation-rooted method chains one per line ([#76](https://github.com/lanwen/frmtr/pull/76))
  Method chains rooted in an object creation with two or more chained calls (e.g. new Builder().setA(x).setB(y)) now break one call per line with the constructor on its own line, matching the layout of name-rooted chains and prettier-java/google-java-format. Single-call constructor invocations and name/factory-rooted chains are unchanged.
- `fix` fix(core): C-style array parameters duplicate the parameter name ([#79](https://github.com/lanwen/frmtr/pull/79))
- `fix` fix(core): keep type Javadoc attached when a blank line precedes the type ([#80](https://github.com/lanwen/frmtr/pull/80))
  A type's leading Javadoc separated from the type by a blank line is no longer moved above the package statement; it stays attached to the type and formatting is now idempotent for that shape.
- `fix` fix(core): trailing line comment swallows following token (comma/brace) producing non-compiling output ([#83](https://github.com/lanwen/frmtr/pull/83))
  Trailing `//` line comments no longer swallow a following separator comma (annotation arrays) or a following closing brace (inline-collapsed lambda/cast block bodies), so the formatted output stays compiling.
- `fix` fix(core): render package-level annotations (package-info.java) ([#86](https://github.com/lanwen/frmtr/pull/86))
  Package-level annotations (the package-info.java shape) are now rendered on the package declaration instead of being silently dropped. Multi-argument annotations format and wrap like any other declaration annotation.
- `fix` fix(core): anonymous class body dropped in lambda object-creation body ([#87](https://github.com/lanwen/frmtr/pull/87))
  Lambda expression bodies that create an object with an anonymous class body no longer lose that body when the constructor arguments break.
- `fix` fix(core): preserve lone comment in empty @interface body ([#101](https://github.com/lanwen/frmtr/pull/101))
  Empty annotation (`@interface`) bodies now preserve a comment that is their only content instead of collapsing to `{}` and dropping it, matching how empty class, interface, and enum bodies already behave.
- `fix` fix(core): //// comment block indentation drift (non-idempotent) ([#102](https://github.com/lanwen/frmtr/pull/102))
  Markdown (JEP 467 `///`) documentation comments, including `////`-prefixed blocks, are now rendered through the `///` line-comment family and no longer accumulate indentation on each format pass. Indentation after the `///` marker (for example fenced code or nested lists) is preserved.
- `chore` chore(scripts): add sign-prs.sh to re-sign PR commits when ready ([#104](https://github.com/lanwen/frmtr/pull/104))
- `fix` fix(core): preserve comments in non-empty @interface bodies ([#105](https://github.com/lanwen/frmtr/pull/105))
  Brace-line and orphan comments inside non-empty annotation (`@interface`) bodies are no longer dropped. The populated annotation body now routes through the same comment-preserving member-block path as class, interface, enum, and record bodies while keeping its existing single-line opening-brace layout.
- `fix` fix(core): preserve trailing comment after a type's closing brace ([#106](https://github.com/lanwen/frmtr/pull/106))
  Trailing line comments placed directly after a type's closing brace, such as `}// SessionMessage`, are now preserved instead of being silently dropped. This covers single top-level types, the last of several top-level types, and nested types.
- `fix` fix(core): preserve comments nested in method-chain lambda arguments ([#108](https://github.com/lanwen/frmtr/pull/108))
  The expression-lambda body was rendered through the compact opener reconstruction, which flattens the body chain's scope and selector to a single line and strips any leading `//` comment sitting between them. The fix skips that opener shape when reconstructing it would drop a line comment the body carries (a comment outside every argument subtree), falling through to the comment-preserving renderers that route the body through the full chain printer.

  Two adjacent chain-printer issues that comment preservation then exposed are also fixed: a leading line comment on a single-segment method-root chain now owns its own continuation line instead of being glued onto the root's closing parenthesis, and the chain-link comment ownership overlaps (the expression-lambda boundary collecting a chain-link comment, and an inner nested chain plus its enclosing link both claiming a same-line trailing comment) are de-duplicated so each comment is claimed once.

  Verified on the real apache/camel `DynamicRouterRecipientListHelper` source (all 11 comments preserved, idempotent) and covered by the obfuscated `chain-lambda-nested-comment` fixture. Full Gradle test suite green, including AstEquivalenceTest, IdempotencePropertyTest, CommentPresenceDiagnosticTest, and SuspiciousLineWidthAuditTest (no new allowlist entries).
- `fix` fix(core): preserve trailing comment on an interior call argument ([#109](https://github.com/lanwen/frmtr/pull/109))
- `fix` fix(core): preserve trailing comment after = before a wrapped RHS ([#110](https://github.com/lanwen/frmtr/pull/110))
  Trailing `//` comments sitting between `=` and a wrapped right-hand side are now preserved on the `=` line instead of being dropped.
- `fix` fix(core): preserve trailing comment after the final operand of a wrapped binary statement ([#111](https://github.com/lanwen/frmtr/pull/111))
  Trailing line comments after the final operand of a wrapped compound-assignment binary statement are now preserved instead of being dropped.
- `fix` fix(core): preserve inline block comments between binary operands on re-wrap ([#112](https://github.com/lanwen/frmtr/pull/112))
  Between-operand `/* */` block comments in multi-operand boolean/binary expressions are now preserved when the expression re-wraps or stays flat, matching the existing handling for `//` line comments.
- `fix` fix(core): preserve leading block comment before a jump statement in an if body ([#113](https://github.com/lanwen/frmtr/pull/113))
  Multi-line block comments that lead a break statement are now preserved on their own lines above the statement instead of being dropped with a stray leading space. Single-line inline block comments before break are unchanged.
- `fix` fix(core): keep a case label's leading comment block below the label ([#114](https://github.com/lanwen/frmtr/pull/114))
  NOTE: this change involves a layout choice — where a case label's leading comment block goes. The chosen variant is the whole `//` block on its own lines below `case X:` at the statement indent, matching how leading comments render elsewhere (prettier-java / google-java-format convention) and keeping the block together. Please confirm the `styling-preference` label.
- `fix` fix(core): stabilize comment placement before else/else-if (non-idempotent rotation) ([#115](https://github.com/lanwen/frmtr/pull/115))
- `fix` fix(core): converge try-with-resources / near-boundary wrapping (non-idempotent) ([#116](https://github.com/lanwen/frmtr/pull/116))
  Try-with-resources resource sections now collapse to a flat one-line form whenever it fits the configured width, instead of being held broken by the source's prior shape. This removes a non-idempotent wrap cycle where a single resource near the column boundary never stabilized across formatting passes. Involves a wrap-shape choice (single-resource sections prefer the flat form when it fits) and is therefore styling-preference.
- `fix` fix(core): converge near-boundary binary/method-chain wrapping ([#118](https://github.com/lanwen/frmtr/pull/118))
  Fixes a non-idempotent near-boundary binary-expression and method-chain wrapping where the formatter alternated between two equal break strategies and never converged. The break choice is now a deterministic property of the input AST.
- `fix` fix(core): make binary method-call operand break width-deterministic ([#120](https://github.com/lanwen/frmtr/pull/120))
  Binary method-call operands in a broken (one-operator-per-line) binary expression now keep their argument list flat or explode it based only on whether the flat operand overflows the line width, instead of mirroring how the author happened to wrap the call in source. This removes a non-idempotent, source-shape-dependent layout (the third wrap-convergence regime after #98/#117): identical operands in one chain now render the same way and near-boundary layouts converge in a single pass.
- `chore` chore(scripts): harden sign-prs.sh against agent hangs ([#121](https://github.com/lanwen/frmtr/pull/121))
- `fix` fix(core): keep an annotation brace-line comment on the opening line ([#122](https://github.com/lanwen/frmtr/pull/122))
- `fix` fix(core): preserve leading comment block on an expression-lambda body ([#123](https://github.com/lanwen/frmtr/pull/123))
- `feat` feat(gradle): use project-local frmtr tasks ([#124](https://github.com/lanwen/frmtr/pull/124))
  The Gradle plugin no longer auto-applies itself to child projects when applied at the root. Apply `dev.lanwen.frmtr` to each participating project, or use a subprojects convention that applies it after the Java plugin. Run `./gradlew frmtrCheck --continue` to report independent module failures across a multi-project build.
- `fix` fix(core): preserve a comment between a type name and its extends/implements clause ([#139](https://github.com/lanwen/frmtr/pull/139))
  Block comments written between a type name and its extends, implements, or permits clause are now preserved on the header line instead of being dropped.
- `fix` fix(core): preserve an orphan comment in a switch-case body separated by a blank line ([#140](https://github.com/lanwen/frmtr/pull/140))
  A standalone comment between statements in a `case`/`default` body that was followed by a blank line is now preserved. Previously such a comment was dropped because the switch-case statement path did not interleave the switch entry's orphan comments the way ordinary statement blocks do.
- `fix` fix(core): preserve an inline block comment between a parameter type and name ([#141](https://github.com/lanwen/frmtr/pull/141))
  The dropped comment only affected the structured signature path (method bodies with two or more statements). Single-statement-body methods whose signature contains a comment already route through the raw `CommentedMethodSignaturePrinter` fallback, which preserved the comment from source.

  Note: a separate, pre-existing defect (outside this change) was observed while testing — the single-statement raw `CommentedMethodSignaturePrinter` fallback drops a method's `throws` clause when the signature contains any comment. That is unrelated to this fix and is not addressed here.
- `fix` fix(core): preserve the throws clause of a method with a commented signature ([#143](https://github.com/lanwen/frmtr/pull/143))
  The `)`-to-`{` gap carries two kinds of token: the gap comments (already routed to their own channel) and the `throws Ex1, Ex2` clause, which is real signature content. The fix extracts the non-comment tokens from the gap, normalizes them through `CommentedTokenText.tokenLine`, and appends them to the rendered signature immediately after the parameter-list `)`. An empty clause leaves the signature byte-identical, so methods without checked exceptions and methods without a signature comment are unaffected.

  A new `commented-signature-throws-clause` fixture covers a single-exception clause, a multi-exception `throws A, B` clause, and a commented signature with no throws clause. Both the fixture input and output compile with javac, the output is idempotent, and it is AST-equivalent to the input.

  The single-statement body on this fallback path renders at a 2-space body indent, which is already baselined across many existing fixtures. That pre-existing indentation is intentionally left unchanged to keep this fix scoped to the dropped `throws` clause.
- `fix` fix(core): preserve between-operand comments in lambda-body and annotation-value binaries ([#144](https://github.com/lanwen/frmtr/pull/144))
  The broken `lines(...)` path now routes through the comment-aware `commentedBinaryLines` builder when the chain carries between-operand comments (`hasLineComments`). The gate sits after the flat-fit branch, so a flat-fitting binary still delegates to `binaryExpression` and the lone-immediate-left line-comment shape is byte-for-byte unchanged. The nested-continuation form indents lines after the first to mirror the comment-free nested layout. Centralizing the gate in `BinaryExpressionPrinter` means every forced-break caller benefits, matching how the field-initializer and enclosed-expression callers already preserve these comments via `linesWithComments`.

  Two fixtures cover both shapes:
  - `binary-lambda-body-between-operand-comment` (lambda body `p != -1 && // keep this comment` `p > 0`)
  - `annotation-value-binary-between-operand-comment` (annotation value `"a" + // keep me` `"b"`)

  Both keep the comment, compile, and are idempotent. No pre-existing binary/lambda/annotation golden changed; the full Gradle test suite is green.
- `fix` fix(core): preserve all lines of a multi-line comment in a ternary ([#145](https://github.com/lanwen/frmtr/pull/145))
  `commentedConditionalExpression` collected candidate comments into a `Map<Region, Comment>` via `putIfAbsent`, so a block that all classifies to the same region (for example several `//` lines on their own lines before the `?` branch, all `QUESTION_LEADING`) lost every comment after the first.

  The fix holds all comments per region in a `Map<Region, List<Comment>>` and renders them as a `HARD_LINE`-separated block in the region's slot. A region with a single comment renders exactly as before, so existing single-comment placement is unchanged. Both the question-leading and colon-leading regions, which shared the defect, are now covered.

  Collecting all comments exposed a latent double-collection: after a whitespace collapse JavaParser can attach the same comment instance to two child expressions at once, so it reached the candidate list twice through two own-comment associations. The previous `putIfAbsent` masked it; with the per-region list it would render twice and trip the duplicate-claim guardrail. Candidates are now deduplicated by object identity, so the same node is collected at most once however many associations reach it.

  Covered by the new `frmtr-core/src/test/resources/format/ternary-multiline-leading-comment` fixture (question-leading and colon-leading multi-line blocks), verified idempotent and comment-complete. Full `./gradlew test` suite is green.
- `fix` fix(core): keep a multi-line comment between catch clauses on separate lines ([#146](https://github.com/lanwen/frmtr/pull/146))
  JavaParser splits a comment block written between two catch clauses into two buckets: a run of `TryStmt` orphans (handed off as the previous clause's trailing comment) and a final line attached to the next clause (its own leading line comment). The try-clause handoff concatenated each recovered orphan line with no separator, and then concatenated the orphan block with the clause's own leading line, collapsing every line onto one.

  The fix renders the relocated comment as a HARD_LINE-separated block on both seams, entirely within the try path:

  - New `CommentTracker#trailingLineCommentBlockAfter`, a HARD_LINE-joining sibling of `trailingLineCommentsAfter`. The existing concatenating method is left untouched so the sibling `else`-chain handoff is unaffected; a single recovered line renders byte-identically.
  - `StatementPrinter#clauseTrailingComment` routes through the new method, and a new private `clauseLeadingComment` helper separates the handed-off block from the next clause's own leading line with a HARD_LINE when both are present (used at both the catch and finally seams).

  Covered by the new `between-catch-multiline-comment` fixture; output is idempotent and each comment line stays on its own line.
- `fix` fix(core): preserve a trailing line comment after a do-while statement ([#147](https://github.com/lanwen/frmtr/pull/147))
  Preserve a trailing `//` line comment after a multi-line `do { ... } while (cond);` loop, which was previously dropped because the comment is attached to the while-condition rather than the do-while statement.
- `fix` fix(core): preserve leading comments on a braceless else body ([#148](https://github.com/lanwen/frmtr/pull/148))
  With two-or-more leading comments on a braceless then body, the first comment bubbles up as the if condition's trailing line comment, so `elseChainSeparator` took its condition-trailing branch and returned the bare `else` separator. That branch runs before the else-leading branch, but the else body's leading `//` block had already been claimed by the `elseLeadingLineComment` gap slot during the dry-run, so the claimed lines were never rendered and were silently dropped.

  The condition-trailing branch now carries through both the then statement's own trailing line comment and the else body's already-claimed leading `//` block (new `separatorWithThenTrailingAndElseLeading` helper), so neither already-owned cluster is lost. This also covers a whitespace-collapse re-bucketing where the else body's first leading comment surfaces as the then statement's trailing comment.

  The change is scoped entirely to the if/else path in `StatementPrinter` (no shared helpers touched). Coverage: a perturbation-stable block-then `else-body-leading-comments` fixture, plus an inline `StatementPrinterTest` regression for the exact braceless-then trigger shape. The braceless-then shape stays inline rather than a `format/**` fixture because the condition-trailing comment independently explodes the condition under whitespace collapse, so it is not corpus-perturbation-stable.
- `fix` fix(core): preserve a leading comment on a hugged lambda argument ([#149](https://github.com/lanwen/frmtr/pull/149))
  Leading line and block comments on a hugged block-lambda argument are now preserved instead of being silently dropped. The comment is emitted on its own line before the argument and the call breaks rather than hugging; comment-free calls still hug as before. Verified across the default, whitespace-collapsed, and whitespace-expanded source shapes, all idempotent.
- `fix` fix(core): preserve a statement-trailing comment when an argument list re-wraps ([#150](https://github.com/lanwen/frmtr/pull/150))
  Restores a dropped statement-trailing line comment on `throw new X(...);` when the constructor argument list re-wraps one-argument-per-line. Pure correctness; no styling choice. Companion to the interior-argument fix in #109.
- `fix` fix(core): stabilize text-block .formatted argument indentation across passes ([#153](https://github.com/lanwen/frmtr/pull/153))
  In a `return`, when the arguments are already source-multiline the call is routed through `sourceMultilineArguments` (the source-multiline-method-call hook), which lays the arguments one indent under the statement base with the closing paren on the statement-base column (+12 / +8 at the repro indent). When the source keeps the arguments flat, the scope branch instead width-fits `methodCallWithoutScope` inside an extra `Doc.indent`, so a width-driven break lands the same arguments one further indent in (+16 / +12). The two shapes disagree by exactly one indent unit: the first pass over flat source produces +16 / +12, the next pass re-reads the now source-multiline arguments and settles on +12 / +8.

  `MethodCallPrinter.textBlockScopedArgumentList` makes the flat-source overflow path emit the same forced one-indent shape `sourceMultilineArguments` produces, so both source shapes converge on the source-multiline fixed point. It is scoped to plain multi-argument lists (two or more args, no contained comments, no huggable expression lambda) whose compact closing line overflows, leaving single-argument hugging shapes, flat-fitting calls, and commented argument lists owned by the existing branches exactly as before.

  Covered by the new `text-block-formatted-arg-indent` fixture. Both a flat input and a pre-broken (old +16 / +12) variant converge to the identical golden output, and the full `./gradlew test` suite — including the perturbation-based `IdempotencePropertyTest` — is green with no other golden moving.
- `fix` fix(core): preserve a leading line comment on a braceless control-statement body ([#154](https://github.com/lanwen/frmtr/pull/154))
  A braceless control-statement body with a leading line comment now keeps the comment exactly once on an indented line above the body (or inline on the header line when the comment was written there), with idempotent, perturbation-stable placement across `if`/`while`/`do-while`/`for`/`for-each`.
- `fix` fix(core): measure if-condition width at rendered indentation (tab-indented non-idempotence) ([#155](https://github.com/lanwen/frmtr/pull/155))
  This also corrects a latent over-count in the same gate: an `if` nested deep inside a broken method-chain lambda previously over-measured via its source column and was wrongly broken even though the flat line fit. The `multiline-if-condition` golden is updated for this: a 118-column `if (Arrays.equals(...))` line is now kept flat instead of split.

  New fixture `tab-indented-condition-width` pins the regression: a tab-indented nested `if` inside a breaking method chain that is non-idempotent on baseline and idempotent (and collapse/expand convergent) under the fix.

  The broader family of `range.begin.column`-into-width sites was reviewed. `UnaryExpressionPrinter` shows the same tab under-count, but its correct fix needs the assignment-prefix width it does not have (a `nodeLine`-only change converges to an over-width flat line), so it is deferred. The method-call, method-call-chain, return, and lambda-argument width sites did not reproduce tab-indented non-idempotence and are left unchanged to keep the diff small.
- `fix` fix(core): reconcile braceless else-body leading comments with the braceless-body handler ([#157](https://github.com/lanwen/frmtr/pull/157))
  A line comment on its own line at the start of a braceless `else` body now stays indented inside the body, and reformatting it is stable.

  ```java
  // before — comment jumped above `else`
  }
  // note
  else return 2;

  // after — comment stays in the body
  } else
      // note
      return 2;
  ```
- `chore` chore(site): quick-start example carousel, reusable tabs, and Gradle serve task ([#158](https://github.com/lanwen/frmtr/pull/158))
  Website and local dev-server tooling only; no changes to the published formatter, CLI, or Gradle plugin behavior.
- `fix` fix(core): make return-expression wrapping width-deterministic ([#159](https://github.com/lanwen/frmtr/pull/159))
  A `return` whose method call or chain fits on one line now stays flat even when the source had wrapped it across several lines; one that does not fit still breaks one call per line.

  ```java
  // before — fits on one line but the source had wrapped it
  return new DataSourceDescriptorBuilder()
      .setName(theConfiguredDataSourceLogicalNameXxxxxxxxxx);

  // after — collapses to the one line that fits
  return new DataSourceDescriptorBuilder().setName(theConfiguredDataSourceLogicalNameXxxxxxxxxx);
  ```
- `fix` fix(core): make method-chain width probe account for the assignment prefix ([#161](https://github.com/lanwen/frmtr/pull/161))
  A method-call chain assigned to a variable now counts the `name = ` (or `target op `) prefix when deciding whether it fits, so a chain that overflows the line only because of that prefix breaks onto separate lines instead of staying flat past the width limit.

  Before:
  ```java
  routeTable = new RouteTableConfigBuilder().setName("primaryRoutingDomainHandler1").seal().commit().materialize();
  ```

  After:
  ```java
  routeTable = new RouteTableConfigBuilder()
          .setName("primaryRoutingDomainHandler1")
          .seal()
          .commit()
          .materialize();
  ```
- `fix` fix(core): make the method-chain width probe account for nesting depth ([#162](https://github.com/lanwen/frmtr/pull/162))
  A method-call chain used as a wrapped call argument or nested initializer no longer stays on one over-width line when it only fits if you ignore how deeply it is indented. It now breaks at the column where it actually renders.

  Before:
  ```java
  }).retryWhen(
      RetryPlan.create(new Resource("resource-1", Resource.endpoint("localhost", 22)), targetEndpoint, 4).toRetry()
  );
  ```

  After:
  ```java
  }).retryWhen(
      RetryPlan.create(
          new Resource("resource-1", Resource.endpoint("localhost", 22)),
          targetEndpoint,
          4
      ).toRetry()
  );
  ```

  A chain that still fits at its nesting depth is left flat, unchanged.
- `fix` fix(core): make single-argument call attachment width-deterministic ([#164](https://github.com/lanwen/frmtr/pull/164))
  A call or constructor with a single nested-call argument that the author split across lines is no longer kept attached when doing so would push the line past the width limit. It now breaks instead of being emitted over the limit, regardless of how the source was wrapped.

  ```java
  // before (121 columns, over the limit)
  var resolvedRuntimeConfiguration = configurationResolver.resolveAll(buildEnvironmentConfiguration(
      primaryEnvironmentName,
      secondaryEnvironmentName
  ));

  // after
  var resolvedRuntimeConfiguration = configurationResolver.resolveAll(
      buildEnvironmentConfiguration(
          primaryEnvironmentName,
          secondaryEnvironmentName
      )
  );
  ```

  A nested call that still fits when attached is left attached as before.
- `fix` fix(core): make expression-lambda packed-body hug width-deterministic ([#165](https://github.com/lanwen/frmtr/pull/165))
  Expression-lambda arguments whose body is hugged onto the call opener no longer overflow the line width when the call is nested several blocks deep. A hug that fits at the call's real column is kept; one that overflows once the nesting is counted now breaks.

  Before:
  ```java
  for (int attemptIndex = 0; attemptIndex < launchSchedule.attempts(); attemptIndex++) {
      bundleResolver.resolvePreparedWindowBundles(invocation -> regionalWindowBundleReadGateway.findLaunchBundles(
              invocation.getArgument(0),
              invocation.getArgument(1)
      ));
  }
  ```

  After:
  ```java
  for (int attemptIndex = 0; attemptIndex < launchSchedule.attempts(); attemptIndex++) {
      bundleResolver.resolvePreparedWindowBundles(invocation -> regionalWindowBundleReadGateway
                  .findLaunchBundles(invocation.getArgument(0), invocation.getArgument(1))
      );
  }
  ```
- `fix` fix(core): break logical-complement initializer with `= !(` kept on the assignment line and operands one-per-line ([#166](https://github.com/lanwen/frmtr/pull/166))
  An over-width negated condition assigned to a variable now keeps `= !(` on the assignment line and breaks the inner condition one operator per line, instead of breaking after `=`. A negation that fits on one line still stays flat, and the result is now stable whether the source was indented with tabs or spaces.

  ```java
  // before
  boolean handshakeAccepted =
      !(connection.isAuthenticated() && sessionState.hasActiveLease() && connection.protocolVersionMatches());
  ```

  ```java
  // after
  boolean handshakeAccepted = !(
      connection.isAuthenticated()
      && sessionState.hasActiveLease()
      && connection.protocolVersionMatches()
  );
  ```
- `fix` fix(core): preserve a nested comment in a hugged block-lambda initializer ([#169](https://github.com/lanwen/frmtr/pull/169))
  A comment inside a nested block of a hugged block-lambda assignment is no longer dropped.

  ```java
  // before
  CompletableFuture<List<String>> future = CompletableFuture.supplyAsync(() -> {
      if (versions.isEmpty()) {
          addAll(resolve());
      }
      return versions;
  });

  // after
  CompletableFuture<List<String>> future = CompletableFuture.supplyAsync(() -> {
      if (versions.isEmpty()) {
          // first version with recipes
          addAll(resolve());
      }
      return versions;
  });
  ```
- `fix` fix(core): measure return-expression width at the rendered column, not the source column ([#171](https://github.com/lanwen/frmtr/pull/171))
  A `return` written on the same line as a `switch` case label no longer breaks a method-call chain that fits once the `case` and `return` are placed on their own lines, so it formats the same as the equivalent plain `return`.

  ```java
  // before
  case "eventProcessingFailureHandlingMode":
      return target
              .getConfiguration()
              .getEventProcessingFailureHandlingMode();

  // after
  case "eventProcessingFailureHandlingMode":
      return target.getConfiguration().getEventProcessingFailureHandlingMode();
  ```
- `docs` docs: correct formatter convergence claim to measured corpus reality ([#173](https://github.com/lanwen/frmtr/pull/173))

### Previous Unreleased Notes

### Added

- Open-source community files: GitHub issue/PR templates.
- Gradle plugin root-project registration for Java subprojects, inherited module configuration, and module-level
  `frmtr { enabled = false }` opt-out.
- Gradle check build caching and incremental source selection for `frmtrJavaCheck`.
- Public `FrmtrSession` API for sequential callers that want to reuse formatter state across multiple source strings.

### Changed

- Published JVM runtime artifacts now target Java 21 while native CLI builds continue to use GraalVM/JDK 25.
- File-oriented runner formatting now reuses formatter sessions per worker instead of constructing a fresh formatter for
  every file.

### Fixed

- Preserved JSpecify/type-use annotation placement on arrays, multidimensional arrays, wildcard bounds, and varargs.
- Kept explicit constructor invocation arguments (`this(...)` / `super(...)`) width-aware through the shared
  argument-list path.
- Preserved broken wrapping for over-wide nested string-concatenation arguments in method calls.

[Unreleased]: https://github.com/lanwen/frmtr/compare/v0.2.1...main
[0.2.1]: https://github.com/lanwen/frmtr/releases/tag/v0.2.1
[0.2.0]: https://github.com/lanwen/frmtr/releases/tag/v0.2.0
[0.1.0]: https://github.com/lanwen/frmtr/releases/tag/v0.1.0
