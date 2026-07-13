package dev.lanwen.frmtr.java;

import com.github.javaparser.JavaToken;
import com.github.javaparser.Position;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Owns line-comment detection and the source-comment {@link Doc} slots for {@link ConditionalExpressionPrinter}'s ternary
 * rendering.
 *
 * <p>This helper hosts the family that answers "does this {@code ?:} carry a line comment, and where does it render?": the
 * force-broken gate {@link #conditionalContainsLineComment} that routes a commented ternary off the flat arm; the full
 * rebuild {@link #commentedConditionalExpression}, which gathers every candidate line comment from <em>both</em> the child
 * expressions' own comments and the conditional's orphan comments and classifies each — by where it begins in source order
 * relative to the {@code ?} and {@code :} operator-token positions — into a condition-trailing, {@code ?}-leading,
 * then-trailing, {@code :}-leading, or else-trailing slot; and the branch-tail recovery {@link #branchTailTrailingComment}
 * that keeps a comment trailing a multi-line branch's last token on that branch's line. The boundary exists so the ternary
 * printer's flat-versus-broken decision tree can consult one comment authority — must this ternary break to keep a comment,
 * and if so which slot re-emits it — instead of carrying every comment scan and operator-position classification inline.
 *
 * <p>The helper claims no ownership of the ternary's shape, width, or nesting: it reports the comments present and renders
 * the ones it is asked for, but never decides whether the conditional stays flat, breaks for width, or how a branch
 * expression itself lays out. That stays with the caller, which threads {@link #conditionalContainsLineComment} into its
 * force-broken gate, delegates the comment rebuild through {@link #commentedConditionalExpression}, and renders branch
 * expressions on its own {@link ConditionalExpressionPrinter#conditionalBranch} path so their inner comments survive.
 * Because the {@code ?} and {@code :} are not AST nodes, classification keys purely off source position — a relationship a
 * whitespace perturbation cannot change — and every candidate is read from the same own-comment / orphan / contained-comment
 * sets the renderers consume and claimed once by identity, so a withhold verdict and the render stay in lockstep and no
 * comment is double-claimed.
 */
final class ConditionalCommentLayout {

    private final CommentTracker comments;

    private final JavaCommentPlacementPolicy commentPlacement;

    private final ExpressionRendering rendering;

    ConditionalCommentLayout(
            CommentTracker comments,
            JavaCommentPlacementPolicy commentPlacement,
            ExpressionRendering rendering
    ) {
        this.comments = comments;
        this.commentPlacement = commentPlacement;
        this.rendering = rendering;
    }

    /**
     * Recovers a line comment that trails a ternary branch's last token but that the branch renderer drops, and renders
     * it inline after the branch.
     *
     * <p>This covers the then-branch &rarr; {@code :} gap when the then-branch is a multi-line binary chain. JavaParser
     * attaches a {@code base + offset // tuned} comment to the whole branch (its own comment, which
     * {@link #commentedConditionalExpression} already routes through the {@code THEN_TRAILING} slot), but in
     * {@code aaa + bbb // mid + ccc // tail} it instead parks the trailing {@code // tail} after the chain's last operand
     * as that operand's leading trivia. The binary continuation renderer only offers comments <em>between</em> operands,
     * so it reaches {@code // mid} but never a comment that begins after the whole chain's last token, and the ternary
     * then-branch &rarr; {@code :} slot is owned by neither printer. This is the ternary then-branch analog of the
     * after-initializer/before-{@code ;} slot recovered by
     * {@link JavaCommentPlacementPolicy#trailingInitializerCommentsBeforeSemicolon(Node, Node)}.
     *
     * <p>Rendering happens here, on the branch's last printed line, rather than re-routing the whole conditional through
     * {@link #commentedConditionalExpression}: that path renders branches with the own-comment-stripping clone renderer,
     * which a multi-line branch's between-operand comments ({@code // mid}) cannot survive because a cloned node is absent
     * from the per-run comment map. Keeping the branch on its existing {@link ConditionalExpressionPrinter#conditionalBranch}
     * renderer preserves every inner comment; only the genuinely-trailing tail comment is added back here.
     *
     * <p>The comment is claimed once by identity through {@link CommentTracker#comment(Comment)}: the binary renderer
     * never offers it (it has no operand after the last to pin it between), so this is its only claimant. A branch with no
     * such trailing comment — including the {@code @default} single-line then-branch whose comment is the branch's own
     * trivia, not contained trivia after the branch end — yields {@link Doc#EMPTY} and leaves the layout unchanged.
     */
    Doc branchTailTrailingComment(Expression branch) {
        Optional<Comment> branchOwn = branch.getComment();
        return branch.getAllContainedComments()
                .stream()
                .filter(LineComment.class::isInstance)
                .filter(comment -> branchOwn.map(own -> own != comment).orElse(true))
                .filter(comment -> CommentIndex.startsAfterEndOf(branch, comment))
                .min(CommentIndex.sourceOrderComparator())
                .map(comment -> Doc.concat(Doc.text(" "), comments.comment(comment)))
                .orElse(Doc.EMPTY);
    }

    /**
     * Rebuilds a conditional expression when line comments are attached around the ternary operators.
     *
     * <p>JavaParser attaches these comments to nearby expressions, not to {@code ?} or {@code :} tokens, and which bucket
     * it picks depends on the source layout: at one shape a {@code ? // x} comment is the then expression's own leading
     * comment, while a whitespace perturbation that moves it onto its own line re-buckets it as one of the conditional's
     * orphan comments. The formatter therefore gathers every candidate line comment from <em>both</em> the child
     * expressions' own comments and the conditional's orphan comments, then classifies each by where it begins in source
     * order relative to the condition, then, and else expression ranges and the {@code ?} / {@code :} operator-token
     * positions: trailing the condition, leading the {@code ?} branch, trailing the then branch, leading the {@code :}
     * branch, or trailing the else branch. A line comment that actually trails the containing expression statement is
     * left to statement-level handling.
     *
     * <p>The classification is deliberately position-based rather than column-arithmetic or attachment-bucket based. The
     * {@code ?} and {@code :} are not AST nodes, so the branch a comment belongs to is decided purely by whether the
     * comment begins before or after each operator token's source position — a relationship a whitespace perturbation
     * cannot change.
     */
    Optional<Doc> commentedConditionalExpression(ConditionalExpr expression) {
        List<Comment> candidates = ternaryBranchComments(expression);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        Optional<Position> questionPosition = operatorPosition(
            expression,
            "?",
            expression.getCondition(),
            expression.getThenExpr()
        );
        Optional<Position> colonPosition = operatorPosition(
            expression,
            ":",
            expression.getThenExpr(),
            expression.getElseExpr()
        );
        Map<Region, List<Comment>> byRegion = new EnumMap<>(Region.class);
        for (Comment comment : candidates) {
            byRegion
                .computeIfAbsent(
                    classifyTernaryComment(expression, comment, questionPosition, colonPosition),
                    region -> new ArrayList<>()
                )
                .add(comment);
        }
        return Optional.of(
            Doc.concat(
                conditionalConditionWithTrailingComment(
                    expression.getCondition(),
                    regionComment(byRegion, Region.CONDITION_TRAILING)
                ),
                Doc.indent(
                    Doc.concat(
                        Doc.HARD_LINE,
                        conditionalCommentedBranch(
                            "?",
                            expression.getThenExpr(),
                            regionComment(byRegion, Region.QUESTION_LEADING),
                            regionComment(byRegion, Region.THEN_TRAILING)
                        ),
                        Doc.HARD_LINE,
                        conditionalCommentedBranch(
                            ":",
                            expression.getElseExpr(),
                            regionComment(byRegion, Region.COLON_LEADING),
                            regionComment(byRegion, Region.ELSE_TRAILING)
                        )
                    )
                )
            )
        );
    }

    /** Branch slot a candidate line comment renders into; STATEMENT_TRAILING is never rendered here. */
    private enum Region {
        CONDITION_TRAILING,
        QUESTION_LEADING,
        THEN_TRAILING,
        COLON_LEADING,
        ELSE_TRAILING,
        STATEMENT_TRAILING
    }

    /** Source interval a comment begins in, split by the {@code ?} / {@code :} operator-token positions. */
    private enum Zone {
        BEFORE_THEN,
        THEN_TO_COLON,
        COLON_TO_ELSE,
        ELSE_OR_AFTER
    }

    /** Which interval of the ternary the comment begins in, split by the {@code ?} / {@code :} operator-token positions. */
    private Zone zoneOf(ConditionalExpr expression, Comment comment, Optional<Position> colonPosition) {
        if (CommentIndex.startsBefore(comment, expression.getThenExpr())) {
            return Zone.BEFORE_THEN;
        }
        if (startsBeforeOperator(comment, colonPosition)) {
            return Zone.THEN_TO_COLON;
        }
        if (CommentIndex.startsBefore(comment, expression.getElseExpr())) {
            return Zone.COLON_TO_ELSE;
        }
        return Zone.ELSE_OR_AFTER;
    }

    /**
     * Classifies a candidate line comment into its ternary region by source position. STATEMENT_TRAILING is left to
     * statement-level handling (never rendered here). Mirrors the prior position-based classification exactly.
     */
    private Region classifyTernaryComment(
            ConditionalExpr expression,
            Comment comment,
            Optional<Position> questionPosition,
            Optional<Position> colonPosition
    ) {
        return switch (zoneOf(expression, comment, colonPosition)) {
            // condition / `?` region. The comment trails the condition only in the flat `cond ? // x` shape — after the
            // `?` token yet still on the condition's end line — which keeps it before the line break. Every other comment
            // here (on its own line before or after `?`, inline before `?`) leads the `?` branch.
            case BEFORE_THEN -> conditionTrailsBeforeQuestionBranch(expression, comment, questionPosition)
                    ? Region.CONDITION_TRAILING
                    : Region.QUESTION_LEADING;
            // then / `:` region before the `:` token. A comment inline-trailing the then expression trails the then
            // branch; a comment on its own line here leads the `:` branch, the mirror of the condition / `?` split.
            case THEN_TO_COLON -> CommentIndex.startsAfterNodeOnSameLine(expression.getThenExpr(), comment)
                    ? Region.THEN_TRAILING
                    : Region.COLON_LEADING;
            case COLON_TO_ELSE -> Region.COLON_LEADING;
            case ELSE_OR_AFTER -> conditionalElseCommentIsStatementTrailing(expression, comment)
                    ? Region.STATEMENT_TRAILING
                    : Region.ELSE_TRAILING;
        };
    }

    /**
     * Renders every comment a region claimed to a Doc, or {@link Doc#EMPTY} when that region has no comment.
     *
     * <p>A single region can claim several line comments when the source stacks a multi-line {@code //} block in one slot
     * (for example a comment block on its own lines before the {@code ?} branch). They are rendered in source order as a
     * {@link Doc#HARD_LINE}-separated block so no line is dropped; a region with exactly one comment renders that comment
     * alone, leaving the single-comment placement unchanged.
     */
    private Doc regionComment(Map<Region, List<Comment>> byRegion, Region region) {
        List<Comment> regionComments = byRegion.get(region);
        if (regionComments == null || regionComments.isEmpty()) {
            return Doc.EMPTY;
        }
        return Doc.join(Doc.HARD_LINE, regionComments.stream().map(comments::comment).toList());
    }

    /**
     * Collects the line comments that sit around a ternary's operators, from both the child expressions' own comments
     * and the conditional's orphan comments, in source order.
     *
     * <p>Comments from a nested conditional branch are excluded: they are already owned by the inner {@link
     * ConditionalExpr} and would otherwise be classified twice. Each candidate is included once even when it is reachable
     * through more than one association. Dedup is by <em>object identity</em> rather than {@code equals}: after a
     * whitespace collapse JavaParser can attach the <em>same</em> comment instance to two child expressions at once (for
     * instance both the condition's and the then expression's own comment), and a content-based {@code contains} check
     * would still admit that single instance twice. A comment claimed twice would render twice and trip the formatter's
     * duplicate-claim guardrail, so the same node must be collected at most once however many associations reach it.
     */
    private List<Comment> ternaryBranchComments(ConditionalExpr expression) {
        List<Comment> candidates = new ArrayList<>();
        Set<Comment> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ternaryOwnComment(expression.getCondition()).ifPresent(comment -> addCandidate(candidates, seen, comment));
        ternaryOwnComment(expression.getThenExpr()).ifPresent(comment -> addCandidate(candidates, seen, comment));
        ternaryOwnComment(expression.getElseExpr()).ifPresent(comment -> addCandidate(candidates, seen, comment));
        for (Comment orphan : expression.getOrphanComments()) {
            if (orphan instanceof LineComment) {
                addCandidate(candidates, seen, orphan);
            }
        }
        candidates.removeIf(comment -> belongsToNestedConditional(expression, comment));
        candidates.sort(CommentIndex.sourceOrderComparator());
        return candidates;
    }

    /** Appends {@code comment} unless that exact instance was already collected, keeping candidates identity-unique. */
    private void addCandidate(List<Comment> candidates, Set<Comment> seen, Comment comment) {
        if (seen.add(comment)) {
            candidates.add(comment);
        }
    }

    private Optional<Comment> ternaryOwnComment(Expression branch) {
        return branch.getComment().filter(LineComment.class::isInstance);
    }

    /**
     * Reports whether {@code comment} begins inside one of this conditional's branches that is itself a conditional, so
     * the nested ternary owns the comment and this level must not claim it.
     */
    private boolean belongsToNestedConditional(ConditionalExpr expression, Comment comment) {
        return expression.findAll(ConditionalExpr.class).stream()
                .filter(nested -> nested != expression)
                .anyMatch(nested -> nested.getRange()
                        .flatMap(nestedRange -> comment.getRange().map(
                                commentRange -> commentRange.begin.isAfterOrEqual(nestedRange.begin)
                                        && commentRange.begin.isBeforeOrEqual(nestedRange.end)
                        ))
                        .orElse(false));
    }

    private Doc conditionalConditionWithTrailingComment(Expression condition, Doc trailingComment) {
        Doc trailing = trailingComment == Doc.EMPTY ? Doc.EMPTY : Doc.concat(Doc.text(" "), trailingComment);
        return Doc.concat(rendering.renderWithoutOwnComment(condition), trailing);
    }

    /**
     * Reports whether a condition / {@code ?}-region comment trails the condition rather than leading the {@code ?}
     * branch.
     *
     * <p>This is the flat {@code cond ? // x} shape: the comment begins after the {@code ?} token yet still on the
     * condition's end line, so rendering it as a condition-trailing comment keeps it before the line break. A comment on
     * its own line — before or after {@code ?} — or one inline before {@code ?} leads the {@code ?} branch instead. The
     * predicate keys off source position — the comment begins after the {@code ?} operator yet on the condition's end
     * line — so it preserves the {@code @default} classification.
     */
    private boolean conditionTrailsBeforeQuestionBranch(
            ConditionalExpr expression,
            Comment comment,
            Optional<Position> questionPosition
    ) {
        return questionPosition
                .filter(position -> CommentIndex.startsAfter(comment, position))
                .map(position -> CommentIndex.startsAfterNodeOnSameLine(expression.getCondition(), comment))
                .orElse(false);
    }

    /**
     * Reports whether {@code comment} begins before {@code operatorPosition} in source order.
     *
     * <p>When the operator token position is unavailable (a missing range), the comment is treated as <em>not</em>
     * before it, so a comment with no position falls into the after-operator branch by default rather than being
     * silently reclassified.
     */
    private boolean startsBeforeOperator(Comment comment, Optional<Position> operatorPosition) {
        return operatorPosition.map(position -> CommentIndex.startsBefore(comment, position)).orElse(false);
    }

    /**
     * Finds the source position of the ternary operator token (the {@code ?} or {@code :}) that sits in the source-order
     * gap between two of the conditional's child expressions.
     *
     * <p>The {@code ?} is the only such token between the condition and the then expression, and the {@code :} the only
     * one between the then and else expression, so scanning the conditional's token stream for the matching operator
     * whose position lies strictly between {@code before}'s end and {@code after}'s begin pins the outer operator without
     * confusing it with an operator from a nested conditional (those live inside a branch sub-range).
     */
    private Optional<Position> operatorPosition(
            ConditionalExpr expression,
            String operatorToken,
            Expression before,
            Expression after
    ) {
        Position lowerBound = before.getRange().map(range -> range.end).orElse(null);
        Position upperBound = after.getRange().map(range -> range.begin).orElse(null);
        if (lowerBound == null || upperBound == null) {
            return Optional.empty();
        }
        return expression.getTokenRange()
                .flatMap(tokenRange -> {
                    for (JavaToken token : tokenRange) {
                        if (!token.getText().equals(operatorToken)) {
                            continue;
                        }
                        Position tokenBegin = token.getRange().map(range -> range.begin).orElse(null);
                        if (tokenBegin != null && tokenBegin.isAfter(lowerBound) && tokenBegin.isBefore(upperBound)) {
                            return Optional.of(tokenBegin);
                        }
                    }
                    return Optional.empty();
                });
    }

    /**
     * Prints one commented ternary branch after the surrounding classifier has decided whether the comment belongs
     * before or after the branch expression.
     *
     * <p>A leading comment renders on its own line(s) <em>before</em> the operator token, matching the source shape where
     * the comment block sits above the {@code ?} or {@code :}; the operator then leads its operand on the following line.
     * When a region claimed several stacked line comments they arrive here already joined with {@link Doc#HARD_LINE}, so
     * the whole block prints above the operator with no line dropped.
     *
     * <p>A branch can carry both a leading and a trailing comment at once when a whitespace perturbation re-buckets
     * comments onto the branch's line; both are rendered so neither is lost. At the {@code @default} shape a branch never
     * holds both, so this leaves the unperturbed layout untouched while keeping the perturbed shapes comment-complete.
     */
    private Doc conditionalCommentedBranch(
            String operatorToken,
            Expression branch,
            Doc leadingComment,
            Doc trailingComment
    ) {
        Doc trailing = trailingComment == Doc.EMPTY ? Doc.EMPTY : Doc.concat(Doc.text(" "), trailingComment);
        if (leadingComment != Doc.EMPTY) {
            return Doc.concat(
                leadingComment,
                Doc.HARD_LINE,
                Doc.text(operatorToken + " "),
                rendering.renderWithoutOwnComment(branch),
                trailing
            );
        }
        return Doc.concat(Doc.text(operatorToken + " "), rendering.renderWithoutOwnComment(branch), trailing);
    }

    private boolean conditionalElseCommentIsStatementTrailing(ConditionalExpr expression, Comment comment) {
        return expression.getParentNode()
                .stream()
                .flatMap(parent -> findAncestorExpressionStatement(parent).stream())
                .anyMatch(statement -> CommentIndex.startsAfterNodeOnSameLine(statement, comment));
    }

    private Optional<ExpressionStmt> findAncestorExpressionStatement(Node node) {
        Optional<Node> parent = node.getParentNode();
        while (parent.isPresent()) {
            Node current = parent.orElseThrow();
            if (current instanceof ExpressionStmt expressionStmt) {
                return Optional.of(expressionStmt);
            }
            parent = current.getParentNode();
        }
        return Optional.empty();
    }

    /**
     * Reports whether the conditional carries a line comment the flat single-line arm cannot represent, so the broken
     * ternary shape must be forced to keep the comment. A {@code //} comment runs to end of line, so it cannot sit on the
     * flat {@code cond ? then : else} line without swallowing the operators after it. Block comments are not gated here:
     * they can ride the flat line inline. This is the comment-safety companion to the width-driven auto path.
     */
    boolean conditionalContainsLineComment(ConditionalExpr expression) {
        return commentPlacement.hasContainedLineComments(expression);
    }
}
