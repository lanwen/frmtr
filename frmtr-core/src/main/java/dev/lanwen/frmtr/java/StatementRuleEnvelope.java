package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import dev.lanwen.frmtr.doc.Doc;

/**
 * Applies statement-level pragma, raw-source, and comment gates before formatted statement content is dispatched.
 *
 * <p>This helper owns the outer statement rule envelope: formatter off/on and ignore pragmas, raw source recovery,
 * leading and trailing statement comments, and {@link TryStmt} comment special cases. The boundary keeps those
 * source-sensitive gates out of {@link StatementPrinter}, which renders structured non-switch statement bodies, out of
 * {@link SwitchPrinter}, which owns switch grammar, and out of {@link StatementDispatcher}, which only chooses between
 * those content rules.
 *
 * <p>Callers still choose when a statement context is needed and provide the already-wired content dispatcher.
 * Expression formatting, block formatting, declaration-body formatting, switch rendering, and non-switch statement
 * rendering stay with their existing owners. Representative pragma coverage includes
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/formatter-on-off/inside_block/input.java} with
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/formatter-on-off/inside_block/frmtr.output.java}
 * and {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/require-pragma/format-pragma/input.java}
 * with
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/require-pragma/format-pragma/frmtr.output.java}.
 */
final class StatementRuleEnvelope {
    private final CommentTracker comments;
    private final JavaCommentPlacementPolicy commentPlacement;
    private final FormatterPragmas formatterPragmas;
    private final RawPreservedSource rawPreservedSource;
    private final JavaFormatRule<Statement> statementContent;

    StatementRuleEnvelope(
            CommentTracker comments,
            JavaCommentPlacementPolicy commentPlacement,
            FormatterPragmas formatterPragmas,
            RawPreservedSource rawPreservedSource,
            JavaFormatRule<Statement> statementContent) {
        this.comments = comments;
        this.commentPlacement = commentPlacement;
        this.formatterPragmas = formatterPragmas;
        this.rawPreservedSource = rawPreservedSource;
        this.statementContent = statementContent;
    }

    /**
     * Applies statement-level formatter pragmas and comment attachment before structured statement content rendering.
     *
     * <p>Formatter off/on pragmas update persistent state across later statements, so this gate must run before the
     * content dispatcher. Formatted statements route to {@link StatementDispatcher} only after statement-level raw
     * output, leading comments, and trailing line comments have been selected.
     */
    Doc statement(Statement statement) {
        FormatterPragmas.PrintAction action = formatterPragmas.statementAction(statement);
        if (action == FormatterPragmas.PrintAction.RAW_WITH_TRAILING_HARD_LINE) {
            return Doc.concat(rawStatement(statement), Doc.HARD_LINE);
        }
        if (action == FormatterPragmas.PrintAction.RAW) {
            return rawStatement(statement);
        }
        Doc trailing = statement instanceof TryStmt ? Doc.EMPTY : comments.trailingLineComment(statement);
        Doc leading = leadingComment(statement, trailing);
        Doc body = statementContent.format(statement);
        return Doc.concat(leading, body, trailing == Doc.EMPTY ? Doc.EMPTY : Doc.concat(Doc.text(" "), trailing));
    }

    /**
     * Recovers raw statement source while preserving leading comments for statements whose raw span excludes them.
     *
     * <p>Try statements keep their leading/trailing comment handling inside the structured try renderer because
     * JavaParser exposes comments around try/catch/finally in source-sensitive positions that are not equivalent to the
     * ordinary leading statement slot.
     */
    private Doc rawStatement(Statement statement) {
        Doc leading = statement instanceof TryStmt ? Doc.EMPTY : comments.leading(statement);
        return Doc.concat(leading, rawPreservedSource.rawWithoutOwnComment(statement));
    }

    private Doc leadingComment(Statement statement, Doc trailing) {
        if (statement instanceof TryStmt || hasInlineBreakBlockComment(statement) || hasInlineSwitchBlockComment(statement)) {
            return Doc.EMPTY;
        }
        return trailing == Doc.EMPTY ? comments.leading(statement) : Doc.EMPTY;
    }

    /**
     * Break statements can use an own block comment as inline statement text, so the normal leading slot stays empty.
     */
    private boolean hasInlineBreakBlockComment(Statement statement) {
        return statement instanceof BreakStmt
                && commentPlacement.ownComment(statement, JavaCommentTrivia::isBlock).isPresent();
    }

    /**
     * Switch statements can carry a same-line block comment before {@code switch}, so the content rule places it inline.
     */
    private boolean hasInlineSwitchBlockComment(Statement statement) {
        return statement instanceof SwitchStmt
                && commentPlacement.ownComment(statement, JavaCommentTrivia::isBlock).isPresent();
    }
}
