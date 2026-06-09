package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.EmptyStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchStmt;
import dev.lanwen.frmtr.FormatterException;
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
    private static final String BLOCK_STATEMENT_LIST_RECOVERY_FAILURE =
            "Unable to recover Java parse error inside block statement list: ";

    private final CommentTracker comments;
    private final JavaCommentPlacementPolicy commentPlacement;
    private final SourceText sourceText;
    private final RecoveredListPlanner recoveredListPlanner;
    private final RecoveredRawGapPrinter rawGaps;
    private final boolean recoverParseProblems;
    private final JavaFormatRule<Statement> statementRenderer;
    private final Predicate<Statement> hasPragma;

    BlockPrinter(
            JavaFormatContext context,
            JavaFormatRule<Statement> statementRenderer,
            Predicate<Statement> hasPragma) {
        this.comments = context.comments;
        this.commentPlacement = context.commentPlacementPolicy;
        this.sourceText = context.sourceText;
        this.recoveredListPlanner = context.recoveredListPlanner;
        this.rawGaps = new RecoveredRawGapPrinter(context, BlockPrinter::blockStatementListRecoveryFailure);
        this.recoverParseProblems = context.recoverParseProblems;
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
        Optional<RecoveredListPlanner.Plan<Statement>> recoveryPlan = recoveryPlan(block);
        if (recoveryPlan.isPresent() && hasRawGap(recoveryPlan.orElseThrow())) {
            return recoveredBlock(block, List.of(), recoveryPlan.orElseThrow());
        }
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
        Optional<RecoveredListPlanner.Plan<Statement>> recoveryPlan = recoveryPlan(block);
        if (recoveryPlan.isPresent() && hasRawGap(recoveryPlan.orElseThrow())) {
            List<Doc> leadingDocs = leadingInside == Doc.EMPTY ? List.of() : List.of(leadingInside);
            return recoveredBlock(block, leadingDocs, recoveryPlan.orElseThrow());
        }
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

    /**
     * Emits a recovered block interior by letting raw gaps own their original source spacing.
     *
     * <p>Raw gap regions already include the whitespace between the nearest valid siblings or block braces. This path
     * therefore inserts formatter separators only between adjacent valid siblings; adding normal separators around raw
     * gaps would duplicate source line breaks and shift the preserved malformed text.
     */
    private Doc recoveredBlock(
            BlockStmt block,
            List<Doc> leadingInside,
            RecoveredListPlanner.Plan<Statement> plan) {
        List<RecoveredRawGapPrinter.RawGapRegion> rawGapRegions = rawGaps.rawGapRegions(plan);
        List<SourceRegion> rawRegions = rawGaps.regions(rawGapRegions);
        rawGaps.requireRecoverableRawRegions(block, rawGapRegions);
        List<Doc> leadingDocs = new ArrayList<>(leadingInside);
        leadingDocs.addAll(blockOrphanCommentStatements(block, rawRegions));

        List<Doc> contents = new ArrayList<>();
        if (!leadingDocs.isEmpty()) {
            contents.add(Doc.HARD_LINE);
            contents.add(Doc.join(Doc.HARD_LINE, leadingDocs));
        }

        Statement previousValidStatement = null;
        EntryKind previousEntry = leadingDocs.isEmpty() ? EntryKind.NONE : EntryKind.LEADING_DOC;
        int rawGapIndex = 0;
        for (RecoveredListPlanner.Entry<Statement> entry : plan.entries()) {
            switch (entry) {
                case RecoveredListPlanner.ValidSibling<?> valid -> {
                    Statement currentStatement = (Statement) valid.sibling();
                    Optional<Doc> maybeStatement = printableStatement(previousValidStatement, currentStatement);
                    if (maybeStatement.isEmpty()) {
                        continue;
                    }
                    if (contents.isEmpty()) {
                        contents.add(Doc.HARD_LINE);
                    } else if (previousEntry == EntryKind.VALID_STATEMENT) {
                        contents.add(statementSeparator(previousValidStatement, currentStatement));
                    } else if (previousEntry == EntryKind.LEADING_DOC) {
                        contents.add(Doc.HARD_LINE);
                    } else if (previousEntry == EntryKind.RAW_GAP_WITH_TRAILING_BREAK) {
                        contents.add(Doc.HARD_LINE);
                    }
                    contents.add(maybeStatement.orElseThrow());
                    previousValidStatement = currentStatement;
                    previousEntry = EntryKind.VALID_STATEMENT;
                }
                case RecoveredListPlanner.RawGap<?> ignored -> {
                    RecoveredRawGapPrinter.RawGapRegion rawRegion = rawGapRegions.get(rawGapIndex++);
                    if (rawRegion.region().beginOffset() < rawRegion.region().endOffset()) {
                        contents.add(rawGaps.raw(block, rawRegion, "blockStatementList"));
                    }
                    previousEntry = rawRegion.trailingBreakReplaced()
                            ? EntryKind.RAW_GAP_WITH_TRAILING_BREAK
                            : EntryKind.RAW_GAP;
                }
            }
        }

        if (contents.isEmpty()) {
            return Doc.text("{}");
        }
        Doc closingBreak = switch (previousEntry) {
            case RAW_GAP -> Doc.EMPTY;
            case NONE, LEADING_DOC, VALID_STATEMENT, RAW_GAP_WITH_TRAILING_BREAK -> Doc.HARD_LINE;
        };
        return Doc.concat(Doc.text("{"), Doc.indent(Doc.concat(contents)), closingBreak, Doc.text("}"));
    }

    private Optional<Doc> printableStatement(Statement previousStatement, Statement currentStatement) {
        if (currentStatement.isEmptyStmt() && previousStatement instanceof SwitchStmt) {
            return Optional.empty();
        }
        if (currentStatement instanceof EmptyStmt emptyStmt) {
            return blockEmptyStatementComment(emptyStmt);
        }
        return Optional.of(statementRenderer.format(currentStatement));
    }

    private Optional<RecoveredListPlanner.Plan<Statement>> recoveryPlan(BlockStmt block) {
        if (!recoverParseProblems || !hasRecoverableBlockStatementListProblem(block)) {
            return Optional.empty();
        }
        RecoveredListPlanner.Plan<Statement> plan = recoveredListPlanner.planWithCallerOwnedSafety(
                block,
                blockInteriorRegion(block),
                block.getStatements(),
                this::isSafeBlockStatementRecoverySibling);
        if (!plan.isSafe()) {
            throw blockStatementListRecoveryFailure(plan.unsafe().orElseThrow().reason());
        }
        return Optional.of(plan);
    }

    private boolean hasRecoverableBlockStatementListProblem(BlockStmt block) {
        if (block.getStatements().stream().anyMatch(this::isCollapsedMalformedSwitchStatement)) {
            return false;
        }
        if (block.getParsed() != Node.Parsedness.PARSED) {
            return true;
        }
        return block.getStatements().stream().anyMatch(this::requiresBlockStatementListRecovery);
    }

    private boolean requiresBlockStatementListRecovery(Statement statement) {
        return !isSafeBlockStatementRecoverySibling(statement);
    }

    private boolean isSafeBlockStatementRecoverySibling(Statement statement) {
        if (statement.getParsed() != Node.Parsedness.PARSED) {
            return false;
        }
        if (statement instanceof SwitchStmt switchStmt
                && SwitchPrinter.hasRecoverableSwitchEntryListProblem(switchStmt)) {
            return true;
        }
        return isFullyParsed(statement);
    }

    private boolean isCollapsedMalformedSwitchStatement(Statement statement) {
        return SwitchPrinter.isCollapsedMalformedSwitchStatement(statement, sourceText);
    }

    private static boolean isFullyParsed(Node node) {
        return node.stream().allMatch(descendant -> descendant.getParsed() == Node.Parsedness.PARSED);
    }

    private SourceRegion blockInteriorRegion(BlockStmt block) {
        if (block.getRange().isEmpty()) {
            throw blockStatementListRecoveryFailure("block is missing a source range");
        }
        try {
            SourceRegion blockRegion = sourceText.region(block.getRange().orElseThrow());
            if (blockRegion.endOffset() - blockRegion.beginOffset() < 2) {
                throw new IllegalArgumentException("block source range is too small to contain braces");
            }
            String blockSource = sourceText.slice(blockRegion);
            if (blockSource.charAt(0) != '{' || blockSource.charAt(blockSource.length() - 1) != '}') {
                throw new IllegalArgumentException("block source range must start with '{' and end with '}'");
            }
            return sourceText.region(blockRegion.beginOffset() + 1, blockRegion.endOffset() - 1);
        } catch (IllegalArgumentException exception) {
            throw blockStatementListRecoveryFailure(exception.getMessage(), exception);
        }
    }

    private static boolean hasRawGap(RecoveredListPlanner.Plan<Statement> plan) {
        return plan.entries().stream().anyMatch(RecoveredListPlanner.RawGap.class::isInstance);
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

    private List<Doc> blockOrphanCommentStatements(BlockStmt block, List<SourceRegion> rawRegions) {
        return commentPlacement.orphanCommentsOutsideChildRanges(block, block.getStatements()).stream()
                .filter(comment -> comment.comment().getRange().isEmpty()
                        || rawRegions.stream().noneMatch(region -> RecoveredRawGapPrinter.contains(
                                region,
                                sourceText.region(comment.comment().getRange().orElseThrow()))))
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

    private static FormatterException blockStatementListRecoveryFailure(String reason) {
        return new FormatterException(BLOCK_STATEMENT_LIST_RECOVERY_FAILURE + reason);
    }

    private static FormatterException blockStatementListRecoveryFailure(String reason, Throwable cause) {
        return new FormatterException(BLOCK_STATEMENT_LIST_RECOVERY_FAILURE + reason, cause);
    }

    private enum EntryKind {
        /**
         * No recovered block content has been emitted yet, so the next printable entry opens the block body.
         */
        NONE,

        /**
         * Caller-provided or block-orphan docs were emitted before the statement list and need one hard-line separator.
         */
        LEADING_DOC,

        /**
         * The previous emitted entry was a parsed statement, so the next parsed statement can use normal separator rules.
         */
        VALID_STATEMENT,

        /**
         * The previous emitted entry was a raw recovered gap that kept its trailing line break in the raw source.
         */
        RAW_GAP,

        /**
         * The previous emitted entry was a raw recovered gap whose trailing line break was moved to formatter-owned docs.
         */
        RAW_GAP_WITH_TRAILING_BREAK
    }
}
