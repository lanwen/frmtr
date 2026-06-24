package dev.lanwen.frmtr.java;

import com.github.javaparser.GeneratedJavaParserConstants;
import com.github.javaparser.JavaToken;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.RecordPatternExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.expr.TypePatternExpr;
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

    private final Function<NodeWithModifiers<?>, String> modifiers;

    private final ToIntFunction<String> currentIndentedWidth;

    private final ToIntFunction<String> blockStatementWidth;

    /**
     * Names the switch-label layouts after the caller has already selected structured switch-entry printing.
     *
     * <p>The enum owns only the local label shape. It does not decide whether an entry is raw-preserved, whether guards
     * wrap, or how the entry body renders; those choices stay with the surrounding switch-entry pipeline.
     */
    private enum SwitchLabelLayout {
        /** Keep the complete {@code case ...} label on one line because it fits or a single label does not need wrapping. */
        FLAT,

        /** Wrap one record-pattern label after {@code case} because the pattern itself exceeds the available width. */
        SINGLE_WRAPPED_LABEL,

        /** Put each label on its own line because a comma-separated label list exceeds the available width. */
        WRAPPED_LABEL_LIST,
    }

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
        this.modifiers = modifiers;
        this.currentIndentedWidth = currentIndentedWidth;
        this.blockStatementWidth = blockStatementWidth;
    }

    /**
     * Prints a statement switch after {@link StatementRuleEnvelope} has applied pragmas and leading comment policy.
     *
     * <p>Empty switch statements keep the legacy expanded block shape because statement switches used that shape before
     * this helper existed. Non-empty switches enter the shared switch block path after the selector has been rendered by
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
        Doc label = switchEntryLabel(entry);
        Doc guard = switchEntryGuard(entry);
        Doc entryDoc = switch (switchEntryLayout(entry)) {
            case STATEMENT_GROUP -> switchStatementGroupEntry(
                label,
                guard,
                entry.getStatements(),
                caseLabelTrailingComment(entry)
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
            Doc.indent(Doc.concat(Doc.HARD_LINE, statementRenderer.format(statement)))
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
     * label block comments ({@code case REMOTE /* remote *}{@code /}) stay owned by {@link #commentPreservingCaseLabel}.
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
     * after the {@code case} keyword); those remain owned by {@link #commentPreservingCaseLabel}, so this contributes
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
     * Offers the line comment(s) that trail a colon {@code case} label after the label list and before the
     * statement-group body ({@code case 2: // note}).
     *
     * <p>JavaParser attaches such a comment to the label expression (the {@code @default}/collapsed shape) or, under a
     * whitespace perturbation that lifts it onto its own line, to the entry's orphan bucket, never to the entry's own
     * trivia, so the entry-scoped trailing slot in {@link #switchEntry} never sees it and it was dropped. This recovers it
     * shape-independently from the label/entry buckets bounded to the gap after the last label and before the body
     * (see {@link JavaCommentPlacementPolicy#gapLineCommentsBefore}), which by construction excludes the body statement's
     * own leading comment (the statement renderer prints that) and any later entry's leading comment (it lies past this
     * entry's body). Each recovered comment is claimed under the entry's trailing slot exactly once and rendered as a
     * {@link Doc#lineSuffix(Doc)} by {@link #switchStatementGroupEntry}, so it flushes at the colon line's break and the
     * body still lays out on the next line, whatever line source put the comment on. Only old-style {@code case:}/
     * {@code default:} statement groups reach this path; rule entries keep their own trailing handling.
     */
    private Doc caseLabelTrailingComment(SwitchEntry entry) {
        Optional<Node> lastLabel = entry.getLabels().getLast().map(Node.class::cast);
        Node afterNode = lastLabel.orElse(entry);
        Node body = entry.getStatements().isEmpty() ? entry : entry.getStatements().get(0);
        if (afterNode == body) {
            return Doc.EMPTY;
        }
        List<Doc> commentDocs = commentPlacementPolicy
                .gapLineCommentsBefore(afterNode, body, arrowLeadingCommentBuckets(entry))
                .stream()
                .map(comment -> comments.comment(comment, entry, OwnerSlot.TRAILING))
                .filter(doc -> doc != Doc.EMPTY)
                .toList();
        return commentDocs.isEmpty() ? Doc.EMPTY : Doc.join(Doc.text(" "), commentDocs);
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
     * Prints the {@code case} or {@code default} label before a switch entry's guard and arrow/colon.
     *
     * <p>Default labels may include source-only text such as comments before {@code default}; case labels can stay flat,
     * wrap as one record pattern, or wrap as one label per line when the comma-separated label list is too wide.
     */
    private Doc switchEntryLabel(SwitchEntry entry) {
        if (entry.isDefault()) {
            return Doc.text(defaultSwitchEntryLabel(entry));
        }
        Optional<Doc> commented = commentPreservingCaseLabel(entry);
        if (commented.isPresent()) {
            return commented.orElseThrow();
        }
        String flatLabels = entry.getLabels()
                .stream()
                .map(this::switchLabelText)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        String flat = "case " + flatLabels;
        return switch (switchLabelLayout(entry, flat)) {
            case FLAT -> Doc.text(flat);
            case SINGLE_WRAPPED_LABEL -> Doc.concat(Doc.text("case "), switchLabel(entry.getLabels().get(0)));
            case WRAPPED_LABEL_LIST -> Doc.concat(
                Doc.text("case"),
                Doc.indent(
                    Doc.concat(
                        Doc.HARD_LINE,
                        Doc.join(
                            Doc.concat(Doc.text(","), Doc.HARD_LINE),
                            entry.getLabels().stream().map(label -> Doc.text(switchLabelText(label))).toList()
                        )
                    )
                )
            );
        };
    }

    /**
     * Renders a {@code case} label list that carries inline block comments ({@code case REMOTE /* remote *}{@code /,
     * HYBRID}) from its raw commented token text, preserving the comments structured rendering would otherwise strip.
     *
     * <p>At the {@code @default} shape a single-line entry with such a comment is preserved verbatim by
     * {@link #rawSingleLineSwitchEntry}; once a whitespace perturbation spreads the entry across lines, that raw path no
     * longer fires and the comma-separated label list is rebuilt token by token, dropping the comments because
     * {@link #switchLabelText} renders only the label expression. This path keeps the comments by rebuilding the label
     * region with {@link CommentedTokenText#tokenLine}, which reproduces the source spacing of inline label comments, and
     * accounts those comments as raw-rendered so the print-once guardrails still see them. Labels without block comments
     * never enter this path, so ordinary case labels are unaffected.
     */
    private Optional<Doc> commentPreservingCaseLabel(SwitchEntry entry) {
        Node boundary = entry.getStatements().isEmpty() ? entry : entry.getStatements().get(0);
        List<JavaCommentTrivia> labelComments =
            commentPlacementPolicy.blockCommentsBefore(arrowLeadingCommentBuckets(entry), boundary)
                    .stream()
                    .filter(comment -> !comment.startsBefore(entry))
                    .toList();
        if (labelComments.isEmpty()) {
            return Optional.empty();
        }
        String raw = rawSource.raw(entry);
        int boundaryIndex = defaultLabelBoundary(raw);
        if (boundaryIndex < 0) {
            return Optional.empty();
        }
        String labelText = CommentedTokenText.tokenLine(CommentedTokenText.tokens(raw.substring(0, boundaryIndex)));
        if (labelText.isEmpty()) {
            return Optional.empty();
        }
        List<JavaCommentTrivia> renderedLabelComments = labelComments.stream()
                .filter(comment -> beginsWithinLabelRegion(entry, comment, boundaryIndex))
                .toList();
        if (renderedLabelComments.isEmpty()) {
            return Optional.empty();
        }
        comments.accountRaw(renderedLabelComments.stream().map(JavaCommentTrivia::comment).toList());
        return Optional.of(Doc.text(labelText));
    }

    /**
     * Reports whether a label block comment begins inside the rebuilt label region (the raw token text before the
     * arrow/colon), so accounting it matches what {@code labelText} actually renders.
     *
     * <p>{@link JavaCommentPlacementPolicy#blockCommentsBefore} bounds its result by the body node, so it can include a
     * comment parked in the arrow-to-body gap ({@code case X -> /* mid *}{@code / body}) that {@code labelText} never
     * reproduces. Mapping the comment's absolute source offset to the same coordinate space as {@code boundaryIndex}
     * (a character index into {@link RawSource#raw(Node)}, i.e. relative to the entry's stripped token range) and
     * keeping only comments before the boundary makes the accounted set equal the rendered set by construction.
     */
    private boolean beginsWithinLabelRegion(SwitchEntry entry, JavaCommentTrivia comment, int boundaryIndex) {
        return entry.getRange()
                .map(range -> sourceText.region(range).beginOffset())
                .flatMap(entryRawBeginOffset -> commentBeginOffset(comment)
                    .map(commentBeginOffset -> commentBeginOffset - entryRawBeginOffset < boundaryIndex))
                .orElse(false);
    }

    private Optional<Integer> commentBeginOffset(JavaCommentTrivia comment) {
        return comment.comment()
                .getRange()
                .map(range -> sourceText.region(range).beginOffset());
    }

    private SwitchLabelLayout switchLabelLayout(SwitchEntry entry, String flat) {
        if (entry.getLabels().size() == 1 && !switchLabelBreaks(entry.getLabels().get(0))) {
            return SwitchLabelLayout.FLAT;
        }
        if (currentIndentedWidth.applyAsInt(flat + " -> {}") <= options.lineWidth()) {
            return SwitchLabelLayout.FLAT;
        }
        if (entry.getLabels().size() == 1) {
            return SwitchLabelLayout.SINGLE_WRAPPED_LABEL;
        }
        return SwitchLabelLayout.WRAPPED_LABEL_LIST;
    }

    /**
     * Reports whether a record-pattern label needs its own wrapped pattern rendering.
     */
    private boolean switchLabelBreaks(Expression label) {
        return label instanceof RecordPatternExpr
            && currentIndentedWidth.applyAsInt("case " + switchLabelText(label) + " -> {}") > options.lineWidth();
    }

    /**
     * Prints a single label, wrapping record patterns whose type and component list cannot fit flat.
     */
    private Doc switchLabel(Expression label) {
        if (label instanceof RecordPatternExpr recordPattern && switchLabelBreaks(label)) {
            return recordPattern(recordPattern);
        }
        return Doc.text(switchLabelText(label));
    }

    private String switchLabelText(Expression label) {
        if (label instanceof TypePatternExpr) {
            return rawSource.normalizeWhitespace(label.toString());
        }
        if (label instanceof RecordPatternExpr) {
            return rawSource.normalizeWhitespace(label.toString());
        }
        return compactSource.compact(label);
    }

    private String defaultSwitchEntryLabel(SwitchEntry entry) {
        String raw = rawSource.raw(entry);
        int boundary = defaultLabelBoundary(raw);
        if (boundary < 0) {
            return "default";
        }
        String label = CommentedTokenText.tokenLine(CommentedTokenText.tokens(raw.substring(0, boundary)));
        return label.isEmpty() ? "default" : label;
    }

    private int defaultLabelBoundary(String raw) {
        int colon = raw.indexOf(':');
        int arrow = raw.indexOf("->");
        if (colon < 0) {
            return arrow;
        }
        if (arrow < 0) {
            return colon;
        }
        return Math.min(colon, arrow);
    }

    /**
     * Prints a record-pattern label with one component per line when the flat label is wider than the line budget.
     *
     * <p>Pattern labels use normalized raw text for compact cases because JavaParser's ordinary expression printer does
     * not preserve all source-only pattern spelling. Once the label wraps, this method keeps the type and modifiers in
     * the normal type pipeline and recursively wraps nested record patterns that are also too wide.
     */
    private Doc recordPattern(RecordPatternExpr pattern) {
        return Doc.concat(
            Doc.text(modifiers.apply(pattern) + compactSource.compactTypeLike(pattern.getType()) + "("),
            Doc.indent(
                Doc.concat(
                    Doc.HARD_LINE,
                    Doc.join(
                        Doc.concat(Doc.text(","), Doc.HARD_LINE),
                        pattern.getPatternList()
                                .stream()
                                .map(this::recordPatternComponent)
                                .toList()
                    )
                )
            ),
            Doc.HARD_LINE,
            Doc.text(")")
        );
    }

    private Doc recordPatternComponent(Expression pattern) {
        if (pattern instanceof RecordPatternExpr recordPattern && switchLabelBreaks(pattern)) {
            return recordPattern(recordPattern);
        }
        return Doc.text(switchLabelText(pattern));
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
                    .map(this::switchLabelText)
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
            Doc label,
            Doc guard,
            NodeList<Statement> statements,
            Doc labelTrailingComment
    ) {
        Doc trailing = labelTrailingComment == Doc.EMPTY
            ? Doc.EMPTY
            : Doc.lineSuffix(Doc.concat(Doc.text(" "), labelTrailingComment));
        if (statements.size() == 1 && statements.get(0).isBlockStmt()) {
            return Doc.concat(
                label,
                guard,
                Doc.text(": "),
                trailing,
                switchStatementGroupBlock(statements.get(0).asBlockStmt())
            );
        }
        return Doc.concat(label, guard, Doc.text(":"), trailing, switchEntryStatements(statements));
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
        return blockRenderer.format(block);
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
            return expressionWithTailRenderer.render(expressionStmt.getExpression(), ExpressionTail.SEMICOLON);
        }
        return Doc.concat(statementRenderer.format(statement));
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
        return blockRenderer.format(block);
    }

    private Doc switchEntryStatements(NodeList<Statement> statements) {
        if (statements.isEmpty()) {
            return Doc.EMPTY;
        }
        List<Doc> docs = new ArrayList<>();
        Statement previous = null;
        for (Statement current : statements) {
            if (previous != null) {
                docs.add(statementSeparator.apply(previous, current));
            }
            docs.add(statementRenderer.format(current));
            previous = current;
        }
        return Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.concat(docs)));
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
