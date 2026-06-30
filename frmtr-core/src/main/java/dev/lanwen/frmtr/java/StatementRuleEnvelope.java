package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import dev.lanwen.frmtr.FormatterException;
import dev.lanwen.frmtr.doc.Doc;

/**
 * Applies statement-level pragma, raw-source, and comment gates before formatted statement content is dispatched.
 *
 * <p>This helper owns the outer statement rule envelope: formatter off/on and ignore pragmas, raw source recovery,
 * leading and trailing statement comments, and {@link TryStmt} comment special cases. The boundary keeps those
 * source-sensitive gates out of {@link StatementPrinter}, which renders structured statement bodies, and out of
 * {@link SwitchPrinter}, which owns reusable switch-entry grammar for switch statements and switch expressions.
 *
 * <p>Callers still choose when a statement context is needed and provide the already-wired content dispatcher.
 * Expression formatting, block formatting, declaration-body formatting, switch-entry rendering, and statement rendering
 * stay with their existing owners. Representative pragma coverage includes
 * {@code frmtr-core/src/test/resources/format/formatter-pragma-inside-block/input.java} with
 * {@code frmtr-core/src/test/resources/format/formatter-pragma-inside-block/frmtr-default.output.java}
 * and {@code frmtr-core/src/test/resources/format/require-pragma-format/input.java}
 * with
 * {@code frmtr-core/src/test/resources/format/require-pragma-format/frmtr-default.output.java}.
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
            JavaFormatRule<Statement> statementContent
    ) {
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
     * content renderer. Formatted statements route to {@link StatementPrinter} only after statement-level raw output,
     * leading comments, and trailing line comments have been selected.
     */
    Doc statement(Statement statement) {
        return statement(statement, statementContent);
    }

    Doc statement(Statement statement, JavaFormatRule<Statement> statementContent) {
        requireFullyParsed(statement);
        FormatterPragmas.PrintAction action = formatterPragmas.statementAction(statement);
        if (action == FormatterPragmas.PrintAction.RAW_WITH_TRAILING_HARD_LINE) {
            return label(statement, Doc.concat(rawStatement(statement), Doc.HARD_LINE));
        }
        if (action == FormatterPragmas.PrintAction.RAW) {
            return label(statement, rawStatement(statement));
        }
        // A statement's attached trailing line comment can already have been claimed and placed by an enclosing
        // construct's own clause handling (e.g. an if/else chain that renders a nested statement's trailing comment).
        // Skip the envelope offer when that comment is already printed so it is not duplicate-claimed; output is
        // unchanged because the first claimant placed it and this re-offer only ever rendered empty.
        Doc trailing = statement instanceof TryStmt || trailingLineCommentAlreadyPrinted(statement)
            ? Doc.EMPTY
            : comments.trailingLineComment(statement);
        Doc leading = leadingComment(statement, trailing);
        Doc body = statementContent.format(statement, LayoutContext.root());
        return label(
            statement,
            Doc.concat(leading, body, trailing == Doc.EMPTY ? Doc.EMPTY : Doc.concat(Doc.text(" "), trailing))
        );
    }

    private boolean trailingLineCommentAlreadyPrinted(Statement statement) {
        return commentPlacement.trailingLineComment(statement)
                .map(comments::isPrinted)
                .orElse(false);
    }

    private Doc label(Statement statement, Doc doc) {
        return Doc.label("java.statement:" + statement.getClass().getSimpleName(), doc);
    }

    /**
     * Recovers raw statement source while preserving leading comments for statements whose raw span excludes them.
     *
     * <p>Try statements still keep clause-adjacent trailing comment handling inside the structured try renderer because
     * JavaParser exposes comments around try/catch/finally in source-sensitive positions. Adjacent line comments before
     * the {@code try} keyword use the ordinary leading cluster so multi-line statement notes stay together.
     */
    private Doc rawStatement(Statement statement) {
        Doc leading = comments.leadingCluster(statement);
        return Doc.concat(leading, rawPreservedSource.rawWithoutOwnComment(statement));
    }

    private Doc leadingComment(Statement statement, Doc trailing) {
        if (hasInlineBreakBlockComment(statement) || hasInlineSwitchBlockComment(statement)) {
            return Doc.EMPTY;
        }
        if (statement instanceof TryStmt) {
            return comments.leadingCluster(statement);
        }
        Doc adjacentLeading = comments.adjacentLeadingLineComments(statement);
        return trailing == Doc.EMPTY ? Doc.concat(adjacentLeading, comments.leading(statement)) : adjacentLeading;
    }

    private static void requireFullyParsed(Statement statement) {
        if (statement.stream().allMatch(node -> node.getParsed() == Node.Parsedness.PARSED)) {
            return;
        }
        if (
            statement instanceof SwitchStmt switchStmt
            && SwitchPrinter.hasRecoverableSwitchEntryListProblem(switchStmt)
        ) {
            return;
        }
        // TODO: Expose the rejected recovered statement through formatter diagnostics once recovery reporting exists.
        throw new FormatterException(
            "Unsupported Java parse-error recovery reached statement formatter: " + statement.getClass().getSimpleName()
        );
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
