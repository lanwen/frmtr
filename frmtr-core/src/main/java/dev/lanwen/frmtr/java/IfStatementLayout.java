package dev.lanwen.frmtr.java;

import com.github.javaparser.GeneratedJavaParserConstants;
import com.github.javaparser.JavaToken;
import com.github.javaparser.Position;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import dev.lanwen.frmtr.FormatterOptions;
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

    private final FormatterOptions options;

    private final LayoutWidth layoutWidth;

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
            FormatterOptions options,
            LayoutWidth layoutWidth,
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
        this.options = options;
        this.layoutWidth = layoutWidth;
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
        // When a block then is followed by a real else, let the else-leading gap block (computed in the else branch
        // below) own every gap line; claim the standalone then-trailing slot only otherwise. A collapse can re-attach a
        // gap line as the then block's own trailing comment, and claiming it here would render it alone and drop the
        // rest of the block.
        boolean elseLeadingGapOwnsThenTrailing = statement.getThenStmt().isBlockStmt() && statement.getElseStmt()
                .filter(elseStatement -> !elseStatement.isEmptyStmt())
                .isPresent();
        Doc thenTrailingLineComment = elseLeadingGapOwnsThenTrailing
            ? Doc.EMPTY
            : trailingLineComment.apply(statement.getThenStmt());
        // Claim the else-leading gap block in Doc-construction order (before the then branch, though it renders after the
        // then `}`) so the record-only pre-pass owns the whole block here; otherwise the block printer claims a
        // collapse-reattached gap line on the `}` line and splits the block. The `else` keyword position is the boundary
        // between the two slots: a comment before it is a separator (this gap slot), one after it leads a braceless else
        // body — those render indented under `else` via the braceless-body handler, and claiming the body block here
        // keeps the two slots from double-owning a line.
        boolean thenBrokeBraceless = bracelessThenBrokeOnLeadingComment(statement) || joinedFormOverflows(statement);
        Optional<Statement> bracelessElse = statement.getElseStmt()
                .filter(elseStatement -> !elseStatement.isEmptyStmt())
                .filter(elseStatement -> !elseStatement.isIfStmt())
                .filter(elseStatement -> !elseStatement.isBlockStmt());
        Optional<Position> elseKeyword = bracelessElse.isPresent()
            ? elseKeywordPosition(statement)
            : Optional.empty();
        Doc elseLeadingLineComment = statement.getElseStmt()
                .filter(elseStatement -> !elseStatement.isEmptyStmt())
                .map(elseStatement -> elseLeadingLineComment(statement, elseStatement, elseKeyword))
                .orElse(Doc.EMPTY);
        // Build the braceless else body in Doc-construction order (before the then branch and separator) so the pre-pass
        // records its leading `//` block under the body's leading slot first — the separator slot then cannot reclaim a
        // collapse-rebucketed body-leading line and split it off the body. As the sole renderer for a braceless else
        // body, it also keeps a reattached separator comment from tripping nestedStatement's generic leading-comment
        // body break.
        Optional<Doc> bracelessElseBody = bracelessElse.map(
            elseStatement -> bracelessElseBody(statement, elseStatement, elseKeyword, thenBrokeBraceless)
        );
        Doc conditionTrailingLineComment = controlConditions.closeParenTrailingLineComment(statement.getCondition());
        Doc betweenThenAndElseBlockComment = blockCommentBetweenThenAndElse(statement);
        docs.add(ifCondition(statement));
        if (conditionTrailingLineComment != Doc.EMPTY) {
            docs.add(Doc.text(" "));
            docs.add(conditionTrailingLineComment);
            docs.add(ifThenStatementAfterConditionTrailingComment(statement));
        } else if (joinedFormOverflows(statement) && !statement.getThenStmt().isBlockStmt()) {
            // The collapsed one-line if/else would overflow, so break the braceless then onto its own indented line
            // (the else side follows via thenBrokeBraceless). The trailing space before the HARD_LINE is trimmed by the
            // renderer, leaving `if (cond)` alone on its line.
            docs.add(Doc.text(" "));
            docs.add(ifThenStatementAfterConditionTrailingComment(statement));
        } else {
            docs.add(Doc.text(" "));
            docs.add(ifThenStatement(statement));
        }
        statement.getElseStmt()
                .ifPresent(elseStatement -> {
                    if (elseStatement.isEmptyStmt()) {
                        docs.add(emptyElseStatement(statement, elseStatement));
                        return;
                    }
                    // For `} /* c */ else {` the between-then-and-else block comment is also a same-line block comment
                    // before the else. Offer the else-leading slot only when the between slot did not claim it, so the
                    // comment is claimed once (elseChainSeparator returns on the between slot first, so output is
                    // unchanged).
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
                            elseLeadingBlockComment,
                            thenBrokeBraceless
                        )
                    );
                    if (bracelessElseBody.isPresent()) {
                        // bracelessElseBody (computed above) renders the whole braceless else body: it breaks and
                        // indents when an after-`else` leading `//` block is present, else collapses onto the `else`
                        // line, claiming the leading block exactly once.
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
     * <p>A block between the then {@code }} and {@code else}/{@code else if} is split across nodes (trailing lines as the
     * {@code if}'s orphans, the line above {@code else} as that node's own trivia); reading either slot alone splits the
     * block and rotates the lines every pass (re-parsing re-splits them differently), mangling {@code else if} into
     * {@code else //\n if}. Claiming the whole gap block in one slot
     * ({@link JavaCommentPlacementPolicy#gapLeadingLineCommentBlock(Node, Node, java.util.Collection)}) renders it once,
     * together, above {@code else}. At {@code @default} the block is one contiguous run, rendered in the same order.
     *
     * <p>{@code elseKeywordUpperBound}, when present, restricts this slot to the separator gap (comments before the
     * {@code else} keyword). It is supplied only for a braceless else body, whose own leading {@code //} block lives
     * <em>after</em> {@code else} and is owned by {@link #bracelessElseBody(IfStmt, Statement, java.util.Optional)};
     * absent for an {@code else if} or block else, where the whole gap block is recovered as before.
     */
    private Doc elseLeadingLineComment(
            IfStmt statement,
            Statement elseStatement,
            Optional<Position> elseKeywordUpperBound
    ) {
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
     * <p>The else-body counterpart of {@link LoopStatementLayout#bracelessLoopBody(Node, Node, Statement)}: the body
     * normally collapses onto the {@code else} line ({@code else return 2;}), but a leading line comment there would
     * comment out the body, so the body breaks to an indented line below it. JavaParser splits the leading block (line
     * above the body is its own trivia; earlier lines re-bucket onto the {@code if} orphans or then branch under
     * perturbation), so the re-bucketed lines are recovered via
     * {@link JavaCommentPlacementPolicy#gapLineCommentsBefore}, bounded to <em>after</em> the {@code else} keyword so a
     * genuine separator comment (owned by {@link #elseLeadingLineComment}) is not pulled in. Claiming each line once
     * leaves the block neither dropped under perturbation nor double-printed at {@code @default}.
     *
     * <p>Sole renderer for a braceless else body: with no after-{@code else} leading comment it collapses onto the
     * {@code else} line like {@link StatementPrinter#nestedStatement(Statement)}, so a reattached separator comment does
     * not trip that method's generic leading-comment body break on a later pass.
     */
    private Doc bracelessElseBody(
            IfStmt statement,
            Statement elseStatement,
            Optional<Position> elseKeyword,
            boolean thenBrokeBraceless
    ) {
        List<JavaCommentTrivia> aboveBodyComments = commentPlacement
                .gapLineCommentsBefore(
                    statement.getThenStmt(),
                    elseStatement,
                    List.of(statement, statement.getThenStmt())
                )
                .stream()
                .filter(
                    trivia -> elseKeyword.map(position -> CommentIndex.startsAfter(trivia.comment(), position)).orElse(
                        true
                    )
                )
                .toList();
        // The body's own leading comment counts only when it sits after the `else` keyword. A collapse can re-attach a
        // genuine separator comment (one written before `else`) onto the else statement as its own leading trivia; that
        // comment belongs to the separator slot, not the body, so the else-keyword bound keeps it from triggering a
        // body break here.
        boolean bodyOwnsLeadingLineComment = commentPlacement.leadingComment(elseStatement)
                .filter(JavaCommentTrivia::isLine)
                .filter(trivia -> !trivia.startsAfterEndOf(elseStatement))
                .filter(
                    trivia -> elseKeyword.map(position -> CommentIndex.startsAfter(trivia.comment(), position)).orElse(
                        true
                    )
                )
                .isPresent();
        // When the braceless then broke onto its own line(s), the else body must break under `else` too, even with no
        // leading comment of its own -- otherwise the whole if/else expanded except the else body, which would cram
        // `else body;` onto the `else` line while the then sits multi-line above it.
        if (aboveBodyComments.isEmpty() && !bodyOwnsLeadingLineComment && !thenBrokeBraceless) {
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
     * <p>The {@code else} keyword has no AST node, so callers classifying a comment as a separator (before {@code else})
     * or an else-body leading comment (after {@code else}) read its token position directly, like
     * {@link ConditionalExpressionPrinter} does for {@code ?}/{@code :}. Empty when the token range is unavailable;
     * callers then treat the whole gap as the separator.
     */
    private Optional<Position> elseKeywordPosition(IfStmt statement) {
        Optional<Position> thenEnd = statement.getThenStmt()
                .getRange()
                .map(range -> range.end);
        return statement.getTokenRange()
                .flatMap(tokenRange -> {
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
     * <p>Mirrors {@link TryStatementLayout}'s clause-trailing recovery: at {@code @default} the body's own trailing
     * comment ({@link CommentTracker#trailingLineComment(Node)}) owns it; under a collapse that re-buckets it onto the
     * {@link IfStmt} orphans, recover the {@code if} orphan line comment source-ordered after the else body (the else is
     * the last clause).
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
        statement.getElseStmt()
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
     * <p>The own path uses a column window — the comment shares the then-end line immediately after {@code }} (within two
     * columns) and before {@code else} — which distinguishes {@code } /* note *}{@code / else} from an
     * {@code else /* note *}{@code / {} comment (both the else node's own block comment on that line). This keeps
     * {@code @default} byte-identical and lets the {@code else}-leading comment fall through to
     * {@link #ifStatement(IfStmt)}'s {@code elseLeadingBlockComment} slot.
     *
     * <p>A perturbation pushing the comment onto its own line re-buckets it as an {@link IfStmt} orphan, which the column
     * predicates lose; the orphan fallback recovers the {@code if} orphan block comment source-ordered strictly between
     * the then end and the else begin. It sees only orphans, so an {@code else}-leading comment (still the else node's
     * own trivia) is never claimed here.
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
        return Doc.indent(
            Doc.concat(Doc.HARD_LINE, statementRenderer.format(statement.getThenStmt(), LayoutContext.root()))
        );
    }

    private Doc elseChainSeparator(
            IfStmt statement,
            Statement elseStatement,
            Doc conditionTrailingLineComment,
            Doc thenTrailingLineComment,
            Doc betweenThenAndElseBlockComment,
            Doc elseLeadingLineComment,
            Doc elseLeadingBlockComment,
            boolean thenBrokeBraceless
    ) {
        if (conditionTrailingLineComment != Doc.EMPTY && !statement.getThenStmt().isBlockStmt()) {
            // The condition trailing comment is rendered on the `if` line by ifStatement; this slot only emits the
            // separator. But when a braceless then's first leading comment bubbled up as that condition trailing comment,
            // the then-trailing and already-claimed else-leading clusters still exist — returning the bare separator
            // would silently drop them, so emit whichever is present before `else`.
            return separatorWithThenTrailingAndElseLeading(thenTrailingLineComment, elseLeadingLineComment);
        }
        if (thenTrailingLineComment != Doc.EMPTY) {
            // Keep a braceless then's trailing comment on the then body's own line ({@code stmt; // note}); only `else`
            // moves down. Breaking the comment onto its own separator line instead re-buckets it as an else-leading
            // comment on re-parse, which perturbs the else-trailing claim and drops the else body's own comment.
            return Doc.concat(Doc.text(" "), thenTrailingLineComment, Doc.HARD_LINE, Doc.text("else "));
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
        if (thenBrokeBraceless) {
            // The braceless then broke onto its own line(s) (a leading `//` comment), so `else` cannot hang off the
            // then's last line: it goes on its own line. A block else keeps `{` on the `else` line; a braceless else
            // body breaks and indents beneath it ({@link #bracelessElseBody}), so it needs no trailing space here.
            return elseStatement.isBlockStmt()
                ? Doc.concat(Doc.HARD_LINE, Doc.text("else "))
                : Doc.concat(Doc.HARD_LINE, Doc.text("else"));
        }
        return Doc.text(" else ");
    }

    /**
     * Whether a braceless then-branch carries a leading {@code //} comment that forces it -- and so the whole if/else --
     * to break. Mirrors {@link StatementPrinter}'s braceless-body break predicate: with such a comment the then renders
     * multi-line, so the {@code else} must move to its own line rather than cram onto the then's last line.
     */
    private boolean bracelessThenBrokeOnLeadingComment(IfStmt statement) {
        Statement thenStatement = statement.getThenStmt();
        if (thenStatement.isBlockStmt()) {
            return false;
        }
        return commentPlacement.leadingComment(thenStatement)
                .filter(JavaCommentTrivia::isLine)
                .filter(trivia -> !trivia.startsAfterEndOf(thenStatement))
                .isPresent();
    }

    /**
     * Whether the braceless if/else collapsed onto one line would overflow the line width, forcing then (and a simple
     * else) onto their own lines. Source-neutral: it measures a comment-stripped clone rendered from the AST (its
     * retained source token range is cleared) at the statement's indentation, so an author's wrapping cannot flip the
     * verdict and the result is a fixpoint. An else-if or block else lands on its own line(s), so it is dropped before
     * measuring — only the collapsible head shares the line.
     */
    private boolean joinedFormOverflows(IfStmt statement) {
        if (statement.getThenStmt().isBlockStmt()) {
            return false;
        }
        IfStmt clone = statement.clone();
        clone.getElseStmt()
                .filter(elseStatement -> elseStatement.isIfStmt() || elseStatement.isBlockStmt())
                .ifPresent(elseStatement -> clone.setElseStmt(null));
        clone.removeComment();
        List.copyOf(clone.getOrphanComments()).forEach(clone::removeOrphanComment);
        List.copyOf(clone.getAllContainedComments()).forEach(Node::remove);
        clone.setTokenRange(null);
        return layoutWidth.nodeLine(statement, compact.apply(clone)) > options.lineWidth();
    }

    /**
     * Builds the {@code else} separator for the condition-trailing-comment branch, carrying through both the then
     * statement's own trailing line comment and the else body's already-claimed leading {@code //} block.
     *
     * <p>These two clusters render via other branches when no condition trailing comment is present; once a braceless
     * then's leading comment has bubbled onto the {@code if} line, this is the only branch reached, so it must render
     * whichever cluster exists rather than drop one the pre-pass already recorded as owned here. Each gets its own line
     * above {@code else} in source order (then-trailing, then else-leading).
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
