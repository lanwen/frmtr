package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import dev.lanwen.frmtr.java.MethodCallChainPrinter.MethodCallChainTail;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Renders one dotted chain link — the {@code .selector(args)} segment — in every shape the chain layout needs: flat,
 * width-broken, force-broken, comment/lambda-carrying, and root-close-attached. This helper owns the segment renderer's
 * candidate ladder, the selector prefix (leading/interspersed/name comments), the final-segment suffix, and the field-access
 * suffix; it leaves chain analysis, the fan/root shapes, the continuation indent, and the comment-claim policy to the caller.
 */
final class ChainSegmentRenderer {

    private final TypePrinter types;

    private final MethodCallPrinter calls;

    private final CompactSourceText compactSource;

    private final LayoutWidth layoutWidth;

    private final FormatterOptions options;

    private final SourceShapePolicy sourceShapePolicy;

    private final ChainSegmentWidthLayout segmentWidth;

    private final ChainCommentLayout chainComments;

    private final CommentTracker comments;

    private final CommentedExpressionListPrinter commentedExpressionLists;

    private final ChainSelectorLambdaLayout chainSelectorLambda;

    private final MethodCallChainSourcePlanner methodChainPlanner;

    private final BiFunction<String, NodeList<Expression>, Optional<Doc>> huggableBlockLambdaArguments;

    private final BiFunction<String, MethodCallExpr, Optional<Doc>> commentedExpressionLambdaArgument;

    private final Function<MethodCallExpr, MethodCallChainSourcePlanner.MethodCallChainAnalysis> methodCallChainAnalysis;

    private final Predicate<MethodCallChainSourcePlanner.MethodCallChainAnalysis> chainBreaksByRule;

    ChainSegmentRenderer(
            TypePrinter types,
            MethodCallPrinter calls,
            CompactSourceText compactSource,
            LayoutWidth layoutWidth,
            FormatterOptions options,
            SourceShapePolicy sourceShapePolicy,
            ChainSegmentWidthLayout segmentWidth,
            ChainCommentLayout chainComments,
            CommentTracker comments,
            CommentedExpressionListPrinter commentedExpressionLists,
            ChainSelectorLambdaLayout chainSelectorLambda,
            MethodCallChainSourcePlanner methodChainPlanner,
            BiFunction<String, NodeList<Expression>, Optional<Doc>> huggableBlockLambdaArguments,
            BiFunction<String, MethodCallExpr, Optional<Doc>> commentedExpressionLambdaArgument,
            Function<MethodCallExpr, MethodCallChainSourcePlanner.MethodCallChainAnalysis> methodCallChainAnalysis,
            Predicate<MethodCallChainSourcePlanner.MethodCallChainAnalysis> chainBreaksByRule
    ) {
        this.types = types;
        this.calls = calls;
        this.compactSource = compactSource;
        this.layoutWidth = layoutWidth;
        this.options = options;
        this.sourceShapePolicy = sourceShapePolicy;
        this.segmentWidth = segmentWidth;
        this.chainComments = chainComments;
        this.comments = comments;
        this.commentedExpressionLists = commentedExpressionLists;
        this.chainSelectorLambda = chainSelectorLambda;
        this.methodChainPlanner = methodChainPlanner;
        this.huggableBlockLambdaArguments = huggableBlockLambdaArguments;
        this.commentedExpressionLambdaArgument = commentedExpressionLambdaArgument;
        this.methodCallChainAnalysis = methodCallChainAnalysis;
        this.chainBreaksByRule = chainBreaksByRule;
    }

    Doc methodCallChainSegment(MethodCallExpr expression) {
        return methodCallChainSegment(expression, false);
    }

    Doc methodCallChainSegment(MethodCallExpr expression, MethodCallChainTail finalSegmentSuffix) {
        return methodCallChainSegment(expression, Optional.empty(), finalSegmentSuffix);
    }

    private Doc methodCallChainSegment(MethodCallExpr expression, boolean reserveStatementTerminator) {
        return methodCallChainSegment(expression, reserveStatementTerminator, layoutWidth::continuationStatement);
    }

    private Doc methodCallChainSegmentAttachedToRootClose(
            MethodCallExpr expression,
            MethodCallChainTail finalSegmentSuffix
    ) {
        return methodCallChainSegmentAttachedToRootClose(
            expression,
            finalSegmentSuffix,
            layoutWidth::currentIndented
        );
    }

    Doc methodCallChainSegmentAttachedToRootClose(
            MethodCallExpr expression,
            MethodCallChainTail finalSegmentSuffix,
            ToIntFunction<String> lineWidth
    ) {
        // Measure the segment at the continuation column of the root's closing line (the {@code ")" + segment} closure),
        // not the beside-a-token source column. This segment attaches to the broken root's {@code )} on its continuation
        // line ({@code ).thenReturn(arg)}), so its argument-break gate must use that rendered column; the default
        // source-column estimate reads the author's shape and flips the segment's argument list between broken and
        // collapsed across passes (the {@code when(...).thenReturn(...)} family).
        return methodCallChainSegment(
            expression,
            Optional.empty(),
            finalSegmentSuffix,
            segment -> lineWidth.applyAsInt(")" + segment),
            true
        );
    }

    private Doc brokenMethodCallChainSegment(
            MethodCallExpr expression,
            MethodCallChainTail finalSegmentSuffix
    ) {
        String typeArguments = expression.getTypeArguments()
                .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                .orElse("");
        return brokenMethodCallSegment(
            expression,
            "." + typeArguments + expression.getNameAsString(),
            Doc.EMPTY,
            finalSegmentSuffix
        );
    }

    private Doc methodCallChainSegment(
            MethodCallExpr expression,
            boolean reserveStatementTerminator,
            ToIntFunction<String> compactSegmentWidth
    ) {
        return methodCallChainSegment(
            expression,
            reserveStatementTerminator,
            compactSegmentWidth,
            MethodCallChainTail.EMPTY,
            false
        );
    }

    private Doc methodCallChainSegment(
            MethodCallExpr expression,
            boolean reserveStatementTerminator,
            ToIntFunction<String> compactSegmentWidth,
            MethodCallChainTail finalSegmentSuffix,
            boolean segmentOnOwnLine
    ) {
        String typeArguments = expression.getTypeArguments()
                .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                .orElse("");
        String prefix = "." + typeArguments + expression.getNameAsString();
        Doc segmentPrefix = methodCallSegmentPrefix(expression);
        if (expression.getArguments().isEmpty()) {
            Optional<Doc> commentedArguments =
                calls.emptyMethodCallArguments(prefix, expression);
            if (commentedArguments.isPresent()) {
                return Doc.concat(segmentPrefix, commentedArguments.orElseThrow(), finalSegmentSuffix.doc());
            }
            return Doc.concat(segmentPrefix, Doc.text(prefix + "()" + finalSegmentSuffix));
        }
        Optional<Doc> sourceMultilineArguments = sourceMultilineMethodCallSegmentArguments(prefix, expression, finalSegmentSuffix);
        if (sourceMultilineArguments.isPresent()) {
            return Doc.concat(segmentPrefix, sourceMultilineArguments.orElseThrow());
        }
        Optional<Doc> huggableLambda =
            huggableBlockLambdaArguments.apply(prefix, expression.getArguments());
        if (huggableLambda.isPresent()) {
            return Doc.concat(segmentPrefix, huggableLambda.orElseThrow(), finalSegmentSuffix.doc());
        }
        Optional<Doc> commentedExpressionLambda =
            commentedExpressionLambdaArgument.apply(prefix, expression);
        if (commentedExpressionLambda.isPresent()) {
            return Doc.concat(segmentPrefix, commentedExpressionLambda.orElseThrow(), finalSegmentSuffix.doc());
        }
        Optional<Doc> huggedCommentedExpressionLambda = chainSelectorLambda.huggedCommentCarryingExpressionLambdaSegment(prefix, expression, finalSegmentSuffix);
        if (huggedCommentedExpressionLambda.isPresent()) {
            return Doc.concat(segmentPrefix, huggedCommentedExpressionLambda.orElseThrow());
        }
        // The chain-SELECTOR expression-lambda position. A chain selector whose sole trailing argument is an expression
        // lambda ({@code .map(entry -> body)}) renders SOURCE-NEUTRALLY here. Reading source shape at this position would
        // re-render the SAME selector two different ways across passes — the generic {@code Doc.group} argument shape when
        // its arguments fit flat, a hug when they span lines — so the segment's rendered width would flip and any enclosing
        // {@code bestFitting}/attach decision with it. Rendering AST-purely instead is what lets expr-lambda-selector
        // chains fan without a withhold. {@link #sourceNeutralExpressionLambdaSegment} chooses between two pure-AST arms
        // (flat selector vs. hugged/fanned body) with a {@link Doc#conditionalGroup}, so the DocRenderer picks hug-vs-break
        // at the true live column. Block-lambda and comment-carrying lambdas are handled by the earlier branches (they
        // never reach here), so this only ever sees a clean expression lambda.
        Optional<Doc> sourceNeutralExpressionLambda = chainSelectorLambda.sourceNeutralExpressionLambdaSegment(
            prefix,
            expression,
            segmentPrefix,
            finalSegmentSuffix,
            segmentOnOwnLine,
            compactSegmentWidth
        );
        if (sourceNeutralExpressionLambda.isPresent()) {
            return sourceNeutralExpressionLambda.orElseThrow();
        }
        Optional<Doc> commentedArguments = commentedExpressionLists.parenthesized(prefix, expression, expression.getArguments());
        if (commentedArguments.isPresent()) {
            return Doc.concat(segmentPrefix, commentedArguments.orElseThrow(), finalSegmentSuffix.doc());
        }
        String compactSegment = prefix
            + "("
            + segmentWidth.methodCallSegmentArgumentsWidthText(expression.getArguments())
            + ")"
            + finalSegmentSuffix;
        if (segmentWidth.methodCallSegmentArgumentsShouldBreak(
                expression,
                reserveStatementTerminator,
                compactSegment,
                compactSegmentWidth,
                segmentOnOwnLine
            )) {
            return brokenMethodCallSegment(expression, prefix, segmentPrefix, finalSegmentSuffix);
        }
        return Doc.concat(
            segmentPrefix,
            Doc.group(
                Doc.concat(
                    Doc.text(prefix + "("),
                    Doc.indent(
                        Doc.concat(
                            Doc.SOFT_LINE,
                            calls.methodCallArgumentList(prefix, expression.getArguments(), Doc.LINE)
                        )
                    ),
                    Doc.SOFT_LINE,
                    Doc.text(")" + finalSegmentSuffix)
                )
            )
        );
    }

    Doc brokenMethodCallSegment(
            MethodCallExpr expression,
            String prefix,
            Doc segmentPrefix,
            MethodCallChainTail finalSegmentSuffix
    ) {
        return Doc.concat(
            segmentPrefix,
            Doc.text(prefix + "("),
            Doc.indent(
                Doc.concat(
                    Doc.HARD_LINE,
                    calls.methodCallArgumentList(prefix, expression.getArguments(), Doc.HARD_LINE)
                )
            ),
            Doc.HARD_LINE,
            Doc.text(")" + finalSegmentSuffix)
        );
    }

    /**
     * Whether the selector's sole argument is a single inner call/creation with its own arguments that the opener can hug
     * onto the dotted line. Structural, comment/lambda-free (hugging a lambda or comment carrier strands the body), and
     * indent-independent, so both passes offer the identical hug arm and the width choice is left to the renderer ranking.
     * An inner call that is itself a chain ({@code RetryPolicy.restart().withResetChildren(true)}) is excluded: the chain
     * printer owns its fan, and hugging mid-chain would break it after an inner selector's {@code (}.
     */
    boolean segmentArgumentOpenerHugApplies(MethodCallExpr expression) {
        if (expression.getArguments().size() != 1 || sourceShapePolicy.hasContainedComments(expression)) {
            return false;
        }
        Expression argument = expression.getArgument(0);
        if (argument instanceof MethodCallExpr call) {
            return !call.getArguments().isEmpty()
                && call.getScope().filter(MethodCallExpr.class::isInstance).isEmpty()
                && !sourceShapePolicy.hasContainedComments(call)
                && call.getArguments().stream().noneMatch(LambdaExpr.class::isInstance);
        }
        if (argument instanceof ObjectCreationExpr creation) {
            return !creation.getArguments().isEmpty()
                && creation.getAnonymousClassBody().isEmpty()
                && !sourceShapePolicy.hasContainedComments(creation)
                && creation.getArguments().stream().noneMatch(LambdaExpr.class::isInstance);
        }
        return false;
    }

    /**
     * Whether the selector's single inner-call argument, rendered flat on its own exploded continuation line, overflows.
     * Only then is the opener-hug worthwhile: when the exploded argument already fits one line, that shape is cleaner than
     * hugging the opener and dangling the closer. Measured source-neutrally from the AST (rebuilt flat text at the
     * argument's rendered indent) so the verdict is a fixpoint.
     */
    boolean segmentArgumentOverflowsExplodedLine(MethodCallExpr expression) {
        String argumentFlat = compactSource.commentFree(expression.getArgument(0));
        return layoutWidth.nodeIndentWidth(expression) + options.indentUnit().length() + argumentFlat.length()
            > options.lineWidth();
    }

    /**
     * Whether a SHORT final selector wraps a single call/creation argument that will not render flat — the shape that
     * reads better attached to its receiver with the argument one indent deep than fanned. Short-name-gated so a longer
     * selector keeps the exploded fan. The argument breaks either because it is an object-creation-rooted / rule-breaking
     * chain (fans one selector per line at any width) or because its flat form overflows its own exploded line.
     */
    boolean finalSegmentAttachesShortBreakingCallArgument(MethodCallExpr call) {
        if (
            call.getNameAsString().length() >= SingleCallArgumentOpenerHugLayout.SHORT_CALL_NAME_LIMIT
            || call.getArguments().size() != 1
        ) {
            return false;
        }
        Expression argument = call.getArgument(0);
        NodeList<Expression> innerArguments;
        if (argument instanceof MethodCallExpr inner) {
            innerArguments = inner.getArguments();
        } else if (argument instanceof ObjectCreationExpr creation) {
            innerArguments = creation.getArguments();
        } else {
            return false;
        }
        if (innerArguments.isEmpty() || innerArguments.stream().anyMatch(LambdaExpr.class::isInstance)) {
            return false;
        }
        if (argument instanceof MethodCallExpr innerChain) {
            MethodCallChainSourcePlanner.MethodCallChainAnalysis argumentAnalysis =
                methodCallChainAnalysis.apply(innerChain);
            if (chainBreaksByRule.test(argumentAnalysis)
                    || methodChainPlanner.rootObjectCreationNeedsBreak(argumentAnalysis)) {
                return true;
            }
        }
        return segmentArgumentOverflowsExplodedLine(call);
    }

    /**
     * A no-op stub: a chain segment's argument list breaks by width rather than being preserved in its authored
     * multi-line shape, so this always returns empty. Retained so the candidate-ladder dispatch in
     * {@link #methodCallChainSegment} stays wired.
     */
    private Optional<Doc> sourceMultilineMethodCallSegmentArguments(
            String prefix,
            MethodCallExpr expression,
            MethodCallChainTail finalSegmentSuffix
    ) {
        return Optional.empty();
    }

    private Doc methodCallSegmentPrefix(MethodCallExpr expression) {
        List<JavaCommentTrivia> leadingComments = chainComments.leadingLineCommentsBeforeSegment(expression);
        Doc leading = Doc.concat(
            leadingComments
                    .stream()
                    .map(comments::comment)
                    .filter(comment -> comment != Doc.EMPTY)
                    .map(comment -> Doc.concat(comment, Doc.HARD_LINE))
                    .toList()
        );
        // A block or Javadoc comment interspersed between two chain links — e.g. `.define(A)` then `/** doc */` then
        // `.define(B)` — is parked on the B selector depending on layout. Recover it from the orphan pool first (the
        // expanded shape) so a single source-position query owns the slot for every whitespace shape, then fall through
        // to the selector's own comment (the canonical/collapsed shape). Both are claimed under the same anchor by
        // identity, so whichever shape applies, the comment renders exactly once.
        Doc interspersedOrphans = chainComments.interspersedOrphanCommentsBeforeSelector(expression);
        // JavaParser attaches a line comment that sits between the scope and the selector to the selector name as its own
        // comment, so the same comment can also be offered by a neighboring slot: the leading-line slot above (same prefix
        // call) or the previous segment's between-segments trailing slot. The name comment is offered here under its own
        // (expression, OWN) ownership key — distinct from the bare (comment, INTERLEAVED) key those neighbors use — so the
        // dry-run records the true first-traversal claimant and {@code ownsHere} suppresses whichever offer lost. Output
        // is unchanged because the suppressed offer already lost the first-claim race and rendered empty. The
        // same-prefix leading offer is also excluded by identity here so the name slot never re-claims this segment's own
        // leading comment. A Javadoc selector comment is accepted alongside line and block comments because JavaParser
        // parses a `/** ... */` between chain links as a Javadoc attached to the next selector, and dropping it on
        // kind alone lost it in every shape.
        Optional<Comment> rawNameComment = expression.getName()
                .getComment()
                .filter(comment -> comment instanceof LineComment
                        || comment instanceof BlockComment
                        || comment instanceof JavadocComment)
                .filter(comment -> CommentIndex.startsBefore(comment, expression.getName()))
                .filter(comment -> leadingComments.stream().noneMatch(leadingTrivia -> leadingTrivia.comment() == comment));
        Doc nameComment = rawNameComment
                .map(comment -> comments.comment(comment, expression, OwnerSlot.OWN))
                .orElse(Doc.EMPTY);
        if (nameComment == Doc.EMPTY) {
            return Doc.concat(leading, interspersedOrphans);
        }
        Doc namePrefix = rawNameComment
                .filter(comment -> comment instanceof BlockComment
                        && CommentIndex.startsOnSameLine(comment, expression.getName())
                )
                .map(ignored -> Doc.concat(nameComment, Doc.text(" ")))
                .orElseGet(() -> Doc.concat(nameComment, Doc.HARD_LINE));
        return Doc.concat(leading, interspersedOrphans, namePrefix);
    }

    List<Doc> methodCallChainSegments(List<MethodCallExpr> calls, MethodCallChainTail finalSegmentSuffix) {
        List<Doc> segments = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            Optional<MethodCallExpr> next = i + 1 < calls.size() ? Optional.of(calls.get(i + 1)) : Optional.empty();
            // Every segment in this one-per-line layout renders alone on its own continuation line, so the final
            // segment must be measured at the continuation indent rather than its stale source column.
            segments.add(
                methodCallChainSegment(
                    calls.get(i),
                    next,
                    next.isEmpty() ? finalSegmentSuffix : MethodCallChainTail.EMPTY,
                    layoutWidth::continuationStatement,
                    true
                )
            );
        }
        return segments;
    }

    private Doc methodCallChainSegment(MethodCallExpr expression, Optional<MethodCallExpr> nextCall) {
        return methodCallChainSegment(expression, nextCall, MethodCallChainTail.EMPTY);
    }

    Doc methodCallChainSegment(
            MethodCallExpr expression,
            Optional<MethodCallExpr> nextCall,
            MethodCallChainTail finalSegmentSuffix
    ) {
        return methodCallChainSegment(expression, nextCall, finalSegmentSuffix, layoutWidth::continuationStatement);
    }

    Doc methodCallChainSegment(
            MethodCallExpr expression,
            Optional<MethodCallExpr> nextCall,
            MethodCallChainTail finalSegmentSuffix,
            ToIntFunction<String> compactSegmentWidth
    ) {
        return methodCallChainSegment(expression, nextCall, finalSegmentSuffix, compactSegmentWidth, false);
    }

    Doc methodCallChainSegment(
            MethodCallExpr expression,
            Optional<MethodCallExpr> nextCall,
            MethodCallChainTail finalSegmentSuffix,
            ToIntFunction<String> compactSegmentWidth,
            boolean segmentOnOwnLine
    ) {
        MethodCallChainTail segmentSuffix = nextCall.isEmpty() ? finalSegmentSuffix : MethodCallChainTail.EMPTY;
        // A single method-call argument that itself trails a line comment binds that comment inside the argument, though
        // it belongs after this segment's `)`. Claim the segment's trailing slot before rendering the argument (first
        // offer wins the ownership pre-pass) so the argument renders comment-free and the comment stays a stable `) // c`.
        boolean claimTrailingBeforeArgument =
            expression.getArguments().size() == 1
            && expression.getArgument(0) instanceof MethodCallExpr argument
            && chainComments.hasOwnTrailingLineComment(argument);
        Doc claimedTrailingComment = claimTrailingBeforeArgument
            ? segmentTrailingComment(expression, nextCall)
            : Doc.EMPTY;
        Doc segment = methodCallChainSegment(
            expression,
            nextCall.isEmpty(),
            compactSegmentWidth,
            segmentSuffix,
            segmentOnOwnLine
        );
        Doc trailingComment = claimTrailingBeforeArgument
            ? claimedTrailingComment
            : segmentTrailingComment(expression, nextCall);
        if (trailingComment == Doc.EMPTY) {
            return segment;
        }
        return Doc.concat(segment, Doc.lineSuffix(Doc.concat(Doc.text(" "), trailingComment)));
    }

    /** The segment's between-segments trailing line comment ({@code nextCall} present) or its final trailing comment. */
    private Doc segmentTrailingComment(MethodCallExpr expression, Optional<MethodCallExpr> nextCall) {
        return nextCall
                .map(next -> chainComments.trailingLineCommentBeforeNextSegment(expression, Optional.of(next)))
                .orElseGet(() -> chainComments.finalTrailingLineComment(expression));
    }

    Doc appendFinalSegmentSuffix(Doc doc, MethodCallChainTail finalSegmentSuffix) {
        return finalSegmentSuffix.appendTo(doc);
    }

    Doc fieldAccessMethodCallSegment(FieldAccessExpr fieldAccess, MethodCallExpr methodCall) {
        String typeArguments = methodCall.getTypeArguments()
                .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                .orElse("");
        return Doc.text(
            fieldAccessSuffixAfterMethodRoot(fieldAccess)
                + "."
                + typeArguments
                + methodCall.getNameAsString()
                + "("
                + compactSource.compactJoin(methodCall.getArguments())
                + ")"
        );
    }

    private String fieldAccessSuffixAfterMethodRoot(FieldAccessExpr fieldAccess) {
        Expression scope = fieldAccess.getScope();
        if (scope instanceof MethodCallExpr) {
            return "." + fieldAccess.getNameAsString();
        }
        if (scope instanceof FieldAccessExpr innerFieldAccess) {
            return fieldAccessSuffixAfterMethodRoot(innerFieldAccess) + "." + fieldAccess.getNameAsString();
        }
        return "." + fieldAccess.getNameAsString();
    }
}
