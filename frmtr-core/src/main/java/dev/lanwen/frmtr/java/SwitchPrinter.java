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

    private final CommentPlacement commentPlacement;

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
        this.commentPlacement = context.commentPlacement;
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
        Doc leadingBlockComment = commentPlacement.ownSameLineBlockCommentBeforeNode(statement);
        Doc prefix = leadingBlockComment == Doc.EMPTY ? Doc.EMPTY : Doc.concat(leadingBlockComment, Doc.text(" "));
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
        List<Doc> entryDocs = new ArrayList<>();
        entryDocs.addAll(entries.stream().map(this::switchEntry).toList());
        return Doc.concat(
            Doc.text("{"),
            Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.join(Doc.HARD_LINE, entryDocs))),
            Doc.HARD_LINE,
            Doc.text("}")
        );
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
            case STATEMENT_GROUP -> switchStatementGroupEntry(label, guard, entry.getStatements());
            case EMPTY_RULE -> Doc.concat(label, guard, Doc.text(" ->"));
            case COMMENTED_RULE_BODY -> {
                Statement statement = entry.getStatements().get(0);
                yield Doc.concat(
                    label,
                    guard,
                    Doc.text(" ->"),
                    Doc.indent(Doc.concat(Doc.HARD_LINE, statementRenderer.format(statement)))
                );
            }
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

    /**
     * Recovers a contiguous line-comment cluster before a {@code case} label even when JavaParser splits the cluster
     * between switch-level orphan comments and the entry's own leading comment.
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
            return Doc.EMPTY;
        }
        return Doc.concat(leadingComment, Doc.HARD_LINE);
    }

    private SwitchEntryLayout switchEntryLayout(SwitchEntry entry) {
        if (entry.getType() == SwitchEntry.Type.STATEMENT_GROUP) {
            return SwitchEntryLayout.STATEMENT_GROUP;
        }
        if (entry.getStatements().isEmpty()) {
            return SwitchEntryLayout.EMPTY_RULE;
        }
        if (hasLeadingOwnComment(entry.getStatements().get(0))) {
            return SwitchEntryLayout.COMMENTED_RULE_BODY;
        }
        return SwitchEntryLayout.INLINE_RULE_BODY;
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

    private Doc switchStatementGroupEntry(Doc label, Doc guard, NodeList<Statement> statements) {
        if (statements.size() == 1 && statements.get(0).isBlockStmt()) {
            return Doc.concat(
                label,
                guard,
                Doc.text(": "),
                switchStatementGroupBlock(statements.get(0).asBlockStmt())
            );
        }
        return Doc.concat(label, guard, Doc.text(":"), switchEntryStatements(statements));
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
