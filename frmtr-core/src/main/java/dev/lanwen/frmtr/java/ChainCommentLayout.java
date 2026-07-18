package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Owns chain-comment detection and the source-comment {@link Doc} slots for {@link MethodCallChainPrinter}.
 *
 * <p>This helper hosts the family of predicates that answer "does this chain or selector carry a comment, and of which
 * kind?" — a selector's leading-line / name / argument-gap / leading-gap-block comment, a chain's inter-segment or
 * trailing line comments, and a root-to-first-selector trailing line comment — together with the renderers that emit the
 * comments those predicates find: the leading-line and interspersed-orphan selector prefixes, the between-segments and
 * root-to-first-selector trailing slots, and the final-segment trailing slot. The boundary exists so the chain printer's
 * layout decision tree can consult one comment authority — is this chain comment-free enough to fan / stay flat, and if
 * not, which slot re-emits the comment — instead of carrying every comment-placement scan inline. Every predicate here
 * reads the same comment-candidate sets the renderers consume, so a withhold verdict and the render stay in lockstep and
 * no comment is double-claimed.
 *
 * <p>The helper claims no ownership of chain analysis, layout, or width: it reports the comments present and renders the
 * ones it is asked for, but never decides a chain's shape or where a selector lands. That stays with the caller, which
 * threads these predicates into its {@code MethodCallChainSourcePlanner} analysis and its fan / stay-flat gates. The
 * chain-level "does the final selector trail a line comment?" query stays on the caller (it needs a full chain analysis)
 * and delegates only the leaf {@link #finalTrailingLineComments} scan to this helper.
 */
final class ChainCommentLayout {

    private final CommentTracker comments;

    private final JavaCommentPlacementPolicy commentPlacement;

    private final CommentedExpressionListPrinter commentedExpressionLists;

    ChainCommentLayout(
            CommentTracker comments,
            JavaCommentPlacementPolicy commentPlacement,
            CommentedExpressionListPrinter commentedExpressionLists
    ) {
        this.comments = comments;
        this.commentPlacement = commentPlacement;
        this.commentedExpressionLists = commentedExpressionLists;
    }

    boolean methodCallSegmentHasComment(MethodCallExpr expression) {
        return methodCallSegmentHasNameComment(expression)
            || methodCallSegmentHasLeadingLineComment(expression)
            || methodCallSegmentHasArgumentGapComment(expression);
    }

    boolean methodCallSegmentHasLeadingLineComment(MethodCallExpr expression) {
        return !leadingLineCommentsBeforeSegment(expression).isEmpty();
    }

    boolean methodCallSegmentHasArgumentGapComment(MethodCallExpr expression) {
        return commentedExpressionLists.hasUnprintedLineComments(expression, expression.getArguments());
    }

    boolean methodCallSegmentHasLineComments(MethodCallExpr expression) {
        return commentedExpressionLists.hasLineComments(expression, expression.getArguments());
    }

    /**
     * Reports whether the only selector of a method-call-rooted chain carries a block comment parked in the gap between
     * the root and the selector, for example {@code create() /* doc *}{@code / .seal()}.
     *
     * <p>JavaParser attaches such a gap block comment to the selector's name (see {@code methodCallSegmentPrefix}), so the
     * stay-flat gate's contained-comment scan on the root misses it and the chain reaches the single-segment branch. This
     * predicate lets that branch break the segment onto its own continuation line, where the segment prefix re-emits the
     * comment with its source space, instead of gluing it flat and dropping the space. It deliberately accepts only a
     * block (or Javadoc) comment that starts after the root ends and before the selector name so an ordinary leading
     * comment already handled elsewhere, or a comment that belongs to the root, is not re-claimed here.
     */
    boolean methodCallSegmentHasLeadingGapBlockComment(Expression root, MethodCallExpr segment) {
        return segment.getName()
                .getComment()
                .filter(comment -> comment instanceof BlockComment || comment instanceof JavadocComment)
                .filter(comment -> CommentIndex.startsBefore(comment, segment.getName()))
                .filter(comment -> root.getRange()
                            .flatMap(rootRange -> comment.getRange()
                                        .map(commentRange -> commentRange.begin.isAfter(rootRange.end))
                            )
                            .orElse(false)
                )
                .isPresent();
    }

    boolean methodCallSegmentHasNameComment(MethodCallExpr expression) {
        return expression.getName()
                .getComment()
                .filter(comment -> CommentIndex.startsBefore(comment, expression.getName()))
                .isPresent();
    }

    boolean methodCallChainHasTrailingLineComments(List<MethodCallExpr> calls) {
        for (int index = 0; index + 1 < calls.size(); index++) {
            if (!trailingLineCommentsBeforeNextSegment(calls.get(index), calls.get(index + 1)).isEmpty()) {
                return true;
            }
        }
        return !calls.isEmpty() && !finalTrailingLineComments(calls.getLast()).isEmpty();
    }

    /**
     * Reports whether a chain carries an inter-segment {@code //} <em>line</em> comment — the comment class whose only
     * safe render keeps the chain fanned one selector per line. Callers use this to route such a chain off the stay-flat
     * path and onto the comment-preserving fan, so the comment is not dropped.
     *
     * <p>It covers the three inter-segment positions a {@code //} comment can occupy:
     * <ul>
     *   <li><b>root → first selector</b> — a line comment the author parked after the root and before the first selector,
     *       whether owned by the root as its trailing comment / root-to-first-selector-gap
     *       ({@link #rootHasTrailingLineCommentBeforeFirstSegment}) or attached as the first selector's leading comment
     *       ({@link #leadingLineCommentsBeforeSegment});</li>
     *   <li><b>dot-gap</b> — a line comment leading a later selector on its own continuation line, e.g. {@code .a()}⏎
     *       {@code // note}⏎{@code .b()} ({@link #leadingLineCommentsBeforeSegment} on each call);</li>
     *   <li><b>between selectors</b> — a trailing line comment in the gap after one selector and before the next, e.g.
     *       {@code .a() // note}⏎{@code .b()} ({@link #trailingLineCommentsBeforeNextSegment}).</li>
     * </ul>
     *
     * <p><strong>Line comments only.</strong> A {@code //} comment runs to end-of-line, so it forces the next selector
     * onto a later line and the chain cannot stay flat. Block comments ({@code create() /* doc *}{@code / .seal()}) are
     * deliberately excluded because they can sit inline without a line break — the chain can stay flat — so folding them
     * in would fan a chain that need not fan. This predicate consults only the same line-comment candidate sets the
     * imperative comment-preserving render consumes; it claims no comment, so placement stays owned by the render.
     */
    boolean chainHasInterSegmentLineComment(Expression root, List<MethodCallExpr> calls) {
        if (calls.isEmpty()) {
            return false;
        }
        // root -> first selector: root-owned trailing / gap line comment, or the first selector's own leading comment.
        if (rootHasTrailingLineCommentBeforeFirstSegment(root, calls)
            || methodCallSegmentHasLeadingLineComment(calls.getFirst())) {
            return true;
        }
        for (int index = 0; index < calls.size(); index++) {
            // dot-gap: a line comment leading a later selector on its own continuation line.
            if (index > 0 && methodCallSegmentHasLeadingLineComment(calls.get(index))) {
                return true;
            }
            // between selectors: a trailing line comment after this selector and before the next.
            if (index + 1 < calls.size()
                && !trailingLineCommentsBeforeNextSegment(calls.get(index), calls.get(index + 1)).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Recovers a block or Javadoc comment that sits between this segment's scope and its selector but that JavaParser
     * parked as an orphan of the call rather than as the selector's own trivia.
     *
     * <p>This is the orphan-bucket sibling of the selector's own-comment slot in {@link #methodCallSegmentPrefix}. At the
     * canonical and collapsed shapes JavaParser attaches a {@code .define(A) /** doc *}{@code / .define(B)} comment to the
     * {@code B} selector, so the own-comment slot renders it; an expanded whitespace shape re-buckets the identical
     * comment onto the enclosing call's orphan pool even though the AST is otherwise unchanged, so the own slot does not
     * hold it and it would be dropped without this recovery. Selecting by source position from the orphan pool — strictly after the scope ends and
     * strictly before the selector begins — keeps the comment owned by this between-links slot whatever the layout.
     *
     * <p>The orphan pool is read directly from the node ({@link Node#getOrphanComments()}) rather than through the
     * comment-placement map, because the assignment/initializer renderers hand the chain printer a {@link Node#clone()
     * clone} of the chain expression (see {@code ExpressionRuleEnvelope.expressionWithoutOwnComment}). A clone carries its
     * orphan comments forward, but the identity-keyed placement map only knows the original parse node, so the map answers
     * empty for the clone; the node's own orphan list is the one association that survives the clone. Line comments are
     * deliberately excluded: they are already recovered by {@link #leadingLineCommentsBeforeSegment} and the
     * between-segments trailing slot. Each comment is offered under {@link OwnerSlot#ORPHAN} and claimed once, so the
     * canonical/collapsed shape — where the comment is the selector's own trivia and not in the orphan pool — is left
     * byte-identical.
     */
    Doc interspersedOrphanCommentsBeforeSelector(MethodCallExpr expression) {
        Optional<Expression> scope = expression.getScope();
        if (scope.isEmpty() || expression.getOrphanComments().isEmpty()) {
            return Doc.EMPTY;
        }
        // Empty-argument selectors ({@code .util()}, {@code .build()}) are recovered here too. They route their inside-
        // the-parens orphans through {@code MethodCallPrinter.emptyMethodCallArguments}, but that owner now excludes the
        // between-links orphan (the one this slot selects: strictly after the scope ends and before the selector begins),
        // so the two slots partition the call's orphan pool by source position and each orphan is claimed exactly once.
        // Without this recovery the between-links comment before an empty-argument selector is dropped whenever the call
        // reaches the printer as a clone (the assignment/initializer value path), because the clone's orphans survive on
        // the node but not in the placement map the empty-argument owner reads.
        Expression scoped = scope.orElseThrow();
        return Doc.concat(
            expression.getOrphanComments()
                    .stream()
                    .map(JavaCommentTrivia::from)
                    .filter(trivia -> trivia.isBlock() || trivia.isJavadoc())
                    .filter(trivia -> trivia.liesBetween(scoped, expression.getName()))
                    .sorted((left, right) ->
                        CommentIndex.sourceOrderComparator().compare(left.comment(), right.comment()))
                    .map(trivia -> comments.comment(trivia, expression, OwnerSlot.ORPHAN))
                    .filter(comment -> comment != Doc.EMPTY)
                    .map(comment -> Doc.concat(comment, Doc.HARD_LINE))
                    .toList()
        );
    }

    List<JavaCommentTrivia> leadingLineCommentsBeforeSegment(MethodCallExpr expression) {
        Optional<Expression> scope = expression.getScope();
        if (scope.isEmpty()) {
            return List.of();
        }
        int scopeEndLine = CommentIndex.endLine(scope.orElseThrow(), Integer.MIN_VALUE);
        int nameBeginLine = CommentIndex.beginLine(expression.getName(), Integer.MAX_VALUE);
        return commentPlacement.containedComments(expression)
                .stream()
                .filter(JavaCommentTrivia::isLine)
                .filter(comment -> comment.beginLine(Integer.MIN_VALUE) > scopeEndLine)
                .filter(comment -> comment.beginLine(Integer.MAX_VALUE) < nameBeginLine)
                .sorted((left, right) -> CommentIndex.sourceOrderComparator().compare(left.comment(), right.comment()))
                .toList();
    }

    Doc trailingLineCommentBeforeNextSegment(Node expression, Optional<MethodCallExpr> nextCall) {
        if (nextCall.isEmpty()) {
            return Doc.EMPTY;
        }
        MethodCallExpr next = nextCall.orElseThrow();
        // A between-segments gap comment is offered under the next segment's own INTERLEAVED anchor — the slot that names
        // "a comment interleaved before this chain link". Anchoring to next rather than to the comment's own node lets
        // comment ownership disambiguate the competing offers without a build-order isPrinted skip. A comment on the same
        // physical line as this segment's close can also be the same-line final-trailing comment of an inner chain nested
        // in this segment's lambda argument (the collapsed {@code .orElseThrow(...) // note .orElseGet(...)} shape, where
        // the inner chain's last call and this outer link share a line): that inner render runs first and claims it under
        // its own (innerFinalCall, INTERLEAVED) slot, while previous's argument-list render claims a comment inside
        // previous's args under (previous, INTERLEAVED). Both are different keys from (next, INTERLEAVED), so ownsHere
        // blocks this slot and comment(...) returns Doc.EMPTY here (caught by the != Doc.EMPTY filter). next is chosen as
        // the anchor precisely because the gap comment always sits structurally before next, so no offer from next's own
        // subtree competes for it under this key — unlike previous, whose argument-list render shares the (previous,
        // INTERLEAVED) key and would double-render the same comment through the idempotent-by-owner re-claim branch. A pure
        // gap comment no neighbor claimed is owned here and placed by this slot. The candidate stream is de-duplicated by
        // comment identity inside trailingLineCommentsBeforeNextSegment, so the same comment is never offered twice under
        // this shared key.
        List<Doc> sourceComments = trailingLineCommentsBeforeNextSegment(expression, next)
                .stream()
                .map(trivia -> comments.comment(trivia, next, OwnerSlot.INTERLEAVED))
                .filter(comment -> comment != Doc.EMPTY)
                .toList();
        return sourceComments.isEmpty() ? Doc.EMPTY : Doc.join(Doc.text(" "), sourceComments);
    }

    Doc rootTrailingLineCommentBeforeFirstSegment(Expression root, List<MethodCallExpr> calls) {
        if (calls.isEmpty()) {
            return Doc.EMPTY;
        }
        return trailingLineCommentBeforeNextSegment(root, Optional.of(calls.getFirst()));
    }

    /**
     * Reports whether the chain root carries a trailing / root-to-first-selector-gap line comment that the imperative
     * chain renderer would re-emit through {@link #rootTrailingLineCommentBeforeFirstSegment}, for example
     * {@code new Zone(api, auth, "name") // restart note}⏎{@code .withProperty(...)}.
     *
     * <p><strong>Why the fan's other comment gates miss it.</strong> JavaParser attaches such a comment as the root
     * expression's <em>own</em> comment (the {@code ObjectCreationExpr} / root {@code MethodCallExpr} it trails), not as a
     * child or contained comment. {@link MethodCallChainAnalysis#rootHasComments()} is built from
     * {@link SourceShapePolicy#hasContainedComments(Node)} — which lists a node's orphans and its children's comments but
     * <em>not</em> the node's own comment — plus {@code rootToFirstSelectorGapHasBlockComment}, which matches only block
     * {@code /* *}{@code /} markers. The per-selector comment scans key on the selectors' own trivia, and the
     * trailing-line-comment scan only inspects the gaps <em>between</em> and <em>after</em> selectors. So a line comment
     * owned by the root in the gap before the first selector is invisible to every existing comment gate, the chain reads
     * comment-free, and the source-neutral fan ({@code chainFanOut}) re-renders the root through ordinary expression
     * dispatch — which does not carry the root's own comment — silently dropping it.
     *
     * <p>Detecting it here off the same {@link #trailingLineCommentsBeforeNextSegment} candidate set the renderer consumes
     * keeps the withhold verdict and the render in lockstep: any comment this predicate sees is one the imperative path
     * will actually place, so folding it into {@code hasComments} routes the chain off the fan and onto that
     * comment-preserving path without over- or under-withholding. This reads the candidate set only; it does not claim or
     * mark any comment printed, so the real render still owns placement.
     */
    boolean rootHasTrailingLineCommentBeforeFirstSegment(Expression root, List<MethodCallExpr> calls) {
        if (calls.isEmpty()) {
            return false;
        }
        return !trailingLineCommentsBeforeNextSegment(root, calls.getFirst()).isEmpty();
    }

    List<JavaCommentTrivia> trailingLineCommentsBeforeNextSegment(Node previous, MethodCallExpr next) {
        List<JavaCommentTrivia> candidates = new ArrayList<>();
        commentPlacement.trailingLineComment(previous).ifPresent(candidates::add);
        candidates.addAll(commentPlacement.containedComments(previous));
        candidates.addAll(lineCommentCandidatesBeforeNextSegment(next));
        // The three candidate sources overlap, so the same comment node can be offered more than once. Dedupe on
        // JavaParser comment identity rather than the record's value equality: structurally equal but distinct comment
        // nodes (e.g. two chain links carrying the same `// text`, or several empty `//` continuation markers) must each
        // survive, while a genuine reference-equal repeat from the overlapping sources is still collapsed.
        Set<Comment> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        return candidates
                .stream()
                .filter(comment -> seen.add(comment.comment()))
                .filter(comment -> comment.startsAfterNodeOnSameLine(previous))
                .filter(comment -> comment.startsBeforeBeginLine(next.getName()))
                .toList();
    }

    List<JavaCommentTrivia> lineCommentCandidatesBeforeNextSegment(MethodCallExpr next) {
        if (!next.getArguments().isEmpty()) {
            return commentPlacement.lineCommentsBeforeFirst(next, next.getArguments().get(0));
        }
        return commentPlacement.containedComments(next)
                .stream()
                .filter(JavaCommentTrivia::isLine)
                .toList();
    }

    /**
     * Whether JavaParser bound a trailing line comment to {@code node}'s own last token (its own comment), as opposed
     * to a comment sitting deeper inside {@code node}'s subtree. Distinguishes an argument chain that itself trails a
     * comment ({@code chain.a().b() // note}) from one whose comment binds to a nested leaf ({@code List.of(x // note)}).
     */
    boolean hasOwnTrailingLineComment(Node node) {
        return commentPlacement.trailingLineComment(node).isPresent();
    }

    /**
     * Keeps a final segment's same-line comment after the rendered call, even when the call arguments break.
     */
    Doc finalTrailingLineComment(MethodCallExpr expression) {
        // This final segment's same-line trailing line comment is also offered by a neighboring chain render (an outer
        // segment's argument render that reaches the same final call, say) under the comment's own anchorless
        // (comment, INTERLEAVED) key. Offering it here under this segment's own (expression, INTERLEAVED) anchor lets
        // comment ownership disambiguate: when the neighboring render owns it, this slot is not the recorded owner and
        // comment(...) returns Doc.EMPTY (caught by the != Doc.EMPTY filter below); a comment no neighbor claimed is
        // owned here and placed by this final-segment slot. Anchoring to the distinct (expression, INTERLEAVED) key
        // rather than the comment's own node is what makes the ownership gate sufficient, so no build-order isPrinted
        // skip is needed.
        List<Doc> sourceComments = finalTrailingLineComments(expression)
                .stream()
                .map(comment -> comments.comment(comment, expression, OwnerSlot.INTERLEAVED))
                .filter(comment -> comment != Doc.EMPTY)
                .toList();
        return sourceComments.isEmpty() ? Doc.EMPTY : Doc.join(Doc.text(" "), sourceComments);
    }

    List<JavaCommentTrivia> finalTrailingLineComments(MethodCallExpr expression) {
        List<JavaCommentTrivia> sourceComments = new ArrayList<>();
        commentPlacement.trailingLineComment(expression).ifPresent(sourceComments::add);
        commentPlacement.containedComments(expression)
                .stream()
                .filter(JavaCommentTrivia::isLine)
                .filter(comment -> comment.startsAfterNodeOnSameLine(expression))
                .filter(
                    comment -> sourceComments.stream().noneMatch(existing -> existing.comment() == comment.comment())
                )
                .forEach(sourceComments::add);
        return sourceComments;
    }
}
