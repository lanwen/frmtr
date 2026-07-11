package dev.lanwen.frmtr.java;

import com.github.javaparser.GeneratedJavaParserConstants;
import com.github.javaparser.JavaToken;
import com.github.javaparser.Position;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Renders the if/else-chain statement family: the condition header, the then branch, the else/else-if chain, the
 * empty-body shapes, and the source-shape-independent comment recovery those slots need.
 *
 * <p>This helper owns everything reachable only from {@link StatementPrinter}'s {@code IfStmt} switch arm: the else-chain
 * separator layout, the braceless-else body, the empty-then and empty-else shapes, the {@code else}-keyword position scan,
 * and the comment handoffs between the condition, then branch, and else branch (the else-leading {@code //} block, the
 * block comment between the then {@code }} and {@code else}, the then/else trailing line comments, and the block comment
 * carried inside an empty-body header). The boundary exists so {@link StatementPrinter}'s statement-kind dispatch can keep
 * the other statement grammars local instead of carrying this large, comment-heavy if cluster inline, mirroring the
 * injected per-kind renderers ({@code blockRenderer}, {@code switchStatementRenderer}) and the {@link TryStatementLayout}
 * StatementPrinter already delegates to.
 *
 * <p>Condition, expression, and block formatting stay with their existing owners and are reached through the callbacks
 * injected here ({@code ifConditionRenderer}, {@code statementRenderer}, {@code sameLineBlockCommentBeforeNode}, and
 * {@code compact}). Nested statements route back through the shared {@code nestedStatement} handle so a nested body gets
 * the same raw/pragma/comment gate and switch routing as any other statement, and the shared empty-body and comment-text
 * flattening ({@code emptyBodyOwnBlockComment}, {@code trailingEmptyBodyBlockComment}, {@code commentText}, and
 * {@code trailingLineComment}) stays a StatementPrinter concern injected as a handle because the loop and simple-statement
 * paths use it too.
 */
final class IfStatementLayout {

    private final CommentTracker comments;

    private final JavaCommentPlacementPolicy commentPlacement;

    private final ControlConditionPrinter controlConditions;

    private final JavaFormatRule<Statement> statementRenderer;

    private final Function<Expression, Doc> ifConditionRenderer;

    private final Function<Node, Doc> sameLineBlockCommentBeforeNode;

    private final Function<Node, String> compact;

    private final Function<Statement, Doc> nestedStatement;

    private final Function<Doc, String> commentText;

    private final Function<Statement, Doc> emptyBodyOwnBlockComment;

    private final Function<Node, String> trailingEmptyBodyBlockComment;

    private final Function<Node, Doc> trailingLineComment;

    IfStatementLayout(
            CommentTracker comments,
            JavaCommentPlacementPolicy commentPlacement,
            ControlConditionPrinter controlConditions,
            JavaFormatRule<Statement> statementRenderer,
            Function<Expression, Doc> ifConditionRenderer,
            Function<Node, Doc> sameLineBlockCommentBeforeNode,
            Function<Node, String> compact,
            Function<Statement, Doc> nestedStatement,
            Function<Doc, String> commentText,
            Function<Statement, Doc> emptyBodyOwnBlockComment,
            Function<Node, String> trailingEmptyBodyBlockComment,
            Function<Node, Doc> trailingLineComment
    ) {
        this.comments = comments;
        this.commentPlacement = commentPlacement;
        this.controlConditions = controlConditions;
        this.statementRenderer = statementRenderer;
        this.ifConditionRenderer = ifConditionRenderer;
        this.sameLineBlockCommentBeforeNode = sameLineBlockCommentBeforeNode;
        this.compact = compact;
        this.nestedStatement = nestedStatement;
        this.commentText = commentText;
        this.emptyBodyOwnBlockComment = emptyBodyOwnBlockComment;
        this.trailingEmptyBodyBlockComment = trailingEmptyBodyBlockComment;
        this.trailingLineComment = trailingLineComment;
    }

    /**
     * Prints if/else chains while preserving the comment slots between the condition, then branch, and else branch.
     *
     * <p>The forks here are driven by source layout rather than Java syntax alone: empty bodies can keep comments inside
     * the header line, comments between {@code then} and {@code else} stay between those tokens, and nested {@code else
     * if} routes back through the outer statement callback so it gets the same raw/pragma/comment gate and switch
     * routing as any other nested statement.
     */
    Doc ifStatement(IfStmt statement) {
        if (statement.getThenStmt().isEmptyStmt()) {
            return ifWithEmptyThenStatement(statement);
        }
        List<Doc> docs = new ArrayList<>();
        // A line comment on the then branch's `}` line that is followed by `else`/`else if` belongs to the else-leading
        // gap block, not to a separate then-trailing slot: a collapse can re-attach such a gap line as the then block's
        // own trailing comment, and claiming it here would let elseChainSeparator's then-trailing branch render it alone
        // and drop the rest of the block. When the then is a block and an else follows, let the gap block (computed in
        // the else branch below) own every gap line; only claim the standalone then-trailing slot otherwise.
        boolean elseLeadingGapOwnsThenTrailing = statement.getThenStmt().isBlockStmt()
            && statement.getElseStmt().filter(elseStatement -> !elseStatement.isEmptyStmt()).isPresent();
        Doc thenTrailingLineComment = elseLeadingGapOwnsThenTrailing
            ? Doc.EMPTY
            : trailingLineComment.apply(statement.getThenStmt());
        // Claim the else-leading gap block before rendering the then branch so the dry-run records the whole block as the
        // gap's own (in Doc-construction order, which is what the record-only pre-pass follows), even though it renders
        // after the then branch's `}`. Otherwise a gap line that a collapse re-attached as the then block's own trailing
        // comment would be claimed by the block printer first and render on the `}` line, splitting the block.
        // A braceless (non-`else if`) else body owns its own leading `//` block through the braceless-body handler
        // (the same family the then/while/for bodies use), so its leading comments are placed indented under `else`,
        // not hoisted above the `else` keyword. The separator gap slot below is then bounded to the genuine
        // then-`}`-to-`else` gap (comments that start before the `else` keyword), and the body block is claimed here so
        // the two slots never double-own a line. The `else` keyword position is the boundary between the two slots: a
        // comment before it is a separator comment, one after it leads the body.
        Optional<Statement> bracelessElse = statement.getElseStmt()
                .filter(elseStatement -> !elseStatement.isEmptyStmt())
                .filter(elseStatement -> !elseStatement.isIfStmt())
                .filter(elseStatement -> !elseStatement.isBlockStmt());
        Optional<Position> elseKeyword = bracelessElse.isPresent() ? elseKeywordPosition(statement) : Optional.empty();
        Doc elseLeadingLineComment = statement.getElseStmt()
                .filter(elseStatement -> !elseStatement.isEmptyStmt())
                .map(elseStatement -> elseLeadingLineComment(statement, elseStatement, elseKeyword))
                .orElse(Doc.EMPTY);
        // Render the braceless else body here, in Doc-construction order, before the then branch and the separator, so
        // the dry-run records its leading `//` block under the else body's leading slot first: a collapse that
        // re-buckets a body-leading line onto the if orphan pool then cannot let the separator slot reclaim it and split
        // the block off the body it leads. This is the sole renderer for a braceless else body, so a separator comment
        // that a collapse re-attaches as the body's own leading trivia does not trip the generic leading-comment body
        // break in nestedStatement (it stays in the separator slot, the body collapses onto the `else` line).
        Optional<Doc> bracelessElseBody = bracelessElse
                .map(elseStatement -> bracelessElseBody(statement, elseStatement, elseKeyword));
        Doc conditionTrailingLineComment = controlConditions.closeParenTrailingLineComment(statement.getCondition());
        Doc betweenThenAndElseBlockComment = blockCommentBetweenThenAndElse(statement);
        docs.add(ifCondition(statement));
        if (conditionTrailingLineComment != Doc.EMPTY) {
            docs.add(Doc.text(" "));
            docs.add(conditionTrailingLineComment);
            docs.add(ifThenStatementAfterConditionTrailingComment(statement));
        } else {
            docs.add(Doc.text(" "));
            docs.add(ifThenStatement(statement));
        }
        statement
                .getElseStmt()
                .ifPresent(elseStatement -> {
                    if (elseStatement.isEmptyStmt()) {
                        docs.add(emptyElseStatement(statement, elseStatement));
                        return;
                    }
                    // A block comment between the then-block close and else is recovered by blockCommentBetweenThenAndElse
                    // above and takes priority in elseChainSeparator; for `} /* c */ else {` that same comment is also a
                    // same-line block comment before the else statement. Only offer the else-leading block comment when
                    // the between-then-and-else slot did not already claim it, so the comment is claimed once. Output is
                    // unchanged because elseChainSeparator returns on the between slot before it ever reads the else
                    // leading block comment.
                    Doc elseLeadingBlockComment = betweenThenAndElseBlockComment == Doc.EMPTY
                        ? sameLineBlockCommentBeforeNode.apply(elseStatement)
                        : Doc.EMPTY;
                    Doc elseTrailingLineComment = elseTrailingLineComment(statement, elseStatement);
                    docs.add(
                        elseChainSeparator(
                            statement,
                            elseStatement,
                            conditionTrailingLineComment,
                            thenTrailingLineComment,
                            betweenThenAndElseBlockComment,
                            elseLeadingLineComment,
                            elseLeadingBlockComment
                        )
                    );
                    if (bracelessElseBody.isPresent()) {
                        // A braceless (non-`else if`) else body is rendered entirely by bracelessElseBody (computed
                        // above): it breaks and indents the body when an after-`else` leading `//` block is present and
                        // otherwise collapses the body onto the `else` line, claiming the leading block exactly once.
                        docs.add(bracelessElseBody.orElseThrow());
                    } else {
                        docs.add(
                            elseStatement.isIfStmt()
                                ? statementRenderer.format(elseStatement, LayoutContext.root())
                                : nestedStatement.apply(elseStatement)
                        );
                    }
                    if (elseTrailingLineComment != Doc.EMPTY) {
                        docs.add(Doc.text(" "));
                        docs.add(elseTrailingLineComment);
                    }
                });
        if (statement.getElseStmt().isEmpty() && thenTrailingLineComment != Doc.EMPTY) {
            docs.add(Doc.text(" "));
            docs.add(thenTrailingLineComment);
        }
        return Doc.concat(docs);
    }

    /**
     * Recovers the {@code //} comment block that leads the {@code else} keyword ({@code } // note\nelse}), independent of
     * source shape, as a single together-rendered cluster.
     *
     * <p>A multi-line block written between the then branch's {@code }} and {@code else}/{@code else if} is not held by a
     * single node: JavaParser keeps the trailing lines as the enclosing {@code if}'s orphan trivia while the line
     * directly above the {@code else}/{@code else if} node becomes that node's own leading trivia. Reading only one of
     * those two slots (own first, orphan fallback) split the block — one line rendered above {@code else}, the rest
     * folded into the nested {@code else if}'s leading cluster, which mangled {@code else if} into {@code else //\n if}
     * and rotated the lines every pass because re-parsing re-split the block onto different nodes. We instead claim the
     * whole gap block in one slot (see
     * {@link JavaCommentPlacementPolicy#gapLeadingLineCommentBlock(Node, Node, java.util.Collection)}) so it renders once,
     * together, above {@code else}, and the nested {@code else if} can no longer reclaim a leading line. At
     * {@code @default} the block is a single contiguous run, so this renders the same lines in the same order.
     *
     * <p>{@code elseKeywordUpperBound}, when present, restricts this slot to the genuine separator gap — comments that
     * start before the {@code else} keyword ({@code } // note\nelse}). It is supplied only for a braceless (non-{@code
     * else if}) else body, whose own leading {@code //} block lives <em>after</em> the {@code else} keyword and is owned
     * by {@link #bracelessElseBody(IfStmt, Statement, java.util.Optional)} instead. For an {@code else if} or a block
     * else the bound is absent and this keeps recovering the whole gap block as before, so the #115 rotation fix and the
     * block-else separator placement are unchanged.
     */
    private Doc elseLeadingLineComment(IfStmt statement, Statement elseStatement, Optional<Position> elseKeywordUpperBound) {
        return comments.gapLeadingLineCommentBlock(
            statement,
            statement.getThenStmt(),
            elseStatement,
            List.of(statement),
            elseKeywordUpperBound
        );
    }

    /**
     * Renders a braceless (non-{@code else if}) else body that carries a leading {@code //} block, claiming each line of
     * that block exactly once under the else body's leading slot and placing it indented under {@code else}, above the
     * body statement.
     *
     * <p>This is the else-body counterpart of {@link StatementPrinter#bracelessLoopBody(Node, Node, Statement)}: a braceless else body
     * normally collapses onto the {@code else} line ({@code else return 2;}), but a leading line comment cannot share
     * that line without commenting out the body, so the body breaks to an indented next line with the comment kept above
     * it. The leading block lives in the gap between the {@code else} keyword and the body, but JavaParser splits it: the
     * line directly above the body is the body's own leading trivia (rendered by {@link #statementRenderer}); earlier
     * lines re-bucket onto the enclosing {@code if}'s orphan pool or the then branch under whitespace perturbation. We
     * recover the re-bucketed lines from those buckets through {@link JavaCommentPlacementPolicy#gapLineCommentsBefore},
     * bounded to comments that start <em>after</em> the {@code else} keyword so a genuine then-{@code }}-to-{@code else}
     * separator comment (owned by {@link #elseLeadingLineComment}) is never pulled into the body. Each recovered line is
     * claimed once under the body's leading slot — the same slot the body renderer would claim its own line in — so the
     * whole block is neither dropped under perturbation nor double-printed at {@code @default}.
     *
     * <p>This is the sole renderer for a braceless else body. When no after-{@code else} leading line comment is present
     * it returns the body collapsed onto the {@code else} line, exactly as {@link StatementPrinter#nestedStatement(Statement)} would, so
     * a separator comment that a collapse re-attaches as the body's own leading trivia (it belongs to
     * {@link #elseLeadingLineComment}, not the body) does not trip the generic leading-comment body break in
     * {@link StatementPrinter#nestedStatement(Statement)} on a later pass.
     */
    private Doc bracelessElseBody(IfStmt statement, Statement elseStatement, Optional<Position> elseKeyword) {
        List<JavaCommentTrivia> aboveBodyComments = commentPlacement
                .gapLineCommentsBefore(statement.getThenStmt(), elseStatement, List.of(statement, statement.getThenStmt()))
                .stream()
                .filter(trivia -> elseKeyword.map(position -> CommentIndex.startsAfter(trivia.comment(), position))
                        .orElse(true))
                .toList();
        // The body's own leading comment counts only when it sits after the `else` keyword. A collapse can re-attach a
        // genuine separator comment (one written before `else`) onto the else statement as its own leading trivia; that
        // comment belongs to the separator slot, not the body, so the else-keyword bound keeps it from triggering a
        // body break here.
        boolean bodyOwnsLeadingLineComment = commentPlacement.leadingComment(elseStatement)
                .filter(JavaCommentTrivia::isLine)
                .filter(trivia -> !trivia.startsAfterEndOf(elseStatement))
                .filter(trivia -> elseKeyword.map(position -> CommentIndex.startsAfter(trivia.comment(), position))
                        .orElse(true))
                .isPresent();
        if (aboveBodyComments.isEmpty() && !bodyOwnsLeadingLineComment) {
            return statementRenderer.format(elseStatement, LayoutContext.root());
        }
        List<Doc> indented = new ArrayList<>();
        indented.add(Doc.HARD_LINE);
        for (JavaCommentTrivia aboveComment : aboveBodyComments) {
            Doc rendered = comments.comment(aboveComment, elseStatement, OwnerSlot.LEADING);
            if (rendered == Doc.EMPTY) {
                continue;
            }
            indented.add(rendered);
            indented.add(Doc.HARD_LINE);
        }
        indented.add(statementRenderer.format(elseStatement, LayoutContext.root()));
        return Doc.indent(Doc.concat(indented));
    }

    /**
     * Locates the source position of the {@code else} keyword that follows the then branch, scanning the {@code if}
     * statement's token range for the {@code ELSE} token whose position is after the then branch ends.
     *
     * <p>The {@code else} keyword has no AST node of its own, so callers that must classify a comment as a separator
     * (before {@code else}) or an else-body leading comment (after {@code else}) read its token position directly, the
     * same way {@link ConditionalExpressionPrinter} reads the {@code ?}/{@code :} token positions. Returns
     * {@link Optional#empty()} when the token range is unavailable, in which case callers fall back to treating the whole
     * gap as the separator (the pre-existing behavior).
     */
    private Optional<Position> elseKeywordPosition(IfStmt statement) {
        Optional<Position> thenEnd = statement.getThenStmt().getRange().map(range -> range.end);
        return statement.getTokenRange().flatMap(tokenRange -> {
            for (JavaToken token : tokenRange) {
                if (token.getKind() != GeneratedJavaParserConstants.ELSE) {
                    continue;
                }
                Optional<Position> tokenStart = token.getRange().map(range -> range.begin);
                if (tokenStart.isPresent() && thenEnd.map(end -> tokenStart.orElseThrow().isAfter(end)).orElse(true)) {
                    return tokenStart;
                }
            }
            return Optional.empty();
        });
    }

    /**
     * Recovers the line comment that trails the {@code else} body ({@code } else {} // note}), independent of source
     * shape.
     *
     * <p>This mirrors the try-clause {@code clauseTrailingComment} recovery in {@link TryStatementLayout}. At
     * {@code @default} the comment sits on the else body's end line, so {@link CommentTracker#trailingLineComment(Node)}
     * (via {@link StatementPrinter#trailingLineComment(Node)}) owns it. When a collapse perturbation places it on the shared else
     * {@code }} position so {@code startsAfterEndOf(elseStatement)} fails, JavaParser re-buckets it onto the
     * {@link IfStmt} orphan pool; we then recover the {@code if} orphan line comment that source-orders after the else
     * body ends (open to the statement's end, since the else is the last clause).
     */
    private Doc elseTrailingLineComment(IfStmt statement, Statement elseStatement) {
        Doc own = trailingLineComment.apply(elseStatement);
        if (own != Doc.EMPTY) {
            return own;
        }
        return comments.trailingLineCommentsAfter(statement, elseStatement, Optional.empty());
    }

    private Doc ifWithEmptyThenStatement(IfStmt statement) {
        List<Doc> docs = new ArrayList<>();
        docs.add(Doc.text("if (" + ifEmptyThenCondition(statement) + ");"));
        statement
                .getElseStmt()
                .ifPresent(elseStatement -> {
                    docs.add(Doc.HARD_LINE);
                    docs.add(
                        elseStatement.isEmptyStmt()
                            ? Doc.text("else;" + trailingEmptyBodyBlockComment.apply(elseStatement))
                            : Doc.concat(Doc.text("else "), nestedStatement.apply(elseStatement))
                    );
                });
        return Doc.concat(docs);
    }

    private String ifEmptyThenCondition(IfStmt statement) {
        List<String> parts = new ArrayList<>();
        parts.add(compact.apply(statement.getCondition()));
        String thenComment = commentText.apply(emptyBodyOwnBlockComment.apply(statement.getThenStmt()));
        if (!thenComment.isEmpty()) {
            parts.add(thenComment);
        }
        String betweenThenAndElse = commentText.apply(blockCommentBetweenThenAndElse(statement));
        if (!betweenThenAndElse.isEmpty()) {
            parts.add(betweenThenAndElse);
        }
        statement.getElseStmt()
                .filter(Statement::isEmptyStmt)
                .map(emptyBodyOwnBlockComment::apply)
                .map(commentText::apply)
                .filter(comment -> !comment.isEmpty())
                .ifPresent(parts::add);
        return String.join(" ", parts);
    }

    /**
     * Recovers the block comment that sits between the then branch's {@code }} and the {@code else} keyword
     * ({@code } /* note *}{@code / else}), independent of source shape.
     *
     * <p>The own path keeps the original column arithmetic: at {@code @default} JavaParser attaches the comment to the
     * else node as its own trivia, and the comment shares the then-end line immediately after {@code }} (within two
     * columns) and lies before the {@code else} node on that line. That column window is what distinguishes a
     * {@code } /* note *}{@code / else} comment from an {@code else /* note *}{@code / {} comment — both are the else
     * node's own block comment on the same line, but only the former sits immediately after {@code }}. Keeping that
     * window means {@code @default} renders byte-identically and the {@code else}-leading comment still falls through to
     * {@link #ifStatement(IfStmt)}'s {@code elseLeadingBlockComment} slot.
     *
     * <p>A whitespace perturbation that pushes the {@code } /* note *}{@code / else} comment onto its own line below the
     * {@code }} re-buckets it as a {@link IfStmt} orphan even though the AST is otherwise identical, so the own path's
     * line/column predicates lose it. The orphan fallback then recovers the {@code if} orphan block comment that
     * source-orders strictly between the then branch end and the else node begin. The fallback only sees orphans, so an
     * {@code else}-leading comment (which stays the else node's own trivia under perturbation) is never claimed here and
     * still renders through the {@code elseLeadingBlockComment} slot.
     */
    private Doc blockCommentBetweenThenAndElse(IfStmt statement) {
        if (statement.getElseStmt().isEmpty()) {
            return Doc.EMPTY;
        }
        Statement thenStatement = statement.getThenStmt();
        Statement elseStatement = statement.getElseStmt().orElseThrow();
        Doc own = statement.getAllContainedComments()
                .stream()
                .filter(BlockComment.class::isInstance)
                .filter(comment -> comment.getRange()
                            .flatMap(commentRange -> thenStatement.getRange().flatMap(
                                    thenRange -> elseStatement.getRange().map(
                                        elseRange -> commentRange.begin.line == thenRange.end.line
                                                && commentRange.begin.column > thenRange.end.column
                                                && commentRange.begin.column <= thenRange.end.column + 2
                                                && commentRange.begin.line == elseRange.begin.line
                                                && commentRange.begin.column < elseRange.begin.column
                                    )
                            ))
                            .orElse(false)
                )
                .findFirst()
                .map(comments::comment)
                .orElse(Doc.EMPTY);
        if (own != Doc.EMPTY) {
            return own;
        }
        return commentPlacement.orphanComments(statement)
                .stream()
                .filter(JavaCommentTrivia::isBlock)
                .filter(comment -> comment.liesBetween(thenStatement, elseStatement))
                .sorted(Comparator.comparing(JavaCommentTrivia::comment, CommentIndex.sourceOrderComparator()))
                .findFirst()
                .map(comments::comment)
                .filter(doc -> doc != Doc.EMPTY)
                .orElse(Doc.EMPTY);
    }

    private Doc emptyElseStatement(IfStmt statement, Statement elseStatement) {
        String elseComment = commentText.apply(emptyBodyOwnBlockComment.apply(elseStatement));
        String prefix = elseComment.isEmpty() ? " else;" : " " + elseComment + " else;";
        return Doc.text(prefix + trailingEmptyBodyBlockComment.apply(elseStatement));
    }

    private Doc ifCondition(IfStmt statement) {
        return Doc.concat(Doc.text("if "), ifConditionRenderer.apply(statement.getCondition()));
    }

    private Doc ifThenStatement(IfStmt statement) {
        if (
            statement.getElseStmt().isEmpty()
            && statement.getThenStmt().isBlockStmt()
            && statement.getThenStmt().asBlockStmt().getStatements().isEmpty()
            && statement.getThenStmt().asBlockStmt().getOrphanComments().isEmpty()
            && compact.apply(statement.getCondition()).contains("instanceof")
        ) {
            return Doc.concat(Doc.text("{"), Doc.HARD_LINE, Doc.text("}"));
        }
        return nestedStatement.apply(statement.getThenStmt());
    }

    private Doc ifThenStatementAfterConditionTrailingComment(IfStmt statement) {
        if (statement.getThenStmt().isBlockStmt()) {
            return Doc.concat(Doc.HARD_LINE, ifThenStatement(statement));
        }
        return Doc.indent(Doc.concat(Doc.HARD_LINE, statementRenderer.format(statement.getThenStmt(), LayoutContext.root())));
    }

    private Doc elseChainSeparator(
            IfStmt statement,
            Statement elseStatement,
            Doc conditionTrailingLineComment,
            Doc thenTrailingLineComment,
            Doc betweenThenAndElseBlockComment,
            Doc elseLeadingLineComment,
            Doc elseLeadingBlockComment
    ) {
        if (conditionTrailingLineComment != Doc.EMPTY && !statement.getThenStmt().isBlockStmt()) {
            // The condition trailing comment is rendered on the `if` line by ifStatement; this slot only emits the
            // separator. A braceless then whose first leading line comment bubbled up as the condition trailing comment
            // does not silence the then-trailing and else-leading slots: the remaining then-leading lines can surface as
            // the then statement's own trailing comment, and the else body's leading `//` block has already been claimed
            // by the elseLeadingLineComment gap slot. Returning the bare separator would leave both already-claimed
            // clusters unrendered and silently drop them, so emit whichever of them is present before `else`.
            return separatorWithThenTrailingAndElseLeading(thenTrailingLineComment, elseLeadingLineComment);
        }
        if (thenTrailingLineComment != Doc.EMPTY) {
            return Doc.concat(Doc.HARD_LINE, thenTrailingLineComment, Doc.HARD_LINE, Doc.text("else "));
        }
        if (betweenThenAndElseBlockComment != Doc.EMPTY) {
            return Doc.concat(Doc.text(" "), betweenThenAndElseBlockComment, Doc.text(" else "));
        }
        if (elseLeadingLineComment != Doc.EMPTY) {
            return Doc.concat(Doc.HARD_LINE, elseLeadingLineComment, Doc.HARD_LINE, Doc.text("else "));
        }
        if (elseLeadingBlockComment != Doc.EMPTY) {
            return Doc.concat(Doc.text(" else "), elseLeadingBlockComment, Doc.text(" "));
        }
        if (elseStatement.isIfStmt() && !statement.getThenStmt().isBlockStmt()) {
            return Doc.concat(Doc.HARD_LINE, Doc.text("else "));
        }
        return Doc.text(" else ");
    }

    /**
     * Builds the {@code else} separator for the condition-trailing-comment branch, carrying through both the then
     * statement's own trailing line comment and the else body's already-claimed leading {@code //} block.
     *
     * <p>The two slots are distinct comment clusters that both survive elsewhere when no condition trailing comment is
     * present (the then-trailing and elseLeadingLineComment branches below render them in turn). Once a braceless then's
     * leading comment has bubbled onto the {@code if} line as the condition trailing comment, this branch is the only one
     * reached, so it must render whichever of the two remaining clusters exist rather than dropping a cluster the dry-run
     * already recorded as owned here. Each present cluster gets its own line above {@code else}, in source order
     * (then-trailing first, else-leading second), matching the layout the no-condition-comment branches produce.
     */
    private Doc separatorWithThenTrailingAndElseLeading(Doc thenTrailingLineComment, Doc elseLeadingLineComment) {
        List<Doc> docs = new ArrayList<>();
        if (thenTrailingLineComment != Doc.EMPTY) {
            docs.add(Doc.HARD_LINE);
            docs.add(thenTrailingLineComment);
        }
        if (elseLeadingLineComment != Doc.EMPTY) {
            docs.add(Doc.HARD_LINE);
            docs.add(elseLeadingLineComment);
        }
        docs.add(Doc.HARD_LINE);
        docs.add(Doc.text("else "));
        return Doc.concat(docs);
    }
}
