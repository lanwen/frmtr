package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import dev.lanwen.frmtr.java.MethodCallChainPrinter.MethodCallChainTail;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.ToIntFunction;

/**
 * Builds the compact-root-with-broken-final-segment shapes: a chain that keeps its root and single selector on one line
 * and breaks only the final segment's argument list ({@code root.selector(}\n args \n{@code )}), plus the sibling that
 * breaks the root's own arguments and glues a no-arg segment to its close. This helper owns those broken shapes and the
 * width gates that decide when they apply; it leaves chain analysis, the fan, and segment rendering to the caller.
 *
 * <p>Kicks in when the root and its selector fit on one line but the final segment must break — e.g.
 * {@code ConnectionPlanner.between(primary, secondary).establishRoute(active, fallback)} keeps the root flat and breaks
 * {@code establishRoute(}'s arguments — or when a no-arg tail glues to a broken root, e.g.
 * {@code when(sharePartition.acquire(...)).thenReturn(...)}. See fixtures {@code method-chain-ranked-broken-segment},
 * {@code broken-root-attached-segment-continuation-width}, and {@code source-multiline-method-root-chain-initializer}.
 */
final class CompactRootBrokenSegmentLayout {

    private final ChainSegmentWidthLayout segmentWidth;

    private final FormatterOptions options;

    private final TypePrinter types;

    private final CompactSourceText compactSource;

    private final LayoutWidth layoutWidth;

    private final MethodCallPrinter calls;

    private final SourceShapePolicy sourceShapePolicy;

    private final ChainSegmentRenderer segmentRenderer;

    private final BiFunction<String, NodeList<Expression>, Optional<Doc>> huggableBlockLambdaArguments;

    private final BiFunction<String, NodeList<Expression>, Optional<String>> huggableBlockLambdaFirstLine;

    private final ExpressionLambdaArgumentLayout.PlanFactory expressionLambdaArgumentPlan;

    CompactRootBrokenSegmentLayout(
            ChainSegmentWidthLayout segmentWidth,
            FormatterOptions options,
            TypePrinter types,
            CompactSourceText compactSource,
            LayoutWidth layoutWidth,
            MethodCallPrinter calls,
            SourceShapePolicy sourceShapePolicy,
            ChainSegmentRenderer segmentRenderer,
            BiFunction<String, NodeList<Expression>, Optional<Doc>> huggableBlockLambdaArguments,
            BiFunction<String, NodeList<Expression>, Optional<String>> huggableBlockLambdaFirstLine,
            ExpressionLambdaArgumentLayout.PlanFactory expressionLambdaArgumentPlan
    ) {
        this.segmentWidth = segmentWidth;
        this.options = options;
        this.types = types;
        this.compactSource = compactSource;
        this.layoutWidth = layoutWidth;
        this.calls = calls;
        this.sourceShapePolicy = sourceShapePolicy;
        this.segmentRenderer = segmentRenderer;
        this.huggableBlockLambdaArguments = huggableBlockLambdaArguments;
        this.huggableBlockLambdaFirstLine = huggableBlockLambdaFirstLine;
        this.expressionLambdaArgumentPlan = expressionLambdaArgumentPlan;
    }

    boolean compactRootFinalSegmentLineOverflows(
            MethodCallExpr methodRoot,
            MethodCallExpr call,
            MethodCallChainTail finalSegmentSuffix,
            ToIntFunction<String> lineWidth,
            LayoutContext layout
    ) {
        if (call.getArguments().stream().anyMatch(argument -> argument instanceof LambdaExpr)) {
            return false;
        }
        String typeArguments = call.getTypeArguments()
                .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                .orElse("");
        String compactLine = compactSource.compact(methodRoot)
            + "."
            + typeArguments
            + call.getNameAsString()
            + "("
            + segmentWidth.methodCallSegmentArgumentsWidthText(call.getArguments())
            + ")"
            + finalSegmentSuffix;
        return lineWidth.applyAsInt(compactLine) > options.lineWidth();
    }

    /**
     * Breaks a method-call root's arguments one-per-line and glues the no-arg final segment to its close
     * ({@code Type.create(}\n args \n{@code ).toRetry()}), chosen when the chain is over width but the final segment
     * has nothing of its own to wrap and the opener still fits. {@code layout} is threaded for signature parity but not
     * consulted here — the opener-fit gate uses the fixed-budget {@code lineWidth}.
     */
    Optional<Doc> brokenRootWithAttachedFinalSegment(
            MethodCallExpr methodRoot,
            MethodCallExpr call,
            MethodCallChainTail finalSegmentSuffix,
            ToIntFunction<String> lineWidth,
            LayoutContext layout
    ) {
        if (
            methodRoot.getArguments().isEmpty()
            || sourceShapePolicy.hasContainedComments(methodRoot)
            || methodRoot.getArguments().stream().anyMatch(argument -> argument instanceof LambdaExpr)
        ) {
            return Optional.empty();
        }
        if (lineWidth.applyAsInt(calls.methodCallPrefix(methodRoot) + "(") > options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                calls.brokenMethodCall(methodRoot),
                segmentRenderer.methodCallChainSegmentAttachedToRootClose(call, finalSegmentSuffix, lineWidth)
            )
        );
    }

    private boolean refuseOpeningSingleSimpleObjectRootChainTail(
            Expression root,
            MethodCallExpr call
    ) {
        return root instanceof ObjectCreationExpr
            && segmentWidth.singleSimpleMethodCallSegmentArgument(call);
    }

    private Optional<Doc> compactRootWithBrokenFinalSegment(Expression root, MethodCallExpr call) {
        return compactRootWithBrokenFinalSegment(
            root,
            call,
            MethodCallChainTail.EMPTY,
            layoutWidth::currentIndented,
            LayoutContext.root()
        );
    }

    Optional<Doc> compactRootWithBrokenFinalSegment(
            Expression root,
            MethodCallExpr call,
            ToIntFunction<String> lineWidth,
            LayoutContext layout
    ) {
        return compactRootWithBrokenFinalSegment(root, call, MethodCallChainTail.EMPTY, lineWidth, layout);
    }

    private Optional<Doc> compactRootWithBrokenFinalSegment(
            Expression root,
            MethodCallExpr call,
            MethodCallChainTail finalSegmentSuffix,
            LayoutContext layout
    ) {
        return compactRootWithBrokenFinalSegment(
            root,
            call,
            finalSegmentSuffix,
            layoutWidth::currentIndented,
            layout
        );
    }

    Optional<Doc> compactRootWithBrokenFinalSegment(
            Expression root,
            MethodCallExpr call,
            MethodCallChainTail finalSegmentSuffix,
            ToIntFunction<String> lineWidth,
            LayoutContext layout
    ) {
        return compactRootWithBrokenFinalSegment(root, call, finalSegmentSuffix, lineWidth, layout, Doc.EMPTY);
    }

    /**
     * Same broken-final-segment shape with {@code argumentLeadingComment} placed own-line after the opened selector's
     * {@code (}. Routing the single-selector root-trailing {@code //} here keeps the marker own-line above the broken
     * argument on both passes instead of a {@code .to( //} suffix that flips. Declines a hugged block-lambda argument
     * when a comment is present so the caller keeps its hugging fallback.
     */
    Optional<Doc> compactRootWithBrokenFinalSegment(
            Expression root,
            MethodCallExpr call,
            MethodCallChainTail finalSegmentSuffix,
            ToIntFunction<String> lineWidth,
            LayoutContext layout,
            Doc argumentLeadingComment
    ) {
        if (call.getArguments().isEmpty()) {
            return Optional.empty();
        }
        if (refuseOpeningSingleSimpleObjectRootChainTail(root, call)) {
            return Optional.empty();
        }
        // The object-creation-root compact-root path has no source-shape bail: whether the constructor root stays compact
        // is decided by the width gate below (`compactRootFirstLineFits`), so a source-multiline root converges on the
        // same verdict.
        if (
            !(root instanceof MethodCallExpr)
            && !(root instanceof ObjectCreationExpr)
            && !(root instanceof FieldAccessExpr)
        ) {
            return Optional.empty();
        }
        String typeArguments = call.getTypeArguments()
                .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                .orElse("");
        String callPrefix = compactSource.compact(root) + "." + typeArguments + call.getNameAsString();
        // A wide constructor root renders flat here, so when the constructor-plus-selector opener overflows defer to the
        // broken object-creation fan, which breaks the constructor's own arguments by width. Object-creation roots only.
        boolean objectCreationOpenerOverflows = root instanceof ObjectCreationExpr
            && compactRootLineWidth(root, callPrefix + "(", lineWidth, layout) > options.lineWidth();
        if (objectCreationOpenerOverflows) {
            return Optional.empty();
        }
        if (!compactRootFirstLineFits(root, callPrefix, call.getArguments(), lineWidth, layout)) {
            return Optional.empty();
        }
        Optional<Doc> huggableLambda =
            huggableBlockLambdaArguments.apply(callPrefix, call.getArguments());
        if (huggableLambda.isPresent()) {
            if (argumentLeadingComment != Doc.EMPTY) {
                return Optional.empty();
            }
            return Optional.of(Doc.concat(huggableLambda.orElseThrow(), finalSegmentSuffix.doc()));
        }
        String prefix = callPrefix + "(";
        if (
            root instanceof ObjectCreationExpr
            && compactRootLineWidth(root, prefix, lineWidth, layout) > options.lineWidth()
        ) {
            return Optional.empty();
        }
        if (lineWidth.applyAsInt(prefix + ")") > options.lineWidth()) {
            return Optional.empty();
        }
        Doc argumentList = calls.methodCallArgumentList(callPrefix, call.getArguments(), Doc.HARD_LINE);
        Doc openedArguments = argumentLeadingComment == Doc.EMPTY
            ? argumentList
            : Doc.concat(argumentLeadingComment, Doc.HARD_LINE, argumentList);
        Doc exploded = Doc.concat(
            Doc.text(prefix),
            Doc.indent(
                Doc.concat(
                    Doc.HARD_LINE,
                    openedArguments
                )
            ),
            Doc.HARD_LINE,
            Doc.text(")" + finalSegmentSuffix)
        );
        // Offer the opener-hug flattest-first against the exploded shape so the renderer hugs onto the compact-root line
        // only when its opener fits; an argument that already fits one exploded line keeps that cleaner shape.
        if (argumentLeadingComment == Doc.EMPTY
                && segmentRenderer.segmentArgumentOpenerHugApplies(call)
                && segmentRenderer.segmentArgumentOverflowsExplodedLine(call)) {
            Doc hugged = Doc.concat(Doc.text(prefix), argumentList, Doc.text(")" + finalSegmentSuffix));
            return Optional.of(Doc.bestFitting(List.of(hugged, exploded)));
        }
        return Optional.of(exploded);
    }

    private boolean compactRootFirstLineFits(
            Expression root,
            String callPrefix,
            NodeList<Expression> arguments,
            ToIntFunction<String> lineWidth,
            LayoutContext layout
    ) {
        Optional<String> blockLambdaFirstLine = huggableBlockLambdaFirstLine.apply(callPrefix, arguments);
        if (
            blockLambdaFirstLine
                    .filter(
                        firstLine -> compactRootLineWidth(
                            root,
                            firstLine,
                            lineWidth,
                            layout
                        ) > options.lineWidth()
                    )
                    .isPresent()
        ) {
            return false;
        }
        Optional<ExpressionLambdaArgumentLayout.Plan> expressionLambdaPlan = expressionLambdaArgumentPlan.plan(
            callPrefix,
            arguments,
            layout
        );
        return expressionLambdaPlan
                .map(plan -> plan.firstLineFits(
                        line -> compactRootLineWidth(root, line, lineWidth, layout),
                        options.lineWidth()
                ))
                .orElse(true);
    }

    /**
     * Measures a compact chain root's first line ({@code root.selector(args…}) at the column where the root renders.
     * A threaded same-line prefix (the {@code return } chain) measures at its exact rendered column; otherwise the
     * caller's own {@code lineWidth} probe is authoritative — it already reflects the true render-time column, unlike a
     * source range, which drifts with an unrelated enclosing construct's own reflow across passes.
     */
    private int compactRootLineWidth(
            Expression root,
            String firstLine,
            ToIntFunction<String> lineWidth,
            LayoutContext layout
    ) {
        return layout.leftEdgePrefix().isEmpty()
            ? lineWidth.applyAsInt(firstLine)
            : threadedPrefixColumn(root, firstLine, layout);
    }

    /**
     * Exact rendered column when a caller threads its fixed same-line prefix (the {@code return } chain threads
     * {@code "return "}).
     */
    private int threadedPrefixColumn(Expression root, String firstLine, LayoutContext layout) {
        return layoutWidth.nodeIndentWidth(root) + layout.leftEdgePrefix().length() + firstLine.length();
    }

        }
