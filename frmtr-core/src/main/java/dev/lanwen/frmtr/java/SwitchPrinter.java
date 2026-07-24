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
     * <p>{@link StatementRuleEnvelope} suppresses the shared leading slot for a {@code switch} carrying an own block
     * comment, so this printer owns it. It selects by source order (not the shared same-line query, which drops the
     * comment when a whitespace perturbation lifts it onto the line above) and chooses inline vs own-line by line
     * position — inline at {@code @default}. The shared same-line policy stays untouched because {@code catch}/
     * {@code finally} prefixes depend on its strict same-line behavior.
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
     * <p>Interleaving the switch's orphan comments (plus the notes {@link #selectorLeadingEntryComments} recovers from
     * the selector under collapse) with the entries in source order restores them before the entry they precede, shape
     * independently — {@link #switchEntryLeadingComments} alone recovers only the part still adjacent to the entry.
     * Claim-coupling keeps an already-emitted comment from rendering twice.
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
     * <p>Under collapse a note before the first {@code case} sharing the selector's line becomes the selector's trailing
     * trivia; selecting the selector's own/orphan line comments in the source-order gap between selector and first entry
     * keeps them owned by the entry list whichever bucket the parser used. Contributes nothing at {@code @default} (the
     * notes are switch orphans there), so that shape is unchanged.
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
        SwitchCaseLabelLayout.CaseLabel label = caseLabelLayout.switchEntryLabel(entry);
        Doc guard = switchEntryGuard(entry);
        EntrySuffix suffix = switchEntrySuffix(entry, guard);
        Doc entryDoc = switch (label) {
            case SwitchCaseLabelLayout.CaseLabel.Fixed(Doc doc) ->
                Doc.concat(doc, suffix.opener(), suffix.body());
            // Rank the flat one-liner and the wrapped label list at the entry's true column: only the header (labels +
            // guard + arrow/colon opener) rides both arms, so the flat/wrapped choice turns purely on whether the header
            // line fits. The body follows the best-fitting node — which measures node-locally — so an over-width body line
            // can never sway the header toward staying flat and over-width.
            case SwitchCaseLabelLayout.CaseLabel.Ranked(Doc flat, Doc wrapped) -> Doc.concat(
                Doc.bestFitting(
                    List.of(Doc.concat(flat, suffix.opener()), Doc.concat(wrapped, suffix.opener()))
                ),
                suffix.body()
            );
        };
        entryDoc = trailingComment == Doc.EMPTY ? entryDoc : Doc.concat(entryDoc, Doc.text(" "), trailingComment);
        return Doc.concat(leadingComment, entryDoc);
    }

    /**
     * The header-line content the label-wrap decision measures ({@code opener}: guard + the {@code ->}/{@code :} marker,
     * a block arm's opening brace, or an inline expression that shares the case line) and the content rendered below it
     * ({@code body}: a block's interior and closing brace, colon-group statements, or a next-line rule body).
     *
     * <p>The split lets {@link #switchEntry} rank the case-label wrap on the header alone — {@code body} is emitted after
     * the ranked best-fitting node, which measures node-locally, so an over-width line in a below-the-header body cannot
     * push the labels toward staying flat and over-width. A body that genuinely lands on the case line (a rule
     * expression) stays in {@code opener} so the wrap still accounts for it.
     */
    private record EntrySuffix(Doc opener, Doc body) {}

    /**
     * Splits an entry after its label into the header opener (guard + arrow/colon marker + any block opening brace) and
     * the trailing body, so the label-wrap decision measures the header only. The body's comments are built here exactly
     * once — {@link #switchEntry} shares this one body across both ranked label arms rather than rebuilding it.
     */
    private EntrySuffix switchEntrySuffix(SwitchEntry entry, Doc guard) {
        return switch (switchEntryLayout(entry)) {
            case STATEMENT_GROUP -> statementGroupSuffix(entry, guard);
            case EMPTY_RULE -> new EntrySuffix(Doc.concat(guard, Doc.text(" ->")), Doc.EMPTY);
            case COMMENTED_RULE_BODY -> new EntrySuffix(
                Doc.concat(guard, Doc.text(" ->")),
                Doc.indent(Doc.concat(
                    Doc.HARD_LINE,
                    statementRenderer.format(entry.getStatements().get(0), LayoutContext.root())
                ))
            );
            case RECOVERED_COMMENTED_RULE_BODY -> recoveredCommentedRuleBodySuffix(guard, entry);
            case INLINE_RULE_BODY -> inlineRuleBodySuffix(guard, entry.getStatements().get(0));
        };
    }

    private EntrySuffix statementGroupSuffix(SwitchEntry entry, Doc guard) {
        NodeList<Statement> statements = entry.getStatements();
        Doc labelTrailingComment = caseLabelTrailingComment(entry);
        List<Doc> labelLeadingBodyComments = caseLabelLeadingBodyComments(entry);
        Doc trailing = labelTrailingComment == Doc.EMPTY
            ? Doc.EMPTY
            : Doc.lineSuffix(Doc.concat(Doc.text(" "), labelTrailingComment));
        if (statements.size() == 1 && statements.get(0).isBlockStmt() && labelLeadingBodyComments.isEmpty()) {
            return new EntrySuffix(
                Doc.concat(guard, Doc.text(": {")),
                Doc.concat(trailing, blockAfterOpeningBrace(switchStatementGroupBlock(statements.get(0).asBlockStmt())))
            );
        }
        return new EntrySuffix(
            Doc.concat(guard, Doc.text(":")),
            Doc.concat(trailing, switchEntryStatements(entry, statements, labelLeadingBodyComments))
        );
    }

    private EntrySuffix inlineRuleBodySuffix(Doc guard, Statement statement) {
        if (statement.isBlockStmt()) {
            Doc block = switchRuleBlock(statement.asBlockStmt());
            // An empty block collapses to {} on the case line, so — like an inline expression — the whole opener stays
            // on the measured header; only a non-empty block drops its interior and closing brace below the header.
            if (block instanceof Doc.Text(String value) && value.equals("{}")) {
                return new EntrySuffix(Doc.concat(guard, Doc.text(" -> {}")), Doc.EMPTY);
            }
            return new EntrySuffix(Doc.concat(guard, Doc.text(" -> {")), blockAfterOpeningBrace(block));
        }
        // A non-block body renders on the header line right after {@code -> }, so it belongs to the measured header, not
        // the outside-body slot: the label wrap must account for the expression that shares the case line with it.
        return new EntrySuffix(
            Doc.concat(guard, Doc.text(" -> "), switchEntryBody(statement)),
            Doc.EMPTY
        );
    }

    /**
     * Renders a {@code case x -> // note body} rule arm whose arrow-leading line comment a whitespace perturbation moved
     * off the body statement (where the {@code COMMENTED_RULE_BODY} suffix renders it) and onto the case label or entry
     * orphan bucket. The recovered comment renders on its own indented line after {@code ->} like the body-owned comment,
     * so the arm matches the {@code @default} shape byte for byte while the body renders inline.
     */
    private EntrySuffix recoveredCommentedRuleBodySuffix(Doc guard, SwitchEntry entry) {
        Statement statement = entry.getStatements().get(0);
        List<Doc> commentLines = new ArrayList<>();
        for (Doc comment : ruleArrowLeadingComments(entry, statement)) {
            commentLines.add(comment);
            commentLines.add(Doc.HARD_LINE);
        }
        return new EntrySuffix(
            Doc.concat(guard, Doc.text(" ->")),
            Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.concat(commentLines), switchEntryBody(statement)))
        );
    }

    /**
     * Re-slices an already-built rule/statement-group block into its interior plus closing brace, dropping the leading
     * opening brace so {@link #switchEntrySuffix} can carry {@code {} on the measured header line and keep the body
     * outside the header's width decision. The block renderer always emits the opening brace first, so this only trims a
     * finished Doc — the body's statements and comments are untouched and still render exactly once.
     */
    private static Doc blockAfterOpeningBrace(Doc block) {
        if (block instanceof Doc.Text(String value) && value.startsWith("{")) {
            return Doc.text(value.substring(1));
        }
        if (
            block instanceof Doc.Concat(List<Doc> docs)
            && !docs.isEmpty()
            && docs.get(0) instanceof Doc.Text(String value)
            && value.equals("{")
        ) {
            return Doc.concat(docs.subList(1, docs.size()));
        }
        throw new IllegalStateException("switch entry block did not start with an opening brace");
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
     * Recovers a contiguous line-comment cluster before a {@code case} label even when JavaParser splits it between
     * switch-level orphans and the entry's own leading comment. A standalone block/Javadoc lead
     * ({@code /* note *}{@code / case X:}) falls outside the line-only cluster, so {@link #switchEntryLeadingBlockComment}
     * offers the entry's non-line leading comment (bounded to before the {@code case} keyword, leaving inline label block
     * comments {@code case REMOTE /* remote *}{@code /} to {@link SwitchCaseLabelLayout#commentPreservingCaseLabel}).
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
     * <p>Restricted to non-line own comments (line comments are already covered) beginning before the entry in source
     * order, which excludes inline label block comments ({@code case REMOTE /* remote *}{@code /}, owned by
     * {@link SwitchCaseLabelLayout#commentPreservingCaseLabel}). The source-order bound is shape-independent: it recovers
     * the comment whether it sits above the {@code case} or collapses onto its line, where a line-based bound would lose
     * it.
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
     * <p>JavaParser buckets such a comment onto the label expression or the entry orphans, never the entry's own trivia,
     * so {@link #switchEntry}'s trailing slot would drop it. This recovers it from the gap after the label and before the
     * body ({@link #caseLabelGapLineComments}, which excludes the body's and later entries' own leading comments),
     * partitioned by {@link #caseLabelLine(SwitchEntry)}: only comments on the label's line genuinely trail it and are
     * offered here (claimed once, rendered as a {@link Doc#lineSuffix(Doc)} by {@link #switchStatementGroupEntry}); a
     * {@code //} block on lines below the label is a leading comment of the body, owned by
     * {@link #caseLabelLeadingBodyComments} rather than hoisted onto the {@code case} line. Only old-style
     * {@code case:}/{@code default:} statement groups reach this; rule entries keep their own trailing handling.
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
     * label when the author placed it on its own line(s) below the label ({@code case X:} then {@code // note} on the next
     * line), rather than trailing the label line.
     *
     * <p>The own-line counterpart of {@link #caseLabelTrailingComment}: same gap recovery, but keeps only comments that do
     * <em>not</em> begin on the label's line ({@link #caseLabelLine(SwitchEntry)}). In every parser shape these are not the
     * body's own trivia, so the body renderer never prints them; left unclaimed they drop, and hoisting them onto the
     * {@code case} line corrupts the attachment. Rendering them once as their own indented lines before the body places
     * them where leading comments render elsewhere. Does not overlap the body's own leading comments
     * ({@link #switchEntryStatements}), which the gap recovery already excludes.
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
     * <p>Statements are sequenced through {@link SourceOrderedCommentInterleaver} like an ordinary block; the interleaver
     * restores a standalone inter-statement {@code //} comment that JavaParser parked as entry orphan trivia, which a
     * plain separator loop would drop — most visibly when a blank line detaches it from the next statement.
     * Claim-coupling keeps comments already placed by {@link #caseLabelTrailingComment}/{@link #caseLabelLeadingBodyComments}
     * from rendering twice; blank-line spacing stays source-driven, matching the regular block path.
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
     * <p>JavaParser buckets label-region comments (a {@code default /*def*}{@code /:} block, an own-line block under the
     * label) alongside genuine inter-statement comments. The label-region ones are already rendered by the label printer
     * ({@link SwitchCaseLabelLayout#defaultSwitchEntryLabel}/{@link SwitchCaseLabelLayout#commentPreservingCaseLabel}) and
     * {@link #caseLabelLeadingBodyComments}, so bounding to comments not before the first statement avoids re-emitting them
     * while still restoring the otherwise-dropped inter-statement orphan.
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
