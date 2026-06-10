package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Prints method calls and method-call chains after expression dispatch has selected a call.
 *
 * <p>This helper owns the call-specific decision tree: auto versus forced chain breaks, compact root plus broken final
 * segment handling, mixed field/method chains, name comments on chain segments, empty argument comments, text-block
 * arguments, and single binary arguments. The boundary exists so {@link JavaPrinter} can keep broad expression
 * dispatch, enclosed suffix breaking, and binary-expression policy in their current owners while object creation stays
 * in {@link ObjectCreationPrinter}, lambda argument rendering stays in {@link LambdaExpressionPrinter}, commented
 * argument lists stay in {@link CommentedExpressionListPrinter}, and method-call layout reads as one state machine.
 *
 * <p>Representative fixture pairs live at
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/member_chain/input.java} with
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/member_chain/frmtr.output.java} and
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/text-blocks/input.java} with
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/text-blocks/frmtr.output.java}; lambda call
 * cases are covered by the two {@code lambda/arrow-parens-*} fixture directories.
 */
final class MethodCallPrinter {
    private final CommentTracker comments;
    private final JavaCommentPlacementPolicy commentPlacement;
    private final RawSource rawSource;
    private final SourceShape sourceShape;
    private final MethodCallChainSourcePlanner methodChainPlanner;
    private final FormatterOptions options;
    private final CompactSourceText compactSource;
    private final TypePrinter types;
    private final CommentedExpressionListPrinter commentedExpressionLists;
    private final Function<Expression, Doc> expressionRenderer;
    private final BiFunction<EnclosedExpr, Boolean, Doc> brokenEnclosedForSuffix;
    private final Function<ObjectCreationExpr, Doc> brokenObjectCreationRenderer;
    private final Function<ObjectCreationExpr, String> objectCreationPrefix;
    private final Function<BinaryExpr, Doc> brokenBinaryExpressionLinesRenderer;
    private final BiFunction<String, NodeList<Expression>, Optional<Doc>> huggableBlockLambdaArguments;
    private final BiFunction<String, NodeList<Expression>, Optional<String>> huggableBlockLambdaFirstLine;
    private final BiFunction<String, MethodCallExpr, Optional<Doc>> commentedExpressionLambdaArgument;
    private final BiFunction<String, NodeList<Expression>, Optional<Doc>> huggableExpressionLambdaArguments;
    private final Function<TextBlockLiteralExpr, String> unformattedTextBlockRenderer;
    private final ToIntFunction<String> currentIndentedWidth;
    private final ToIntFunction<String> blockStatementWidth;

    /**
     * Names whether method-call argument and chain layout is caller-forced or width/comment-driven.
     *
     * <p>The enum owns only the local break-mode distinction. It does not decide which expressions are method calls,
     * when a surrounding statement overflowed, or how arguments render after a break has been selected.
     */
    private enum MethodCallBreakMode {
        /** Let width and comment checks decide whether the call stays flat, groups softly, or becomes a chain. */
        AUTO,

        /** Preserve a caller-selected broken call shape because the surrounding expression already overflowed. */
        FORCED;

        static MethodCallBreakMode fromForced(boolean forced) {
            return forced ? FORCED : AUTO;
        }

        boolean isForced() {
            return this == FORCED;
        }

        Doc argumentLine() {
            return isForced() ? Doc.HARD_LINE : Doc.SOFT_LINE;
        }
    }

    MethodCallPrinter(
            JavaFormatContext context,
            TypePrinter types,
            Function<Expression, Doc> expressionRenderer,
            BiFunction<EnclosedExpr, Boolean, Doc> brokenEnclosedForSuffix,
            Function<ObjectCreationExpr, Doc> brokenObjectCreationRenderer,
            Function<ObjectCreationExpr, String> objectCreationPrefix,
            BiFunction<String, NodeList<Expression>, Optional<Doc>> huggableBlockLambdaArguments,
            BiFunction<String, NodeList<Expression>, Optional<String>> huggableBlockLambdaFirstLine,
            BiFunction<String, MethodCallExpr, Optional<Doc>> commentedExpressionLambdaArgument,
            BiFunction<String, NodeList<Expression>, Optional<Doc>> huggableExpressionLambdaArguments,
            Function<TextBlockLiteralExpr, String> unformattedTextBlockRenderer,
            Function<BinaryExpr, Doc> brokenBinaryExpressionLinesRenderer,
            ToIntFunction<String> currentIndentedWidth,
            ToIntFunction<String> blockStatementWidth) {
        this.comments = context.comments;
        this.commentPlacement = context.commentPlacementPolicy;
        this.rawSource = context.rawSource;
        this.sourceShape = context.sourceShape;
        this.methodChainPlanner = new MethodCallChainSourcePlanner(context, currentIndentedWidth);
        this.options = context.options;
        this.compactSource = context.compactSource;
        this.types = types;
        this.commentedExpressionLists = new CommentedExpressionListPrinter(context, expressionRenderer);
        this.expressionRenderer = expressionRenderer;
        this.brokenEnclosedForSuffix = brokenEnclosedForSuffix;
        this.brokenObjectCreationRenderer = brokenObjectCreationRenderer;
        this.objectCreationPrefix = objectCreationPrefix;
        this.brokenBinaryExpressionLinesRenderer = brokenBinaryExpressionLinesRenderer;
        this.huggableBlockLambdaArguments = huggableBlockLambdaArguments;
        this.huggableBlockLambdaFirstLine = huggableBlockLambdaFirstLine;
        this.commentedExpressionLambdaArgument = commentedExpressionLambdaArgument;
        this.huggableExpressionLambdaArguments = huggableExpressionLambdaArguments;
        this.unformattedTextBlockRenderer = unformattedTextBlockRenderer;
        this.currentIndentedWidth = currentIndentedWidth;
        this.blockStatementWidth = blockStatementWidth;
    }

    Doc methodCall(MethodCallExpr expression) {
        return methodCall(expression, MethodCallBreakMode.AUTO);
    }

    Doc brokenMethodCall(MethodCallExpr expression) {
        return methodCall(expression, MethodCallBreakMode.FORCED);
    }

    /**
     * Chooses the method-call shape once callers know this expression really is a method call.
     *
     * <p>The unforced path tries a chain shape only when the call itself asks for it; the forced path is used by
     * surrounding expression printers that already decided the call arguments must break.
     */
    private Doc methodCall(MethodCallExpr expression, MethodCallBreakMode breakMode) {
        if (expression.getScope().isEmpty()
                && expression.getNameAsString().equals("yield")
                && !expression.getArguments().isEmpty()) {
            return Doc.text("yield (" + compactSource.compactJoin(expression.getArguments()) + ")");
        }
        if (expression.getScope().filter(this::shouldPrintScopeAsDoc).isPresent()) {
            return Doc.concat(
                    expressionRenderer.apply(expression.getScope().orElseThrow()),
                    Doc.text("."),
                    methodCallWithoutScope(expression));
        }
        if (!breakMode.isForced()) {
            Optional<Doc> chain = methodCallChain(expression);
            if (chain.isPresent()) {
                return chain.orElseThrow();
            }
        }
        Optional<Doc> sourceMultilineArguments = sourceMultilineArguments(expression);
        if (sourceMultilineArguments.isPresent()) {
            return sourceMultilineArguments.orElseThrow();
        }
        Optional<Doc> suffixedEnclosed = suffixedEnclosedMethodCall(expression, false);
        if (suffixedEnclosed.isPresent()) {
            return suffixedEnclosed.orElseThrow();
        }
        String prefix = methodCallPrefix(expression);
        if (expression.getArguments().isEmpty()) {
            Optional<Doc> commentedArguments = emptyMethodCallArguments(prefix, expression);
            if (commentedArguments.isPresent()) {
                return commentedArguments.orElseThrow();
            }
            return Doc.text(prefix + "()");
        }
        Optional<Doc> huggableLambda = huggableBlockLambdaArguments.apply(prefix, expression.getArguments());
        if (huggableLambda.isPresent()) {
            return huggableLambda.orElseThrow();
        }
        Optional<Doc> commentedExpressionLambda = commentedExpressionLambdaArgument.apply(prefix, expression);
        if (commentedExpressionLambda.isPresent()) {
            return commentedExpressionLambda.orElseThrow();
        }
        Optional<Doc> huggableExpressionLambda = huggableExpressionLambdaArguments.apply(prefix, expression.getArguments());
        if (huggableExpressionLambda.isPresent()) {
            return huggableExpressionLambda.orElseThrow();
        }
        Optional<Doc> singleTextBlockArgument = singleTextBlockArgument(prefix, expression);
        if (singleTextBlockArgument.isPresent()) {
            return singleTextBlockArgument.orElseThrow();
        }
        Optional<Doc> singleBinaryArgument = singleBinaryArgument(prefix, expression.getArguments(), breakMode);
        if (singleBinaryArgument.isPresent()) {
            return singleBinaryArgument.orElseThrow();
        }
        Optional<Doc> commentedArguments = commentedExpressionLists.parenthesized(prefix, expression, expression.getArguments());
        if (commentedArguments.isPresent()) {
            return commentedArguments.orElseThrow();
        }
        Doc call = Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(Doc.concat(
                        methodCallLine(breakMode),
                        Doc.join(Doc.concat(Doc.text(","), Doc.LINE), expression.getArguments().stream()
                                .map(expressionRenderer)
                                .toList()))),
                methodCallLine(breakMode),
                Doc.text(")"));
        return breakMode.isForced() ? call : Doc.group(call);
    }

    String methodCallPrefix(MethodCallExpr expression) {
        return expression.getScope().map(scope -> compactSource.compact(scope) + ".").orElse("")
                + expression.getTypeArguments()
                        .map(typeArguments -> "<" + compactSource.compactJoin(typeArguments) + ">")
                        .orElse("")
                + expression.getNameAsString();
    }

    Doc methodCallWithoutScope(MethodCallExpr expression) {
        String prefix = expression.getTypeArguments()
                        .map(typeArguments -> "<" + compactSource.compactJoin(typeArguments) + ">")
                        .orElse("")
                + expression.getNameAsString();
        if (expression.getArguments().isEmpty()) {
            Optional<Doc> commentedArguments = emptyMethodCallArguments(prefix, expression);
            if (commentedArguments.isPresent()) {
                return commentedArguments.orElseThrow();
            }
            return Doc.text(prefix + "()");
        }
        Optional<Doc> commentedArguments = commentedExpressionLists.parenthesized(prefix, expression, expression.getArguments());
        if (commentedArguments.isPresent()) {
            return commentedArguments.orElseThrow();
        }
        return Doc.group(Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(Doc.concat(
                        Doc.SOFT_LINE,
                        Doc.join(Doc.concat(Doc.text(","), Doc.LINE), expression.getArguments().stream()
                                .map(expressionRenderer)
                                .toList()))),
                Doc.SOFT_LINE,
                Doc.text(")")));
    }

    Optional<Doc> suffixedEnclosedMethodCall(MethodCallExpr expression, boolean leadingBreak) {
        return expression.getScope()
                .filter(EnclosedExpr.class::isInstance)
                .map(EnclosedExpr.class::cast)
                .filter(scope -> leadingBreak
                        || blockStatementWidth.applyAsInt(compactSource.compact(expression) + ";") > options.lineWidth())
                .map(scope -> Doc.concat(
                        brokenEnclosedForSuffix.apply(scope, leadingBreak),
                        Doc.text("."),
                        methodCallWithoutScope(expression)));
    }

    Optional<Doc> methodCallChain(MethodCallExpr expression) {
        return methodCallChain(expression, MethodCallBreakMode.AUTO);
    }

    Optional<Doc> forcedMethodCallChain(MethodCallExpr expression) {
        return methodCallChain(expression, MethodCallBreakMode.FORCED);
    }

    Optional<Doc> compactRootWithBrokenFinalChainSegment(MethodCallExpr expression) {
        List<MethodCallExpr> calls = new ArrayList<>();
        Expression root = methodChainPlanner.methodCallChainRoot(expression, calls);
        if (root instanceof MethodCallExpr methodRoot && calls.size() == 1) {
            return compactRootWithBrokenFinalSegment(methodRoot, calls.getFirst());
        }
        if (methodChainPlanner.promotesFirstCall(root) && calls.size() == 2) {
            return compactRootWithBrokenFinalSegment(calls.getFirst(), calls.get(1));
        }
        return Optional.empty();
    }

    /**
     * Preserves an already-multiline call statement when the only argument is an object creation.
     *
     * <p>Statement rendering still owns the trailing semicolon, but the call printer owns the call-shape decision because
     * it already owns argument breaks and object-creation argument layout.
     */
    Optional<Doc> sourceMultilineSingleObjectCreationArgumentStatement(
            MethodCallExpr expression,
            ExpressionStmt statement) {
        if (expression.getArguments().size() != 1
                || !(expression.getArguments().get(0) instanceof ObjectCreationExpr)
                || !expression.getAllContainedComments().isEmpty()
                || !rawSource.rawWithoutOwnComment(statement).contains("\n")) {
            return Optional.empty();
        }
        return Optional.of(brokenMethodCall(expression));
    }

    /**
     * Prints a dotted call chain when the call is naturally chain-shaped or when a caller forces the chain break.
     *
     * <p>Auto mode leaves short uncommented calls alone. Forced mode is used by return, assignment, statement, and field
     * contexts that already know the surrounding line overflowed and need a broken call shape.
     */
    Optional<Doc> methodCallChain(MethodCallExpr expression, boolean force) {
        return methodCallChain(expression, MethodCallBreakMode.fromForced(force));
    }

    private Optional<Doc> methodCallChain(MethodCallExpr expression, MethodCallBreakMode breakMode) {
        MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis = methodCallChainAnalysis(expression);
        if ((!breakMode.isForced()
                        && !analysis.hasComments()
                        && !analysis.hasBlockLambdaArgument()
                        && !analysis.sourceMultilineChain()
                        && currentIndentedWidth.applyAsInt(compactSource.compact(expression)) <= options.lineWidth())
                || expression.getScope().isEmpty()) {
            return Optional.empty();
        }
        Expression root = analysis.root();
        List<MethodCallExpr> calls = analysis.calls();
        if (calls.isEmpty()
                || (calls.size() < 2
                        && !(root instanceof MethodCallExpr)
                        && !forcedSingleCallPrefixOverflows(breakMode, expression)
                        && !(breakMode.isForced() && root instanceof ObjectCreationExpr)
                        && !analysis.sourceMultilineChain()
                        && !analysis.rootHasComments()
                        && !analysis.singleCommentedSegment())) {
            return Optional.empty();
        }
        if (breakMode.isForced()
                && calls.size() == 1
                && root.getAllContainedComments().isEmpty()
                && calls.getFirst().getAllContainedComments().isEmpty()
                && !methodCallSegmentHasComment(calls.getFirst())
                && !analysis.rootHasBlockLambdaArgument()) {
            Optional<Doc> compactRootWithBrokenSegment = compactRootWithBrokenFinalSegment(root, calls.getFirst());
            if (compactRootWithBrokenSegment.isPresent()) {
                return compactRootWithBrokenSegment;
            }
        }
        if (calls.size() == 1 && root instanceof MethodCallExpr) {
            return Optional.of(Doc.concat(expressionRenderer.apply(root), methodCallChainSegment(calls.getFirst())));
        }
        if (root instanceof FieldAccessExpr fieldAccess
                && fieldAccess.getScope() instanceof MethodCallExpr methodRoot
                && calls.size() == 1) {
            return Optional.of(Doc.concat(
                    expressionRenderer.apply(methodRoot),
                    Doc.indent(Doc.concat(Doc.HARD_LINE, fieldAccessMethodCallSegment(fieldAccess, calls.getFirst())))));
        }
        MethodCallChainSourcePlanner.MethodCallChainPlan chainPlan =
                methodChainPlanner.plan(analysis, breakMode.isForced());
        root = chainPlan.root();
        calls = chainPlan.calls();
        Doc rootDoc = methodCallChainRootDoc(chainPlan);
        if (calls.isEmpty()) {
            return Optional.of(rootDoc);
        }
        Doc rootTrailingComment = rootTrailingLineCommentBeforeFirstSegment(root, calls);
        if (rootTrailingComment != Doc.EMPTY) {
            rootDoc = Doc.concat(rootDoc, Doc.text(" "), rootTrailingComment);
            if (calls.size() == 1) {
                return Optional.of(Doc.concat(
                        rootDoc,
                        Doc.indent(Doc.concat(Doc.HARD_LINE, methodCallChainSegment(calls.getFirst())))));
            }
        }
        if (chainPlan.rootRendering() == MethodCallChainSourcePlanner.ChainRootRendering.BROKEN_OBJECT_CREATION
                && calls.size() == 1) {
            return Optional.of(Doc.concat(rootDoc, methodCallChainSegment(calls.getFirst())));
        }
        if (root instanceof MethodCallExpr methodRoot
                && calls.size() == 1
                && root.getAllContainedComments().isEmpty()
                && calls.getFirst().getAllContainedComments().isEmpty()
                && !methodCallSegmentHasComment(calls.getFirst())) {
            if (chainPlan.rootRendering() == MethodCallChainSourcePlanner.ChainRootRendering.GROUPED_PROMOTED_METHOD_CALL) {
                if (methodCallSegmentHasBlockLambdaArgument(methodRoot)) {
                    return Optional.of(Doc.concat(rootDoc, methodCallChainSegment(calls.getFirst())));
                }
                return Optional.of(groupedPromotedRootWithSingleSegment(root, rootDoc, calls.getFirst()));
            }
            return Optional.of(Doc.concat(rootDoc, methodCallChainSegment(calls.getFirst())));
        }
        return Optional.of(Doc.concat(
                rootDoc,
                Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.join(Doc.HARD_LINE, methodCallChainSegments(calls))))));
    }

    private boolean forcedSingleCallPrefixOverflows(MethodCallBreakMode breakMode, MethodCallExpr expression) {
        return breakMode.isForced()
                && expression.getScope().isPresent()
                && methodCallSegmentHasBlockLambdaArgument(expression)
                && currentIndentedWidth.applyAsInt(methodCallPrefix(expression) + "(") > options.lineWidth();
    }

    private Doc methodCallChainRootDoc(MethodCallChainSourcePlanner.MethodCallChainPlan chainPlan) {
        return switch (chainPlan.rootRendering()) {
            case INLINE_PROMOTED_METHOD_CALL -> chainPlan.root() instanceof MethodCallExpr methodCall
                    ? inlineMethodCall(methodCall)
                    : expressionRenderer.apply(chainPlan.root());
            case GROUPED_PROMOTED_METHOD_CALL -> chainPlan.root() instanceof MethodCallExpr methodCall
                    ? groupedPromotedMethodCall(methodCall)
                    : expressionRenderer.apply(chainPlan.root());
            case BROKEN_OBJECT_CREATION -> brokenObjectCreationRenderer.apply((ObjectCreationExpr) chainPlan.root());
            case EXPRESSION_RENDERER -> expressionRenderer.apply(chainPlan.root());
        };
    }

    private Doc groupedPromotedMethodCall(MethodCallExpr expression) {
        if (methodCallSegmentHasBlockLambdaArgument(expression)) {
            return blockLambdaSegmentFirstLine(compactSource.compact(expression.getScope().orElseThrow()), expression)
                    .filter(firstLine -> blockStatementWidth.applyAsInt(firstLine) <= options.lineWidth())
                    .map(ignored -> expressionRenderer.apply(expression))
                    .orElseGet(() -> Doc.concat(
                            expressionRenderer.apply(expression.getScope().orElseThrow()),
                            Doc.indent(Doc.concat(Doc.HARD_LINE, methodCallChainSegment(expression)))));
        }
        return expression.getScope()
                .map(scope -> Doc.group(Doc.concat(
                        expressionRenderer.apply(scope),
                        Doc.indent(Doc.concat(Doc.SOFT_LINE, methodCallChainSegment(expression))))))
                .orElseGet(() -> expressionRenderer.apply(expression));
    }

    private Doc groupedPromotedRootWithSingleSegment(Expression root, Doc rootDoc, MethodCallExpr expression) {
        if (methodCallSegmentHasBlockLambdaArgument(expression)) {
            return blockLambdaSegmentFirstLine(compactSource.compact(root), expression)
                    .filter(firstLine -> blockStatementWidth.applyAsInt(firstLine) <= options.lineWidth())
                    .map(ignored -> Doc.concat(rootDoc, methodCallChainSegment(expression)))
                    .orElseGet(() -> Doc.concat(
                            rootDoc,
                            Doc.indent(Doc.concat(Doc.HARD_LINE, methodCallChainSegment(expression)))));
        }
        return Doc.group(Doc.concat(
                rootDoc,
                Doc.indent(Doc.concat(Doc.SOFT_LINE, methodCallChainSegment(expression)))));
    }

    private Optional<String> blockLambdaSegmentFirstLine(String root, MethodCallExpr expression) {
        String prefix = root + "."
                + expression.getTypeArguments()
                        .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                        .orElse("")
                + expression.getNameAsString();
        return huggableBlockLambdaFirstLine.apply(prefix, expression.getArguments());
    }

    private Optional<Doc> compactRootWithBrokenFinalSegment(Expression root, MethodCallExpr call) {
        if (!(root instanceof ObjectCreationExpr || root instanceof MethodCallExpr) || call.getArguments().isEmpty()) {
            return Optional.empty();
        }
        String typeArguments = call.getTypeArguments()
                .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                .orElse("");
        String callPrefix = compactSource.compact(root) + "." + typeArguments + call.getNameAsString();
        Optional<Doc> huggableLambda = huggableBlockLambdaArguments.apply(callPrefix, call.getArguments());
        if (huggableLambda.isPresent()) {
            return huggableLambda;
        }
        String prefix = callPrefix + "(";
        if (currentIndentedWidth.applyAsInt(prefix + ")") > options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(
                Doc.text(prefix),
                Doc.indent(Doc.concat(
                        Doc.HARD_LINE,
                        Doc.join(Doc.concat(Doc.text(","), Doc.HARD_LINE), call.getArguments().stream()
                                .map(expressionRenderer)
                                .toList()))),
                Doc.HARD_LINE,
                Doc.text(")")));
    }

    /**
     * Breaks chains that alternate method calls and field accesses as one structural chain.
     *
     * <p>A normal method-call chain can walk through method-call scopes directly. Mixed chains need a separate
     * structural-root path because field accesses can hide the earlier method-call root behind one or more field names.
     */
    Optional<Doc> mixedFieldMethodCallChain(MethodCallExpr expression) {
        if (!expression.getAllContainedComments().isEmpty()) {
            return Optional.empty();
        }
        List<Doc> segments = new ArrayList<>();
        Optional<Expression> root = collectMixedFieldMethodCallChain(expression, segments);
        if (root.isEmpty() || segments.size() < 2) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(
                expressionRenderer.apply(root.orElseThrow()),
                Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.join(Doc.HARD_LINE, segments)))));
    }

    Optional<Expression> mixedFieldMethodCallRoot(MethodCallExpr expression) {
        if (!expression.getAllContainedComments().isEmpty()) {
            return Optional.empty();
        }
        if (mixedFieldMethodCallSegmentCount(expression) < 2) {
            return Optional.empty();
        }
        return mixedFieldMethodCallStructuralRoot(expression);
    }

    private int mixedFieldMethodCallSegmentCount(MethodCallExpr expression) {
        Optional<Expression> scope = expression.getScope();
        if (scope.isEmpty()) {
            return 0;
        }
        Expression scoped = scope.orElseThrow();
        if (scoped instanceof MethodCallExpr methodScope) {
            int segments = mixedFieldMethodCallSegmentCount(methodScope);
            return segments == 0 ? 0 : segments + 1;
        }
        if (scoped instanceof FieldAccessExpr fieldAccess) {
            Optional<MethodCallExpr> methodRoot = fieldAccessMethodRoot(fieldAccess);
            return methodRoot.map(root -> mixedFieldMethodCallSegmentCount(root) + 1).orElse(0);
        }
        return 1;
    }

    private Optional<Expression> mixedFieldMethodCallStructuralRoot(MethodCallExpr expression) {
        Optional<Expression> scope = expression.getScope();
        if (scope.isEmpty()) {
            return Optional.empty();
        }
        Expression scoped = scope.orElseThrow();
        if (scoped instanceof MethodCallExpr methodScope) {
            return mixedFieldMethodCallStructuralRoot(methodScope);
        }
        if (scoped instanceof FieldAccessExpr fieldAccess) {
            return fieldAccessMethodRoot(fieldAccess).flatMap(this::mixedFieldMethodCallStructuralRoot);
        }
        return Optional.of(scoped);
    }

    private Optional<Expression> collectMixedFieldMethodCallChain(MethodCallExpr expression, List<Doc> segments) {
        Optional<Expression> scope = expression.getScope();
        if (scope.isEmpty()) {
            return Optional.empty();
        }
        Expression scoped = scope.orElseThrow();
        if (scoped instanceof MethodCallExpr methodScope) {
            Optional<Expression> root = collectMixedFieldMethodCallChain(methodScope, segments);
            root.ifPresent(ignored -> segments.add(methodCallChainSegment(expression)));
            return root;
        }
        if (scoped instanceof FieldAccessExpr fieldAccess) {
            Optional<MethodCallExpr> methodRoot = fieldAccessMethodRoot(fieldAccess);
            if (methodRoot.isEmpty()) {
                return Optional.empty();
            }
            Optional<Expression> root = collectMixedFieldMethodCallChain(methodRoot.orElseThrow(), segments);
            root.ifPresent(ignored -> segments.add(fieldAccessMethodCallSegment(fieldAccess, expression)));
            return root;
        }
        segments.add(methodCallChainSegment(expression));
        return Optional.of(scoped);
    }

    private Optional<MethodCallExpr> fieldAccessMethodRoot(FieldAccessExpr fieldAccess) {
        Expression scope = fieldAccess.getScope();
        if (scope instanceof MethodCallExpr methodCall) {
            return Optional.of(methodCall);
        }
        if (scope instanceof FieldAccessExpr innerFieldAccess) {
            return fieldAccessMethodRoot(innerFieldAccess);
        }
        return Optional.empty();
    }

    boolean methodCallChainHasComments(MethodCallExpr expression) {
        return methodCallChainAnalysis(expression).hasComments();
    }

    private MethodCallChainSourcePlanner.MethodCallChainAnalysis methodCallChainAnalysis(MethodCallExpr expression) {
        return methodChainPlanner.analyze(
                expression,
                this::methodCallSegmentHasComment,
                this::methodCallSegmentHasNameComment,
                this::methodCallSegmentHasArgumentGapComment,
                this::methodCallSegmentHasBlockLambdaArgument,
                this::methodCallChainHasTrailingLineComments);
    }

    boolean methodCallChainRootIsObjectCreation(MethodCallExpr expression) {
        return methodChainPlanner.rootIsObjectCreation(expression);
    }

    boolean methodCallChainRootIsFieldAccess(MethodCallExpr expression) {
        return methodChainPlanner.rootIsFieldAccess(expression);
    }

    String methodCallChainFirstLine(MethodCallExpr expression) {
        MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis = methodCallChainAnalysis(expression);
        if (analysis.root() instanceof MethodCallExpr && analysis.calls().size() == 1) {
            return compactSource.compact(expression);
        }
        MethodCallChainSourcePlanner.MethodCallChainPlan plan = methodChainPlanner.plan(analysis, true);
        if (plan.rootRendering() == MethodCallChainSourcePlanner.ChainRootRendering.BROKEN_OBJECT_CREATION) {
            return objectCreationPrefix.apply((ObjectCreationExpr) plan.root()) + "(";
        }
        return compactSource.compact(plan.root());
    }

    private boolean methodCallSegmentHasComment(MethodCallExpr expression) {
        return methodCallSegmentHasNameComment(expression)
                || methodCallSegmentHasArgumentGapComment(expression);
    }

    private boolean methodCallSegmentHasArgumentGapComment(MethodCallExpr expression) {
        return commentedExpressionLists.hasUnprintedLineComments(expression, expression.getArguments());
    }

    private boolean methodCallSegmentHasNameComment(MethodCallExpr expression) {
        return expression.getName().getComment()
                .filter(comment -> CommentIndex.startsBefore(comment, expression.getName()))
                .isPresent();
    }

    private boolean methodCallChainHasTrailingLineComments(List<MethodCallExpr> calls) {
        for (int index = 0; index + 1 < calls.size(); index++) {
            if (!trailingLineCommentsBeforeNextSegment(calls.get(index), calls.get(index + 1)).isEmpty()) {
                return true;
            }
        }
        return !calls.isEmpty() && !finalTrailingLineComments(calls.getLast()).isEmpty();
    }

    private boolean methodCallSegmentHasBlockLambdaArgument(MethodCallExpr expression) {
        return expression.getArguments().stream()
                .anyMatch(argument -> argument instanceof LambdaExpr lambdaExpr
                        && lambdaExpr.getBody().isBlockStmt());
    }

    private Doc inlineMethodCall(MethodCallExpr expression) {
        Doc scope = expression.getScope().map(expressionRenderer).orElse(Doc.EMPTY);
        String typeArguments = expression.getTypeArguments()
                .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                .orElse("");
        String arguments = "(" + compactSource.compactJoin(expression.getArguments()) + ")";
        return Doc.concat(scope, Doc.text("." + typeArguments + expression.getNameAsString() + arguments));
    }

    private Doc methodCallChainSegment(MethodCallExpr expression) {
        Optional<Comment> rawNameComment = expression.getName().getComment()
                .filter(comment -> comment instanceof LineComment || comment instanceof BlockComment)
                .filter(comment -> CommentIndex.startsBefore(comment, expression.getName()));
        Doc nameComment = rawNameComment.map(comments::comment).orElse(Doc.EMPTY);
        String typeArguments = expression.getTypeArguments()
                .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                .orElse("");
        String prefix = "." + typeArguments + expression.getNameAsString();
        Doc segmentPrefix = nameComment == Doc.EMPTY
                ? Doc.EMPTY
                : rawNameComment.filter(comment -> comment instanceof BlockComment
                                && CommentIndex.startsOnSameLine(comment, expression.getName()))
                        .map(ignored -> Doc.concat(nameComment, Doc.text(" ")))
                        .orElseGet(() -> Doc.concat(nameComment, Doc.HARD_LINE));
        if (expression.getArguments().isEmpty()) {
            Optional<Doc> commentedArguments = emptyMethodCallArguments(prefix, expression);
            if (commentedArguments.isPresent()) {
                return Doc.concat(segmentPrefix, commentedArguments.orElseThrow());
            }
            return Doc.concat(segmentPrefix, Doc.text(prefix + "()"));
        }
        Optional<Doc> huggableLambda = huggableBlockLambdaArguments.apply(prefix, expression.getArguments());
        if (huggableLambda.isPresent()) {
            return Doc.concat(segmentPrefix, huggableLambda.orElseThrow());
        }
        Optional<Doc> commentedExpressionLambda = commentedExpressionLambdaArgument.apply(prefix, expression);
        if (commentedExpressionLambda.isPresent()) {
            return Doc.concat(segmentPrefix, commentedExpressionLambda.orElseThrow());
        }
        Optional<Doc> commentedArguments = commentedExpressionLists.parenthesized(prefix, expression, expression.getArguments());
        if (commentedArguments.isPresent()) {
            return Doc.concat(segmentPrefix, commentedArguments.orElseThrow());
        }
        return Doc.concat(segmentPrefix, Doc.group(Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(Doc.concat(Doc.SOFT_LINE, Doc.join(Doc.concat(Doc.text(","), Doc.LINE), expression.getArguments().stream()
                        .map(expressionRenderer)
                        .toList()))),
                Doc.SOFT_LINE,
                Doc.text(")"))));
    }

    private List<Doc> methodCallChainSegments(List<MethodCallExpr> calls) {
        List<Doc> segments = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            Optional<MethodCallExpr> next = i + 1 < calls.size() ? Optional.of(calls.get(i + 1)) : Optional.empty();
            segments.add(methodCallChainSegment(calls.get(i), next));
        }
        return segments;
    }

    private Doc methodCallChainSegment(MethodCallExpr expression, Optional<MethodCallExpr> nextCall) {
        Doc segment = methodCallChainSegment(expression);
        Doc trailingComment = nextCall
                .map(next -> trailingLineCommentBeforeNextSegment(expression, Optional.of(next)))
                .orElseGet(() -> finalTrailingLineComment(expression));
        return trailingComment == Doc.EMPTY ? segment : Doc.concat(segment, Doc.text(" "), trailingComment);
    }

    private Doc trailingLineCommentBeforeNextSegment(Node expression, Optional<MethodCallExpr> nextCall) {
        if (nextCall.isEmpty()) {
            return Doc.EMPTY;
        }
        MethodCallExpr next = nextCall.orElseThrow();
        List<Doc> sourceComments = trailingLineCommentsBeforeNextSegment(expression, next).stream()
                .map(comments::comment)
                .filter(comment -> comment != Doc.EMPTY)
                .toList();
        return sourceComments.isEmpty() ? Doc.EMPTY : Doc.join(Doc.text(" "), sourceComments);
    }

    private Doc rootTrailingLineCommentBeforeFirstSegment(Expression root, List<MethodCallExpr> calls) {
        if (calls.isEmpty()) {
            return Doc.EMPTY;
        }
        return trailingLineCommentBeforeNextSegment(root, Optional.of(calls.getFirst()));
    }

    private List<JavaCommentTrivia> trailingLineCommentsBeforeNextSegment(Node previous, MethodCallExpr next) {
        return lineCommentCandidatesBeforeNextSegment(next).stream()
                .filter(comment -> comment.startsAfterNodeOnSameLine(previous))
                .filter(comment -> comment.startsBeforeBeginLine(next.getName()))
                .toList();
    }

    private List<JavaCommentTrivia> lineCommentCandidatesBeforeNextSegment(MethodCallExpr next) {
        if (!next.getArguments().isEmpty()) {
            return commentPlacement.lineCommentsBeforeFirst(next, next.getArguments().get(0));
        }
        return commentPlacement.containedComments(next).stream()
                .filter(JavaCommentTrivia::isLine)
                .toList();
    }

    /**
     * Keeps a final segment's same-line comment after the rendered call, even when the call arguments break.
     */
    private Doc finalTrailingLineComment(MethodCallExpr expression) {
        List<Doc> sourceComments = finalTrailingLineComments(expression).stream()
                .map(comments::comment)
                .filter(comment -> comment != Doc.EMPTY)
                .toList();
        return sourceComments.isEmpty() ? Doc.EMPTY : Doc.join(Doc.text(" "), sourceComments);
    }

    private List<JavaCommentTrivia> finalTrailingLineComments(MethodCallExpr expression) {
        List<JavaCommentTrivia> sourceComments = new ArrayList<>();
        commentPlacement.trailingLineComment(expression).ifPresent(sourceComments::add);
        commentPlacement.containedComments(expression).stream()
                .filter(JavaCommentTrivia::isLine)
                .filter(comment -> comment.startsAfterNodeOnSameLine(expression))
                .filter(comment -> sourceComments.stream().noneMatch(existing -> existing.comment() == comment.comment()))
                .forEach(sourceComments::add);
        return sourceComments;
    }

    private Doc fieldAccessMethodCallSegment(FieldAccessExpr fieldAccess, MethodCallExpr methodCall) {
        String typeArguments = methodCall.getTypeArguments()
                .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                .orElse("");
        return Doc.text(fieldAccessSuffixAfterMethodRoot(fieldAccess) + "." + typeArguments + methodCall.getNameAsString()
                + "(" + compactSource.compactJoin(methodCall.getArguments()) + ")");
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

    /**
     * Rebuilds empty argument lists that contain comments JavaParser exposes outside the argument list.
     *
     * <p>For calls like {@code call( // note )}, JavaParser can attach the line comment to the call or its scope rather
     * than to a missing argument node, so this method gathers those source-line comments and orphan comments before
     * deciding the call is really empty.
     */
    private Optional<Doc> emptyMethodCallArguments(String prefix, MethodCallExpr expression) {
        List<Doc> argumentComments = new ArrayList<>();
        Doc firstArgumentComment = comments.ownComment(expression, comment -> comment instanceof LineComment
                && CommentIndex.startsOnBeginLine(comment, expression)
                && CommentIndex.startsBeforeEnd(comment, expression));
        if (firstArgumentComment != Doc.EMPTY) {
            argumentComments.add(firstArgumentComment);
        }
        expression.getScope()
                .map(scope -> comments.ownComment(scope, comment -> comment instanceof LineComment
                        && CommentIndex.startsOnBeginLine(comment, expression)
                        && CommentIndex.startsBeforeEnd(comment, expression)))
                .filter(comment -> comment != Doc.EMPTY)
                .ifPresent(argumentComments::add);
        argumentComments.addAll(comments.orphanCommentStatements(expression));
        if (argumentComments.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.join(Doc.HARD_LINE, argumentComments))),
                Doc.HARD_LINE,
                Doc.text(")")));
    }

    /**
     * Keeps a single text block visually isolated from the call prefix and closing parenthesis.
     *
     * <p>Text blocks already own their internal indentation, so grouping them like ordinary arguments makes trailing
     * comments and the closing parenthesis harder to place predictably.
     */
    private Optional<Doc> singleTextBlockArgument(String prefix, MethodCallExpr expression) {
        if (expression.getArguments().size() != 1
                || !(expression.getArguments().get(0) instanceof TextBlockLiteralExpr textBlockLiteralExpr)) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(Doc.concat(Doc.HARD_LINE, textBlockArgument(textBlockLiteralExpr, expression))),
                Doc.HARD_LINE,
                Doc.text(")")));
    }

    private Optional<Doc> sourceMultilineArguments(MethodCallExpr expression) {
        if (expression.getArguments().isEmpty()
                || !expression.getAllContainedComments().isEmpty()
                || !sourceShape.methodCallArgumentsSpanMultipleLines(expression)) {
            return Optional.empty();
        }
        String prefix = methodCallPrefix(expression);
        return Optional.of(Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(Doc.concat(
                        Doc.HARD_LINE,
                        Doc.join(Doc.concat(Doc.text(","), Doc.HARD_LINE), expression.getArguments().stream()
                                .map(expressionRenderer)
                                .toList()))),
                Doc.HARD_LINE,
                Doc.text(")")));
    }

    private Doc textBlockArgument(TextBlockLiteralExpr textBlockLiteralExpr, MethodCallExpr expression) {
        Doc leading = comments.ownComment(textBlockLiteralExpr, LineComment.class::isInstance);
        Doc literal = Doc.text(unformattedTextBlockRenderer.apply(textBlockLiteralExpr));
        Doc trailing = textBlockSameLineTrailingComment(textBlockLiteralExpr, expression);
        if (leading != Doc.EMPTY) {
            return Doc.concat(leading, Doc.HARD_LINE, literal, trailing);
        }
        return Doc.concat(literal, trailing);
    }

    private Doc textBlockSameLineTrailingComment(TextBlockLiteralExpr textBlockLiteralExpr, MethodCallExpr expression) {
        return expression.getOrphanComments().stream()
                .filter(LineComment.class::isInstance)
                .filter(comment -> CommentIndex.startsOnEndLine(textBlockLiteralExpr, comment))
                .findFirst()
                .map(comment -> Doc.concat(Doc.text(" "), comments.comment(comment)))
                .orElse(Doc.EMPTY);
    }

    /**
     * Breaks a single binary argument under the call when the flat call no longer fits.
     *
     * <p>Binary expressions have their own continuation policy, so the call printer only decides that the binary
     * argument gets the entire broken argument list to itself.
     */
    private Optional<Doc> singleBinaryArgument(
            String prefix,
            NodeList<Expression> arguments,
            MethodCallBreakMode breakMode) {
        if (arguments.size() != 1 || !(arguments.get(0) instanceof BinaryExpr binaryExpr)) {
            return Optional.empty();
        }
        if (!breakMode.isForced()
                && currentIndentedWidth.applyAsInt(prefix + "(" + compactSource.compact(binaryExpr) + ")")
                        <= options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(Doc.concat(Doc.HARD_LINE, brokenBinaryExpressionLinesRenderer.apply(binaryExpr))),
                Doc.HARD_LINE,
                Doc.text(")")));
    }

    Optional<Doc> assignmentWithBrokenMethodCallArguments(AssignExpr assignExpr, MethodCallExpr methodCall) {
        if (methodCall.getArguments().isEmpty() || !methodCall.getAllContainedComments().isEmpty()) {
            return Optional.empty();
        }
        String firstLine = compactSource.compact(assignExpr.getTarget()) + " "
                + assignExpr.getOperator().asString()
                + " "
                + methodCallPrefix(methodCall)
                + "(";
        if (blockStatementWidth.applyAsInt(firstLine) > options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(
                expressionRenderer.apply(assignExpr.getTarget()),
                Doc.text(" " + assignExpr.getOperator().asString() + " "),
                brokenMethodCall(methodCall)));
    }

    boolean shouldPrintScopeAsDoc(Expression expression) {
        return expression instanceof ArrayCreationExpr
                || expression instanceof ArrayAccessExpr
                || expression instanceof TextBlockLiteralExpr
                || expression instanceof EnclosedExpr enclosedExpr
                        && enclosedExpr.getInner() instanceof CastExpr;
    }

    private Doc methodCallLine(MethodCallBreakMode breakMode) {
        return breakMode.argumentLine();
    }

}
