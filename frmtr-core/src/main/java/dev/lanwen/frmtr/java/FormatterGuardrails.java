package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.comments.Comment;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Hosts opt-in formatter pipeline checks for mistakes that should be noisy during formatter development.
 *
 * <p>This helper owns debug-only invariant activation, shared failure messages, and the small bits of bookkeeping needed
 * to turn silent formatter accounting misses into actionable failures. The boundary exists so printers and trackers can
 * keep their normal output behavior while still having one place for development guardrails.
 *
 * <p>Callers still decide which pipeline events are meaningful enough to guard, what recovery or fallback behavior is
 * appropriate when guardrails are disabled, and how rendered output should be assembled.
 */
final class FormatterGuardrails {

    static final String ENABLED_PROPERTY = "dev.lanwen.frmtr.debug.guardrails";

    /**
     * Toggles the stricter "each comment is claimed at most once" invariant in {@link #claimComment}. Separate from
     * {@link #ENABLED_PROPERTY} because, unlike comment-drop detection and the transform-identity check, this invariant
     * does <em>not</em> hold under the formatter's current design and so must stay off in CI.
     *
     * <p>{@code CommentTracker} couples claim and render — every render path is
     * {@code commentPlacement.X(node).filter(this::claim)…} — so first-claim-wins both de-dupes and renders. Comment
     * ownership between adjacent constructs is inherently ambiguous, so several printer paths legitimately <em>offer</em>
     * the same comment; the losing claim returns {@code false} and simply skips its (redundant) render, and the comment
     * still reaches the output exactly once. A "fail fast on the second claim" assertion therefore flags benign
     * speculative claims as errors even on golden fixtures that are correct.
     *
     * <p>Roadmap <strong>B1</strong> (source-shape consolidation and shape-independent comment ownership) has landed and
     * made the <em>drop</em> invariant hold — {@code CommentPresenceDiagnosticTest} is green and its {@code KNOWN_DROPS}
     * list is empty. The stricter "claimed at most once" invariant still fails, though: a full-suite run with this toggle
     * on yields ~205 violations, all benign speculative claims (zero drops, zero double-emits). The cause is the eager
     * {@code Optional<Doc>} candidate ladders in {@code MethodCallPrinter}, {@code MethodCallChainPrinter},
     * {@code VariableInitializerLayout}, and {@code LambdaExpressionPrinter}: they render a comment-bearing subtree to
     * probe its layout fit, the probe claims the comment, and the losing candidate is then discarded — leaving the comment
     * claimed once for a render that never reached the output and once for the chosen layout.
     *
     * <p>The strict invariant therefore becomes satisfiable — and this property worth CI-enabling — only once those probes
     * are claim-free: a claim-suppressing render mode, or the <strong>B2</strong> {@code conditionalGroup}/{@code lineSuffix}
     * migration that retires the candidate ladders entirely. Until then it is deferred and left off; the valuable half of
     * the guardrail ({@link #assertAllCommentsAccounted} drop detection and the transform-identity check) runs in CI under
     * {@link #ENABLED_PROPERTY} instead.
     *
     * <p><strong>B2 ownership consolidation, Stage 1 (landed).</strong> The trailing-line-comment family is now migrated
     * to explicit ownership: a read-only pre-pass ({@link CommentTracker#assignOwnership}) assigns each trailing comment
     * its single owning slot up front, and {@link CommentTracker#ownsHere} gates the trailing render on that assignment
     * (see {@link OwnerSlot#TRAILING}). This makes trailing ownership deterministic and shape-independent rather than
     * decided by the implicit first-claim-wins race; empirically a pure source-order rule reproduces the trailing family
     * byte-for-byte (zero cross-node {@code ownsHere} rejections corpus-wide), which is why it is the first family to
     * migrate. Stage 1 does <em>not</em> flip this toggle on: the residual that still violates the strict invariant is
     * (a) the not-yet-migrated traversal-order families (leading/adjacent/own/orphan/interleaved), where a source-order
     * rule diverges on the contested parent-interleaver-beats-child cases, and (b) the candidate-ladder probe re-claims
     * described above — even within the migrated trailing slot, a comment-bearing subtree re-probed for layout fit
     * re-claims its own owner's comment, which an ownership rule cannot dedupe. So strict-claims stays off until both the
     * remaining families migrate and the probes render claim-free.
     */
    static final String STRICT_CLAIMS_PROPERTY = "dev.lanwen.frmtr.debug.guardrails.strict-claims";

    /**
     * Toggles the AST-equivalence verify mode (roadmap B3, layer 1). Separate from {@link #ENABLED_PROPERTY} because
     * verification re-parses the formatted output and so has real cost; it must stay off in the shipped hot path.
     */
    static final String VERIFY_PROPERTY = "dev.lanwen.frmtr.debug.verify";

    private static final int COMMENT_SNIPPET_LENGTH = 80;

    private FormatterGuardrails() {}

    /**
     * Records that a comment has been claimed for rendering and rejects duplicate claims under the strict-claims toggle.
     *
     * <p>Normal formatter runs keep the existing best-effort behavior: a duplicate claim simply returns {@code false} so
     * callers can skip rendering a second copy. The {@code printed} set is populated by {@link JavaCommentTrivia#claim}
     * whether or not the throw fires, so comment-drop detection keeps working with the fail-fast off.
     *
     * <p>The fail-fast is gated on {@value #STRICT_CLAIMS_PROPERTY}, <em>not</em> {@value #ENABLED_PROPERTY}: as
     * documented on {@link #STRICT_CLAIMS_PROPERTY}, the "claimed at most once" invariant does not hold under the current
     * claim/render-coupled design (benign speculative claims are expected), so it stays off in CI and is deferred to
     * roadmap B1/B2. Comment-drop detection ({@link #assertAllCommentsAccounted}) and the transform-identity check stay
     * on {@value #ENABLED_PROPERTY} and run in CI.
     */
    static boolean claimComment(JavaCommentTrivia trivia, Set<Comment> claimedComments) {
        boolean claimed = trivia.claim(claimedComments);
        if (!claimed && strictClaimsEnabled()) {
            throw new AssertionError(
                "Formatter comment guardrail failed: duplicate claim for " + describe(trivia.comment())
            );
        }
        return claimed;
    }

    /**
     * Records comments that reached the output through an explicit raw-source preservation path.
     */
    static void accountRawComments(Node node, Set<Comment> rawRenderedComments) {
        accountRawComments(node.getAllContainedComments(), rawRenderedComments);
    }

    /**
     * Records source-selected comments that reached the output through an explicit raw-source preservation path.
     */
    static void accountRawComments(Collection<? extends Comment> comments, Set<Comment> rawRenderedComments) {
        rawRenderedComments.addAll(comments);
    }

    /**
     * Records raw-preserved comments while excluding the node's own attached comment.
     */
    static void accountRawCommentsWithoutOwnComment(Node node, Set<Comment> rawRenderedComments) {
        Optional<Comment> ownComment = node.getComment();
        node.getAllContainedComments()
                .stream()
                .filter(comment -> ownComment.stream().noneMatch(own -> own == comment))
                .forEach(rawRenderedComments::add);
    }

    /**
     * Asserts that every JavaParser-exposed comment reached either structured rendering or raw preservation.
     */
    static void assertAllCommentsAccounted(
            Node root,
            Set<Comment> claimedComments,
            Set<Comment> rawRenderedComments
    ) {
        if (!enabled()) {
            return;
        }
        List<Comment> missedComments = root.getAllContainedComments()
                .stream()
                .filter(comment -> !claimedComments.contains(comment))
                .filter(comment -> !rawRenderedComments.contains(comment))
                .sorted(CommentIndex.sourceOrderComparator())
                .toList();
        if (!missedComments.isEmpty()) {
            throw new AssertionError(
                "Formatter comment guardrail failed: unclaimed comment "
                    + describe(missedComments.getFirst())
                    + " was exposed by JavaParser but was not printed or raw-accounted before formatting completed"
            );
        }
    }

    /**
     * Captures JavaParser identity state before one transform runs.
     *
     * <p>The snapshot is intentionally empty unless debug guardrails are enabled. Transform implementations keep their
     * normal source-equivalent mutation behavior, while the pipeline gets one canonical place to validate assumptions it
     * relies on before printing starts.
     */
    static TransformSnapshot beforeTransform(JavaFormatTransform transform, CompilationUnit unit) {
        return enabled() ? TransformSnapshot.capture(transformName(transform), unit) : TransformSnapshot.disabled();
    }

    /**
     * Asserts that a transform kept the formatter's current JavaParser identity contract.
     */
    static void assertTransformInvariants(TransformSnapshot before, JavaTransformResult result) {
        before.assertPreserved(result);
    }

    static boolean enabled() {
        return Boolean.getBoolean(ENABLED_PROPERTY);
    }

    static boolean strictClaimsEnabled() {
        return Boolean.getBoolean(STRICT_CLAIMS_PROPERTY);
    }

    static boolean verifyEnabled() {
        return Boolean.getBoolean(VERIFY_PROPERTY);
    }

    /**
     * Asserts that the formatted output re-parses to the same program as the input, modulo trivia.
     *
     * <p>This is a no-op unless {@value #VERIFY_PROPERTY} is set, so normal {@code format(...)} runs pay nothing and
     * stay byte-identical. The comparison contract — which differences are trivia (comments, whitespace, import order)
     * and which are genuine (everything else, including enum-constant count) — lives entirely in {@link AstEquivalence};
     * this method only routes a mismatch into an actionable {@link AssertionError} consistent with the other guardrails.
     *
     * <p>The caller supplies the already-parsed input unit and a freshly re-parsed output unit so this helper does not
     * need the parser configuration. Callers must skip verification for recovered (partially-parsed) inputs, where
     * AST-equivalence is ill-defined.
     */
    static void assertAstEquivalent(CompilationUnit input, CompilationUnit output) {
        // Intentionally re-checks verifyEnabled() even though JavaFormatter already gates its call site: this keeps the
        // helper safe to invoke directly (e.g. from tests) without each caller having to remember the toggle.
        if (!verifyEnabled()) {
            return;
        }
        AstEquivalence.describeDifference(input, output).ifPresent(difference -> {
            throw new AssertionError("Formatter AST-equivalence verify failed: " + difference);
        });
    }

    /**
     * Stores debug-only identity snapshots for one transform invocation.
     *
     * <p>The formatter currently treats transforms as in-place source-equivalent normalization over one JavaParser tree.
     * This snapshot makes that boundary explicit: transforms may reorder existing nodes, but replacing the compilation
     * unit, cloning declarations, or swapping JavaParser-visible comments should fail fast during formatter development.
     */
    static final class TransformSnapshot {

        private final boolean enabled;

        private final String transformName;

        private final CompilationUnit unit;

        private final List<Node> nodes;

        private final Set<Node> nodeIdentities;

        private final List<Comment> comments;

        private final Set<Comment> commentIdentities;

        private final List<ImportDeclaration> imports;

        private final Set<ImportDeclaration> importIdentities;

        private final Map<ImportDeclaration, Optional<Comment>> importComments;

        private TransformSnapshot(
                boolean enabled,
                String transformName,
                CompilationUnit unit,
                List<Node> nodes,
                Set<Node> nodeIdentities,
                List<Comment> comments,
                Set<Comment> commentIdentities,
                List<ImportDeclaration> imports,
                Set<ImportDeclaration> importIdentities,
                Map<ImportDeclaration, Optional<Comment>> importComments
        ) {
            this.enabled = enabled;
            this.transformName = transformName;
            this.unit = unit;
            this.nodes = nodes;
            this.nodeIdentities = nodeIdentities;
            this.comments = comments;
            this.commentIdentities = commentIdentities;
            this.imports = imports;
            this.importIdentities = importIdentities;
            this.importComments = importComments;
        }

        private static TransformSnapshot disabled() {
            return new TransformSnapshot(
                false,
                "",
                null,
                List.of(),
                Set.of(),
                List.of(),
                Set.of(),
                List.of(),
                Set.of(),
                Map.of()
            );
        }

        private static TransformSnapshot capture(String transformName, CompilationUnit unit) {
            List<Node> nodes = unit.stream().toList();
            List<Comment> comments = unit.getAllContainedComments();
            List<ImportDeclaration> imports = List.copyOf(unit.getImports());
            Map<ImportDeclaration, Optional<Comment>> importComments = new IdentityHashMap<>();
            imports.forEach(importDeclaration -> importComments.put(importDeclaration, importDeclaration.getComment()));
            return new TransformSnapshot(
                true,
                transformName,
                unit,
                nodes,
                identitySet(nodes),
                comments,
                identitySet(comments),
                imports,
                identitySet(imports),
                importComments
            );
        }

        private void assertPreserved(JavaTransformResult result) {
            if (!enabled) {
                return;
            }
            if (!transformName.equals(result.transformName())) {
                fail(
                    "returned result metadata for "
                        + result.transformName()
                        + " after the pipeline invoked "
                        + transformName
                );
            }
            CompilationUnit transformed = result.unit();
            if (transformed != unit) {
                fail(
                    "returned a different CompilationUnit instance; transforms must keep the original JavaParser tree "
                        + "unless the transform pipeline is redesigned"
                );
            }
            assertIdentitySetPreserved(
                comments,
                commentIdentities,
                transformed.getAllContainedComments(),
                "JavaParser-visible comment was lost or replaced by identity",
                "introduced a new JavaParser-visible comment identity",
                FormatterGuardrails::describe
            );
            assertImportDeclarationsPreserved(transformed);
            assertIdentitySetPreserved(
                nodes,
                nodeIdentities,
                transformed.stream().toList(),
                "JavaParser tree node was lost or replaced by identity",
                "introduced a new JavaParser tree node identity",
                FormatterGuardrails::describe
            );
        }

        private void assertImportDeclarationsPreserved(CompilationUnit transformed) {
            List<ImportDeclaration> transformedImports = List.copyOf(transformed.getImports());
            assertIdentitySetPreserved(
                imports,
                importIdentities,
                transformedImports,
                "import declaration node was lost or replaced instead of being reordered in place",
                "introduced a new import declaration node instead of reordering existing imports in place",
                FormatterGuardrails::describeImport
            );
            for (ImportDeclaration importDeclaration : imports) {
                Optional<Comment> beforeComment = importComments.get(importDeclaration);
                Optional<Comment> afterComment = importDeclaration.getComment();
                if (commentsDiffer(beforeComment, afterComment)) {
                    fail(
                        "comment attachment changed for "
                            + describeImport(importDeclaration)
                            + "; comments attached to import declarations must remain on their original import nodes"
                    );
                }
            }
        }

        private <T> void assertIdentitySetPreserved(
                List<T> before,
                Set<T> beforeIdentities,
                List<T> after,
                String missingMessage,
                String addedMessage,
                Function<T, String> describe
        ) {
            Set<T> afterIdentities = identitySet(after);
            before.stream()
                    .filter(item -> !afterIdentities.contains(item))
                    .findFirst()
                    .ifPresent(item -> fail(missingMessage + ": " + describe.apply(item)));
            after.stream()
                    .filter(item -> !beforeIdentities.contains(item))
                    .findFirst()
                    .ifPresent(item -> fail(addedMessage + ": " + describe.apply(item)));
        }

        private void fail(String invariant) {
            throw new AssertionError("Formatter transform guardrail failed for " + transformName + ": " + invariant);
        }
    }

    private static String transformName(JavaFormatTransform transform) {
        String simpleName = transform.getClass().getSimpleName();
        return simpleName.isBlank() ? transform.getClass().getName() : simpleName;
    }

    private static boolean commentsDiffer(Optional<Comment> before, Optional<Comment> after) {
        if (before.isEmpty() || after.isEmpty()) {
            return before.isPresent() != after.isPresent();
        }
        return before.orElseThrow() != after.orElseThrow();
    }

    private static <T> Set<T> identitySet(List<T> values) {
        Set<T> identities = Collections.newSetFromMap(new IdentityHashMap<>());
        identities.addAll(values);
        return identities;
    }

    private static String describeImport(ImportDeclaration declaration) {
        String kind = declaration.isStatic() ? "static import " : "import ";
        return "ImportDeclaration " + kind + declaration.getNameAsString() + " at " + range(declaration);
    }

    private static String describe(Node node) {
        return node.getClass().getSimpleName() + " at " + range(node);
    }

    private static String describe(Comment comment) {
        String text = snippet(comment.toString());
        return comment.getClass().getSimpleName() + " at " + range(comment) + " [" + text + "]";
    }

    private static String range(Node node) {
        return node.getRange().map(Object::toString).orElse("unknown range");
    }

    private static String snippet(String text) {
        String singleLine = text.strip().replaceAll("\\R", "\\\\n");
        if (singleLine.length() <= COMMENT_SNIPPET_LENGTH) {
            return singleLine;
        }
        return singleLine.substring(0, COMMENT_SNIPPET_LENGTH) + "...";
    }
}
