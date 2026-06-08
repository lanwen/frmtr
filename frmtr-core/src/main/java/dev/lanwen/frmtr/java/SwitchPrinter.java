package dev.lanwen.frmtr.java;

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
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Renders switch statements, switch expressions, labels, guards, and entry bodies as one switch grammar slice.
 *
 * <p>JavaParser exposes {@link SwitchStmt} through the statement tree and {@link SwitchExpr} through the expression
 * tree, but their blocks share the same labels, guards, rule entries, statement groups, and source-only fallback cases.
 * Keeping those branches together avoids splitting {@code case} layout decisions across statement and expression
 * helpers. {@link StatementDispatcher} owns the outer statement pragma/raw/comment gate before statement switches reach
 * this helper, while {@link JavaPrinter} still wires expression dispatch, raw source helpers, ordinary statement
 * formatting, expression formatting, type formatting, and block formatting. This helper only chooses the switch-specific
 * structure after those outer owners have selected formatted output.
 *
 * <p>Representative fixture pairs for this boundary include
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/switch/input.java} with
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/switch/frmtr.output.java},
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/pattern-matching/input.java} with
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/pattern-matching/frmtr.output.java},
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/yield-statement/input.java} with
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/yield-statement/frmtr.output.java}, and
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/unnamed-variables-and-patterns/input.java}.
 */
final class SwitchPrinter {
    private final CommentTracker comments;
    private final RawSource rawSource;
    private final FormatterOptions options;
    private final JavaFormatRule<Statement> statementRenderer;
    private final JavaFormatRule<Expression> expressionRenderer;
    private final JavaFormatRule<BlockStmt> blockRenderer;
    private final BiFunction<Statement, Statement, Doc> statementSeparator;
    private final Function<Expression, Doc> controlConditionRenderer;
    private final Function<Expression, Doc> binaryExpressionLinesRenderer;
    private final CompactSourceText compactSource;
    private final CommentPlacement commentPlacement;
    private final Function<NodeWithModifiers<?>, String> modifiers;
    private final ToIntFunction<String> currentIndentedWidth;

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
        WRAPPED_LABEL_LIST
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
        INLINE_RULE_BODY
    }

    SwitchPrinter(
            JavaFormatContext context,
            JavaFormatRule<Statement> statementRenderer,
            JavaFormatRule<Expression> expressionRenderer,
            JavaFormatRule<BlockStmt> blockRenderer,
            BiFunction<Statement, Statement, Doc> statementSeparator,
            Function<Expression, Doc> controlConditionRenderer,
            Function<Expression, Doc> binaryExpressionLinesRenderer,
            Function<NodeWithModifiers<?>, String> modifiers,
            ToIntFunction<String> currentIndentedWidth) {
        this.comments = context.comments;
        this.rawSource = context.rawSource;
        this.options = context.options;
        this.statementRenderer = statementRenderer;
        this.expressionRenderer = expressionRenderer;
        this.blockRenderer = blockRenderer;
        this.statementSeparator = statementSeparator;
        this.controlConditionRenderer = controlConditionRenderer;
        this.binaryExpressionLinesRenderer = binaryExpressionLinesRenderer;
        this.compactSource = context.compactSource;
        this.commentPlacement = context.commentPlacement;
        this.modifiers = modifiers;
        this.currentIndentedWidth = currentIndentedWidth;
    }

    /**
     * Prints a statement switch after {@link StatementDispatcher} has applied pragmas and leading comment policy.
     *
     * <p>Empty switch statements keep the legacy expanded block shape because statement switches used that shape before
     * this helper existed. Non-empty switches enter the shared switch block path, with selector line comments inserted
     * as the first item inside the block so they stay on their own line before the first entry.
     */
    Doc switchStatement(SwitchStmt statement) {
        Doc leadingBlockComment = commentPlacement.ownSameLineBlockCommentBeforeNode(statement);
        Doc prefix = leadingBlockComment == Doc.EMPTY ? Doc.EMPTY : Doc.concat(leadingBlockComment, Doc.text(" "));
        if (statement.getEntries().isEmpty()) {
            return Doc.concat(
                    prefix,
                    Doc.text("switch "),
                    controlConditionRenderer.apply(statement.getSelector()),
                    Doc.text(" {"),
                    Doc.HARD_LINE,
                    Doc.text("}"));
        }
        Doc selectorLineComment = comments.ownTriviaComment(statement.getSelector(), JavaCommentTrivia::isLine);
        return Doc.concat(
                prefix,
                Doc.text("switch "),
                controlConditionRenderer.apply(statement.getSelector()),
                Doc.text(" "),
                switchBlock(statement.getEntries(), selectorLineComment));
    }

    /**
     * Prints an expression switch with the same block, label, guard, and entry-body rules as switch statements.
     */
    Doc switchExpression(SwitchExpr expression) {
        return Doc.concat(
                Doc.text("switch (" + compactSource.compact(expression.getSelector()) + ") "),
                switchBlock(expression.getEntries()));
    }

    private Doc switchBlock(NodeList<SwitchEntry> entries) {
        return switchBlock(entries, Doc.EMPTY);
    }

    /**
     * Prints the brace-delimited switch entry list.
     *
     * <p>Empty expression switch blocks stay compact as {@code {}}. Non-empty blocks, or statement switches with a
     * selector line comment passed as {@code leadingInside}, use one required line per block item.
     */
    private Doc switchBlock(NodeList<SwitchEntry> entries, Doc leadingInside) {
        if (entries.isEmpty()) {
            return Doc.text("{}");
        }
        List<Doc> entryDocs = new ArrayList<>();
        if (leadingInside != Doc.EMPTY) {
            entryDocs.add(leadingInside);
        }
        entryDocs.addAll(entries.stream().map(this::switchEntry).toList());
        return Doc.concat(
                Doc.text("{"),
                Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.join(Doc.HARD_LINE, entryDocs))),
                Doc.HARD_LINE,
                Doc.text("}"));
    }

    /**
     * Prints one switch entry, including entry-attached comments and the statement-group versus rule branch.
     *
     * <p>Java's old {@code case:} statement group syntax owns a list of nested statements, while rule entries use
     * {@code case ->} and normally own a single expression, statement, or block body. This method keeps that split
     * local to switch rendering and delegates any nested statement or expression body to the existing renderers.
     */
    private Doc switchEntry(SwitchEntry entry) {
        Doc leadingComment = comments.ownTriviaComment(entry, commentNode -> commentNode.isLine()
                && commentNode.startsBeforeBeginLine(entry));
        if (leadingComment != Doc.EMPTY) {
            leadingComment = Doc.concat(leadingComment, Doc.HARD_LINE);
        }
        Doc trailingComment = comments.ownTriviaComment(entry, commentNode -> commentNode.isLine()
                && commentNode.startsOnBeginLine(entry));
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
                        Doc.indent(Doc.concat(Doc.HARD_LINE, statementRenderer.format(statement))));
            }
            case INLINE_RULE_BODY -> Doc.concat(
                    label,
                    guard,
                    Doc.text(" -> "),
                    switchEntryBody(entry.getStatements().get(0)));
        };
        entryDoc = trailingComment == Doc.EMPTY ? entryDoc : Doc.concat(entryDoc, Doc.text(" "), trailingComment);
        return Doc.concat(leadingComment, entryDoc);
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
        String flatLabels = entry.getLabels().stream()
                .map(this::switchLabelText)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        String flat = "case " + flatLabels;
        return switch (switchLabelLayout(entry, flat)) {
            case FLAT -> Doc.text(flat);
            case SINGLE_WRAPPED_LABEL -> Doc.concat(Doc.text("case "), switchLabel(entry.getLabels().get(0)));
            case WRAPPED_LABEL_LIST -> Doc.concat(
                    Doc.text("case"),
                    Doc.indent(Doc.concat(
                            Doc.HARD_LINE,
                            Doc.join(
                                    Doc.concat(Doc.text(","), Doc.HARD_LINE),
                                    entry.getLabels().stream().map(label -> Doc.text(switchLabelText(label))).toList()))));
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
        int colon = raw.indexOf(':');
        if (colon < 0) {
            return "default";
        }
        String label = CommentedTokenText.tokenLine(CommentedTokenText.tokens(raw.substring(0, colon)));
        return label.isEmpty() ? "default" : label;
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
                Doc.indent(Doc.concat(
                        Doc.HARD_LINE,
                        Doc.join(Doc.concat(Doc.text(","), Doc.HARD_LINE), pattern.getPatternList().stream()
                                .map(this::recordPatternComponent)
                                .toList()))),
                Doc.HARD_LINE,
                Doc.text(")"));
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
                Doc.text(")"));
    }

    private boolean switchGuardBreaks(SwitchEntry entry, Expression guard, String flat) {
        String label = "case " + entry.getLabels().stream()
                .map(this::switchLabelText)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        return guard instanceof EnclosedExpr
                || switchEntryWidth(label + flat + " -> {}") >= options.lineWidth()
                        && !rawSingleLineSwitchEntry(entry).isPresent();
    }

    private int switchEntryWidth(String text) {
        return (options.indentUnit().length() * 3) + text.length();
    }

    private Doc switchStatementGroupEntry(Doc label, Doc guard, NodeList<Statement> statements) {
        if (statements.size() == 1 && statements.get(0).isBlockStmt()) {
            return Doc.concat(label, guard, Doc.text(": "), switchStatementGroupBlock(statements.get(0).asBlockStmt()));
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
        String raw = entry.getTokenRange().map(Object::toString).orElseGet(() -> rawSource.rawWithoutOwnComment(entry)).stripTrailing();
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
        return Optional.of(Doc.text(raw));
    }

    private Doc switchEntryBody(Statement statement) {
        if (statement.isBlockStmt()) {
            return switchRuleBlock(statement.asBlockStmt());
        }
        if (statement instanceof ExpressionStmt expressionStmt) {
            return Doc.concat(expressionRenderer.format(expressionStmt.getExpression()), Doc.text(";"));
        }
        return Doc.concat(statementRenderer.format(statement));
    }

    /**
     * Prints the block body of a rule entry after {@code ->}.
     *
     * <p>Rule-entry empty blocks intentionally use the same expanded shape as old-style statement group blocks. Any
     * real statement or orphan comment inside the block returns to normal block rendering.
     */
    private Doc switchRuleBlock(BlockStmt block) {
        if (block.getStatements().isEmpty() && block.getOrphanComments().isEmpty()) {
            return Doc.concat(Doc.text("{"), Doc.HARD_LINE, Doc.text("}"));
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
}
