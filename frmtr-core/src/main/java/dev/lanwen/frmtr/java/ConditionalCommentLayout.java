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
 * <p>This helper answers "does this {@code ?:} carry a line comment, and where does it render?" through three entry points:
 * the force-broken gate {@link #conditionalContainsLineComment}, the full rebuild
 * {@link #commentedConditionalExpression} (which classifies each candidate into a condition-trailing, {@code ?}-leading,
 * then-trailing, {@code :}-leading, or else-trailing slot), and the branch-tail recovery
 * {@link #branchTailTrailingComment}. The boundary exists so the printer's flat-versus-broken decision tree can consult one
 * comment authority instead of carrying every comment scan and operator-position classification inline.
 *
 * <p>The helper claims no ownership of the ternary's shape, width, or nesting; that stays with the caller, which threads
 * these entry points into its dispatch and renders branch expressions on its own
 * {@link ConditionalExpressionPrinter#conditionalBranch} path so their inner comments survive. Because the {@code ?} and
 * {@code :} are not AST nodes, classification keys purely off source position, and every candidate is read from the same
 * own/orphan/contained sets the renderers consume and claimed once by identity, so the withhold verdict and the render stay
 * in lockstep and no comment is double-claimed.
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
     * <p>This covers the then-branch &rarr; {@code :} gap when the then-branch is a multi-line binary chain: in
     * {@code aaa + bbb // mid + ccc // tail} JavaParser parks the trailing {@code // tail} after the chain's last operand,
     * where the between-operand binary renderer never reaches it and the ternary slot owns neither — the ternary analog of
     * {@link JavaCommentPlacementPolicy#trailingInitializerCommentsBeforeSemicolon(Node, Node)}.
     *
     * <p>Rendering happens here, on the branch's last printed line, rather than re-routing the conditional through
     * {@link #commentedConditionalExpression}, whose own-comment-stripping clone renderer cannot preserve a multi-line
     * branch's inner comments. The comment is claimed once by identity ({@link CommentTracker#comment(Comment)}); a branch
     * with no such trailing comment (including the {@code @default} single-line then-branch) yields {@link Doc#EMPTY}.
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
     * <p>JavaParser attaches these to nearby expressions, not to {@code ?}/{@code :}, and the bucket shifts with layout (a
     * {@code ? // x} comment is the then-expression's own leading comment, or, once on its own line, a conditional
     * orphan). So the formatter gathers every candidate from both the child expressions' own comments and the
     * conditional's orphans, then classifies each by source position relative to the operator tokens: condition-trailing,
     * {@code ?}-leading, then-trailing, {@code :}-leading, or else-trailing. A comment trailing the containing statement is
     * left to statement-level handling. Classification is position-based (the {@code ?}/{@code :} are not AST nodes), so a
     * whitespace perturbation cannot change which branch a comment belongs to.
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
                Doc.label(
                    ConditionalExpressionPrinter.TERNARY_CONDITION_LABEL,
                    conditionalConditionWithTrailingComment(
                        expression.getCondition(),
                        regionComment(byRegion, Region.CONDITION_TRAILING)
                    )
                ),
                Doc.label(
                    ConditionalExpressionPrinter.TERNARY_BRANCHES_LABEL,
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
     * statement-level handling (never rendered here).
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
     * <p>Comments from a nested conditional branch are excluded (the inner {@link ConditionalExpr} owns them). Dedup is by
     * object identity, not {@code equals}: a whitespace collapse can attach the same comment instance to two child
     * expressions at once, and collecting it twice would render (and claim) it twice, tripping the duplicate-claim
     * guardrail.
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
     * <p>True only for the flat {@code cond ? // x} shape — the comment begins after the {@code ?} token yet on the
     * condition's end line — so rendering it condition-trailing keeps it before the line break. Any comment on its own
     * line, or inline before {@code ?}, leads the {@code ?} branch instead.
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
     * <p>Scanning the token stream for the matching operator whose position lies strictly between {@code before}'s end and
     * {@code after}'s begin pins the outer operator — a nested conditional's operators live inside a branch sub-range, so
     * they never fall in this gap.
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
     * <p>A leading comment renders on its own line(s) before the operator token (matching the source shape where the
     * block sits above {@code ?}/{@code :}); stacked lines arrive already {@link Doc#HARD_LINE}-joined. A branch can carry
     * both a leading and a trailing comment when a perturbation re-buckets comments onto its line, so both are rendered;
     * at {@code @default} a branch never holds both, leaving the unperturbed layout untouched.
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
