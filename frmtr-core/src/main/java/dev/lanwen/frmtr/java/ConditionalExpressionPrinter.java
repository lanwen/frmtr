package dev.lanwen.frmtr.java;

import com.github.javaparser.JavaToken;
import com.github.javaparser.Position;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Renders Java conditional expressions and their assignment or initializer-specific break decisions.
 *
 * <p>This helper owns the ternary decision tree around flat versus broken {@code ?:} output, line comments attached near
 * {@code ?} and {@code :}, nested conditional branches, and binary-condition wrapping when a conditional expression is
 * used as an assignment value or variable initializer. The boundary exists because {@link ConditionalExpr} nodes appear
 * in several caller contexts, but the ternary-specific comment and width decisions are the same once those callers have
 * decided that they need conditional expression formatting.
 *
 * <p>{@link JavaPrinter} still owns general expression dispatch, assignment dispatch, raw source and pragma gates, field
 * declaration layout, and binary-expression policy. This helper receives those decisions as callbacks and only chooses
 * the shape of the conditional expression itself. Representative fixture pairs for this boundary include
 * {@code frmtr-core/src/test/resources/format/conditional-expression-space-indentation/input.java} with
 * {@code frmtr-core/src/test/resources/format/conditional-expression-space-indentation/frmtr-default.output.java},
 * {@code frmtr-core/src/test/resources/format/conditional-expression-tab-indentation/input.java} with
 * {@code frmtr-core/src/test/resources/format/conditional-expression-tab-indentation/frmtr-default.output.java},
 * {@code frmtr-core/src/test/resources/format/variable-declarations/input.java} with
 * {@code frmtr-core/src/test/resources/format/variable-declarations/frmtr-default.output.java}, and
 * {@code frmtr-core/src/test/resources/format/expression-operators-layout/input.java} with
 * {@code frmtr-core/src/test/resources/format/expression-operators-layout/frmtr-default.output.java}.
 */
final class ConditionalExpressionPrinter {

    private final CommentTracker comments;

    private final JavaCommentPlacementPolicy commentPlacement;

    private final FormatterOptions options;

    private final SourceShapePolicy sourceShapePolicy;

    private final CompactSourceText compactSource;

    private final LayoutWidth layoutWidth;

    private final LayoutDecisionLog layoutDecisions;

    private final Function<Expression, Doc> expressionRenderer;

    private final Function<Expression, Doc> expressionWithoutOwnCommentRenderer;

    private final BiFunction<Expression, Boolean, Doc> binaryExpressionLinesRenderer;

    private final BiFunction<Expression, Boolean, Doc> nestedBinaryExpressionLinesRenderer;

    private final Predicate<Expression> expressionHasParenthesizedNestedBinary;

    /**
     * Names whether conditional-expression layout is caller-forced or selected by local width and comment checks.
     *
     * <p>The enum owns only the ternary break mode. Assignment, return, field, and enclosed-expression callers still
     * decide when their surrounding context requires the forced mode.
     */
    private enum ConditionalBreakMode {
        /** Keep the conditional flat when source-equivalent text fits and no comments or nesting require rebuilding. */
        AUTO,

        /** Print the broken ternary shape because a caller has already selected a multiline conditional context. */
        FORCED;

        static ConditionalBreakMode fromForced(boolean forced) {
            return forced ? FORCED : AUTO;
        }

        boolean isForced() {
            return this == FORCED;
        }
    }

    /**
     * Names how a conditional expression's condition should render when the condition itself is a long binary tree.
     *
     * <p>The enum owns only the condition sub-layout. It leaves branch rendering, assignment detection, and the binary
     * expression continuation policy with their existing owners.
     */
    private enum ConditionalConditionLayout {
        /** Render the condition through ordinary expression dispatch. */
        EXPRESSION,

        /** Render a wide binary condition with the assignment/initializer continuation shape. */
        ASSIGNMENT_CONTINUATION_BINARY,

        /** Render a wide binary condition with nested-expression continuation indentation. */
        NESTED_BINARY,
    }

    ConditionalExpressionPrinter(
            JavaFormatContext context,
            Function<Expression, Doc> expressionRenderer,
            Function<Expression, Doc> expressionWithoutOwnCommentRenderer,
            BiFunction<Expression, Boolean, Doc> binaryExpressionLinesRenderer,
            BiFunction<Expression, Boolean, Doc> nestedBinaryExpressionLinesRenderer,
            Predicate<Expression> expressionHasParenthesizedNestedBinary
    ) {
        this.comments = context.comments;
        this.commentPlacement = context.commentPlacementPolicy;
        this.options = context.options;
        this.sourceShapePolicy = context.sourceShapePolicy;
        this.compactSource = context.compactSource;
        this.layoutWidth = context.layoutWidth;
        this.layoutDecisions = context.layoutDecisions;
        this.expressionRenderer = expressionRenderer;
        this.expressionWithoutOwnCommentRenderer = expressionWithoutOwnCommentRenderer;
        this.binaryExpressionLinesRenderer = binaryExpressionLinesRenderer;
        this.nestedBinaryExpressionLinesRenderer = nestedBinaryExpressionLinesRenderer;
        this.expressionHasParenthesizedNestedBinary = expressionHasParenthesizedNestedBinary;
    }

    /**
     * Chooses the shape for an assignment whose value is a conditional expression.
     *
     * <p>When the condition itself is structurally complex, the whole conditional moves under the assignment operator so
     * the reader sees the assignment first and then the ternary tree. If only the full expression is too wide, but the
     * target, operator, and condition still fit, the condition stays after {@code =} and only the {@code ?} and
     * {@code :} branches break below it.
     */
    Optional<Doc> assignmentWithConditionalValue(AssignExpr assignExpr, ConditionalExpr conditionalExpr) {
        if (
            sourceShapePolicy.wasMultiline(conditionalExpr)
            && sourceShapePolicy.startsOnSameLine(assignExpr, conditionalExpr)
        ) {
            return Optional.of(
                Doc.concat(
                    expressionRenderer.apply(assignExpr.getTarget()),
                    Doc.text(" " + assignExpr.getOperator().asString() + " "),
                    conditionalExpression(conditionalExpr, ConditionalBreakMode.FORCED)
                )
            );
        }
        if (
            shouldBreakBeforeConditionalInitializer(conditionalExpr)
            || shouldBreakBeforeConditionalAssignment(conditionalExpr)
        ) {
            return Optional.of(
                Doc.concat(
                    expressionRenderer.apply(assignExpr.getTarget()),
                    Doc.text(" " + assignExpr.getOperator().asString()),
                    Doc.indent(
                        Doc.concat(
                            Doc.HARD_LINE,
                            conditionalExpression(conditionalExpr, ConditionalBreakMode.FORCED)
                        )
                    )
                )
            );
        }
        String conditionLine = compactSource.compact(assignExpr.getTarget())
            + " "
            + assignExpr.getOperator().asString()
            + " "
            + compactSource.compact(conditionalExpr.getCondition())
            + ";";
        if (layoutWidth.blockStatement(conditionLine) <= options.lineWidth()) {
            return Optional.of(
                Doc.concat(
                    expressionRenderer.apply(assignExpr.getTarget()),
                    Doc.text(" " + assignExpr.getOperator().asString() + " "),
                    conditionalExpression(conditionalExpr, ConditionalBreakMode.FORCED)
                )
            );
        }
        return Optional.empty();
    }

    /**
     * Reports whether initializer callers should start the whole conditional on the next line after {@code =}.
     *
     * <p>A binary condition combined with a binary branch creates a multi-part ternary where keeping the condition after
     * the initializer name makes the tree harder to scan; callers use this fork to put the whole conditional under the
     * assignment instead.
     */
    boolean shouldBreakBeforeConditionalInitializer(ConditionalExpr initializer) {
        return initializer.getCondition() instanceof BinaryExpr
            && (initializer.getThenExpr() instanceof BinaryExpr || initializer.getElseExpr() instanceof BinaryExpr);
    }

    private boolean shouldBreakBeforeConditionalAssignment(ConditionalExpr conditionalExpr) {
        return conditionalExpr.getCondition() instanceof BinaryExpr binaryExpr
            && binaryExpr.findAll(MethodCallExpr.class).stream().findAny().isPresent();
    }

    Doc conditionalExpression(ConditionalExpr expression) {
        return conditionalExpression(expression, ConditionalBreakMode.AUTO);
    }

    /**
     * Prints a conditional expression, preserving flat output until width, nesting, comments, or caller context require
     * the broken ternary shape.
     */
    Doc conditionalExpression(ConditionalExpr expression, boolean forceBreak) {
        return conditionalExpression(expression, ConditionalBreakMode.fromForced(forceBreak));
    }

    private Doc conditionalExpression(ConditionalExpr expression, ConditionalBreakMode breakMode) {
        Optional<Doc> commented = commentedConditionalExpression(expression);
        if (commented.isPresent()) {
            return commented.orElseThrow();
        }
        // A caller-forced context prints the broken ternary shape unconditionally: that is a caller decision, not a width
        // decision, so it never consults the renderer's column. This is the imperative path: the broken shape is built
        // directly as HardLines, so the renderer only ever sees a forced break and cannot narrate the width arithmetic.
        // When the caller forced this conditional apart *because* its flat form overflowed (an assignment/initializer
        // whose one-line ternary was too wide), record that width decision so --explain can attribute the wrap to width.
        // Recording runs after the broken shape is chosen and never influences the produced Doc, so output stays
        // byte-identical. A conditional the author happened to write across multiple source lines is NOT forced here —
        // it reflows through the width-driven auto path below like any other, per reprint-by-default canonicalization.
        //
        // A conditional that carries a line comment the flat arm cannot represent is forced broken as well: this is a
        // comment-safety gate (COMMENT_PRESENCE_GATE), not a source-shape read. {@link #commentedConditionalExpression}
        // above already handles comments that sit around this ternary's own {@code ?}/{@code :} operators; the remaining
        // line comments live inside a branch sub-expression (e.g. a binary branch's between-operand {@code //} comment),
        // which the flat {@code compact} arm would swallow to end-of-line. The broken shape renders each branch through
        // the ordinary expression renderer, which places those inner comments correctly.
        if (breakMode.isForced() || conditionalContainsLineComment(expression)) {
            recordTernaryWidthBreak(expression);
            return brokenConditionalExpression(expression);
        }
        // Auto path: defer the flat-versus-broken width decision to the renderer, which measures the flat arm at the
        // true running column via Doc.conditionalGroup. The earlier gate probed a reconstructed indented width, which
        // disagreed with the column write reaches (the #137/#155 width-at-wrong-column family); a disagreement could
        // print a genuinely over-width ternary flat and let a later pass break it. Measuring at the real column removes
        // that reconstruction, and DocExplainRenderer already reports the group decision at available = lineWidth -
        // column, so no separate width-break recorder is needed.
        return Doc.conditionalGroup(
            List.of(flatConditionalExpression(expression), brokenConditionalExpression(expression))
        );
    }

    /**
     * Builds the single-line ternary layout the renderer renders when the flat form fits the columns left.
     *
     * <p>A ternary whose condition holds a parenthesized nested binary is rebuilt from its parts so the condition passes
     * through the binary continuation policy even in flat form; every other ternary is the compact source text.
     */
    private Doc flatConditionalExpression(ConditionalExpr expression) {
        if (expressionHasParenthesizedNestedBinary.test(expression)) {
            return Doc.concat(
                conditionalCondition(expression),
                Doc.text(" ? "),
                conditionalBranch(expression.getThenExpr()),
                Doc.text(" : "),
                conditionalBranch(expression.getElseExpr())
            );
        }
        return Doc.text(compactSource.compact(expression));
    }

    /**
     * Records the ternary's flat-width decision when the imperative (caller-forced or source-multiline) path breaks a
     * conditional whose single-line form overflowed the line budget, so explain can attribute the wrap to width rather
     * than to an opaque forced break.
     *
     * <p>This fires only for genuine width breaks: a conditional forced apart for nesting or a source-multiline shape
     * while its flat form would still fit is not a width decision and is left unrecorded, so it stays reported as a
     * rule-driven break. The auto path does not call this — it defers the flat-versus-broken choice to
     * {@link Doc#conditionalGroup}, whose {@code ConditionalGroupDecision} self-explains the same width arithmetic; a
     * recorder there would double-report the {@code java.expression:ConditionalExpr} label. Recording runs after the
     * broken shape is chosen and appends only to the observational {@link LayoutDecisionLog}, so it never changes which
     * {@link Doc} is built or the rendered output.
     */
    private void recordTernaryWidthBreak(ConditionalExpr expression) {
        String flat = compactSource.compact(expression);
        int flatWidth = layoutWidth.currentIndented(flat);
        if (flatWidth <= options.lineWidth()) {
            return;
        }
        layoutDecisions.recordWidthBreak(
            "ternary",
            "java.expression:" + expression.getClass().getSimpleName(),
            ternaryPreview(flat),
            flatWidth,
            options.lineWidth(),
            0
        );
    }

    /**
     * Builds a short headline snippet of the ternary up to and including the {@code ?}, so the reader recognizes the
     * conditional without seeing both branches.
     */
    private String ternaryPreview(String flat) {
        int question = flat.indexOf('?');
        return question < 0 ? flat : flat.substring(0, question + 1) + " …";
    }

    private Doc brokenConditionalExpression(ConditionalExpr expression) {
        return Doc.concat(
            conditionalCondition(expression),
            Doc.indent(
                Doc.concat(
                    Doc.HARD_LINE,
                    Doc.text("? "),
                    conditionalBranch(expression.getThenExpr()),
                    branchTailTrailingComment(expression.getThenExpr()),
                    Doc.HARD_LINE,
                    Doc.text(": "),
                    conditionalBranch(expression.getElseExpr())
                )
            )
        );
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
     * from the per-run comment map. Keeping the branch on its existing {@link #conditionalBranch} renderer preserves every
     * inner comment; only the genuinely-trailing tail comment is added back here.
     *
     * <p>The comment is claimed once by identity through {@link CommentTracker#comment(Comment)}: the binary renderer
     * never offers it (it has no operand after the last to pin it between), so this is its only claimant. A branch with no
     * such trailing comment — including the {@code @default} single-line then-branch whose comment is the branch's own
     * trivia, not contained trivia after the branch end — yields {@link Doc#EMPTY} and leaves the layout unchanged.
     */
    private Doc branchTailTrailingComment(Expression branch) {
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
    private Optional<Doc> commentedConditionalExpression(ConditionalExpr expression) {
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
        return Doc.concat(expressionWithoutOwnCommentRenderer.apply(condition), trailing);
    }

    /**
     * Reports whether a condition / {@code ?}-region comment trails the condition rather than leading the {@code ?}
     * branch.
     *
     * <p>This is the flat {@code cond ? // x} shape: the comment begins after the {@code ?} token yet still on the
     * condition's end line, so rendering it as a condition-trailing comment keeps it before the line break. A comment on
     * its own line — before or after {@code ?} — or one inline before {@code ?} leads the {@code ?} branch instead. The
     * predicate is the source-position equivalent of the previous "comment is after the {@code ?} operator and on the
     * condition's end line" rule, so it preserves the {@code @default} classification while no longer depending on
     * reconstructed column arithmetic.
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
     * before it, so a comment with no position falls into the same after-operator branch the previous column logic chose
     * by default rather than being silently reclassified.
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
                expressionWithoutOwnCommentRenderer.apply(branch),
                trailing
            );
        }
        return Doc.concat(Doc.text(operatorToken + " "), expressionWithoutOwnCommentRenderer.apply(branch), trailing);
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
     * Prints the ternary condition and chooses the binary wrapping shape for long binary conditions.
     *
     * <p>When a conditional is the value of an assignment or variable initializer, its condition is already under an
     * assignment continuation, so regular binary lines keep the indentation stable. In nested expression contexts,
     * nested binary lines add the extra continuation shape that makes the inner expression read as subordinate to the
     * outer one.
     */
    private Doc conditionalCondition(ConditionalExpr expression) {
        Expression condition = expression.getCondition();
        return switch (conditionalConditionLayout(expression, condition)) {
            case EXPRESSION -> expressionRenderer.apply(condition);
            case ASSIGNMENT_CONTINUATION_BINARY -> enclosedBinaryCondition(condition, binaryExpressionLinesRenderer);
            case NESTED_BINARY -> enclosedBinaryCondition(condition, nestedBinaryExpressionLinesRenderer);
        };
    }

    private ConditionalConditionLayout conditionalConditionLayout(
            ConditionalExpr expression,
            Expression condition
    ) {
        if (
            binaryCondition(condition).isEmpty()
            || sourceShapePolicy.fitsOnOneLine(condition, layoutWidth::continuationStatement)
        ) {
            return ConditionalConditionLayout.EXPRESSION;
        }
        if (conditionalIsAssignmentValue(expression) || conditionalIsVariableInitializer(expression)) {
            return ConditionalConditionLayout.ASSIGNMENT_CONTINUATION_BINARY;
        }
        return ConditionalConditionLayout.NESTED_BINARY;
    }

    private Doc enclosedBinaryCondition(
            Expression condition,
            BiFunction<Expression, Boolean, Doc> binaryRenderer
    ) {
        BinaryExpr binary = binaryCondition(condition).orElseThrow();
        Doc lines = binaryRenderer.apply(binary, true);
        for (int i = 0; i < enclosedDepth(condition); i++) {
            lines = Doc.concat(
                Doc.text("("),
                Doc.indent(Doc.concat(Doc.HARD_LINE, lines)),
                Doc.HARD_LINE,
                Doc.text(")")
            );
        }
        return lines;
    }

    private Optional<BinaryExpr> binaryCondition(Expression condition) {
        if (condition instanceof BinaryExpr binaryExpr) {
            return Optional.of(binaryExpr);
        }
        if (condition instanceof EnclosedExpr enclosedExpr) {
            return binaryCondition(enclosedExpr.getInner());
        }
        return Optional.empty();
    }

    private int enclosedDepth(Expression condition) {
        int depth = 0;
        Expression current = condition;
        while (current instanceof EnclosedExpr enclosedExpr) {
            depth++;
            current = enclosedExpr.getInner();
        }
        return depth;
    }

    private boolean conditionalIsAssignmentValue(ConditionalExpr expression) {
        return expression.getParentNode()
                .filter(AssignExpr.class::isInstance)
                .map(AssignExpr.class::cast)
                .filter(assignExpr -> assignExpr.getValue() == expression)
                .isPresent();
    }

    private boolean conditionalIsVariableInitializer(ConditionalExpr expression) {
        return expression.getParentNode()
                .filter(VariableDeclarator.class::isInstance)
                .map(VariableDeclarator.class::cast)
                .flatMap(VariableDeclarator::getInitializer)
                .filter(initializer -> initializer == expression)
                .isPresent();
    }

    private Doc conditionalBranch(Expression branch) {
        if (branch instanceof ConditionalExpr conditionalExpr) {
            return conditionalExpression(conditionalExpr, ConditionalBreakMode.FORCED);
        }
        return expressionRenderer.apply(branch);
    }

    /**
     * Reports whether the conditional carries a line comment the flat single-line arm cannot represent, so the broken
     * ternary shape must be forced to keep the comment. A {@code //} comment runs to end of line, so it cannot sit on the
     * flat {@code cond ? then : else} line without swallowing the operators after it. Block comments are not gated here:
     * they can ride the flat line inline. This is the comment-safety companion to the width-driven auto path — the
     * canonical replacement for the retired {@code wasMultiline} force, which happened to catch these because a
     * comment-bearing ternary is usually written across source lines.
     */
    private boolean conditionalContainsLineComment(ConditionalExpr expression) {
        return commentPlacement.hasContainedLineComments(expression);
    }
}
