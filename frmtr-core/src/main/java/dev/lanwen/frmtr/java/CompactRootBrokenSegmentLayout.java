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
     * Breaks a method-call root's own argument list one argument per line and glues the single final segment to its
     * closing parenthesis: {@code Type.create(}\n args \n{@code ).toRetry()}.
     *
     * <p>Reached when {@link #compactRootFinalSegmentLineOverflows} reports the whole chain over width at its rendered
     * line position but the final segment has no arguments of its own to wrap ({@code .toRetry()}/{@code .build()}). It
     * mirrors the shape a source-multiline root produces, but is reached for a flat source root that overflows only
     * because it renders at a deep nesting column. Returns empty (leaving the existing flat layout) unless the root
     * carries breakable arguments, is not already source-multiline, has no comments, and its opener {@code Type.create(}
     * itself fits at {@code lineWidth}, so the broken shape is only chosen when it is both needed and valid.
     *
     * <p>{@code layout} is threaded so the true continuation column ({@link LayoutContext#leftEdgePrefix()}) is
     * available at this statement/field single-segment flat-gate. It is NOT consulted here: the opener-fit decision uses
     * the fixed-budget {@code lineWidth.applyAsInt(…)} floor (the statement/field callers pass an empty-prefix
     * context).
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
     * Same broken-final-segment shape, but with {@code argumentLeadingComment} placed on its own line just after the
     * opened selector's {@code (} and before the argument. The single-selector root-trailing {@code //} case renders here
     * so the marker lands own-line above the broken argument on the receiver-trailing pass too — the same slot the
     * argument-leading pass produces — instead of a {@code .to( //} suffix that flips between passes. Declines a hugged
     * block-lambda argument when a comment is present so the caller keeps its hugging fallback.
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
        // An object-creation root renders here as its whole compact form ({@code compactSource.compact(root)}) on the
        // first line, so a wide constructor ({@code new LogValidator(12 args)}) would keep 256 columns flat and only break
        // the final selector's arguments. Gate the compact-root shape on WIDTH: when the constructor-plus-selector opener
        // ({@code new Type(args).selector(}) already overflows, defer so the chain falls through to the broken
        // object-creation fan, which breaks the constructor's own argument list by width. Scoped to object-creation
        // roots; method-call/field roots keep their existing routing.
        if (
            root instanceof ObjectCreationExpr
            && compactRootLineWidth(root, callPrefix + "(", layout) > options.lineWidth()
        ) {
            return Optional.empty();
        }
        if (!compactRootFirstLineFits(root, callPrefix, call.getArguments(), layout)) {
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
            && compactRootLineWidth(root, prefix, layout) > options.lineWidth()
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
        // When the selector's sole argument is a single inner call/creation that would itself overflow its own exploded
        // line, rank the opener-hug ({@code .thenReturn(List.of(new TopicData<>(} ⏎ …) flattest-first against this
        // exploded shape, so the renderer hugs it onto the compact-root line only when its opener fits and otherwise keeps
        // the exploded fallback. An argument that fits one exploded line keeps that cleaner shape.
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
            LayoutContext layout
    ) {
        Optional<String> blockLambdaFirstLine = huggableBlockLambdaFirstLine.apply(callPrefix, arguments);
        if (
            blockLambdaFirstLine
                    .filter(
                        firstLine -> compactRootLineWidth(
                            root,
                            firstLine,
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
                        line -> compactRootLineWidth(root, line, layout),
                        options.lineWidth()
                ))
                .orElse(true);
    }

    /**
     * Measures a compact chain root's first line ({@code root.selector(args…}) at the column where the root renders.
     *
     * <p>The root's start column reconstructed from {@code range.begin.column} is a source-column read that understates
     * the rendered column once the root is reindented shallower than its true block/type depth. This gate also considers
     * the root's rendered indentation ({@link LayoutWidth#nodeIndentWidth}, which counts every enclosing type and block)
     * and takes the <em>wider</em> of the two, so a root reindented flush-left inside deep nesting is not measured
     * as fitting at its stale shallow column and hugged over width. This mirrors the sibling
     * {@link ExpressionLambdaArgumentLayout} first-line gate and the depth-aware chain probes.
     *
     * <p>Two measurement modes, keyed on whether a caller has threaded the same-line leading prefix through
     * {@link LayoutContext#leftEdgePrefix()}:
     *
     * <ul>
     *   <li><strong>Prefix threaded.</strong> When a caller supplies its fixed leading prefix — the
     *   {@code return } chain threads {@code "return "} — the rendered column is known exactly:
     *   {@code nodeIndentWidth(root) + leftEdgePrefix.length() + firstLine.length()}. The source-column floor is
     *   <em>dropped</em>, because it is only a stand-in for the prefix this arm measures directly and could over- or
     *   under-count when the root is reindented away from its source column. A reindented-flat return chain whose compact
     *   first line is under budget by the stale source column but over budget once {@code return } is added (the
     *   {@code return } is worth exactly the missing width) is then correctly measured over width and fanned out.</li>
     *   <li><strong>No prefix threaded.</strong> Every other caller passes {@code root()} (empty prefix), so the wider-of
     *   rule applies: {@code max(source-column, nodeIndentWidth) + firstLine.length()}. The {@code nodeIndentWidth} arm
     *   keeps a root reindented flush-left inside deep nesting from being measured as fitting at its stale shallow column
     *   and hugged over width; the source column is kept as the <em>floor</em> because it is where these callers'
     *   unmodelled leading prefix (a {@code NAME … = }, a continuation indent) lives. Dropping the floor for these callers
     *   under-measures and regresses {@code source-multiline-method-root-chain-initializer}, so the floor stays for them.</li>
     * </ul>
     */
    private int compactRootLineWidth(
            Expression root,
            String firstLine,
            LayoutContext layout
    ) {
        // With the same-line prefix threaded, measure at the exact rendered column and drop the source-column floor,
        // which is only a stand-in for this prefix.
        if (!layout.leftEdgePrefix().isEmpty()) {
            return layoutWidth.nodeIndentWidth(root) + layout.leftEdgePrefix().length() + firstLine.length();
        }
        return root.getRange()
                .map(range -> Math.max(
                    Math.max(0, range.begin.column + 1) + firstLine.length(),
                    layoutWidth.nodeIndentWidth(root) + firstLine.length()))
                // Rangeless (synthetic) fallback measures at the rendered column, mirroring the prefix-set arm's
                // nodeIndentWidth term, instead of a fixed indentation baseline.
                .orElseGet(() -> layoutWidth.nodeIndentWidth(root) + firstLine.length());
    }

    private boolean expressionLambdaBodyOpenerOverflows(
            Expression root,
            String callPrefix,
            NodeList<Expression> arguments,
            LayoutContext layout
    ) {
        return expressionLambdaArgumentPlan.plan(callPrefix, arguments, layout)
                .filter(plan -> plan.bodyOpenerFitsOnContinuation(layoutWidth::continuationStatement, options.lineWidth()))
                .filter(plan -> plan.bodyOpenerOverflows(
                        line -> compactRootLineWidth(root, line, layout),
                        options.lineWidth()
                ))
                .isPresent();
    }

    private String compactRootCallPrefix(Expression root, MethodCallExpr expression) {
        return compactSource.compact(root)
            + "."
            + expression.getTypeArguments()
                    .map(typeArguments -> "<" + types.compactJoinTypeLike(typeArguments) + ">")
                    .orElse("")
            + expression.getNameAsString();
    }
}
