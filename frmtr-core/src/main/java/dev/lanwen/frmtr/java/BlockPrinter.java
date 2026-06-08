package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.EmptyStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchStmt;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Sequences already formatted statements inside Java block bodies.
 *
 * <p>This helper owns the source-range-sensitive orchestration around statement docs: orphan comments, printable empty
 * statements, and blank-line separators. The boundary exists so {@link JavaPrinter} can keep the statement dispatch
 * tree and statement-specific formatting rules local while block-level trivia rules stay together. It intentionally
 * does not decide how any individual {@link Statement} renders or how formatter pragmas change statement print actions.
 */
final class BlockPrinter {
    private final CommentTracker comments;
    private final JavaCommentPlacementPolicy commentPlacement;
    private final JavaFormatRule<Statement> statementRenderer;
    private final Predicate<Statement> hasPragma;

    BlockPrinter(
            CommentTracker comments,
            JavaCommentPlacementPolicy commentPlacement,
            JavaFormatRule<Statement> statementRenderer,
            Predicate<Statement> hasPragma) {
        this.comments = comments;
        this.commentPlacement = commentPlacement;
        this.statementRenderer = statementRenderer;
        this.hasPragma = hasPragma;
    }

    /**
     * Prints a brace-delimited statement block while preserving source-only comments and blank lines.
     *
     * <p>Plain blocks keep orphan comments as a joined prelude before regular statement sequencing. Comments that fall
     * inside child statement ranges are left to the child statement printer so they are not emitted twice.
     */
    Doc block(BlockStmt block) {
        if (block.getStatements().isEmpty() && !commentPlacement.hasOrphanComments(block)) {
            return Doc.text("{}");
        }
        List<Doc> statements = new ArrayList<>();
        List<Doc> orphanComments = blockOrphanCommentStatements(block);
        if (!orphanComments.isEmpty()) {
            statements.add(Doc.join(Doc.HARD_LINE, orphanComments));
        }
        appendBlockStatements(statements, block.getStatements());
        if (statements.isEmpty()) {
            return Doc.text("{}");
        }
        return statementBlock(statements);
    }

    /**
     * Prints a block after injecting caller-owned docs before the block's statements.
     *
     * <p>Try/catch/finally formatting uses this for line comments that conceptually belong inside the next block even
     * though JavaParser exposes them on the preceding clause. The caller keeps any special empty-block shape decisions.
     */
    Doc blockWithLeading(BlockStmt block, Doc leadingInside) {
        if (block.getStatements().isEmpty()
                && !commentPlacement.hasOrphanComments(block)
                && leadingInside == Doc.EMPTY) {
            return Doc.text("{}");
        }
        List<Doc> statements = new ArrayList<>();
        if (leadingInside != Doc.EMPTY) {
            statements.add(leadingInside);
        }
        statements.addAll(blockOrphanCommentStatements(block));
        appendBlockStatements(statements, block.getStatements());
        return statementBlock(statements);
    }

    /**
     * Chooses the vertical separator between two adjacent statements or before the first real statement.
     *
     * <p>A {@code null} previous statement means the separator follows leading block docs such as orphan comments.
     * Formatter pragmas force a single hard line because pragma comments carry layout intent and should not be expanded
     * into a blank-line decision by source-range fallback rules.
     */
    Doc statementSeparator(Statement previousStatement, Statement currentStatement) {
        if (previousStatement == null) {
            return Doc.HARD_LINE;
        }
        if (hasPragma.test(previousStatement) || hasPragma.test(currentStatement)) {
            return Doc.HARD_LINE;
        }
        boolean hasBlankLineBetween = previousStatement.getRange()
                .flatMap(previousRange -> currentStatement.getRange()
                        .map(currentRange -> effectiveBeginLine(currentStatement, currentRange.begin.line)
                                > previousRange.end.line + 1))
                .orElse(false);
        return hasBlankLineBetween ? Doc.concat(Doc.HARD_LINE, Doc.HARD_LINE) : Doc.HARD_LINE;
    }

    /**
     * Appends printable statements to the block content list in source order.
     *
     * <p>JavaParser can surface empty statements after a switch as statement-list artifacts, so those are skipped.
     * Other empty statements are printable only when they own a line comment; bare semicolon placeholders disappear from
     * formatted block output.
     */
    private void appendBlockStatements(List<Doc> statements, List<Statement> sourceStatements) {
        Statement previousStatement = null;
        for (Statement currentStatement : sourceStatements) {
            if (currentStatement.isEmptyStmt() && previousStatement instanceof SwitchStmt) {
                continue;
            }
            if (currentStatement instanceof EmptyStmt emptyStmt) {
                Optional<Doc> emptyStatementComment = blockEmptyStatementComment(emptyStmt);
                if (emptyStatementComment.isEmpty()) {
                    continue;
                }
                if (!statements.isEmpty()) {
                    statements.add(statementSeparator(previousStatement, currentStatement));
                }
                statements.add(emptyStatementComment.orElseThrow());
                previousStatement = currentStatement;
                continue;
            }
            if (!statements.isEmpty()) {
                statements.add(statementSeparator(previousStatement, currentStatement));
            }
            statements.add(statementRenderer.format(currentStatement));
            previousStatement = currentStatement;
        }
    }

    private Doc statementBlock(List<Doc> statements) {
        return Doc.concat(
                Doc.text("{"),
                Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.concat(statements))),
                Doc.HARD_LINE,
                Doc.text("}"));
    }

    /**
     * Recovers the only empty statements that should remain visible in block output.
     *
     * <p>An empty statement with an attached line comment represents a deliberate commented placeholder, while a bare
     * empty statement is formatting noise for this printer and is skipped by block sequencing.
     */
    private Optional<Doc> blockEmptyStatementComment(EmptyStmt statement) {
        Doc lineComment = comments.ownTriviaComment(statement, JavaCommentTrivia::isLine);
        return lineComment == Doc.EMPTY ? Optional.empty() : Optional.of(lineComment);
    }

    /**
     * Returns orphan comments that belong to the block itself rather than to nested statement bodies.
     */
    private List<Doc> blockOrphanCommentStatements(BlockStmt block) {
        return commentPlacement.orphanCommentsOutsideChildRanges(block, block.getStatements()).stream()
                .map(comments::comment)
                .filter(doc -> doc != Doc.EMPTY)
                .toList();
    }

    /**
     * Returns the line that should start separator calculations for a statement.
     *
     * <p>Attached leading comments visually start the statement earlier than the AST node range. When no comment range is
     * available, the caller's statement begin line remains the fallback.
     */
    private int effectiveBeginLine(Statement statement, int fallback) {
        return commentPlacement.ownComment(statement)
                .map(comment -> comment.beginLine(fallback))
                .orElse(fallback);
    }
}
