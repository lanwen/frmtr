package dev.lanwen.frmtr.java;

import com.github.javaparser.Range;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.TryStmt;
import dev.lanwen.frmtr.FormatterOptions;
import java.util.Optional;
import java.util.function.ToIntFunction;

/**
 * Single per-run home for "should the formatter respect the author's source shape here?" decisions.
 *
 * <p>Formatting features such as preserving a deliberately multiline call, keeping a blank line between members, or
 * keeping a constructor compact are all reads of the original token layout. Historically each of those reads was
 * spelled inline at the call site against {@link RawSource}, {@link SourceText}, or raw {@code getRange()} arithmetic,
 * so the same conceptual question ("was this already multiline?", "was there a blank line between these?") had several
 * subtly different definitions. This policy exists so a printer asks one named, intent-revealing question and never
 * reconstructs source-shape logic inline; with a single definition per decision the formatter has exactly one fixed
 * point to reason about for idempotence.
 *
 * <p>The policy is read-only and built once per formatting run from the source helpers it consults: {@link SourceText}
 * for offset/line indexing, {@link RawSource} for raw token extraction, {@link CompactSourceText} for
 * source-equivalent compact text, {@link JavaCommentPlacementPolicy} for comment associations, and
 * {@link FormatterOptions} for option-gated behavior. It deliberately does <em>not</em> absorb those collaborators'
 * own concerns: it does not own offset/slicing math ({@link SourceText}), raw-output comment accounting
 * ({@link RawPreservedSource}), or parse-recovery boundary rules. It calls them; it does not re-own them.
 *
 * <p>This is the first concrete slice of the deferred formatter-owned syntax view: a narrow metadata owner for
 * layout-from-source decisions that a larger view could later absorb. It is the single home for source-shape reads:
 * the canonical {@link #wasMultiline(Node)} definition and the syntax-specific predicates that build on it (multiline
 * argument lists, same-line starts, throws layout, try-with-resources shape, and so on) all live here, so a printer
 * asks one source-shape object rather than reaching for the same "was this multiline?" answer two different ways.
 */
final class SourceShapePolicy {

    private final SourceText sourceText;

    private final RawSource rawSource;

    private final CompactSourceText compactSource;

    private final JavaCommentPlacementPolicy commentPolicy;

    private final FormatterOptions options;

    SourceShapePolicy(
            SourceText sourceText,
            RawSource rawSource,
            CompactSourceText compactSource,
            JavaCommentPlacementPolicy commentPolicy,
            FormatterOptions options
    ) {
        this.sourceText = sourceText;
        this.rawSource = rawSource;
        this.compactSource = compactSource;
        this.commentPolicy = commentPolicy;
        this.options = options;
    }

    /**
     * Reports whether the node's own source spanned more than one line, the single canonical "was this multiline?"
     * definition for the whole formatter.
     *
     * <p>The decision is range-first with a raw-text fallback: when JavaParser exposes a position range, a node is
     * multiline iff its begin and end lines differ; when the range is absent (for example inside unparsed or recovered
     * regions), it falls back to scanning the node's raw source for a newline after its own attached comment is removed
     * so the comment's own line breaks do not count. Every printer that needs to know whether the author already broke a
     * call, lambda, initializer, or chain across lines asks this one method, so the formatter has exactly one fixed point
     * to reason about for idempotence rather than several range-vs-raw definitions that could disagree on the same node.
     */
    boolean wasMultiline(Node node) {
        return node.getRange()
                .map(range -> range.begin.line < range.end.line)
                .orElseGet(() -> rawSource.rawWithoutOwnComment(node).contains("\n"));
    }

    /**
     * Reports whether a node carries any contained comments, the source-shape gate that decides whether a
     * compact or otherwise source-shaped layout is safe to take without dropping comment content.
     *
     * <p>"Can this stay compact / be reconstructed source-equivalently?" is a layout-from-source decision, so the gate
     * that several printers spelled inline as {@code node.getAllContainedComments().isEmpty()} belongs behind this
     * policy rather than as a bare JavaParser scan at each call site. Containment itself is not re-derived here: the
     * policy delegates to {@link JavaCommentPlacementPolicy#hasContainedComments(Node)}, the run-indexed query the
     * comment-containment work owns, so the formatter keeps one containment index instead of a competing scan.
     *
     * <p>Because the delegate is the per-run comment index, this gate is only valid for original nodes from the current
     * formatting run. Callers that may pass cloned or otherwise detached nodes (for example compact-source
     * reconstruction that strips comments on a clone) must keep their own JavaParser containment scan; the run index
     * reports a clone as comment-free, which would change which layout path is taken.
     */
    boolean hasContainedComments(Node node) {
        return commentPolicy.hasContainedComments(node);
    }

    /**
     * Reports whether the author left a blank line between two source-adjacent nodes, the single canonical definition of
     * the formatter's deliberate-blank-line preservation rule.
     *
     * <p>A blank line existed iff the next node begins more than one line after the previous node ends, so the printers
     * that separate members, enum constants, module directives, record components, and statements all share one
     * {@code + 1} test instead of re-spelling the arithmetic. When either node lacks a source range the decision is
     * {@code false}: with no positions the formatter cannot claim the author asked for a blank line.
     */
    boolean hadBlankLineBetween(Node previous, Node next) {
        return next.getRange()
                .map(nextRange -> hadBlankLineBefore(previous, nextRange.begin.line))
                .orElse(false);
    }

    /**
     * Reports whether a blank line preceded a node whose effective first source line the caller has already resolved.
     *
     * <p>Some printers do not compare a node's raw begin line: JavaParser can fold a leading comment into a node's range,
     * so {@link BlockPrinter} and {@link EnumDeclarationPrinter} first resolve the line of the real code (or recovered
     * gap) that opens the next entry. This overload still owns the one {@code previous.end.line + 1} comparison so that
     * blank-line arithmetic lives in a single place, while leaving the begin-line adjustment to the caller that knows the
     * syntactic context. Returns {@code false} when the previous node lacks a source range.
     */
    boolean hadBlankLineBefore(Node previous, int nextBeginLine) {
        return previous.getRange()
                .map(previousRange -> nextBeginLine > previousRange.end.line + 1)
                .orElse(false);
    }

    /**
     * Reports whether a node's source-equivalent compact form fits on one line at its call-site indentation, the single
     * canonical "can this stay on one line?" width gate.
     *
     * <p>Compact text is source-equivalent by construction, so a width probe over it is itself a source-shape decision:
     * it asks whether the author's node could render flat rather than how a particular printer happens to spell the
     * comparison. This method owns the one {@code <= lineWidth()} comparison while leaving the indentation arithmetic to
     * the caller: each printer passes its own {@code indentedWidth} function so the per-site indent (current statement,
     * first line, block, continuation, …) is preserved exactly. The function is applied to {@link CompactSourceText}'s
     * compact text for the node so compact-text generation stays in that helper and only the fit decision lives here.
     * Sites that test the negation ("must this break?") call {@code !fitsOnOneLine(...)}; the two are the same gate.
     */
    boolean fitsOnOneLine(Node node, ToIntFunction<String> indentedWidth) {
        return indentedWidth.applyAsInt(compactSource.compact(node)) <= options.lineWidth();
    }

    /**
     * Reports whether a method-call chain segment's selector started on a later source line than the previous segment
     * ended, the single canonical definition of an author-broken chain split.
     *
     * <p>A fluent chain such as {@code a.b().c()} is "source-multiline" when the author put a selector on its own line;
     * the planner detects that one segment at a time by asking whether this call's name token begins after the previous
     * segment's last line. The selector is the call's name rather than the whole call, so a scope that itself spans
     * lines does not count as the author breaking before this selector. The comparison is range-only: when either the
     * previous segment or the selector name lacks a source range the answer is {@code false}, because without positions
     * the formatter cannot claim the author split the chain here. This is the {@code selectorOwner} typed as
     * {@link MethodCallExpr} (not the proposal sketch's bare {@code Node}) precisely so the selector-name range stays
     * available and the arithmetic is lifted unchanged from the planner.
     */
    boolean selectorBrokeAfter(Node previous, MethodCallExpr selectorOwner) {
        return previous.getRange()
                .flatMap(previousRange -> selectorOwner.getName().getRange().map(
                        nameRange -> nameRange.begin.line > previousRange.end.line
                ))
                .orElse(false);
    }

    /**
     * Reports whether two nodes begin on the same source line.
     */
    boolean startsOnSameLine(Node left, Node right) {
        return left.getRange()
                .flatMap(leftRange -> right.getRange().map(rightRange -> leftRange.begin.line == rightRange.begin.line))
                .orElse(false);
    }

    /**
     * Reports whether a method call's argument list, excluding the receiver and selector, was already multiline.
     */
    boolean methodCallArgumentsSpanMultipleLines(MethodCallExpr expression) {
        return argumentsSpanMultipleLines(
            expression.getName(),
            expression.getArguments(),
            expression.getRange()
        );
    }

    /**
     * Reports whether an expression tree contains a method call whose argument list was source-multiline.
     */
    boolean containsSourceMultilineMethodCallArgument(Expression expression) {
        if (expression instanceof MethodCallExpr methodCall) {
            return methodCallArgumentsSpanMultipleLines(methodCall);
        }
        if (expression instanceof EnclosedExpr enclosedExpr) {
            return containsSourceMultilineMethodCallArgument(enclosedExpr.getInner());
        }
        if (expression instanceof BinaryExpr binaryExpr) {
            return containsSourceMultilineMethodCallArgument(binaryExpr.getLeft())
                || containsSourceMultilineMethodCallArgument(binaryExpr.getRight());
        }
        return false;
    }

    /**
     * Reports whether a control-condition expression is a logical binary expression after source parentheses are peeled.
     */
    boolean logicalConditionExpression(Expression condition) {
        Expression expression = condition;
        while (expression instanceof EnclosedExpr enclosedExpr) {
            expression = enclosedExpr.getInner();
        }
        return expression instanceof BinaryExpr binaryExpr
            && (binaryExpr.getOperator() == BinaryExpr.Operator.AND
                || binaryExpr.getOperator() == BinaryExpr.Operator.OR);
    }

    /**
     * Reports whether an expression-lambda argument starts on the same source line as the method-call selector.
     */
    boolean expressionLambdaStartsOnSelectorLine(MethodCallExpr expression) {
        Optional<Integer> selectorLine = expression.getName().getRange().map(range -> range.begin.line);
        if (selectorLine.isEmpty()) {
            return false;
        }
        return expression.getArguments()
                .stream()
                .filter(LambdaExpr.class::isInstance)
                .map(LambdaExpr.class::cast)
                .filter(lambda -> lambda.getExpressionBody().isPresent())
                .flatMap(lambda -> lambda.getRange().stream())
                .anyMatch(range -> range.begin.line == selectorLine.orElseThrow());
    }

    /**
     * Reports whether a constructor call's argument list was already multiline.
     */
    boolean objectCreationArgumentsSpanMultipleLines(ObjectCreationExpr expression) {
        return argumentsSpanMultipleLines(
            expression.getType(),
            expression.getArguments(),
            expression.getAnonymousClassBody().isPresent()
                ? Optional.empty()
                : expression.getRange()
        );
    }

    /**
     * Describes source-only try-with-resources section shape after resources have been parsed.
     */
    TryResourcesShape tryResources(TryStmt statement) {
        if (statement.getResources().isEmpty()) {
            return new TryResourcesShape(false, false);
        }
        Optional<Range> statementRange = statement.getRange();
        Optional<Range> firstResourceRange = firstRange(statement.getResources());
        Optional<Range> lastResourceRange = lastRange(statement.getResources());
        Optional<Range> blockRange = statement.getTryBlock().getRange();
        if (
            statementRange.isEmpty()
            || firstResourceRange.isEmpty()
            || lastResourceRange.isEmpty()
            || blockRange.isEmpty()
        ) {
            return new TryResourcesShape(false, false);
        }
        boolean spansMultipleLines =
            firstResourceRange.orElseThrow().begin.line > statementRange.orElseThrow().begin.line
            || nodesSpanMultipleLines(statement.getResources())
            || lastResourceRange.orElseThrow().end.line < blockRange.orElseThrow().begin.line;
        boolean trailingSemicolon = sourceText
                .sliceBetween(lastResourceRange.orElseThrow(), blockRange.orElseThrow())
                .stripLeading()
                .startsWith(";");
        return new TryResourcesShape(spansMultipleLines, trailingSemicolon);
    }

    private boolean argumentsSpanMultipleLines(
            Node prefix,
            NodeList<? extends Node> arguments,
            Optional<Range> containingRange
    ) {
        if (arguments.isEmpty()) {
            return false;
        }
        Optional<Range> prefixRange = prefix.getRange();
        Optional<Range> firstArgumentRange = firstRange(arguments);
        Optional<Range> lastArgumentRange = lastRange(arguments);
        if (prefixRange.isEmpty() || firstArgumentRange.isEmpty() || lastArgumentRange.isEmpty()) {
            return false;
        }
        if (firstArgumentRange.orElseThrow().begin.line > prefixRange.orElseThrow().end.line) {
            return true;
        }
        if (entriesStartOnMultipleLines(arguments)) {
            return true;
        }
        return containingRange
                .map(range -> sourceText.sliceAfterWithin(lastArgumentRange.orElseThrow(), range).contains("\n"))
                .orElse(false);
    }

    private boolean nodesSpanMultipleLines(NodeList<? extends Node> nodes) {
        Optional<Range> first = firstRange(nodes);
        Optional<Range> last = lastRange(nodes);
        return first.isPresent() && last.isPresent() && first.orElseThrow().begin.line < last.orElseThrow().end.line;
    }

    private boolean entriesStartOnMultipleLines(NodeList<? extends Node> nodes) {
        Optional<Range> first = firstRange(nodes);
        if (first.isEmpty()) {
            return false;
        }
        int firstLine = first.orElseThrow().begin.line;
        return nodes.stream()
                .skip(1)
                .flatMap(node -> node.getRange().stream())
                .anyMatch(range -> range.begin.line > firstLine);
    }

    private Optional<Range> firstRange(NodeList<? extends Node> nodes) {
        return nodes.isEmpty() ? Optional.empty() : nodes.get(0).getRange();
    }

    private Optional<Range> lastRange(NodeList<? extends Node> nodes) {
        return nodes.isEmpty() ? Optional.empty() : nodes.get(nodes.size() - 1).getRange();
    }

    record TryResourcesShape(boolean spansMultipleLines, boolean trailingSemicolon) {}
}
