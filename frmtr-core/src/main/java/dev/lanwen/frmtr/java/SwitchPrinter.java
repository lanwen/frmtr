package dev.lanwen.frmtr.java;

import com.github.javaparser.GeneratedJavaParserConstants;
import com.github.javaparser.JavaToken;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import dev.lanwen.frmtr.FormatterException;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Renders switch expressions and reusable switch-entry grammar.
 *
 * <p>Statement switches are selected by {@link StatementPrinter}, and expression switches are selected by
 * {@link ExpressionDispatcher}. Both routes delegate here for selector comments, labels, guards, rule entries, statement
 * groups, and source-only fallback cases so {@code case} layout decisions remain in one place without moving
 * {@link SwitchStmt} out of ordinary statement dispatch.
 *
 * <p>Representative fixture pairs for this boundary include
 * {@code frmtr-core/src/test/resources/format/switch-statement-rules/input.java} with
 * {@code frmtr-core/src/test/resources/format/switch-statement-rules/frmtr-default.output.java},
 * {@code frmtr-core/src/test/resources/format/pattern-matching/input.java} with
 * {@code frmtr-core/src/test/resources/format/pattern-matching/frmtr-default.output.java},
 * {@code frmtr-core/src/test/resources/format/switch-expression-initializer/input.java} with
 * {@code frmtr-core/src/test/resources/format/switch-expression-initializer/frmtr-default.output.java}, and
 * {@code frmtr-core/src/test/resources/format/unnamed-variables-patterns/input.java}.
 */
final class SwitchPrinter {

    private static final String SWITCH_ENTRY_LIST_RECOVERY_FAILURE =
        "Unable to recover Java parse error inside switch entry list: ";

    private final CommentTracker comments;

    private final RawSource rawSource;

    private final SourceShapePolicy sourceShapePolicy;

    private final RawPreservedSource rawPreservedSource;

    private final FormatterOptions options;

    private final SourceText sourceText;

    private final RecoveredListPlanner recoveredListPlanner;

    private final RecoveredRawGapPrinter rawGaps;

    private final boolean recoverParseProblems;

    private final JavaFormatRule<Statement> statementRenderer;

    private final ExpressionTailRenderer expressionWithTailRenderer;

    private final JavaFormatRule<BlockStmt> blockRenderer;

    private final BiFunction<Statement, Statement, Doc> statementSeparator;

    private final ControlConditionPrinter controlConditions;

    private final Function<Expression, Doc> binaryExpressionLinesRenderer;

    private final CompactSourceText compactSource;

    private final JavaCommentPlacementPolicy commentPlacementPolicy;

    private final SourceOrderedCommentInterleaver<SwitchEntry> commentInterleaver;

    private final SourceOrderedCommentInterleaver<Statement> statementCommentInterleaver;

    private final Function<NodeWithModifiers<?>, String> modifiers;

    private final ToIntFunction<String> currentIndentedWidth;

    private final ToIntFunction<String> blockStatementWidth;

    private final SwitchCaseLabelLayout caseLabelLayout;

    /**
     * Names the body shape of a switch entry after labels, guards, and comments have been classified.
     *
     * <p>The enum keeps the statement-group versus rule-entry state machine local to switch formatting. Nested
     * statements, expressions, blocks, and raw source recovery remain delegated to their existing collaborators.
     */
    private enum SwitchEntryLayout {
        /** Render an old-style {@code case:} statement group with zero or more nested statements. */
        STATEMENT_GROUP,

        /** Render a rule entry with no body statements, preserving only the {@code ->} marker. */
        EMPTY_RULE,

        /** Render a rule entry body on the next line because its body statement owns a leading comment. */
        COMMENTED_RULE_BODY,

        /**
         * Render a rule entry body on the next line because an arrow-leading comment was re-attached off the body (to the
         * case label expression or the entry orphan bucket) by a whitespace perturbation.
         */
        RECOVERED_COMMENTED_RULE_BODY,

        /** Render a rule entry body after {@code ->} on the same line through the normal body renderer. */
        INLINE_RULE_BODY,
    }

    /**
     * Names the previous recovered switch-block item so raw gaps and formatted entries do not duplicate separators.
     */
    private enum EntryKind {
        /** No recovered switch-block content has been emitted yet. */
        NONE,

        /** The previous recovered switch-block item was a parsed switch entry rendered structurally. */
        VALID_ENTRY,

        /** The previous recovered switch-block item was a raw source gap that still owns the following separator. */
        RAW_GAP,

        /** The previous recovered switch-block item was a raw source gap whose trailing break moved to formatter docs. */
        RAW_GAP_WITH_TRAILING_BREAK,
    }

    SwitchPrinter(
            JavaFormatContext context,
            JavaFormatRule<Statement> statementRenderer,
            ExpressionTailRenderer expressionWithTailRenderer,
            JavaFormatRule<BlockStmt> blockRenderer,
            BiFunction<Statement, Statement, Doc> statementSeparator,
            ControlConditionPrinter controlConditions,
            Function<Expression, Doc> binaryExpressionLinesRenderer,
            Function<NodeWithModifiers<?>, String> modifiers,
            ToIntFunction<String> currentIndentedWidth,
            ToIntFunction<String> blockStatementWidth
    ) {
        this.comments = context.comments;
        this.rawSource = context.rawSource;
        this.sourceShapePolicy = context.sourceShapePolicy;
        this.rawPreservedSource = context.rawPreservedSource;
        this.options = context.options;
        this.sourceText = context.sourceText;
        this.recoveredListPlanner = context.recoveredListPlanner;
        this.rawGaps = new RecoveredRawGapPrinter(context, SwitchPrinter::switchEntryListRecoveryFailure);
        this.recoverParseProblems = context.recoverParseProblems;
        this.statementRenderer = statementRenderer;
        this.expressionWithTailRenderer = expressionWithTailRenderer;
        this.blockRenderer = blockRenderer;
        this.statementSeparator = statementSeparator;
        this.controlConditions = controlConditions;
        this.binaryExpressionLinesRenderer = binaryExpressionLinesRenderer;
        this.compactSource = context.compactSource;
        this.commentPlacementPolicy = context.commentPlacementPolicy;
        this.commentInterleaver = new SourceOrderedCommentInterleaver<>(context.comments);
        this.statementCommentInterleaver = new SourceOrderedCommentInterleaver<>(context.comments);
        this.modifiers = modifiers;
        this.currentIndentedWidth = currentIndentedWidth;
        this.blockStatementWidth = blockStatementWidth;
        this.caseLabelLayout = new SwitchCaseLabelLayout(
            context.rawSource,
            context.compactSource,
            context.comments,
            context.commentPlacementPolicy,
            context.sourceText,
            context.options,
            modifiers,
            currentIndentedWidth,
            this::arrowLeadingCommentBuckets
        );
    }

    /**
     * Prints a statement switch after {@link StatementRuleEnvelope} has applied pragmas and leading comment policy.
     *
     * <p>Empty switch statements keep the expanded block shape used by statement switches. Non-empty switches enter the
     * shared switch block path after the selector has been rendered by
     * the shared control-condition policy.
     */
    Doc switchStatement(SwitchStmt statement) {
        Doc prefix = switchLeadingBlockCommentPrefix(statement);
        Doc selectorTrailingLineComment = selectorTrailingLineComment(statement.getSelector());
        if (statement.getEntries().isEmpty()) {
            return Doc.concat(
                prefix,
                Doc.text("switch "),
                controlConditions.controlCondition(
                    statement.getSelector(),
                    "switch (",
                    ") {}",
                    blockStatementWidth
                ),
                switchBlockPrefix(selectorTrailingLineComment),
                Doc.text("{"),
                Doc.HARD_LINE,
                Doc.text("}")
            );
        }
        return Doc.concat(
            prefix,
            Doc.text("switch "),
            controlConditions.controlCondition(
                statement.getSelector(),
                "switch (",
                ") {}",
                blockStatementWidth
            ),
            switchBlockPrefix(selectorTrailingLineComment),
            switchBlock(statement, statement.getEntries())
        );
    }

    /**
     * Renders the {@code switch} statement's own leading block comment ({@code /* note *}{@code / switch (...)}),
     * placing it inline before {@code switch} or on its own line by source position.
     *
     * <p>{@link StatementRuleEnvelope} suppresses the shared leading slot whenever the {@code switch} statement carries an
     * own block comment, so this printer owns rendering it. The shared
     * {@link JavaCommentPlacementPolicy#ownSameLineBlockCommentBeforeNode(Node)} query only recovers the comment when it
     * shares the {@code switch} line, which a whitespace perturbation that lifts the comment onto the line above defeats:
     * the envelope still suppresses the slot, so the comment would be dropped. This local prefix instead selects the own
     * block comment by source order ({@link CommentIndex#startsBefore(Comment, Node)}) and then decides inline versus
     * own-line by line position ({@link CommentIndex#startsOnBeginLine(Comment, Node)}). At {@code @default} the comment
     * shares the {@code switch} line, so it renders inline exactly as before. The shared same-line policy is intentionally
     * left untouched because {@code catch}/{@code finally} prefixes depend on its strict same-line behavior.
     */
    private Doc switchLeadingBlockCommentPrefix(SwitchStmt statement) {
        return commentPlacementPolicy.ownComment(
                statement,
                comment -> comment.isBlock() && comment.startsBefore(statement)
            )
            .map(trivia -> {
                Doc comment = comments.comment(trivia);
                if (comment == Doc.EMPTY) {
                    return Doc.EMPTY;
                }
                return trivia.startsOnBeginLine(statement)
                    ? Doc.concat(comment, Doc.text(" "))
                    : Doc.concat(comment, Doc.HARD_LINE);
            })
            .orElse(Doc.EMPTY);
    }

    /**
     * Prints an expression switch with the same block, label, guard, and entry-body rules as switch statements.
     */
    Doc switchExpression(SwitchExpr expression) {
        Doc selectorTrailingLineComment = selectorTrailingLineComment(expression.getSelector());
        return Doc.concat(
            Doc.text("switch "),
            controlConditions.controlCondition(
                expression.getSelector(),
                "switch (",
                ") {}",
                blockStatementWidth
            ),
            switchBlockPrefix(selectorTrailingLineComment),
            switchBlock(expression, expression.getEntries())
        );
    }

    private Doc selectorTrailingLineComment(Expression selector) {
        return controlConditions.closeParenTrailingLineComment(selector);
    }

    private Doc switchBlockPrefix(Doc selectorTrailingLineComment) {
        if (selectorTrailingLineComment == Doc.EMPTY) {
            return Doc.text(" ");
        }
        return Doc.concat(Doc.text(" "), selectorTrailingLineComment, Doc.HARD_LINE);
    }

    /**
     * Prints the brace-delimited switch entry list.
     *
     * <p>Empty expression switch blocks stay compact as {@code {}}. Non-empty blocks use one required line per block
     * item.
     */
    private Doc switchBlock(Node owner, NodeList<SwitchEntry> entries) {
        Optional<RecoveredListPlanner.Plan<SwitchEntry>> recoveryPlan = recoveryPlan(owner, entries);
        if (recoveryPlan.isPresent() && hasRawGap(recoveryPlan.orElseThrow())) {
            return recoveredSwitchBlock(owner, recoveryPlan.orElseThrow());
        }
        if (entries.isEmpty()) {
            return Doc.text("{}");
        }
        return Doc.concat(
            Doc.text("{"),
            Doc.indent(Doc.concat(Doc.HARD_LINE, switchBlockBody(owner, entries))),
            Doc.HARD_LINE,
            Doc.text("}")
        );
    }

    /**
     * Renders the switch entries while restoring switch-owner orphan comments that source placed between the brace and an
     * entry (or between entries), such as a {@code // keep first detail} note stacked before a {@code case}.
     *
     * <p>JavaParser parks a leading comment cluster before a {@code case} as orphans of the {@code switch} (or, when the
     * whitespace is reshaped, mis-attaches one to the selector), and {@link #switchEntryLeadingComments} only recovers
     * the part still adjacently attached to the entry — so the rest was dropped once the layout moved. Interleaving the
     * switch's orphan comments with the entries in source order restores them before the entry they precede, shape
     * independently. Claim-coupling keeps a comment {@code switchEntryLeadingComments} already emitted from rendering
     * twice.
     *
     * <p>The interleaved set also includes the line comments JavaParser mis-attaches to the selector under collapse: a
     * note stacked before the first {@code case} that shares the selector's line becomes the selector's own trailing
     * trivia even though it belongs before the entry. {@link #selectorLeadingEntryComments} recovers it by source
     * position (after the selector, before the first entry), so it interleaves like the switch's own orphans.
     */
    private Doc switchBlockBody(Node owner, NodeList<SwitchEntry> entries) {
        List<JavaCommentTrivia> orphanComments = new ArrayList<>(
            commentPlacementPolicy.orphanCommentsOutsideChildRanges(owner, entries)
        );
        orphanComments.addAll(selectorLeadingEntryComments(owner, entries));
        if (orphanComments.isEmpty()) {
            return Doc.join(Doc.HARD_LINE, entries.stream().map(this::switchEntry).toList());
        }
        return Doc.concat(
            commentInterleaver.interleave(
                owner,
                entries,
                orphanComments,
                (previousSibling, current, index) -> Optional.of(switchEntry(current)),
                new SourceOrderedCommentInterleaver.Spacing<>() {
                    @Override
                    public int beginLine(SwitchEntry sibling) {
                        return CommentIndex.beginLine(sibling, Integer.MAX_VALUE);
                    }

                    @Override
                    public int endLine(SwitchEntry sibling) {
                        return CommentIndex.endLine(sibling, beginLine(sibling));
                    }

                    @Override
                    public Doc separatorBeforeSibling(
                            SourceOrderedCommentInterleaver.PreviousEntry<SwitchEntry> previous,
                            SwitchEntry currentSibling
                    ) {
                        return Doc.HARD_LINE;
                    }

                    @Override
                    public Doc separatorBeforeComment(
                            SourceOrderedCommentInterleaver.PreviousEntry<SwitchEntry> previous,
                            JavaCommentTrivia comment
                    ) {
                        return Doc.HARD_LINE;
                    }
                }
            )
        );
    }

    /**
     * Recovers the line comments JavaParser parked on the selector that source placed before the first entry.
     *
     * <p>Under collapse a note stacked before the first {@code case} that shares the selector's line is re-bucketed onto
     * the selector as its own trailing trivia. Selecting the selector's own and orphan line comments that lie in the
     * source-order gap after the selector ends and before the first entry begins keeps them owned by the entry list,
     * regardless of which bucket the parser used. At the {@code @default} shape the selector owns no such comment (the
     * notes are switch orphans), so this contributes nothing and {@code @default} output is unchanged.
     */
    private List<JavaCommentTrivia> selectorLeadingEntryComments(Node owner, NodeList<SwitchEntry> entries) {
        if (entries.isEmpty()) {
            return List.of();
        }
        return selector(owner)
                .map(selector -> commentPlacementPolicy.gapLineCommentsBefore(
                    selector,
                    entries.get(0),
                    List.of(selector)
                ))
                .orElseGet(List::of);
    }

    private Optional<Expression> selector(Node owner) {
        if (owner instanceof SwitchStmt statement) {
            return Optional.of(statement.getSelector());
        }
        if (owner instanceof SwitchExpr expression) {
            return Optional.of(expression.getSelector());
        }
        return Optional.empty();
    }

    /**
     * Emits a recovered switch block while keeping the selector and braces formatter-owned.
     *
     * <p>Raw gaps are limited to the brace interior selected from the switch token range. Safe entry siblings still use
     * the ordinary switch-entry renderer, and unsafe entries preserve their original source as raw islands.
     */
    private Doc recoveredSwitchBlock(
            Node owner,
            RecoveredListPlanner.Plan<SwitchEntry> plan
    ) {
        List<RecoveredRawGapPrinter.RawGapRegion> rawGapRegions = rawGaps.rawGapRegions(plan);
        rawGaps.requireRecoverableRawRegions(owner, rawGapRegions);

        List<Doc> contents = new ArrayList<>();
        EntryKind previousEntry = EntryKind.NONE;

        int rawGapIndex = 0;
        for (RecoveredListPlanner.Entry<SwitchEntry> entry : plan.entries()) {
            switch (entry) {
                case RecoveredListPlanner.ValidSibling<?> valid -> {
                    SwitchEntry currentEntry = (SwitchEntry) valid.sibling();
                    appendSeparatorBeforeRecoveredSwitchEntry(contents, previousEntry);
                    contents.add(switchEntry(currentEntry));
                    previousEntry = EntryKind.VALID_ENTRY;
                }
                case RecoveredListPlanner.RawGap<?> ignored -> {
                    RecoveredRawGapPrinter.RawGapRegion rawRegion = rawGapRegions.get(rawGapIndex++);
                    if (rawRegion.region().beginOffset() < rawRegion.region().endOffset()) {
                        contents.add(rawGaps.raw(owner, rawRegion, "switchEntryList"));
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
            case NONE, VALID_ENTRY, RAW_GAP_WITH_TRAILING_BREAK -> Doc.HARD_LINE;
        };
        return Doc.concat(Doc.text("{"), Doc.indent(Doc.concat(contents)), closingBreak, Doc.text("}"));
    }

    private void appendSeparatorBeforeRecoveredSwitchEntry(
            List<Doc> contents,
            EntryKind previousEntry
    ) {
        if (contents.isEmpty()) {
            contents.add(Doc.HARD_LINE);
            return;
        }
        switch (previousEntry) {
            case VALID_ENTRY -> contents.add(Doc.HARD_LINE);
            case RAW_GAP_WITH_TRAILING_BREAK -> contents.add(Doc.HARD_LINE);
            case NONE, RAW_GAP -> {
                // Raw source already owns the separation before this formatted entry.
            }
        }
    }

    private Optional<RecoveredListPlanner.Plan<SwitchEntry>> recoveryPlan(
            Node owner,
            NodeList<SwitchEntry> entries
    ) {
        if (!recoverParseProblems || !hasRecoverableSwitchEntryListProblem(owner, entries)) {
            return Optional.empty();
        }
        RecoveredListPlanner.Plan<SwitchEntry> plan = recoveredListPlanner.plan(
            owner,
            requireSwitchBlockInteriorRegion(owner),
            entries,
            entry -> entry.getParsed() == Node.Parsedness.PARSED
        );
        if (!plan.isSafe()) {
            throw switchEntryListRecoveryFailure(plan.unsafe().orElseThrow().reason());
        }
        return Optional.of(plan);
    }

    private SourceRegion requireSwitchBlockInteriorRegion(Node owner) {
        try {
            return switchBlockInteriorRegion(owner);
        } catch (IllegalArgumentException exception) {
            throw switchEntryListRecoveryFailure(exception.getMessage(), exception);
        }
    }

    private SourceRegion switchBlockInteriorRegion(Node owner) {
        List<JavaToken> tokens = tokens(owner);
        int openingBraceIndex = switchBlockOpeningBraceIndex(tokens);
        JavaToken openingBrace = tokens.get(openingBraceIndex);
        JavaToken closingBrace = switchBlockClosingBrace(tokens, openingBraceIndex);
        SourceRegion openingRegion = tokenRegion(openingBrace, "opening brace");
        SourceRegion closingRegion = tokenRegion(closingBrace, "closing brace");
        if (closingRegion.beginOffset() < openingRegion.endOffset()) {
            throw new IllegalArgumentException("switch block braces are not ordered");
        }
        return sourceText.region(openingRegion.endOffset(), closingRegion.beginOffset());
    }

    private List<JavaToken> tokens(Node owner) {
        return owner.getTokenRange()
                .map(tokenRange -> {
                    List<JavaToken> collected = new ArrayList<>();
                    tokenRange.forEach(collected::add);
                    return collected;
                })
                .orElseThrow(() -> new IllegalArgumentException("switch node is missing a token range"));
    }

    private int switchBlockOpeningBraceIndex(List<JavaToken> tokens) {
        int parenDepth = 0;
        boolean sawSwitch = false;
        for (int i = 0; i < tokens.size(); i++) {
            JavaToken token = tokens.get(i);
            if (token.getKind() == GeneratedJavaParserConstants.SWITCH) {
                sawSwitch = true;
                continue;
            }
            if (!sawSwitch) {
                continue;
            }
            if (token.getKind() == GeneratedJavaParserConstants.LPAREN) {
                parenDepth++;
                continue;
            }
            if (token.getKind() == GeneratedJavaParserConstants.RPAREN) {
                parenDepth--;
                continue;
            }
            if (token.getKind() == GeneratedJavaParserConstants.LBRACE && parenDepth == 0) {
                return i;
            }
        }
        throw new IllegalArgumentException("switch block source range is missing an opening brace");
    }

    private JavaToken switchBlockClosingBrace(List<JavaToken> tokens, int openingBraceIndex) {
        int braceDepth = 0;
        for (int i = openingBraceIndex; i < tokens.size(); i++) {
            JavaToken token = tokens.get(i);
            if (token.getKind() == GeneratedJavaParserConstants.LBRACE) {
                braceDepth++;
                continue;
            }
            if (token.getKind() == GeneratedJavaParserConstants.RBRACE) {
                braceDepth--;
                if (braceDepth == 0) {
                    return token;
                }
            }
        }
        throw new IllegalArgumentException("switch block source range is missing a closing brace");
    }

    private SourceRegion tokenRegion(JavaToken token, String description) {
        return token.getRange()
                .map(sourceText::region)
                .orElseThrow(
                    () -> new IllegalArgumentException("switch block " + description + " is missing a source range")
                );
    }

    private static boolean hasRawGap(RecoveredListPlanner.Plan<SwitchEntry> plan) {
        return plan.entries().stream().anyMatch(RecoveredListPlanner.RawGap.class::isInstance);
    }

    /**
     * Prints one switch entry, including entry-attached comments and the statement-group versus rule branch.
     *
     * <p>Java's old {@code case:} statement group syntax owns a list of nested statements, while rule entries use
     * {@code case ->} and normally own a single expression, statement, or block body. This method keeps that split
     * local to switch rendering and delegates any nested statement or expression body to the existing renderers.
     */
    private Doc switchEntry(SwitchEntry entry) {
        Doc leadingComment = switchEntryLeadingComments(entry);
        Doc trailingComment = comments.ownTriviaComment(
            entry,
            commentNode -> commentNode.isLine()
                    && commentNode.startsOnBeginLine(entry)
        );
        if (trailingComment == Doc.EMPTY) {
            Optional<Doc> raw = rawSingleLineSwitchEntry(entry);
            if (raw.isPresent()) {
                return Doc.concat(leadingComment, raw.orElseThrow());
            }
        }
        Doc label = caseLabelLayout.switchEntryLabel(entry);
        Doc guard = switchEntryGuard(entry);
        Doc entryDoc = switch (switchEntryLayout(entry)) {
            case STATEMENT_GROUP -> switchStatementGroupEntry(
                entry,
                label,
                guard,
                entry.getStatements(),
                caseLabelTrailingComment(entry),
                caseLabelLeadingBodyComments(entry)
            );
            case EMPTY_RULE -> Doc.concat(label, guard, Doc.text(" ->"));
            case COMMENTED_RULE_BODY -> commentedRuleBody(label, guard, entry.getStatements().get(0));
            case RECOVERED_COMMENTED_RULE_BODY -> recoveredCommentedRuleBody(label, guard, entry);
            case INLINE_RULE_BODY -> Doc.concat(
                label,
                guard,
                Doc.text(" -> "),
                switchEntryBody(entry.getStatements().get(0))
            );
        };
        entryDoc = trailingComment == Doc.EMPTY ? entryDoc : Doc.concat(entryDoc, Doc.text(" "), trailingComment);
        return Doc.concat(leadingComment, entryDoc);
    }

    private Doc commentedRuleBody(Doc label, Doc guard, Statement statement) {
        return Doc.concat(
            label,
            guard,
            Doc.text(" ->"),
            Doc.indent(Doc.concat(Doc.HARD_LINE, statementRenderer.format(statement, LayoutContext.root())))
        );
    }

    /**
     * Renders a {@code case x -> // note body} rule arm whose arrow-leading line comment a whitespace perturbation moved
     * off the body statement (where {@link #commentedRuleBody} would have rendered it) and onto the case label expression
     * or the entry orphan bucket.
     *
     * <p>The recovered comment renders on its own indented line after {@code ->}, exactly as the body-owned comment does,
     * so the arm matches the {@code @default} shape byte for byte while the body renders inline through the ordinary body
     * renderer (the body no longer owns the comment in this shape).
     */
    private Doc recoveredCommentedRuleBody(Doc label, Doc guard, SwitchEntry entry) {
        Statement statement = entry.getStatements().get(0);
        List<Doc> commentLines = new ArrayList<>();
        for (Doc comment : ruleArrowLeadingComments(entry, statement)) {
            commentLines.add(comment);
            commentLines.add(Doc.HARD_LINE);
        }
        return Doc.concat(
            label,
            guard,
            Doc.text(" ->"),
            Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.concat(commentLines), switchEntryBody(statement)))
        );
    }

    /**
     * Recovers the arrow-leading line comments of a rule entry from the buckets a whitespace perturbation re-attaches them
     * to: the comma-separated case label expressions and the entry's own orphan comments. Bounded to the gap after the
     * guard (or last label) and before the body so guard-internal or body-internal comments stay with their owners.
     */
    private List<Doc> ruleArrowLeadingComments(SwitchEntry entry, Statement body) {
        Optional<Node> afterNode = ruleArrowGapStart(entry);
        if (afterNode.isEmpty()) {
            return List.of();
        }
        return comments.gapLineCommentsBefore(afterNode.orElseThrow(), body, arrowLeadingCommentBuckets(entry));
    }

    private Optional<Node> ruleArrowGapStart(SwitchEntry entry) {
        if (entry.getGuard().isPresent()) {
            return entry.getGuard().map(Node.class::cast);
        }
        return entry.getLabels().getLast().map(Node.class::cast);
    }

    /**
     * Recovers a contiguous line-comment cluster before a {@code case} label even when JavaParser splits the cluster
     * between switch-level orphan comments and the entry's own leading comment.
     *
     * <p>The adjacent line-cluster recovery is line-only, so a standalone block or Javadoc comment that leads a
     * {@code case} ({@code /* note *}{@code / case X:}) lands in neither the cluster nor the line-only own-comment
     * fallback and was dropped. {@link #switchEntryLeadingBlockComment} closes that gap: it offers the entry's own
     * non-line leading comment, bounded by source order to comments that begin before the {@code case} keyword so inline
     * label block comments ({@code case REMOTE /* remote *}{@code /}) stay owned by {@link SwitchCaseLabelLayout#commentPreservingCaseLabel}.
     */
    private Doc switchEntryLeadingComments(SwitchEntry entry) {
        Doc leadingComments = comments.adjacentLeadingLineComments(entry);
        if (leadingComments != Doc.EMPTY) {
            return leadingComments;
        }
        Doc leadingComment = comments.ownTriviaComment(
            entry,
            commentNode -> commentNode.isLine()
                    && commentNode.startsBeforeBeginLine(entry)
        );
        if (leadingComment == Doc.EMPTY) {
            leadingComment = switchEntryLeadingBlockComment(entry);
        }
        if (leadingComment == Doc.EMPTY) {
            return Doc.EMPTY;
        }
        return Doc.concat(leadingComment, Doc.HARD_LINE);
    }

    /**
     * Offers the entry's own block or Javadoc comment that source placed before the {@code case} label.
     *
     * <p>Restricted to non-line own comments (the line case is already covered) that begin before the entry in source
     * order, which excludes inline label block comments such as {@code case REMOTE /* remote *}{@code /} (those begin
     * after the {@code case} keyword); those remain owned by {@link SwitchCaseLabelLayout#commentPreservingCaseLabel}, so this contributes
     * nothing where that path already renders. The source-order bound is shape-independent: it recovers the comment both
     * at the {@code @default} shape (own line above the {@code case}) and under a collapse that pulls it onto the
     * {@code case} line, where a line-based bound would lose it.
     */
    private Doc switchEntryLeadingBlockComment(SwitchEntry entry) {
        return comments.ownTriviaComment(
            entry,
            commentNode -> !commentNode.isLine()
                    && commentNode.startsBefore(entry)
        );
    }

    /**
     * Offers the line comment(s) that trail a colon {@code case}/{@code default} label on the same source line as the label
     * (or label list), before the statement-group body ({@code case 2: // note}).
     *
     * <p>JavaParser attaches such a comment to the label expression (the {@code @default}/collapsed shape) or to the
     * entry's orphan bucket, never to the entry's own trivia, so the entry-scoped trailing slot in {@link #switchEntry}
     * never sees it and it was dropped. This recovers it from the label/entry buckets bounded to the gap after the label
     * and before the body (see {@link #caseLabelGapLineComments}), which by construction excludes the body statement's own
     * leading comment (the statement renderer prints that) and any later entry's leading comment (it lies past this entry's
     * body).
     *
     * <p>The gap recovery is partitioned by whether each comment begins on the label's own source line
     * ({@link #caseLabelLine(SwitchEntry)} — the last label's line for {@code case X:}, the entry's begin line for
     * {@code default:}). Only the same-line comments genuinely trail the label the way the author wrote them, so only those
     * are offered here; they are claimed under the entry's trailing slot exactly once and rendered as a
     * {@link Doc#lineSuffix(Doc)} by {@link #switchStatementGroupEntry}, flushing at the colon line's break with the body
     * laid out on the next line. A {@code //} comment block the author placed on its own line(s) below the label is instead
     * a leading comment of the body; {@link #caseLabelLeadingBodyComments} owns those so the printer no longer hoists the
     * first such line onto the {@code case} line and joins it with the rest below it (issue #88). The partition is what
     * keeps both shapes correct: a genuine trailing comment begins on the label line, an own-line leading block begins on a
     * later line. Only old-style {@code case:}/{@code default:} statement groups reach this path; rule entries keep their
     * own trailing handling.
     */
    private Doc caseLabelTrailingComment(SwitchEntry entry) {
        int labelLine = caseLabelLine(entry);
        List<Doc> commentDocs = caseLabelGapLineComments(entry)
                .stream()
                .filter(comment -> comment.beginLine(Integer.MIN_VALUE) == labelLine)
                .map(comment -> comments.comment(comment, entry, OwnerSlot.TRAILING))
                .filter(doc -> doc != Doc.EMPTY)
                .toList();
        return commentDocs.isEmpty() ? Doc.EMPTY : Doc.join(Doc.text(" "), commentDocs);
    }

    /**
     * Offers the leading {@code //} comment block JavaParser parked in the gap after a colon {@code case}/{@code default}
     * label and before the statement-group body, when the author placed it on its own line(s) below the label rather than
     * trailing the label line ({@code case X:} then {@code // note} on the next line).
     *
     * <p>This is the own-line counterpart of {@link #caseLabelTrailingComment}: it consumes the same gap recovery but keeps
     * only the comments that do <em>not</em> begin on the label's own line ({@link #caseLabelLine(SwitchEntry)}).
     * JavaParser splits such a block between the label expression (which owns the first line) and the body statement (which
     * owns the rest), or — under a whitespace perturbation that pushes a genuine trailing comment onto its own line — parks
     * the single line on the label expression or the entry orphan pool. In every such shape the comment is not the body
     * statement's own leading trivia, so the body renderer never prints it; left unclaimed here it was dropped, and the
     * older code that hoisted it onto the {@code case} line corrupted its attachment (issue #88). Rendering the recovered
     * comments as their own lines at the statement indent before the body, claimed once under the entry's leading slot,
     * places them exactly where leading comments render elsewhere and keeps the whole block together. The body's own
     * leading comments still render through {@link #switchEntryStatements}; the gap recovery already excludes those (it
     * never returns the body's own comment), so the two sets do not overlap.
     */
    private List<Doc> caseLabelLeadingBodyComments(SwitchEntry entry) {
        int labelLine = caseLabelLine(entry);
        return caseLabelGapLineComments(entry)
                .stream()
                .filter(comment -> comment.beginLine(Integer.MIN_VALUE) != labelLine)
                .map(comment -> comments.comment(comment, entry, OwnerSlot.LEADING))
                .filter(doc -> doc != Doc.EMPTY)
                .toList();
    }

    /**
     * Returns the source line the entry's label sits on so the gap recovery can tell a genuine trailing comment (same
     * line) from an own-line leading block (later line): the last label's begin line for {@code case X:}, the entry's begin
     * line for the label-less {@code default:}.
     */
    private int caseLabelLine(SwitchEntry entry) {
        Node anchor = entry.getLabels().getLast().map(Node.class::cast).orElse(entry);
        return CommentIndex.beginLine(anchor, Integer.MAX_VALUE);
    }

    /**
     * Recovers the line comments JavaParser parked between a statement-group label's colon and the body, choosing the
     * anchor by label kind: {@code case X:} entries bound the gap by the last label expression
     * ({@link JavaCommentPlacementPolicy#gapLineCommentsBefore}), while the label-less {@code default:} bounds it by the
     * entry's own begin position ({@link JavaCommentPlacementPolicy#defaultLabelGapLineCommentsBefore}) because there is no
     * label node to anchor on and {@code liesBetween(entry, body)} would match nothing.
     */
    private List<JavaCommentTrivia> caseLabelGapLineComments(SwitchEntry entry) {
        Node body = entry.getStatements().isEmpty() ? entry : entry.getStatements().get(0);
        if (entry.getLabels().isEmpty()) {
            if (body == entry) {
                return List.of();
            }
            return commentPlacementPolicy.defaultLabelGapLineCommentsBefore(
                entry,
                body,
                arrowLeadingCommentBuckets(entry)
            );
        }
        Node afterNode = entry.getLabels().getLast().map(Node.class::cast).orElseThrow();
        if (afterNode == body) {
            return List.of();
        }
        return commentPlacementPolicy.gapLineCommentsBefore(afterNode, body, arrowLeadingCommentBuckets(entry));
    }

    private SwitchEntryLayout switchEntryLayout(SwitchEntry entry) {
        if (entry.getType() == SwitchEntry.Type.STATEMENT_GROUP) {
            return SwitchEntryLayout.STATEMENT_GROUP;
        }
        if (entry.getStatements().isEmpty()) {
            return SwitchEntryLayout.EMPTY_RULE;
        }
        Statement body = entry.getStatements().get(0);
        if (hasLeadingOwnComment(body)) {
            return SwitchEntryLayout.COMMENTED_RULE_BODY;
        }
        if (hasRecoverableArrowLeadingComment(entry, body)) {
            return SwitchEntryLayout.RECOVERED_COMMENTED_RULE_BODY;
        }
        return SwitchEntryLayout.INLINE_RULE_BODY;
    }

    private boolean hasRecoverableArrowLeadingComment(SwitchEntry entry, Statement body) {
        return !commentPlacementPolicy
                .gapLineCommentsBefore(
                    ruleArrowGapStart(entry).orElse(body),
                    body,
                    arrowLeadingCommentBuckets(entry)
                )
                .isEmpty();
    }

    private List<Node> arrowLeadingCommentBuckets(SwitchEntry entry) {
        List<Node> buckets = new ArrayList<>(entry.getLabels());
        buckets.add(entry);
        return buckets;
    }

    /**
     * Prints a {@code when} guard, wrapping enclosed or too-wide guards as a parenthesized binary-expression block.
     *
     * <p>The raw single-line fallback stays flat for source-only entries, so those entries are excluded from guard
     * wrapping. Ordinary long guards delegate their internal expression layout back to the binary-expression renderer.
     */
    private Doc switchEntryGuard(SwitchEntry entry) {
        if (entry.getGuard().isEmpty()) {
            return Doc.EMPTY;
        }
        Expression guard = entry.getGuard().orElseThrow();
        String flat = " when " + compactSource.compact(guard);
        if (!switchGuardBreaks(entry, guard, flat)) {
            return Doc.text(flat);
        }
        Expression guardedExpression = guard instanceof EnclosedExpr enclosedExpr ? enclosedExpr.getInner() : guard;
        return Doc.concat(
            Doc.text(" when ("),
            Doc.indent(Doc.concat(Doc.HARD_LINE, binaryExpressionLinesRenderer.apply(guardedExpression))),
            Doc.HARD_LINE,
            Doc.text(")")
        );
    }

    private boolean switchGuardBreaks(SwitchEntry entry, Expression guard, String flat) {
        String label = "case "
            + entry.getLabels()
                    .stream()
                    .map(caseLabelLayout::switchLabelText)
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("");
        return (
            guard instanceof EnclosedExpr
            || (switchEntryWidth(label + flat + " -> {}") >= options.lineWidth()
                && !rawSingleLineSwitchEntry(entry).isPresent())
        );
    }

    private int switchEntryWidth(String text) {
        return (options.indentUnit().length() * 3) + text.length();
    }

    private Doc switchStatementGroupEntry(
            SwitchEntry entry,
            Doc label,
            Doc guard,
            NodeList<Statement> statements,
            Doc labelTrailingComment,
            List<Doc> labelLeadingBodyComments
    ) {
        Doc trailing = labelTrailingComment == Doc.EMPTY
            ? Doc.EMPTY
            : Doc.lineSuffix(Doc.concat(Doc.text(" "), labelTrailingComment));
        if (statements.size() == 1 && statements.get(0).isBlockStmt() && labelLeadingBodyComments.isEmpty()) {
            return Doc.concat(
                label,
                guard,
                Doc.text(": "),
                trailing,
                switchStatementGroupBlock(statements.get(0).asBlockStmt())
            );
        }
        return Doc.concat(
            label,
            guard,
            Doc.text(":"),
            trailing,
            switchEntryStatements(entry, statements, labelLeadingBodyComments)
        );
    }

    /**
     * Prints an explicit block after an old-style {@code case:} label.
     *
     * <p>Empty blocks keep the expanded two-line brace shape used by the original switch formatter, while non-empty
     * blocks are ordinary statement blocks and stay delegated to {@link BlockPrinter}.
     */
    private Doc switchStatementGroupBlock(BlockStmt block) {
        if (block.getStatements().isEmpty() && block.getOrphanComments().isEmpty()) {
            return Doc.concat(Doc.text("{"), Doc.HARD_LINE, Doc.text("}"));
        }
        return blockRenderer.format(block, LayoutContext.root());
    }

    private boolean hasLeadingOwnComment(Statement statement) {
        return statement.getComment()
                .map(JavaCommentTrivia::from)
                .filter(comment -> comment.startsBeforeBeginLine(statement))
                .isPresent();
    }

    /**
     * Preserves single-line rule entries whose source contains switch syntax JavaParser cannot rebuild structurally.
     *
     * <p>Comments inside a rule label/body and labels such as {@code null, default} are source-only enough that the safe
     * formatter action is to keep the original single-line entry. Ordinary entries that merely fit on one line still use
     * structured printing so comments and nested bodies stay under normal formatter ownership.
     */
    private Optional<Doc> rawSingleLineSwitchEntry(SwitchEntry entry) {
        if (entry.getType() == SwitchEntry.Type.STATEMENT_GROUP) {
            return Optional.empty();
        }
        String raw = entry
                .getTokenRange()
                .map(Object::toString)
                .orElseGet(() -> rawSource.rawWithoutOwnComment(entry))
                .stripTrailing();
        if (!raw.contains("->") || raw.contains("\n")) {
            return Optional.empty();
        }
        boolean preservesSourceOnlySyntax = raw.contains("/*") || raw.contains("null, default");
        if (!preservesSourceOnlySyntax && currentIndentedWidth.applyAsInt(raw) <= options.lineWidth()) {
            return Optional.empty();
        }
        if (!preservesSourceOnlySyntax) {
            return Optional.empty();
        }
        return Optional.of(rawPreservedSource.raw(entry, raw));
    }

    private Doc switchEntryBody(Statement statement) {
        if (statement.isBlockStmt()) {
            return switchRuleBlock(statement.asBlockStmt());
        }
        if (statement instanceof ExpressionStmt expressionStmt) {
            return expressionWithTailRenderer.render(
                expressionStmt.getExpression(),
                ExpressionTail.SEMICOLON,
                currentIndentedWidth
            );
        }
        return Doc.concat(statementRenderer.format(statement, LayoutContext.root()));
    }

    /**
     * Prints the block body of a rule entry after {@code ->}.
     *
     * <p>Rule-entry empty blocks stay compact as {@code {}} so no-op arrow rules keep their source-level visual weight.
     * Any real statement or orphan comment inside the block returns to normal block rendering.
     */
    private Doc switchRuleBlock(BlockStmt block) {
        if (block.getStatements().isEmpty() && block.getOrphanComments().isEmpty()) {
            return Doc.text("{}");
        }
        return blockRenderer.format(block, LayoutContext.root());
    }

    /**
     * Lays out a {@code case:} statement group's nested statements at the entry indent, prepending any
     * {@code labelLeadingBodyComments} (the own-line {@code //} block the author placed under the label, recovered by
     * {@link #caseLabelLeadingBodyComments}) as their own lines before the first statement so the whole leading block stays
     * together above the body, matching how leading comments render elsewhere. When the entry has no nested statement the
     * recovered comment block is the only body content, so it is laid out on its own indented line(s) without a trailing
     * break.
     *
     * <p>The nested statements are sequenced through {@link SourceOrderedCommentInterleaver} exactly like an ordinary
     * statement block ({@code BlockPrinter}). The interleaver is what restores a standalone {@code //} comment the author
     * placed between two statements in the case body: JavaParser parks such a comment as the {@code switch} entry's orphan
     * trivia rather than as either neighbour's own trivia, so the plain statement-separator loop never offered it to a
     * {@link CommentTracker} claim slot and it was dropped — most visibly when a blank line after the comment detaches it
     * from the following statement (issue #133). Claim-coupling keeps the comments already placed by
     * {@link #caseLabelTrailingComment} or {@link #caseLabelLeadingBodyComments} from rendering twice. Blank-line spacing
     * between statements and around restored comments stays source-driven, matching the regular block path.
     */
    private Doc switchEntryStatements(
            SwitchEntry entry,
            NodeList<Statement> statements,
            List<Doc> labelLeadingBodyComments
    ) {
        if (statements.isEmpty()) {
            if (labelLeadingBodyComments.isEmpty()) {
                return Doc.EMPTY;
            }
            return Doc.indent(
                Doc.concat(Doc.HARD_LINE, Doc.join(Doc.HARD_LINE, labelLeadingBodyComments))
            );
        }
        List<Doc> docs = switchEntryStatementContents(entry, statements);
        Doc leading = labelLeadingBodyComments.isEmpty()
            ? Doc.EMPTY
            : Doc.concat(Doc.join(Doc.HARD_LINE, labelLeadingBodyComments), Doc.HARD_LINE);
        return Doc.indent(Doc.concat(Doc.HARD_LINE, leading, Doc.concat(docs)));
    }

    /**
     * Returns the entry orphan comments that belong <em>between or after</em> the case body statements, dropping any that
     * begin before the first statement.
     *
     * <p>JavaParser parks a {@code default /*def*}{@code /:} block comment and an own-line {@code //} block stacked under
     * the label in the same entry orphan bucket as a genuine inter-statement comment. The label-region comments are
     * already rendered by the label printer ({@link SwitchCaseLabelLayout#defaultSwitchEntryLabel} / {@link SwitchCaseLabelLayout#commentPreservingCaseLabel}, which
     * use raw token text and therefore never mark a {@link CommentTracker} claim) and by
     * {@link #caseLabelLeadingBodyComments}. Bounding the interleaved set to comments that do not start before the first
     * statement keeps the body interleaver from re-emitting a label-region comment a second time while still restoring the
     * inter-statement orphan that the plain separator loop dropped (issue #133).
     */
    private List<JavaCommentTrivia> interStatementOrphanComments(SwitchEntry entry, NodeList<Statement> statements) {
        List<JavaCommentTrivia> orphans = commentPlacementPolicy.orphanCommentsOutsideChildRanges(entry, statements);
        if (statements.isEmpty()) {
            return orphans;
        }
        Statement firstStatement = statements.get(0);
        return orphans.stream()
                .filter(comment -> !comment.startsBefore(firstStatement))
                .toList();
    }

    /**
     * Interleaves the case body's statements with the {@code switch} entry's orphan comments by source line, reusing the
     * statement-block separator policy so blank lines and restored inter-statement comments space the same way they do in
     * a plain block. Orphan comments that fall inside a nested statement's range stay with that statement's renderer; only
     * comments the parser left outside every statement range are restored here.
     */
    private List<Doc> switchEntryStatementContents(SwitchEntry entry, NodeList<Statement> statements) {
        return statementCommentInterleaver.interleave(
            entry,
            statements,
            interStatementOrphanComments(entry, statements),
            (previous, current, index) -> Optional.of(statementRenderer.format(current, LayoutContext.root())),
            new SourceOrderedCommentInterleaver.Spacing<>() {
                @Override
                public int beginLine(Statement sibling) {
                    return CommentIndex.beginLine(sibling, Integer.MAX_VALUE);
                }

                @Override
                public int endLine(Statement sibling) {
                    return CommentIndex.endLine(sibling, beginLine(sibling));
                }

                @Override
                public Doc separatorBeforeSibling(
                        SourceOrderedCommentInterleaver.PreviousEntry<Statement> previous,
                        Statement currentSibling
                ) {
                    if (previous.kind() == SourceOrderedCommentInterleaver.EntryKind.SIBLING) {
                        return statementSeparator.apply(previous.sibling().orElseThrow(), currentSibling);
                    }
                    return switchEntrySourceLineSeparator(previous.endLine(), beginLine(currentSibling));
                }

                @Override
                public Doc separatorBeforeComment(
                        SourceOrderedCommentInterleaver.PreviousEntry<Statement> previous,
                        JavaCommentTrivia comment
                ) {
                    return switchEntrySourceLineSeparator(previous.endLine(), comment.beginLine(Integer.MAX_VALUE));
                }
            }
        );
    }

    /**
     * Chooses a single or blank-line separator from the source line gap, used when an interleaved orphan comment sits
     * beside a case-body statement so a blank line the author kept around the comment survives, matching the regular
     * statement block's source-driven spacing.
     */
    private Doc switchEntrySourceLineSeparator(int previousEndLine, int currentBeginLine) {
        if (previousEndLine == Integer.MIN_VALUE || currentBeginLine == Integer.MAX_VALUE) {
            return Doc.HARD_LINE;
        }
        return currentBeginLine > previousEndLine + 1 ? Doc.concat(Doc.HARD_LINE, Doc.HARD_LINE) : Doc.HARD_LINE;
    }

    static boolean hasRecoverableSwitchEntryListProblem(SwitchStmt statement) {
        return hasRecoverableSwitchEntryListProblem(statement, statement.getEntries());
    }

    static boolean hasRecoverableSwitchEntryListProblem(SwitchExpr expression) {
        return hasRecoverableSwitchEntryListProblem(expression, expression.getEntries());
    }

    static boolean isRecoverableSwitchEntryListSibling(SwitchEntry entry) {
        return entry.getParentNode()
                .map(parent -> {
                    if (parent instanceof SwitchStmt statement) {
                        return hasRecoverableSwitchEntryListProblem(statement);
                    }
                    if (parent instanceof SwitchExpr expression) {
                        return hasRecoverableSwitchEntryListProblem(expression);
                    }
                    return false;
                })
                .orElse(false);
    }

    static Optional<SwitchEntry> nearestSwitchEntryListSibling(Node recoveredNode) {
        Optional<Node> current = Optional.of(recoveredNode);
        while (current.isPresent()) {
            Node node = current.orElseThrow();
            if (node instanceof SwitchEntry entry && isSwitchEntryListSibling(entry)) {
                return Optional.of(entry);
            }
            current = node.getParentNode();
        }
        return Optional.empty();
    }

    static boolean isCollapsedMalformedSwitchStatement(
            Statement statement,
            SourceText sourceText
    ) {
        if (statement.getParsed() == Node.Parsedness.PARSED || statement.getRange().isEmpty()) {
            return false;
        }
        try {
            SourceRegion statementRegion = sourceText.region(statement.getRange().orElseThrow());
            return startsWithSwitchKeyword(
                sourceText.slice(statementRegion)
            ) || isAfterSwitchKeywordTrivia(sourceText, statementRegion.beginOffset());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean hasRecoverableSwitchEntryListProblem(
            Node owner,
            NodeList<SwitchEntry> entries
    ) {
        return (
            owner.getParsed() == Node.Parsedness.PARSED
            && entries.stream().anyMatch(entry -> !isFullyParsed(entry))
            && owner.stream()
                    .filter(node -> node != owner)
                    .filter(node -> node.getParsed() != Node.Parsedness.PARSED)
                    .allMatch(node -> nearestSwitchEntryListSibling(node).filter(entries::contains).isPresent())
        );
    }

    private static boolean isSwitchEntryListSibling(SwitchEntry entry) {
        return entry.getParentNode()
                .filter(parent -> {
                    if (parent instanceof SwitchStmt statement) {
                        return statement.getEntries().contains(entry);
                    }
                    if (parent instanceof SwitchExpr expression) {
                        return expression.getEntries().contains(entry);
                    }
                    return false;
                })
                .isPresent();
    }

    private static boolean isFullyParsed(Node node) {
        return node.stream().allMatch(descendant -> descendant.getParsed() == Node.Parsedness.PARSED);
    }

    private static boolean isAfterSwitchKeywordTrivia(
            SourceText sourceText,
            int statementBeginOffset
    ) {
        String prefix = sourceText.slice(sourceText.region(0, statementBeginOffset));
        int cursor = skipBackwardTrivia(prefix, prefix.length());
        int switchBegin = cursor - "switch".length();
        return switchBegin >= 0
            && prefix.regionMatches(switchBegin, "switch", 0, "switch".length())
            && isKeywordBoundary(prefix, switchBegin - 1)
            && isKeywordBoundary(prefix, cursor);
    }

    private static boolean startsWithSwitchKeyword(String source) {
        String stripped = source.stripLeading();
        return stripped.startsWith("switch") && isKeywordBoundary(stripped, "switch".length());
    }

    private static int skipBackwardTrivia(String source, int cursor) {
        int current = cursor;
        while (current > 0) {
            current = skipBackwardWhitespace(source, current);
            if (current >= 2 && source.charAt(current - 2) == '*' && source.charAt(current - 1) == '/') {
                int commentBegin = source.lastIndexOf("/*", current - 2);
                if (commentBegin < 0) {
                    return current;
                }
                current = commentBegin;
                continue;
            }
            int lineStart = Math.max(source.lastIndexOf('\n', current - 1), source.lastIndexOf('\r', current - 1)) + 1;
            int lineCommentBegin = source.lastIndexOf("//", current - 1);
            if (lineCommentBegin >= lineStart) {
                current = lineCommentBegin;
                continue;
            }
            return current;
        }
        return current;
    }

    private static int skipBackwardWhitespace(String source, int cursor) {
        int current = cursor;
        while (current > 0 && Character.isWhitespace(source.charAt(current - 1))) {
            current--;
        }
        return current;
    }

    private static boolean isKeywordBoundary(String source, int index) {
        return index < 0 || index >= source.length() || !Character.isJavaIdentifierPart(source.charAt(index));
    }

    private static FormatterException switchEntryListRecoveryFailure(String reason) {
        return new FormatterException(SWITCH_ENTRY_LIST_RECOVERY_FAILURE + reason);
    }

    private static FormatterException switchEntryListRecoveryFailure(String reason, Throwable cause) {
        return new FormatterException(SWITCH_ENTRY_LIST_RECOVERY_FAILURE + reason, cause);
    }
}
