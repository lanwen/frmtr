package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
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
 * arguments, and over-wide binary arguments. The boundary exists so {@link JavaPrinter} can keep broad expression
 * dispatch, enclosed suffix breaking, and binary-expression policy in their current owners while object creation stays
 * in {@link ObjectCreationPrinter}, lambda argument rendering stays in {@link LambdaExpressionPrinter}, commented
 * argument lists stay in {@link CommentedExpressionListPrinter}, and method-call layout reads as one state machine.
 *
 * <p>Representative fixture pairs live at
 * {@code frmtr-core/src/test/resources/format/method-chain-member-access/input.java} with
 * {@code frmtr-core/src/test/resources/format/method-chain-member-access/frmtr-default.output.java} and
 * {@code frmtr-core/src/test/resources/format/text-block-raw-method-call/input.java} with
 * {@code frmtr-core/src/test/resources/format/text-block-raw-method-call/frmtr-default.output.java}; lambda call
 * cases are covered by the two {@code lambda/arrow-parens-*} fixture directories.
 */
final class MethodCallPrinter {

    private final CommentTracker comments;

    private final JavaCommentPlacementPolicy commentPlacement;

    private final SourceShapePolicy sourceShapePolicy;

    private final FormatterOptions options;

    private final LayoutWidth layoutWidth;

    private final CompactSourceText compactSource;

    private final CommentedExpressionListPrinter commentedExpressionLists;

    private final MethodCallChainPrinter methodChains;

    private final JavaFormatRule<Expression> expressionRenderer;

    private final BiFunction<EnclosedExpr, Boolean, Doc> brokenEnclosedForSuffix;

    private final BiFunction<ObjectCreationExpr, String, Doc> objectCreationWithSuffix;

    private final Function<Expression, Optional<Doc>> brokenArgumentExpressionRenderer;

    private final BreakableArgumentExpressionPrinter breakableArguments;

    private final BiFunction<String, NodeList<Expression>, Optional<Doc>> huggableBlockLambdaArguments;

    private final BiFunction<String, MethodCallExpr, Optional<Doc>> commentedExpressionLambdaArgument;

    private final ExpressionLambdaArgumentLayout.HuggableExpressionLambdaArguments huggableExpressionLambdaArguments;

    private final ExpressionLambdaArgumentLayout.PlanFactory expressionLambdaArgumentPlan;

    private final Function<TextBlockLiteralExpr, String> unformattedTextBlockRenderer;

    private final TextBlockArgumentSourceLayout textBlockArguments;

    private final LayoutDecisionLog layoutDecisions;

    private final ArgumentHeaviness argumentHeaviness = new ArgumentHeaviness();

    MethodCallPrinter(
            JavaFormatContext context,
            TypePrinter types,
            JavaFormatRule<Expression> expressionRenderer,
            BiFunction<EnclosedExpr, Boolean, Doc> brokenEnclosedForSuffix,
            Function<ObjectCreationExpr, Doc> brokenObjectCreationRenderer,
            Function<ObjectCreationExpr, Doc> widthDrivenObjectCreationRenderer,
            BiFunction<ObjectCreationExpr, String, Doc> objectCreationWithSuffix,
            Function<ObjectCreationExpr, String> objectCreationPrefix,
            BiFunction<String, NodeList<Expression>, Optional<Doc>> huggableBlockLambdaArguments,
            BiFunction<String, NodeList<Expression>, Optional<Doc>> huggableMethodChainBlockLambdaArguments,
            BiFunction<String, NodeList<Expression>, Optional<String>> huggableBlockLambdaFirstLine,
            BiFunction<String, MethodCallExpr, Optional<Doc>> commentedExpressionLambdaArgument,
            ExpressionLambdaArgumentLayout.HuggableExpressionLambdaArguments huggableExpressionLambdaArguments,
            ExpressionLambdaArgumentLayout.PlanFactory expressionLambdaArgumentPlan,
            Function<LambdaExpr, Optional<Doc>> huggedGapCommentedLambdaBody,
            Function<LambdaExpr, String> lambdaParameters,
            ExpressionLambdaArgumentLayout.ExpressionLambdaMethodCallBodyOpener expressionLambdaMethodCallBodyOpener,
            ExpressionLambdaArgumentLayout.ExpressionLambdaLogicalBinaryBodyOpenerHug expressionLambdaLogicalBinaryBodyOpenerHug,
            Function<TextBlockLiteralExpr, String> unformattedTextBlockRenderer,
            Function<Expression, Optional<Doc>> brokenArgumentExpressionRenderer
    ) {
        this.comments = context.comments;
        this.commentPlacement = context.commentPlacementPolicy;
        this.sourceShapePolicy = context.sourceShapePolicy;
        this.options = context.options;
        this.layoutWidth = context.layoutWidth;
        this.compactSource = context.compactSource;
        this.commentedExpressionLists = new CommentedExpressionListPrinter(
            context,
            node -> expressionRenderer.format(node, LayoutContext.root())
        );
        this.methodChains = new MethodCallChainPrinter(
            context,
            this,
            types,
            this.commentedExpressionLists,
            expressionRenderer,
            brokenObjectCreationRenderer,
            widthDrivenObjectCreationRenderer,
            objectCreationPrefix,
            huggableMethodChainBlockLambdaArguments,
            huggableBlockLambdaFirstLine,
            commentedExpressionLambdaArgument,
            huggableExpressionLambdaArguments,
            expressionLambdaArgumentPlan,
            huggedGapCommentedLambdaBody,
            lambdaParameters,
            expressionLambdaMethodCallBodyOpener,
            expressionLambdaLogicalBinaryBodyOpenerHug
        );
        this.expressionRenderer = expressionRenderer;
        this.brokenEnclosedForSuffix = brokenEnclosedForSuffix;
        this.objectCreationWithSuffix = objectCreationWithSuffix;
        this.brokenArgumentExpressionRenderer = brokenArgumentExpressionRenderer;
        this.breakableArguments = new BreakableArgumentExpressionPrinter(
            context.sourceShapePolicy,
            context.options,
            node -> expressionRenderer.format(node, LayoutContext.root()),
            brokenArgumentExpressionRenderer,
            compactSource::compact,
            methodChains::binaryFansChainOperand,
            context.layoutWidth
        );
        this.huggableBlockLambdaArguments = huggableBlockLambdaArguments;
        this.commentedExpressionLambdaArgument = commentedExpressionLambdaArgument;
        this.huggableExpressionLambdaArguments = huggableExpressionLambdaArguments;
        this.expressionLambdaArgumentPlan = expressionLambdaArgumentPlan;
        this.unformattedTextBlockRenderer = unformattedTextBlockRenderer;
        this.textBlockArguments = new TextBlockArgumentSourceLayout(
            context.sourceText,
            context.options,
            unformattedTextBlockRenderer
        );
        this.layoutDecisions = context.layoutDecisions;
    }

    private ToIntFunction<String> lineWidth(LayoutWidth.LineBudget lineBudget) {
        return text -> layoutWidth.line(lineBudget, text);
    }

    /**
     * The fixed-budget column oracle handed to the expression-lambda hug seams at the top-level method-call argument
     * positions this printer owns (not a fanned chain selector).
     *
     * <p>D3 keystone: reproduces the seam's historical {@code expressionFirstLineWidth} baseline exactly
     * ({@code layoutWidth.line(CONTINUATION, text)}), so threading it is byte-identical. Consuming the true column is the
     * atomic D3 flip, out of scope for this slice.
     */
    private ToIntFunction<String> expressionLambdaColumnWidthFallback() {
        return lineWidth(LayoutWidth.LineBudget.CONTINUATION);
    }

    Doc methodCall(MethodCallExpr expression) {
        return methodCall(expression, LayoutContext.root());
    }

    /**
     * The public method-call entry that receives the caller's {@link LayoutContext}.
     *
     * <p>LDM-2f (#190): the context is threaded from here down to the chain width gates so a follow-up can attribute
     * the same-line {@code leftEdgePrefix} at the rendered column. This slice is pure plumbing — the prefix is not read
     * yet and no width decision changes — so {@link #methodCall(MethodCallExpr)} keeps its {@link LayoutContext#root()}
     * default and output stays byte-identical.
     */
    Doc methodCall(MethodCallExpr expression, LayoutContext layout) {
        return methodCall(expression, MethodCallBreakMode.AUTO, layout);
    }

    Doc brokenMethodCall(MethodCallExpr expression) {
        return methodCall(expression, MethodCallBreakMode.FORCED, LayoutContext.root());
    }

    Doc methodCallWithTail(MethodCallExpr expression, ExpressionTail tail) {
        return methodCallWithTail(expression, tail, LayoutWidth.LineBudget.CURRENT);
    }

    Doc methodCallWithTail(
            MethodCallExpr expression,
            ExpressionTail tail,
            LayoutWidth.LineBudget lineBudget
    ) {
        MethodCallBreakMode breakMode = methodCallWithTailOverflows(expression, tail, lineBudget)
            ? MethodCallBreakMode.FORCED
            : MethodCallBreakMode.AUTO;
        return methodCallWithTail(expression, tail, breakMode, lineBudget);
    }

    // LDM-2f / chain-unify U3 (#190): the statement expression renderer's forced-chain entry (reached only from
    // StatementPrinters). It threads a real LayoutContext (STATEMENT position) instead of the implicit root(), so the
    // statement caller is ready to list a chainFanOut arm through bestFitting in U4. A statement chain owns its own first
    // column, so the leftEdgePrefix is empty (the gate reads stay a no-op) but the chain's first-line width becomes the
    // statement's real rendered column ({@code nodeLine(expression, ...)}, which counts every enclosing block/type) rather
    // than the fixed {@code LineBudget.BLOCK} baseline the seam threaded before. Because a statement always renders at its
    // own block depth (no stacked continuation indent an argument can accumulate), {@code nodeLine} is exactly that
    // column, so already-formatted input is byte-identical while a statement chain nested deeper than the two-level budget
    // is now measured at its true depth. The outer break-or-flat gate (methodCallStatementWidth) still keys on the
    // threaded LineBudget and is left unchanged.
    Doc forcedMethodCallWithTail(
            MethodCallExpr expression,
            ExpressionTail tail,
            LayoutWidth.LineBudget lineBudget
    ) {
        LayoutContext statementLayout = new LayoutContext(
            EnclosingConstruct.STATEMENT,
            "",
            "",
            false
        );
        // Compute the statement's rendered indentation once — nodeIndentWidth walks the ancestor chain, so folding it into
        // the width closure (which the chain probes call repeatedly) would make it O(depth) per probe. The statement's
        // first line carries no leading whitespace, so nodeIndentWidth(expr) + text.length() equals nodeLine(expr, text).
        int statementIndentWidth = layoutWidth.nodeIndentWidth(expression);
        return methodCallWithTail(
            expression,
            tail,
            MethodCallBreakMode.FORCED,
            lineBudget,
            firstLine -> statementIndentWidth + firstLine.length(),
            statementLayout
        );
    }

    Doc brokenMethodCallWithClosingLine(MethodCallExpr expression, String closingLine) {
        String prefix = methodCallPrefix(expression);
        return Doc.concat(
            Doc.text(prefix + "("),
            Doc.indent(
                Doc.concat(
                    Doc.HARD_LINE,
                    methodCallArgumentList(prefix, expression.getArguments(), Doc.HARD_LINE)
                )
            ),
            Doc.HARD_LINE,
            Doc.text(closingLine)
        );
    }

    Optional<Doc> packedExpressionLambdaMethodCallChainBody(String firstLine, MethodCallExpr expression) {
        return methodChains.packedExpressionLambdaBodyChain(firstLine, expression);
    }

    /**
     * Chooses the method-call shape once callers know this expression really is a method call.
     *
     * <p>The unforced path tries a chain shape only when the call itself asks for it; the forced path is used by
     * surrounding expression printers that already decided the call arguments must break.
     */
    private Doc methodCall(MethodCallExpr expression, MethodCallBreakMode breakMode, LayoutContext layout) {
        if (
            expression.getScope().isEmpty()
            && expression.getNameAsString().equals("yield")
            && !expression.getArguments().isEmpty()
        ) {
            return Doc.text("yield (" + compactSource.compactJoin(expression.getArguments()) + ")");
        }
        if (expression.getScope().filter(this::shouldPrintScopeAsDoc).isPresent()) {
            Expression scope = expression.getScope().orElseThrow();
            if (scope instanceof TextBlockLiteralExpr) {
                Optional<Doc> stableTextBlockCall = comments.speculatively(
                    () -> textBlockScopedArgumentList(scope, expression)
                );
                if (stableTextBlockCall.isPresent()) {
                    return stableTextBlockCall.orElseThrow();
                }
            }
            Doc call = methodCallWithoutScope(expression);
            if (scope instanceof TextBlockLiteralExpr) {
                call = Doc.indent(call);
            }
            return Doc.concat(
                expressionRenderer.format(scope, LayoutContext.root()),
                Doc.text("."),
                call
            );
        }
        Optional<Doc> sourceMultilineExpressionLambda = comments.speculatively(
            () -> sourceMultilineExpressionLambda(expression, layout)
        );
        if (sourceMultilineExpressionLambda.isPresent()) {
            return sourceMultilineExpressionLambda.orElseThrow();
        }
        if (!breakMode.isForced()) {
            Optional<Doc> chain = comments.speculatively(() -> methodCallChain(expression, layout));
            if (chain.isPresent()) {
                return chain.orElseThrow();
            }
        } else if (methodCallChainIsSourceMultiline(expression) || chainFansByCanonicalRule(expression)) {
            // A FORCED chain that fans by the canonical rule
            // ({@code chainBreaksByRule}: an object-creation / no-scope-call root with two or more selectors, a factory
            // root with two or more selectors after the factory call, or a plain receiver with three or more) routes to
            // the source-neutral {@code chainFanOut} through {@code chainFansByCanonicalRule}, which admits it even when
            // {@code methodCallChainIsSourceMultiline} is false (as it is for a reads-clean chain). Such a FORCED chain reached
            // through {@code brokenMethodCall} — the object-creation-rooted-chain lambda body / initializer over-width
            // blocker ({@code map(entry -> new OffsetFetchRequestTopics().setName(...).setPartitionIndexes(...))}), and
            // every other canonical-fan chain rendered via a broken enclosing construct — would otherwise fall through to
            // the plain method-call render that keeps the chain flat and breaks only the final call's arguments (the
            // over-width blocker). {@code chainFanOut} renders the root width-driven (an object-creation root through
            // {@code promotedObjectCreationRootDoc}'s {@code Doc.group}, whose constructor argument list breaks at the
            // renderer's live column) and fans every selector onto its own dotted continuation line — a pure function of
            // the AST, so both passes rebuild the identical fan (idempotence Δ0). {@code chainFansByCanonicalRule} already
            // excludes comment-bearing and block-lambda chains, so this only routes the clean fan-threshold chains a
            // FORCED render must break anyway.
            Optional<Doc> chain = comments.speculatively(() -> methodCallChain(expression, breakMode, "", layout));
            if (chain.isPresent()) {
                return chain.orElseThrow();
            }
        }
        Optional<Doc> sourceMultilineArguments = comments.speculatively(() -> sourceMultilineArguments(expression));
        if (sourceMultilineArguments.isPresent()) {
            return sourceMultilineArguments.orElseThrow();
        }
        Optional<Doc> suffixedEnclosed = comments.speculatively(() -> suffixedEnclosedMethodCall(expression, false));
        if (suffixedEnclosed.isPresent()) {
            return suffixedEnclosed.orElseThrow();
        }
        String prefix = methodCallPrefix(expression);
        if (expression.getArguments().isEmpty()) {
            Optional<Doc> commentedArguments = comments.speculatively(() -> emptyMethodCallArguments(prefix, expression));
            if (commentedArguments.isPresent()) {
                return commentedArguments.orElseThrow();
            }
            return Doc.text(prefix + "()");
        }
        // A heavy argument list (see {@link ArgumentHeaviness}) must break one-per-line, so a trailing lambda argument
        // must NOT hug the opener — hugging keeps the sibling arguments on the opener line. Skip the pure lambda-hug
        // branches when heavy and let the call fall through to the generic exploded argument list below, which forces the
        // break. Method calls do not opt into the wide-argument-count rule (a five-argument method call is common); only
        // the nested-token signal marks a plain call heavy. The commented-expression-lambda branch is left ungated so a
        // heavy call still preserves an argument comment through its own comment-aware layout.
        boolean heavy = argumentHeaviness.isHeavy(expression.getArguments(), false);
        if (!heavy) {
            Optional<Doc> huggableLambda = comments.speculatively(
                () -> huggableBlockLambdaArguments.apply(prefix, expression.getArguments())
            );
            if (huggableLambda.isPresent()) {
                return huggableLambda.orElseThrow();
            }
        }
        Optional<Doc> commentedExpressionLambda = comments.speculatively(
            () -> commentedExpressionLambdaArgument.apply(prefix, expression)
        );
        if (commentedExpressionLambda.isPresent()) {
            return commentedExpressionLambda.orElseThrow();
        }
        if (!heavy) {
            Optional<Doc> huggableExpressionLambda = comments.speculatively(
                () -> huggableExpressionLambdaArguments.render(
                    prefix,
                    expression.getArguments(),
                    expressionLambdaColumnWidthFallback()
                )
            );
            if (huggableExpressionLambda.isPresent()) {
                return huggableExpressionLambda.orElseThrow();
            }
        }
        Optional<Doc> brokenExpressionLambdaArguments = comments.speculatively(
            () -> brokenExpressionLambdaArgumentsForOverflow(prefix, expression, layout)
        );
        if (brokenExpressionLambdaArguments.isPresent()) {
            return brokenExpressionLambdaArguments.orElseThrow();
        }
        Optional<Doc> singleTextBlockArgument = comments.speculatively(
            () -> singleTextBlockArgument(prefix, expression)
        );
        if (singleTextBlockArgument.isPresent()) {
            return singleTextBlockArgument.orElseThrow();
        }
        Optional<Doc> singleObjectCreationArgument = comments.speculatively(
            () -> singleObjectCreationArgument(prefix, expression)
        );
        if (singleObjectCreationArgument.isPresent()) {
            return singleObjectCreationArgument.orElseThrow();
        }
        // Canonical-fan cutover seam, the single-CHAIN-ARGUMENT hug position (#190). Checked BEFORE the
        // {@link #singleMethodCallArgument} hug and the generic exploded argument list below — because
        // the oscillation it closes is exactly those two disagreeing across passes for a call whose sole argument is a
        // fan-threshold chain ({@code assertTrue(result.getExecutionInfo().get(0).contains(...))},
        // {@code Arrays.stream(res.getKey().trim().split(...))}, {@code Optional.of(description.partitions()....leader())}).
        // One shape hugs the chain onto the opener ({@code assertTrue(result}⏎{@code .getExecutionInfo()…}) while the other
        // breaks the argument onto its own indented line
        // ({@code assertTrue(}⏎{@code result}⏎{@code .getExecutionInfo()…}⏎{@code )}), so an unranked verdict alternates
        // between the two shapes.
        // Ranking the hug and the exploded arm with {@code Doc.bestFitting} at the true rendered column makes the verdict a
        // fixpoint by construction, the same {@code bestFitting}-over-one-{@code chainFanOut} technique the initializer
        // break-after-{@code =} and lambda-body arrow seams use. Self-gates to fan-threshold comment/lambda-free single
        // chain arguments; every other single-call argument still reaches the hug / generic paths below.
        Optional<Doc> singleFanChainArgument = comments.speculatively(
            () -> singleFanChainArgumentBestFitting(prefix, expression)
        );
        if (singleFanChainArgument.isPresent()) {
            return singleFanChainArgument.orElseThrow();
        }
        Optional<Doc> singleMethodCallArgument = comments.speculatively(
            () -> singleMethodCallArgument(prefix, expression)
        );
        if (singleMethodCallArgument.isPresent()) {
            return singleMethodCallArgument.orElseThrow();
        }
        Optional<Doc> singleBinaryArgument = comments.speculatively(
            () -> singleBinaryArgument(prefix, expression.getArguments(), breakMode)
        );
        if (singleBinaryArgument.isPresent()) {
            return singleBinaryArgument.orElseThrow();
        }
        Optional<Doc> commentedArguments = comments.speculatively(
            () -> commentedExpressionLists.parenthesized(prefix, expression, expression.getArguments())
        );
        if (commentedArguments.isPresent()) {
            return commentedArguments.orElseThrow();
        }
        Doc call = Doc.concat(
            heavy ? Doc.BREAK_PARENT : Doc.EMPTY,
            Doc.text(prefix + "("),
            Doc.indent(
                Doc.concat(
                    methodCallLine(breakMode),
                    methodCallArgumentList(prefix, expression.getArguments(), Doc.LINE)
                )
            ),
            methodCallLine(breakMode),
            Doc.text(")")
        );
        if (breakMode.isForced()) {
            recordArgumentListWidthBreak(expression, prefix);
            return call;
        }
        return Doc.group(call);
    }

    private Doc methodCallWithTail(
            MethodCallExpr expression,
            ExpressionTail tail,
            MethodCallBreakMode breakMode,
            LayoutWidth.LineBudget lineBudget
    ) {
        return methodCallWithTail(expression, tail, breakMode, lineBudget, lineWidth(lineBudget), LayoutContext.root());
    }

    // LDM-2f / chain-unify U3 (#190): the with-tail seam threads a caller-chosen first-line width and LayoutContext to
    // the chain gates. The default overload above still passes {@code lineWidth(lineBudget)} + {@code root()} (so every
    // existing caller — array elements, expression-with-tail — stays byte-identical). The statement caller
    // (StatementPrinters#forcedMethodCallWithTail) threads a {@code nodeLine}-based first-line width so the chain measures
    // at the statement's real rendered column instead of the fixed {@code LineBudget.BLOCK} baseline, and a
    // {@code LayoutContext} (empty {@code leftEdgePrefix} — a statement chain owns its own first column) so a later slice
    // can route the statement fan-out through {@code bestFitting}.
    private Doc methodCallWithTail(
            MethodCallExpr expression,
            ExpressionTail tail,
            MethodCallBreakMode breakMode,
            LayoutWidth.LineBudget lineBudget,
            ToIntFunction<String> firstLineWidth,
            LayoutContext layout
    ) {
        // Canonical-fan cutover seam (End-state A): a fan-threshold, comment/lambda-free EXPRESSION-STATEMENT chain
        // ({@code foo.a().b().c();}) fans one selector per line, and it must do so through the SAME source-neutral fan on
        // every pass. On a source-multiline-argument pass the FORCED methodCallChain below skips its own canonical-fan routes
        // (they gate {@code !sourceMultilineArguments}) and lands on the imperative {@code canAttachFirstSegmentToSimpleRoot}
        // branch, which folds the first selector onto a simple receiver root ({@code active.createTopics(...)}); the
        // already-fanned re-format then has single-line arguments, the early fan route fires, and the first selector splits
        // onto its own line ({@code active}⏎{@code .createTopics(...)}). The two passes disagree forever. Routing the chain
        // through {@code chainFanOut} here, ahead of the source-shape-sensitive branch, makes both passes rebuild the
        // identical fan (a fixpoint by construction, the same argument the initializer / factory-root / assignment-RHS
        // cutover seams rely on). Expression-lambda / comment-bearing chains are withheld inside {@code canonicalFanChain}
        // (deferred lambda-arrow seam) and stay on the imperative branches below.
        //
        // Scoped to the STATEMENT position (the statement caller threads {@code EnclosingConstruct.STATEMENT} and the empty
        // {@code leftEdgePrefix} of a statement that owns its own first column). The other tail callers reaching this
        // overload pass {@code LayoutContext.root()}: array elements and expression-with-tail (whose fan is out of this
        // seam's scope) and, notably, the single-inner-call HUG shape {@code outer(inner(...))} in
        // {@link #singleMethodCallArgument}, whose deliberate opener-hug the fan would break. Those keep their existing
        // routing until their own seam; only the statement chain fans here.
        // A canonical fan ({@code chainFansByCanonicalRule}) reaches this with-tail seam through two doors: a STATEMENT-position
        // chain ({@code foo.a().b().c();}, threaded with {@code EnclosingConstruct.STATEMENT}), and a trailing-line-comment
        // return / initializer chain ({@code return streamsListResult.streams().get(0).streamArn(); // XXX}) routed here by its
        // printer's final-trailing-comment special case. Both must fan through the SAME source-neutral {@code canonicalFanChain}
        // on every pass. The trailing-comment door is source-shape-fragile: JavaParser parks the {@code // XXX} on the STATEMENT
        // when the chain is flat but on the LAST SELECTOR once it breaks, so the comment only reaches THIS seam on the broken
        // (comment-visible) pass — the flat pass fans the same chain through {@code canonicalFanChain} at its printer's
        // comment-free route (e.g. the return conditional group). Routing the comment-visible pass through {@code canonicalFanChain}
        // here too makes both passes rebuild the identical fan instead of dropping to the imperative fan-from-first ladder
        // (the camel {@code ShardIteratorHandler} / {@code CsvDataFormat} / {@code DefaultSupervisingRouteController} /
        // {@code ExportBaseCommand} residuals). Comment-free non-statement chains are NOT admitted here (the extra condition
        // requires a final trailing line comment), so array-element / single-argument-hug callers keep their existing routing.
        if (
            !tail.isEmpty()
            && (layout.enclosing() == EnclosingConstruct.STATEMENT
                || methodChains.chainFansByCanonicalRuleWithTrailingLineComment(expression))
        ) {
            Optional<Doc> canonicalFan = comments.speculatively(
                () -> methodChains.canonicalFanChain(expression, tail.text(), layout)
            );
            if (canonicalFan.isPresent()) {
                return canonicalFan.orElseThrow();
            }
        }
        if (tail.isEmpty()) {
            return methodCall(expression, breakMode, layout);
        }
        Optional<Doc> chain = comments.speculatively(
            () -> methodCallChain(expression, breakMode, tail.text(), lineBudget, firstLineWidth, layout)
        );
        if (chain.isPresent()) {
            return chain.orElseThrow();
        }
        if (finalTrailingLineComments(expression).isEmpty()) {
            Optional<Doc> unsuffixedChain = comments.speculatively(
                () -> methodCallChain(expression, breakMode, "", lineBudget, firstLineWidth, layout)
            );
            if (unsuffixedChain.isPresent()) {
                return tail.appendTo(unsuffixedChain.orElseThrow());
            }
        }
        return appendTailBeforeFinalTrailingLineComment(
            methodCall(expression, breakMode, layout),
            expression,
            tail
        );
    }

    private boolean methodCallWithTailOverflows(
            MethodCallExpr expression,
            ExpressionTail tail,
            LayoutWidth.LineBudget lineBudget
    ) {
        return layoutWidth.line(lineBudget, compactSource.compact(expression) + tail.text()) > options.lineWidth();
    }

    private Doc appendTailBeforeFinalTrailingLineComment(
            Doc call,
            MethodCallExpr expression,
            ExpressionTail tail
    ) {
        Doc trailingComment = finalTrailingLineComment(expression);
        if (trailingComment == Doc.EMPTY) {
            return tail.appendTo(call);
        }
        return Doc.concat(call, tail.doc(), Doc.text(" "), trailingComment);
    }

    private Doc finalTrailingLineComment(MethodCallExpr expression) {
        List<Doc> sourceComments = finalTrailingLineComments(expression)
                .stream()
                .map(comments::comment)
                .filter(comment -> comment != Doc.EMPTY)
                .toList();
        return sourceComments.isEmpty() ? Doc.EMPTY : Doc.join(Doc.text(" "), sourceComments);
    }

    private List<JavaCommentTrivia> finalTrailingLineComments(MethodCallExpr expression) {
        List<JavaCommentTrivia> sourceComments = new ArrayList<>();
        commentPlacement.trailingLineComment(expression).ifPresent(sourceComments::add);
        commentPlacement.containedComments(expression)
                .stream()
                .filter(JavaCommentTrivia::isLine)
                .filter(comment -> comment.startsAfterNodeOnSameLine(expression))
                .filter(
                    comment -> sourceComments.stream().noneMatch(existing -> existing.comment() == comment.comment())
                )
                .forEach(sourceComments::add);
        return sourceComments;
    }

    /**
     * Records the argument list's flat-width decision when a forced call breaks because its single-line form overflowed
     * the budget, so explain can report the real arithmetic instead of an opaque forced break.
     *
     * <p>The auto path is left to the renderer, which width-fits its {@link Doc.Group} and is already explained by the
     * renderer trace. Only the forced path, where the caller already measured an overflow and the printer emits hard
     * breaks, needs the printer to record its own measurement. Recording an argument list whose compact form would
     * still fit is skipped, since such a forced break is not a width decision. This runs after the broken shape is
     * built and does not change it.
     */
    private void recordArgumentListWidthBreak(MethodCallExpr expression, String prefix) {
        String compactArguments = compactSource.compactJoin(expression.getArguments());
        String compactCall = prefix + "(" + compactArguments + ")";
        // C10-a: --explain-only measurement at the call's rendered column (mirrors ChainWidthBreakExplain#record);
        // never influences the emitted Doc, only the recorded flatWidth and the self-gate below.
        int flatWidth = layoutWidth.nodeLine(expression, compactCall);
        if (flatWidth <= options.lineWidth()) {
            return;
        }
        layoutDecisions.recordWidthBreak(
            "argument list",
            "java.expression:" + expression.getClass().getSimpleName(),
            prefix + "(…)",
            flatWidth,
            options.lineWidth(),
            expression.getArguments().size()
        );
    }

    private Optional<Doc> brokenExpressionLambdaArgumentsForOverflow(
            String prefix,
            MethodCallExpr expression,
            LayoutContext layout
    ) {
        Optional<ExpressionLambdaArgumentLayout.Plan> plan = expressionLambdaArgumentPlan.plan(
            prefix,
            expression.getArguments(),
            layout
        );
        if (plan.filter(argument -> expressionLambdaBodyOpenerOverflows(expression, argument, layout)).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(
                    Doc.concat(
                        Doc.HARD_LINE,
                        methodCallArgumentList(prefix, expression.getArguments(), Doc.HARD_LINE)
                    )
                ),
                Doc.HARD_LINE,
                Doc.text(")")
            )
        );
    }

    String methodCallPrefix(MethodCallExpr expression) {
        return expression.getScope().map(scope -> compactSource.compact(scope) + ".").orElse("")
            + expression.getTypeArguments()
                    .map(typeArguments -> "<" + compactSource.compactJoin(typeArguments) + ">")
                    .orElse("")
            + expression.getNameAsString();
    }

    /**
     * Renders {@code """…""".name(arg, …)} with one stable argument indent regardless of whether the source already
     * broke the argument list.
     *
     * <p>A multi-argument text-block-scoped call reaches one of two layouts depending only on the source shape of its
     * arguments. When the argument list is already source-multiline, a {@code return} routes the call through
     * {@link #sourceMultilineArguments} (via {@code ReturnExpressionPrinter}'s source-multiline-method-call hook), which
     * lays the arguments one indent under the statement base with the closing paren on the statement-base column. When the
     * source keeps the arguments flat, the scope branch instead width-fits {@link #methodCallWithoutScope} inside an extra
     * {@link Doc#indent(Doc)}, so a width-driven break lands the same arguments one further indent in. The two shapes
     * disagree by exactly one indent unit, which makes {@code format(format(x)) != format(x)} once a flat call overflows
     * and the next pass re-reads the now source-multiline arguments. This method makes the flat-source overflow path emit
     * the same forced one-indent shape {@link #sourceMultilineArguments} produces, so both source shapes converge on the
     * source-multiline fixed point.
     *
     * <p>It intentionally yields ({@link Optional#empty()}) for the layouts the scope branch must keep owning unchanged.
     * Single-argument calls are left alone: a lone text block, object creation, or method call hugs the opener so its own
     * body breaks under it, and those shapes are stable across passes because they never take the source-multiline hook;
     * only a list of two or more arguments drifts. A flat call whose compact closing line still fits stays flat, a
     * huggable expression lambda keeps its hugged shape, and any contained comments defer to the commented-argument list,
     * matching the cases {@link #sourceMultilineArguments} also declines so the convergence stays scoped to the plain
     * breakable multi-argument list that would otherwise drift.
     */
    private Optional<Doc> textBlockScopedArgumentList(Expression scope, MethodCallExpr expression) {
        if (
            !(scope instanceof TextBlockLiteralExpr textBlockLiteralExpr)
            || expression.getArguments().size() < 2
            || !expression.getAllContainedComments().isEmpty()
            || hasHuggableExpressionLambdaArgument(expression)
        ) {
            return Optional.empty();
        }
        String selector = methodCallSelector(expression);
        String literal = unformattedTextBlockRenderer.apply(textBlockLiteralExpr);
        String closingLine = literal.substring(literal.lastIndexOf('\n') + 1)
            + "." + selector + "(" + compactSource.compactJoin(expression.getArguments()) + ")";
        if (closingLine.length() <= options.lineWidth()) {
            return Optional.empty();
        }
        String prefix = methodCallPrefix(expression);
        return Optional.of(
            Doc.concat(
                expressionRenderer.format(scope, LayoutContext.root()),
                Doc.text("." + selector + "("),
                Doc.indent(
                    Doc.concat(
                        Doc.HARD_LINE,
                        methodCallArgumentList(prefix, expression.getArguments(), Doc.HARD_LINE)
                    )
                ),
                Doc.HARD_LINE,
                Doc.text(")")
            )
        );
    }

    Doc methodCallWithoutScope(MethodCallExpr expression) {
        String prefix =
            expression.getTypeArguments()
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
        Optional<Doc> commentedArguments = commentedExpressionLists.parenthesized(
            prefix,
            expression,
            expression.getArguments()
        );
        if (commentedArguments.isPresent()) {
            return commentedArguments.orElseThrow();
        }
        return Doc.group(
            Doc.concat(
                argumentHeaviness.isHeavy(expression.getArguments(), false) ? Doc.BREAK_PARENT : Doc.EMPTY,
                Doc.text(prefix + "("),
                Doc.indent(
                    Doc.concat(
                        Doc.SOFT_LINE,
                        methodCallArgumentList(prefix, expression.getArguments(), Doc.LINE)
                    )
                ),
                Doc.SOFT_LINE,
                Doc.text(")")
            )
        );
    }

    Optional<Doc> suffixedEnclosedMethodCall(MethodCallExpr expression, boolean leadingBreak) {
        return expression.getScope()
                .filter(EnclosedExpr.class::isInstance)
                .map(EnclosedExpr.class::cast)
                .filter(scope -> leadingBreak
                        // C10-b: measure the enclosed-scope call at its true rendered block/type depth
                        // ({@link LayoutWidth#nodeLine}) instead of the fixed BLOCK baseline.
                        || layoutWidth.nodeLine(expression, compactSource.compact(expression) + ";")
                            > options.lineWidth()
                )
                .map(scope -> Doc.concat(
                        brokenEnclosedForSuffix.apply(scope, leadingBreak),
                        Doc.text("."),
                        methodCallWithoutScope(expression)
                ));
    }

    Optional<Doc> methodCallChain(MethodCallExpr expression) {
        return methodChains.methodCallChain(expression);
    }

    Optional<Doc> methodCallChain(MethodCallExpr expression, LayoutContext layout) {
        return methodChains.methodCallChain(expression, layout);
    }

    Optional<Doc> forcedMethodCallChain(MethodCallExpr expression) {
        return methodChains.forcedMethodCallChain(expression);
    }

    Optional<Doc> forcedMethodCallChain(
            MethodCallExpr expression,
            LayoutWidth.LineBudget lineBudget
    ) {
        return methodChains.forcedMethodCallChain(expression, lineBudget);
    }

    // LDM-2f (#190): the layout-carrying delegators the return chain uses to thread its {@code "return "} left-edge
    // prefix down to the chain width gates. Callers without a prefix keep the overloads above (which pass {@code root()}),
    // so they stay byte-identical until their own activation slice.
    Optional<Doc> forcedMethodCallChain(
            MethodCallExpr expression,
            LayoutWidth.LineBudget lineBudget,
            LayoutContext layout
    ) {
        return methodChains.forcedMethodCallChain(expression, lineBudget, layout);
    }

    Optional<Doc> forcedMethodCallChain(
            MethodCallExpr expression,
            ToIntFunction<String> firstLineWidth
    ) {
        return methodChains.forcedMethodCallChain(expression, firstLineWidth);
    }

    Optional<Doc> forcedMethodCallChain(
            MethodCallExpr expression,
            ToIntFunction<String> firstLineWidth,
            LayoutContext layout
    ) {
        return methodChains.forcedMethodCallChain(expression, firstLineWidth, layout);
    }

    Optional<Doc> packedMethodCallChain(
            MethodCallExpr expression,
            ToIntFunction<String> firstLineWidth
    ) {
        return methodChains.packedMethodCallChain(expression, firstLineWidth);
    }

    Optional<String> compactMethodCallChainRoot(MethodCallExpr expression) {
        return methodChains.compactMethodCallChainRoot(expression);
    }

    Optional<Doc> compactRootWithBrokenFinalChainSegment(
            MethodCallExpr expression,
            LayoutWidth.LineBudget lineBudget
    ) {
        return methodChains.compactRootWithBrokenFinalChainSegment(expression, lineBudget);
    }

    // LDM-2f (#190): the layout-carrying delegator the return chain uses to thread its {@code "return "} left-edge prefix
    // down to {@code compactRootLineWidth}. The no-{@code layout} overload above passes {@code root()}.
    Optional<Doc> compactRootWithBrokenFinalChainSegment(
            MethodCallExpr expression,
            LayoutWidth.LineBudget lineBudget,
            LayoutContext layout
    ) {
        return methodChains.compactRootWithBrokenFinalChainSegment(expression, lineBudget, layout);
    }

    // Canonical-fan cutover seam (End-state A): the delegator the return chain uses so a fan-threshold, comment/lambda-free
    // return chain fans through the source-neutral {@code chainFanOut}, ahead of the return caller's source-multiline
    // branches. The return caller threads {@code withLeftEdgePrefix("return ")}; the suffix is empty (the return terminator
    // {@code ;} is appended outside the value).
    Optional<Doc> canonicalFanChain(
            MethodCallExpr expression,
            String finalSegmentSuffix,
            LayoutContext layout
    ) {
        return methodChains.canonicalFanChain(expression, finalSegmentSuffix, layout);
    }

    // Canonical-fan cutover seam U8: the boolean sibling of {@link #canonicalFanChain}, used by the broken-argument
    // printer to detect that a binary/ternary argument's dispatched {@code flat} rendering already fans a chain operand by
    // the End-state A rule, so it must not also offer the source-shape-sensitive operand-per-line {@code broken} arm.
    boolean chainFansByCanonicalRule(MethodCallExpr expression) {
        return methodChains.chainFansByCanonicalRule(expression);
    }

    // Canonical-fan cutover seam (G bucket): the binary/logical/string-concat OPERAND sibling of
    // {@link #chainFansByCanonicalRule}. Reports whether a binary/ternary expression contains a chain operand the
    // End-state A rule fans, so a caller whose flat arm already fans that operand must commit the flat shape instead of a
    // source-shape-gated operand-per-line break. See {@link MethodCallChainPrinter#binaryFansChainOperand}.
    boolean binaryFansChainOperand(Expression expression) {
        return methodChains.binaryFansChainOperand(expression);
    }

    // Canonical-fan cutover seam U7: the lambda-body-position gate — the End-state A rule scoped to the roots the
    // lambda-body fan renders idempotently (object-creation roots withheld; see
    // {@link MethodCallChainPrinter#lambdaBodyChainFansByCanonicalRule}).
    boolean lambdaBodyChainFansByCanonicalRule(MethodCallExpr expression) {
        return methodChains.lambdaBodyChainFansByCanonicalRule(expression);
    }

    // Trivial-receiver first-selector attach: reports whether a chain's root is a trivial receiver, so the lambda-body
    // arrow seam keeps such a body anchored on the {@code ->} line. See
    // {@link MethodCallChainPrinter#chainRootIsTrivialReceiver(MethodCallExpr)}.
    boolean chainRootIsTrivialReceiver(MethodCallExpr expression) {
        return methodChains.chainRootIsTrivialReceiver(expression);
    }

    Optional<Doc> sourceMultilineMethodCallStatement(
            MethodCallExpr expression,
            ExpressionStmt statement
    ) {
        return methodChains.sourceMultilineMethodCallStatement(expression, statement);
    }

    Optional<Doc> methodCallChain(MethodCallExpr expression, boolean force) {
        return methodChains.methodCallChain(expression, force);
    }

    Optional<Doc> methodCallChain(
            MethodCallExpr expression,
            MethodCallBreakMode breakMode,
            String finalSegmentSuffix
    ) {
        return methodChains.methodCallChain(expression, breakMode, finalSegmentSuffix, LayoutContext.root());
    }

    Optional<Doc> methodCallChain(
            MethodCallExpr expression,
            MethodCallBreakMode breakMode,
            String finalSegmentSuffix,
            LayoutContext layout
    ) {
        return methodChains.methodCallChain(expression, breakMode, finalSegmentSuffix, layout);
    }

    Optional<Doc> methodCallChain(
            MethodCallExpr expression,
            MethodCallBreakMode breakMode,
            String finalSegmentSuffix,
            LayoutWidth.LineBudget lineBudget
    ) {
        return methodChains.methodCallChain(expression, breakMode, finalSegmentSuffix, lineBudget, LayoutContext.root());
    }

    Optional<Doc> methodCallChain(
            MethodCallExpr expression,
            MethodCallBreakMode breakMode,
            String finalSegmentSuffix,
            LayoutWidth.LineBudget lineBudget,
            ToIntFunction<String> firstLineWidth
    ) {
        return methodChains.methodCallChain(
            expression,
            breakMode,
            finalSegmentSuffix,
            lineBudget,
            firstLineWidth,
            LayoutContext.root()
        );
    }

    // LDM-2f / chain-unify U3 (#190): the layout-carrying overload used by the statement with-tail seam to thread its
    // rendered-column first-line width alongside a real LayoutContext (empty prefix). The overload above keeps passing
    // {@code root()} for callers that have no context yet.
    Optional<Doc> methodCallChain(
            MethodCallExpr expression,
            MethodCallBreakMode breakMode,
            String finalSegmentSuffix,
            LayoutWidth.LineBudget lineBudget,
            ToIntFunction<String> firstLineWidth,
            LayoutContext layout
    ) {
        return methodChains.methodCallChain(
            expression,
            breakMode,
            finalSegmentSuffix,
            lineBudget,
            firstLineWidth,
            layout
        );
    }

    Optional<Doc> mixedFieldMethodCallChain(MethodCallExpr expression) {
        return methodChains.mixedFieldMethodCallChain(expression);
    }

    Optional<Expression> mixedFieldMethodCallRoot(MethodCallExpr expression) {
        return methodChains.mixedFieldMethodCallRoot(expression);
    }

    boolean methodCallChainHasComments(MethodCallExpr expression) {
        return methodChains.methodCallChainHasComments(expression);
    }

    boolean methodCallChainHasFinalTrailingLineComment(MethodCallExpr expression) {
        return methodChains.methodCallChainHasFinalTrailingLineComment(expression);
    }

    boolean methodCallChainIsSourceMultiline(MethodCallExpr expression) {
        return methodChains.methodCallChainIsSourceMultiline(expression);
    }

    MethodCallChainSourcePlanner.InitializerChainShape methodCallChainInitializerShape(MethodCallExpr expression) {
        return methodChains.methodCallChainInitializerShape(expression);
    }

    boolean methodCallChainRootIsObjectCreation(MethodCallExpr expression) {
        return methodChains.methodCallChainRootIsObjectCreation(expression);
    }

    boolean methodCallChainRootIsFieldAccess(MethodCallExpr expression) {
        return methodChains.methodCallChainRootIsFieldAccess(expression);
    }

    String methodCallChainFirstLine(MethodCallExpr expression) {
        return methodChains.methodCallChainFirstLine(expression);
    }

    /**
     * Rebuilds empty argument lists that contain comments JavaParser exposes outside the argument list.
     *
     * <p>For calls like {@code call( // note )}, JavaParser can attach the line comment to the call, its scope, or its
     * name rather than to a missing argument node, so this method gathers those source-line comments and orphan comments
     * before deciding the call is really empty.
     *
     * <p>The where-it-attaches choice is shape-dependent: at the source-multiline shape {@code // note} is the call's
     * own orphan comment, but collapsing {@code call(// note)} onto one line makes JavaParser re-attach it to the call's
     * name child as the name's own trivia. Recovering the name's own line comment too keeps the same comment owned by
     * the call regardless of layout. Each recovery is source-bounded to the inside of the parens (begins before the call
     * end), so a comment that actually trails the completed call stays out of this empty-argument path.
     *
     * <p>The orphan recovery additionally excludes any orphan that lies in the gap <em>between the scope and the
     * selector name</em> — the between-links comment of a method chain (e.g. {@code .define(A) /** doc *}{@code / .util()}).
     * That comment belongs before the selector, not inside its empty {@code ()}, and is recovered by
     * {@link MethodCallChainPrinter}'s between-links slot. Both slots offer the orphan under the same
     * {@code (expression, ORPHAN)} key, so claiming it here too would double-claim it; partitioning by source position
     * (inside-parens orphans here, between-scope-and-name orphans there) keeps each orphan claimed exactly once.
     */
    Optional<Doc> emptyMethodCallArguments(String prefix, MethodCallExpr expression) {
        List<Doc> argumentComments = new ArrayList<>();
        Doc callOwnComment = comments.ownComment(
            expression,
            comment -> isLineCommentInsideParens(comment, expression)
                    && CommentIndex.startsOnBeginLine(comment, expression)
        );
        if (callOwnComment != Doc.EMPTY) {
            argumentComments.add(callOwnComment);
        }
        expression.getScope()
                .map(scope -> comments.ownComment(
                        scope,
                        comment -> isLineCommentInsideParens(comment, expression)
                                && CommentIndex.startsOnBeginLine(comment, expression)
                ))
                .filter(comment -> comment != Doc.EMPTY)
                .ifPresent(argumentComments::add);
        Doc nameOwnComment = comments.ownComment(
            expression.getName(),
            comment -> isLineCommentInsideParens(comment, expression)
                    && CommentIndex.startsAfterEndOf(expression.getName(), comment)
        );
        if (nameOwnComment != Doc.EMPTY) {
            argumentComments.add(nameOwnComment);
        }
        argumentComments.addAll(
            comments.orphanCommentStatements(expression, comment -> !isInterspersedBeforeSelector(comment, expression))
        );
        if (argumentComments.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.join(Doc.HARD_LINE, argumentComments))),
                Doc.HARD_LINE,
                Doc.text(")")
            )
        );
    }

    /** A line comment that begins before the call's source end, i.e. inside the parentheses rather than trailing it. */
    private static boolean isLineCommentInsideParens(Comment comment, MethodCallExpr expression) {
        return comment instanceof LineComment && CommentIndex.startsBeforeEnd(comment, expression);
    }

    /**
     * A between-links chain comment: an orphan that sits in the source gap between the call's scope and its selector
     * name (e.g. the {@code /** doc *}{@code /} in {@code .define(A) /** doc *}{@code / .util()}). Such a comment belongs
     * before the selector, where {@link MethodCallChainPrinter}'s between-links slot recovers it, not inside the empty
     * {@code ()} this empty-argument path renders. Excluding it here partitions the call's orphan pool by source
     * position so the same comment is never claimed by both slots.
     */
    private static boolean isInterspersedBeforeSelector(Comment comment, MethodCallExpr expression) {
        return expression.getScope()
                .map(scope -> CommentIndex.liesBetween(comment, scope, expression.getName()))
                .orElse(false);
    }

    /**
     * Keeps a single text block visually isolated from the call prefix and closing parenthesis.
     *
     * <p>Text blocks already own their internal indentation, so grouping them like ordinary arguments makes trailing
     * comments and the closing parenthesis harder to place predictably.
     */
    private Optional<Doc> singleTextBlockArgument(String prefix, MethodCallExpr expression) {
        if (
            expression.getArguments().size() != 1
            || !(expression.getArguments().get(0) instanceof TextBlockLiteralExpr textBlockLiteralExpr)
        ) {
            return Optional.empty();
        }
        // A line comment sitting before or after the text-block argument (// leading / // trailing around the block)
        // lives in the argument gaps, which this hug-the-block layout does not render. Defer to the commented argument
        // list so the surrounding comment is preserved; without this it is dropped. The text block's own interior is
        // string content, never a comment, so a plain text-block call still takes this compact path.
        if (commentedExpressionLists.hasLineComments(expression, expression.getArguments())) {
            return Optional.empty();
        }
        Doc argument = textBlockArguments.methodCallIsExpressionLambdaBody(expression)
            ? textBlockArguments.expressionLambdaSourceMultilineArgument(textBlockLiteralExpr)
            : Doc.indent(Doc.concat(Doc.HARD_LINE, textBlockArgument(textBlockLiteralExpr, expression)));
        return Optional.of(
            Doc.concat(
                Doc.text(prefix + "("),
                argument,
                Doc.HARD_LINE,
                Doc.text(")")
            )
        );
    }

    /**
     * Always empty: a method call's argument list breaks purely by the renderer's width verdict at its true column, with
     * no source-multiline preservation. Kept (returning empty) so its callers' source-multiline hook wiring stays wired.
     */
    Optional<Doc> sourceMultilineArguments(MethodCallExpr expression) {
        return Optional.empty();
    }

    /**
     * Always empty: a single inner-object-creation argument is not hugged onto the opener — it breaks by width through the
     * generic path. Kept (returning empty) so the dispatch hook stays wired.
     */
    private Optional<Doc> singleObjectCreationArgument(String prefix, MethodCallExpr expression) {
        return Optional.empty();
    }

    /**
     * Makes the hug-versus-explode verdict of a call whose sole argument is a
     * fan-threshold method-call chain SOURCE-NEUTRAL by ranking two AST-derived shapes with {@link Doc#bestFitting} at the
     * true rendered column (#190), so a chain argument whose rendered form force-fans resolves to the same shape on every
     * pass instead of picking divergent shapes from the source line layout.
     *
     * <p>The affected family is {@code assertTrue(chain)} / {@code Arrays.stream(chain)} / {@code Optional.of(chain)}:
     * without ranking, such a call can hug the chain onto the opener on one pass and explode it onto its own indented line
     * on the next, alternating forever. Both {@code Doc.bestFitting} arms wrap the SAME source-neutral fan and are pure AST
     * functions, so both passes rank the same two candidates and pick the same one; the verdict is a fixpoint by
     * construction.
     *
     * <p>Both arms wrap ONE fan Doc, produced ONCE by {@code canonicalFanChain} through the source-neutral
     * {@code chainFanOut} with an empty {@link LayoutContext#leftEdgePrefix()} ({@link LayoutContext#root()}):
     * <ul>
     *   <li><b>Hugged</b> ({@code assertTrue(result}⏎{@code .getExecutionInfo()}⏎{@code .get(0)}⏎{@code .contains(…))}): the
     *       opener text {@code prefix + "("} precedes the fan, so the chain root hugs the opener line and the fan's own
     *       continuation indent lays each selector one per line under it, the closing {@code )} glued to the final
     *       selector.</li>
     *   <li><b>Exploded</b> ({@code assertTrue(}⏎{@code result}⏎{@code .getExecutionInfo()}…⏎{@code )}): the opener breaks and
     *       the same fan renders under one argument indent, the closing {@code )} dedented — byte-identical to the generic
     *       exploded argument list's shape.</li>
     * </ul>
     * Rendering one prefix-agnostic fan and sharing it across arms is load-bearing (the initializer-seam lesson): the fan's
     * root renders at {@link LayoutContext#root()} in BOTH arms, so a promoted-factory or method-call root's opener group
     * cannot break differently between the two arms and re-flip; {@code bestFitting} scores line-count + overflow at the
     * live column, so the hugged arm (fewer lines, root hugged) wins whenever the opener fits and the exploded arm wins only
     * when it overflows.
     *
     * <p>Returns empty for a single argument the fan withholds (a non-fan chain, an object-creation root, or any
     * comment/lambda carrier — {@code canonicalFanChain} returns empty), for a call carrying its own comments, or for a
     * multi-argument call, so every such call reaches the unchanged {@link #singleMethodCallArgument} / generic paths.
     *
     * <p>Scoped to a HOST call that is itself NOT part of a fanning chain: the host's own scope must be a non-call
     * receiver (empty, a {@code NameExpr}/{@code FieldAccessExpr} qualifier, {@code this}/{@code super}) and the host must
     * not be the scope of a parent {@code MethodCallExpr} (i.e. not a chain segment with trailing selectors). Without this
     * the seam fires on a chain selector such as {@code request.topics().add(new CreatableTopic()...)} and forces the whole
     * enclosing chain to explode ({@code request}⏎{@code .topics()}⏎{@code .add(}…) — a regression, since the enclosing
     * chain's fan is owned by the chain printer, not this argument seam. The chain-selector-hosted single-argument hug is
     * the deferred nested-root slice; here we only stabilize the standalone statement/return/initializer/argument host
     * ({@code assertTrue(chain)}, {@code Optional.of(chain)}), whose opener text renders atomically at its column.
     */
    private Optional<Doc> singleFanChainArgumentBestFitting(String prefix, MethodCallExpr expression) {
        if (
            expression.getArguments().size() != 1
            || !(expression.getArgument(0) instanceof MethodCallExpr argument)
            || !expression.getAllContainedComments().isEmpty()
            || expression.getScope().filter(MethodCallExpr.class::isInstance).isPresent()
            || hostIsChainSegment(expression)
        ) {
            return Optional.empty();
        }
        Optional<Doc> fan = methodChains.canonicalFanChain(argument, "", LayoutContext.root());
        if (fan.isEmpty()) {
            return Optional.empty();
        }
        Doc fanDoc = fan.orElseThrow();
        Doc hugged = Doc.concat(Doc.text(prefix + "("), fanDoc, Doc.text(")"));
        Doc exploded = Doc.concat(
            Doc.text(prefix + "("),
            Doc.indent(Doc.concat(Doc.HARD_LINE, fanDoc)),
            Doc.HARD_LINE,
            Doc.text(")")
        );
        // PR #279 review (#3/#4): prefer breaking right after the call's `(` — the chain argument on its own indented
        // line, the closing `)` dedented to the opener's column ({@code Response.listUsers(}⏎ chain ⏎{@code )},
        // {@code buffer.append(}⏎ chain ⏎{@code )}) — over hugging the chain root onto the opener line and dangling the
        // `)` on the final selector. The exploded arm carries priority 1, so among the arms that FIT the renderer keeps
        // it regardless of line count; the hugged arm (priority 0) stays as a fitting-fallback for the (unreachable in
        // practice) case where the exploded opener itself overflows. Because the exploded first line is a strict prefix
        // of the hugged first line and both wrap the SAME source-neutral fan, the exploded arm fits whenever the hugged
        // one does, so the verdict is deterministic and idempotent — both passes rebuild and rank the same two shapes.
        return Optional.of(Doc.bestFitting(List.of(exploded, hugged), new int[] {1, 0}));
    }

    /**
     * Reports whether {@code expression} is a non-final segment of an enclosing method-call chain — i.e. its parent is a
     * {@code MethodCallExpr} that uses it as the scope ({@code foo.bar(...)}{@code .baz()} → {@code foo.bar(...)} is a chain
     * segment). The single-fan-chain-argument seam withholds these: the enclosing chain's fan/hug shape is owned by the
     * chain printer, so reshaping the segment's argument list here would fight that owner across passes.
     */
    private boolean hostIsChainSegment(MethodCallExpr expression) {
        return expression.getParentNode()
                .filter(MethodCallExpr.class::isInstance)
                .map(MethodCallExpr.class::cast)
                .flatMap(MethodCallExpr::getScope)
                .filter(scope -> scope == expression)
                .isPresent();
    }

    /**
     * Always empty: a single inner-method-call argument is not hugged onto the opener — it breaks by width through the
     * generic path. Kept (returning empty) so the dispatch hook stays wired.
     */
    private Optional<Doc> singleMethodCallArgument(String prefix, MethodCallExpr expression) {
        return Optional.empty();
    }

    /**
     * Reports whether attaching ("hugging") a single inner call/object-creation argument to this call's opener would push
     * the shared first line ({@code outer(inner(}) past the line width, measured at the call's <em>real</em> rendered
     * column rather than the bare block indent.
     *
     * <p>Probing the {@code CURRENT} line budget on {@code prefix + "(" + innerPrefix +
     * "("} alone is prefix-blind: when the call is the value of an initializer or assignment
     * ({@code NAME = outer(inner(…))}) the {@code NAME = } prefix sharing the line is not counted, so a naive probe
     * attaches the hug even when the opener visibly overflows. Measuring at the real rendered column — attach only when
     * the hugged opener fits — mirrors the prefix-aware first-line probe
     * threaded into method-chain layout for the assignment column (#161) and the chain arm's stay-flat rule (#163): the
     * column where the value begins, not just its indentation, decides whether the flat shape is legal.
     *
     * <p>The value prefix that shares the call's first line is reconstructed from the source range: the call's start
     * column minus its enclosing statement's start column is exactly the width of whatever precedes the call on that line
     * (the {@code NAME = }, {@code target op }, {@code return }, …), and that delta is invariant under reindentation. The
     * real first-line width is therefore the statement's rendered indentation plus that prefix delta plus the opener text,
     * which is measured against the enclosing statement's nesting depth rather than the bare-call indent a prefix-blind
     * probe assumes. The delta is taken only when the call and its statement begin on the same source line; a call that
     * already starts its own line has no shared prefix, so the probe falls back to the plain indented width for those
     * callers.
     */
    private boolean attachedOpenerOverflows(MethodCallExpr expression, String openerLine) {
        return sharedFirstLineWidth(expression)
                .map(prefixedIndent -> prefixedIndent + openerLine.length())
                // C10-b: the own-line fallback (a call that starts its own line, no shared prefix) measures at the call's
                // true rendered block/type depth ({@link LayoutWidth#nodeLine}) instead of the fixed CURRENT baseline.
                .orElseGet(() -> layoutWidth.nodeLine(expression, openerLine))
            > options.lineWidth();
    }

    private Optional<Integer> sharedFirstLineWidth(MethodCallExpr expression) {
        return expression.getRange()
                .flatMap(callRange -> enclosingStatement(expression)
                        .filter(statement -> statement.getRange()
                                .filter(statementRange -> statementRange.begin.line == callRange.begin.line)
                                .isPresent())
                        .map(statement -> layoutWidth.nodeIndentWidth(statement)
                            + Math.max(0, callRange.begin.column - statement.getRange().orElseThrow().begin.column)));
    }

    private Optional<Statement> enclosingStatement(Node node) {
        Optional<Node> ancestor = node.getParentNode();
        while (ancestor.isPresent()) {
            Node current = ancestor.orElseThrow();
            if (current instanceof Statement statement) {
                return Optional.of(statement);
            }
            ancestor = current.getParentNode();
        }
        return Optional.empty();
    }

    Optional<Doc> sourceMultilineExpressionLambda(MethodCallExpr expression) {
        return sourceMultilineExpressionLambda(expression, LayoutContext.root());
    }

    /**
     * Always empty: a trailing expression lambda is hugged or exploded purely by the width-driven plan
     * ({@link ExpressionLambdaArgumentLayout#plan}) reached through the generic argument path, with no source-multiline
     * preservation. Kept (returning empty) so the dispatch hook stays wired.
     */
    Optional<Doc> sourceMultilineExpressionLambda(MethodCallExpr expression, LayoutContext layout) {
        return Optional.empty();
    }

    private boolean expressionLambdaBodyOpenerOverflows(
            MethodCallExpr expression,
            ExpressionLambdaArgumentLayout.Plan argument,
            LayoutContext layout
    ) {
        return !argument.bodyFirstSourceLineFits()
            && argument.bodyOpenerFitsOnContinuation(lineWidth(LayoutWidth.LineBudget.CONTINUATION), options.lineWidth())
            && argument.bodyOpenerOverflows(line -> methodCallRootLineWidth(expression, line, layout), options.lineWidth());
    }

    /**
     * Measures the call's first line ({@code prefix(args lambda ->}) at the column where the call renders, gating whether
     * a source-multiline expression-lambda argument can be hugged.
     *
     * <p>C10 (#217): this gate reconstructs the call column from {@code range.begin.column}, a source-column read that
     * understates the rendered column once the call is reindented shallower than its true block/type depth. It now also
     * considers the call's rendered indentation ({@link LayoutWidth#nodeIndentWidth}, which counts every enclosing type
     * and block) and takes the <em>wider</em> of the two, mirroring the chain-printer root gates
     * ({@code MethodCallChainPrinter.compactRootLineWidth}/{@code rootLineWidth}), the sibling
     * {@link ExpressionLambdaArgumentLayout} first-line gate (#226), and the single-argument hug gate
     * {@link #attachedOpenerOverflows}.
     *
     * <p>The source column is kept as the <em>floor</em> rather than replaced: this call can be an initializer/return
     * value whose {@code = }/{@code return } leading prefix shares the measured line, and {@code nodeIndentWidth}
     * (nesting depth only) does not carry that prefix while the source column does. Flooring by the source column keeps
     * it accounted for, so the probe can only ever measure wider and never under-measures a prefixed call. The change is
     * byte-identical on the fixture corpus and on every reindented/nested probe.
     *
     * <p>LDM-2f / chain-unify U3 (#190): activated to read {@code layout.leftEdgePrefix()} the same way its sibling
     * {@code MethodCallChainPrinter.compactRootLineWidth} does — when the prefix is non-empty it measures the call's first
     * line at the exact rendered column {@code nodeIndentWidth(expression) + leftEdgePrefix.length() + firstLine.length()}
     * and drops the source-column floor. Reading an empty prefix is a strict no-op, so every caller keeps the wider-of
     * floor. No current caller of this source-multiline expression-lambda hug gate threads a non-empty prefix into it
     * (the statement and argument chain callers thread an empty prefix), so the activation is byte-identical readiness for
     * a future prefixed lambda-hug caller — unlike the chain-printer's {@code rootLineWidth}/{@code selectorLineWidth},
     * which the initializer chain already reaches with a real prefix and so cannot be activated here without moving a
     * golden.
     */
    private int methodCallRootLineWidth(MethodCallExpr expression, String firstLine, LayoutContext layout) {
        // LDM-2f (#190): with the same-line prefix threaded, measure at the exact rendered column and drop the
        // source-column floor, which was only ever a stand-in for this prefix.
        if (!layout.leftEdgePrefix().isEmpty()) {
            return layoutWidth.nodeIndentWidth(expression) + layout.leftEdgePrefix().length() + firstLine.length();
        }
        return expression.getRange()
                .map(range -> Math.max(
                    Math.max(0, range.begin.column + 1) + firstLine.length(),
                    layoutWidth.nodeIndentWidth(expression) + firstLine.length()))
                // C10-a: rangeless (synthetic) fallback measures at the rendered column, mirroring the wider-of arm's
                // nodeIndentWidth term above, instead of the fixed one-indent baseline.
                .orElseGet(() -> layoutWidth.nodeIndentWidth(expression) + firstLine.length());
    }

    private boolean hasHuggableExpressionLambdaArgument(MethodCallExpr expression) {
        return expression.getArguments()
                .stream()
                .anyMatch(argument -> argument instanceof LambdaExpr lambdaExpr
                        && lambdaExpr.getExpressionBody().filter(this::huggableExpressionLambdaBody).isPresent()
                );
    }

    private boolean huggableExpressionLambdaBody(Expression body) {
        if (body instanceof MethodCallExpr || body instanceof ConditionalExpr) {
            return true;
        }
        if (logicalBinaryBody(body).isPresent()) {
            return true;
        }
        if (body instanceof LambdaExpr lambdaExpr) {
            return lambdaExpr.getExpressionBody().filter(this::huggableExpressionLambdaBody).isPresent();
        }
        return false;
    }

    private boolean isLogicalBinaryOperator(BinaryExpr expression) {
        return expression.getOperator() == BinaryExpr.Operator.AND
            || expression.getOperator() == BinaryExpr.Operator.OR;
    }

    private Optional<BinaryExpr> logicalBinaryBody(Expression body) {
        if (body instanceof BinaryExpr binaryExpr && isLogicalBinaryOperator(binaryExpr)) {
            return Optional.of(binaryExpr);
        }
        if (body instanceof EnclosedExpr enclosedExpr) {
            return logicalBinaryBody(enclosedExpr.getInner());
        }
        return Optional.empty();
    }

    /**
     * Keeps a source-multiline method-call scope structured when a later call's arguments force their own multiline
     * layout, instead of compacting that scope into the later call prefix.
     */


    private String methodCallSelector(MethodCallExpr expression) {
        return (
            expression.getTypeArguments()
                    .map(typeArguments -> "<" + compactSource.compactJoin(typeArguments) + ">")
                    .orElse("")
            + expression.getNameAsString()
        );
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

    Doc methodCallArgumentList(NodeList<Expression> arguments, Doc line) {
        List<Doc> docs = new ArrayList<>();
        for (int index = 0; index < arguments.size(); index++) {
            Expression argument = arguments.get(index);
            boolean last = index == arguments.size() - 1;
            docs.add(methodCallArgumentDoc(
                argument,
                last ? "" : ",",
                last && line == Doc.HARD_LINE,
                line == Doc.HARD_LINE
            ));
            if (!last) {
                docs.add(argumentConsumesSuffix(argument) ? line : Doc.concat(Doc.text(","), line));
            }
        }
        return Doc.concat(docs);
    }

    Doc methodCallArgumentList(String prefix, NodeList<Expression> arguments, Doc line) {
        return methodCallArgumentList(arguments, line);
    }

    private boolean argumentConsumesSuffix(Expression argument) {
        return argument instanceof ObjectCreationExpr || argument instanceof MethodCallExpr;
    }

    /**
     * Keeps expressions with their own broken form breakable inside a method-call argument list.
     *
     * <p>The ordinary expression renderer owns the flat spelling. Once the surrounding call list breaks, the expression
     * helper owns the continuation lines so breakable arguments do not collapse back onto an over-wide argument line.
     */
    private Doc methodCallArgumentDoc(
            Expression argument,
            String suffix,
            boolean allowSuffixlessTrailingComment,
            boolean sourceMultilineList
    ) {
        if (
            argument instanceof MethodCallExpr methodCall
            && (!suffix.isEmpty()
                || (allowSuffixlessTrailingComment && !methodCallArgumentTrailingLineComments(methodCall).isEmpty()))
        ) {
            Optional<Doc> textBlockChain = textBlockRootChainArgumentWithSuffix(methodCall, suffix);
            if (textBlockChain.isPresent()) {
                return textBlockChain.orElseThrow();
            }
            Optional<Doc> compact = compactMethodCallArgumentWithSuffix(methodCall, suffix);
            if (compact.isPresent()) {
                return compact.orElseThrow();
            }
        }
        // Canonical-fan cutover seam (End-state A): a fan-threshold, comment/lambda-free method-call argument fans one
        // selector per line through the SAME source-neutral fan on every pass, ahead of the source-shape-sensitive chain
        // routes below. Both the suffix-carrying FORCED route and the AUTO route further down reach
        // {@code MethodCallChainPrinter.methodCallChain}, whose own canonical-fan routes gate {@code !sourceMultilineArguments}
        // and so fall to the imperative {@code canAttachFirstSegmentToSimpleRoot} branch on a source-multiline-argument pass
        // — folding the first selector onto a simple receiver root and flipping split<->attach against the fanned re-format.
        // Emitting {@code chainFanOut} here removes that dependence (the fan is a pure function of the AST). The ARGUMENT
        // {@link LayoutContext} carries an empty {@code leftEdgePrefix} for the same reason the AUTO route below does: an
        // argument's extra offset is pure continuation indentation the enclosing list applies at render time, which
        // {@code chainFanOut}'s relative {@code Doc.indent} continuation reproduces without a textual prefix. The fan fires by
        // the link-count/root-kind rule ({@code chainBreaksByRule}), which is width-independent, so the unmodelled
        // continuation indent never affects whether it fires. Expression-lambda / comment-bearing chains stay withheld inside
        // {@code canonicalFanChain} (deferred lambda-arrow seam).
        if (argument instanceof MethodCallExpr methodCall) {
            LayoutContext canonicalFanLayout = new LayoutContext(
                EnclosingConstruct.ARGUMENT,
                "",
                "",
                false
            );
            Optional<Doc> canonicalFan = comments.speculatively(
                () -> methodChains.canonicalFanChain(methodCall, suffix, canonicalFanLayout)
            );
            if (canonicalFan.isPresent()) {
                return canonicalFan.orElseThrow();
            }
        }
        if (argument instanceof MethodCallExpr methodCall && !suffix.isEmpty()) {
            Optional<Doc> chain = methodCallChain(methodCall, MethodCallBreakMode.FORCED, suffix);
            if (chain.isPresent()) {
                return chain.orElseThrow();
            }
            return methodCallWithTail(methodCall, ExpressionTail.of(suffix));
        }
        if (argument instanceof ObjectCreationExpr objectCreation && !suffix.isEmpty()) {
            return objectCreationWithSuffix.apply(objectCreation, suffix);
        }
        if (argument instanceof MethodCallExpr methodCall && !methodCall.getAllContainedComments().isEmpty()) {
            Optional<Doc> chain = methodCallChain(methodCall, MethodCallBreakMode.FORCED, suffix);
            if (chain.isPresent()) {
                return chain.orElseThrow();
            }
        }
        // A method-call chain argument that fits the top-level chain probe but overflows once placed at this argument
        // list's continuation indentation must break here rather than emit an over-width flat line. The flat fallbacks
        // below render the chain through the AUTO chain printer with the CURRENT budget, which is blind to the argument's
        // real (deeper) column; threading the CONTINUATION budget lets the chain printer's nesting-aware probe (#160/#161)
        // break the chain at its actual position. A chain that still fits at this depth returns empty and falls through
        // to the unchanged flat rendering, so non-overflowing arguments stay byte-identical.
        //
        // LDM-2f / chain-unify U3 (#190): thread a real LayoutContext (ARGUMENT position) for the
        // argument chain instead of the implicit root(), so the argument caller is ready to list a chainFanOut arm through
        // bestFitting in U4. The leftEdgePrefix is left EMPTY on purpose: unlike a return/initializer value — whose whole
        // same-line prefix is textual (`return `, `NAME = `) and whose column nodeIndentWidth already captures — an
        // argument's extra offset is pure continuation INDENTATION applied by the enclosing list's nested Doc.indent at
        // render time, and an argument can sit under several stacked continuations that nodeIndentWidth (block/type depth
        // only) does not count. The chain width gates therefore keep their wider-of source-column floor
        // (max(source-column, nodeIndentWidth)), which is exactly where that unmodelled continuation indent still lives,
        // and the stay-flat gate keeps measuring at the fixed CONTINUATION budget — byte-identical. Dropping that floor
        // here via a nodeIndentWidth-based prefix under-measures a deeply nested argument and regresses it to an
        // over-width flat line, so the rendered-column attribution of the continuation indent is left to a later slice.
        if (argument instanceof MethodCallExpr methodCall) {
            LayoutContext argumentLayout = new LayoutContext(
                EnclosingConstruct.ARGUMENT,
                "",
                "",
                false
            );
            Optional<Doc> chain = comments.speculatively(
                () -> methodCallChain(
                    methodCall,
                    MethodCallBreakMode.AUTO,
                    suffix,
                    LayoutWidth.LineBudget.CONTINUATION,
                    lineWidth(LayoutWidth.LineBudget.CONTINUATION),
                    argumentLayout
                )
            );
            if (chain.isPresent()) {
                return chain.orElseThrow();
            }
        }
        // D1a (#190): the breakable-argument seam threads a real ARGUMENT-position LayoutContext (empty leftEdgePrefix +
        // `suffix` as trailingContent) into its break-gate for the eventual reflow-by-width flip. That context is derived
        // from `suffix` alone, so BreakableArgumentExpressionPrinter builds it once in argumentLayout(suffix) — the caller
        // supplies nothing it lacks, and passing a hand-built copy here would only duplicate that factory.
        if (sourceMultilineList) {
            return breakableArguments.sourceMultilineArgument(argument, suffix);
        }
        return breakableArguments.argument(argument, suffix);
    }

    private Optional<Doc> textBlockRootChainArgumentWithSuffix(MethodCallExpr expression, String suffix) {
        if (!expression.getAllContainedComments().isEmpty()) {
            return Optional.empty();
        }
        List<MethodCallExpr> calls = new ArrayList<>();
        Expression cursor = expression;
        while (cursor instanceof MethodCallExpr call) {
            if (call.getScope().isEmpty()) {
                return Optional.empty();
            }
            calls.add(0, call);
            cursor = call.getScope().orElseThrow();
        }
        if (calls.size() < 2 || !(cursor instanceof TextBlockLiteralExpr textBlockLiteralExpr)) {
            return Optional.empty();
        }
        StringBuilder tail = new StringBuilder();
        for (MethodCallExpr call : calls) {
            tail.append(".")
                    .append(methodCallSelector(call))
                    .append("(")
                    .append(compactSource.compactJoin(call.getArguments()))
                    .append(")");
        }
        String literal = unformattedTextBlockRenderer.apply(textBlockLiteralExpr);
        String closingLine = literal.substring(literal.lastIndexOf('\n') + 1) + tail + suffix;
        if (closingLine.length() > options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(Doc.text(literal), Doc.text(tail + suffix)));
    }

    private Optional<Doc> compactMethodCallArgumentWithSuffix(MethodCallExpr expression, String suffix) {
        // A compact (flat text) rendering hides every break inside the argument, so decline it when the argument holds a
        // call/constructor whose argument list is heavy (see ArgumentHeaviness) — that list must still break one-per-line
        // even though the whole argument fits on the line. The argument then falls through to the chain/tail path, which
        // renders the heavy call through the break-aware printer (PR #279 comment #1: a heavy constructor root breaks even
        // when it sits inside a fitting chain argument).
        if (argumentHeaviness.containsHeavyArgumentList(expression)) {
            return Optional.empty();
        }
        List<JavaCommentTrivia> trailingComments = methodCallArgumentTrailingLineComments(expression);
        if (!trailingComments.isEmpty() && hasNonTrailingContainedComments(expression, trailingComments)) {
            return Optional.empty();
        }
        String code =
            (trailingComments.isEmpty() ? compactSource.compact(expression) : compactSource.commentFree(expression))
            + suffix;
        if (layoutWidth.line(LayoutWidth.LineBudget.CONTINUATION, code) > options.lineWidth()) {
            return Optional.empty();
        }
        List<Doc> renderedTrailing = trailingComments.stream()
                .map(comments::comment)
                .filter(comment -> comment != Doc.EMPTY)
                .toList();
        if (renderedTrailing.isEmpty()) {
            return Optional.of(Doc.text(code));
        }
        return Optional.of(Doc.concat(Doc.text(code), Doc.text(" "), Doc.join(Doc.text(" "), renderedTrailing)));
    }

    private boolean hasNonTrailingContainedComments(
            MethodCallExpr expression,
            List<JavaCommentTrivia> trailingComments
    ) {
        return expression.getAllContainedComments()
                .stream()
                .anyMatch(comment -> trailingComments.stream().noneMatch(trailing -> trailing.comment() == comment));
    }

    private List<JavaCommentTrivia> methodCallArgumentTrailingLineComments(MethodCallExpr expression) {
        List<JavaCommentTrivia> sourceComments = new ArrayList<>();
        commentPlacement.trailingLineComment(expression).ifPresent(sourceComments::add);
        commentPlacement.containedComments(expression)
                .stream()
                .filter(JavaCommentTrivia::isLine)
                .filter(comment -> comment.startsOnEndLine(expression) || comment.startsAfterNodeOnSameLine(expression))
                .filter(comment -> sourceComments.stream().noneMatch(
                        existing -> existing.comment() == comment.comment()
                ))
                .forEach(sourceComments::add);
        int endLine = CommentIndex.endLine(expression, Integer.MIN_VALUE);
        expression.getAllContainedComments()
                .stream()
                .filter(LineComment.class::isInstance)
                .map(JavaCommentTrivia::from)
                .filter(comment -> comment.beginLine(Integer.MAX_VALUE) == endLine)
                .filter(comment -> sourceComments.stream().noneMatch(
                        existing -> existing.comment() == comment.comment()
                ))
                .forEach(sourceComments::add);
        return sourceComments;
    }

    private Doc textBlockSameLineTrailingComment(TextBlockLiteralExpr textBlockLiteralExpr, MethodCallExpr expression) {
        return expression.getOrphanComments()
                .stream()
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
     *
     * <p>Canonical-fan cutover seam (G bucket): the binary/logical/string-concat OPERAND carrier at the single-binary
     * argument position. When the sole argument is a binary/ternary that fans a fluent chain operand
     * ({@code assertTrue(chain.isPresent()
     * && chain2)}, {@code println("..." + chain)}, {@code assertTrue((Double) chain.metricValue() > 0.0)}), committing the
     * source-neutral {@code flat} — the chain-fanned operand with the operator kept on its line — whenever the binary fans a
     * chain operand by the rule ({@code binaryFansChainOperand}) makes the verdict a fixpoint by construction: {@code flat}
     * is a pure function of the AST (the chain fans by the width-independent link-count rule on every pass), so this path
     * and {@link BreakableArgumentExpressionPrinter#sourceMultilineArgument} — which observes the wrapped argument list a
     * prior pass produced — emit the same shape rather than alternating between the operand-fanned form and the
     * broken-argument delegate's operator-on-its-own-line form. Chains the rule does not fan (a plain-receiver 1–2-link
     * operand, the #119 {@code binary-chain-wrap-converge} guard) and comment / lambda chains are withheld by
     * {@code binaryFansChainOperand}, so those arguments keep the broken-argument delegate below byte-for-byte.
     */
    private Optional<Doc> singleBinaryArgument(
            String prefix,
            NodeList<Expression> arguments,
            MethodCallBreakMode breakMode
    ) {
        if (arguments.size() != 1 || !(arguments.get(0) instanceof BinaryExpr binaryExpr)) {
            return Optional.empty();
        }
        if (
            !breakMode.isForced()
            && layoutWidth.line(
                LayoutWidth.LineBudget.CURRENT,
                prefix + "(" + compactSource.compact(binaryExpr) + ")"
            ) <= options.lineWidth()
        ) {
            return Optional.empty();
        }
        Doc argument = methodChains.binaryFansChainOperand(binaryExpr)
            ? expressionRenderer.format(binaryExpr, LayoutContext.root())
            : brokenArgumentExpressionRenderer.apply(binaryExpr)
                    .orElseGet(() -> expressionRenderer.format(binaryExpr, LayoutContext.root()));
        return Optional.of(
            Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(Doc.concat(Doc.HARD_LINE, argument)),
                Doc.HARD_LINE,
                Doc.text(")")
            )
        );
    }

    Optional<Doc> assignmentWithBrokenMethodCallArguments(AssignExpr assignExpr, MethodCallExpr methodCall) {
        return assignmentWithBrokenMethodCallArguments(assignExpr, methodCall, "");
    }

    Optional<Doc> assignmentWithBrokenMethodCallArgumentsAndSemicolon(
            AssignExpr assignExpr,
            MethodCallExpr methodCall
    ) {
        return assignmentWithBrokenMethodCallArguments(assignExpr, methodCall, ";");
    }

    private Optional<Doc> assignmentWithBrokenMethodCallArguments(
            AssignExpr assignExpr,
            MethodCallExpr methodCall,
            String finalSegmentSuffix
    ) {
        // The assignment knows the prefix that shares the value's first line ({@code target op }); a method-chain value
        // must measure its stay-flat width against that prefix, not against the bare block indent. This mirrors
        // VariableInitializerLayout.firstLineWidth, which threads {@code name = } into the chain so a chain that only
        // overflows once the prefix is counted breaks instead of being emitted flat over width.
        String assignmentPrefix = compactSource.compact(assignExpr.getTarget())
            + " "
            + assignExpr.getOperator().asString()
            + " ";
        // C10-c (U5/F4): the assignment prefix ({@code target op }) already shares the value's first line, so measure the
        // chain's stay-flat width at the RHS's true rendered block/type column ({@link LayoutWidth#nodeIndentWidth}) plus
        // that fixed left-edge prefix, instead of the fixed BLOCK baseline. A reindented statement is then measured at the
        // column it is actually written at (F3), and a value that only overflows once the prefix is counted still breaks.
        ToIntFunction<String> prefixedFirstLineWidth =
            text -> layoutWidth.nodeIndentWidth(methodCall) + assignmentPrefix.length() + text.length();
        // Canonical-fan cutover seam (End-state A): a fan-threshold, comment/lambda-free assignment-RHS chain fans one
        // selector per line, and it must do so through the SAME source-neutral fan on every pass — otherwise the RHS
        // opener flips split<->attach. On a source-multiline-argument pass the FORCED methodCallChain below skips its own
        // canonical-fan routes (they gate {@code !sourceMultilineArguments}) and lands on the imperative
        // {@code canAttachFirstSegmentToSimpleRoot} branch, which folds the first selector onto the target line
        // ({@code x = parser.accepts(...)}); the already-fanned re-format then has single-line arguments, the fan route
        // fires, and the selector splits onto its own line ({@code x = parser}⏎{@code .accepts(...)}). Routing the chain
        // through {@code chainFanOut} here, ahead of the source-shape-sensitive branch, with the {@code target op }
        // {@link LayoutContext#leftEdgePrefix() leftEdgePrefix} threaded (so a promoted factory root measures its opener at
        // the rendered column), makes both passes rebuild the identical fan. This is the assignment-side analogue of
        // {@code VariableInitializerLayout.variableInitializerCanonicalFan}; expression-lambda RHS chains are withheld
        // inside {@code canonicalFanChain} (deferred lambda-arrow seam) and stay on the imperative branches below.
        Optional<Doc> canonicalFan = methodChains.canonicalFanChain(
            methodCall,
            finalSegmentSuffix,
            LayoutContext.root().withLeftEdgePrefix(assignmentPrefix)
        );
        if (canonicalFan.isPresent()) {
            return assignmentValueChain(assignExpr, canonicalFan.orElseThrow());
        }
        if (methodCallChainIsSourceMultiline(methodCall)) {
            Optional<Doc> chain = methodCallChain(
                methodCall,
                MethodCallBreakMode.FORCED,
                finalSegmentSuffix,
                LayoutWidth.LineBudget.BLOCK,
                prefixedFirstLineWidth
            );
            if (chain.isPresent()) {
                return assignmentValueChain(assignExpr, chain.orElseThrow());
            }
        }
        // A single-line source chain whose flat statement only overflows once the assignment prefix is counted (for
        // example {@code routeTable = new X().setName(...).seal().commit().go();} at 121 columns under a 120-column
        // budget) reaches here even when its final segment takes no arguments, so the empty-argument early return below
        // would otherwise strand it flat over width. Route a genuinely breakable multi-segment chain through the
        // prefix-aware forced chain first; the chain gate keeps a fitting chain flat, so a short value is unaffected.
        if (prefixedFirstLineWidth.applyAsInt(compactSource.compact(methodCall) + finalSegmentSuffix) > options.lineWidth()) {
            Optional<Doc> chain = methodCallChain(
                methodCall,
                MethodCallBreakMode.FORCED,
                finalSegmentSuffix,
                LayoutWidth.LineBudget.BLOCK,
                prefixedFirstLineWidth
            );
            if (chain.isPresent()) {
                return assignmentValueChain(assignExpr, chain.orElseThrow());
            }
        }
        if (methodCall.getArguments().isEmpty() || !methodCall.getAllContainedComments().isEmpty()) {
            return Optional.empty();
        }
        String firstLine = assignmentPrefix + methodCallPrefix(methodCall) + "(";
        // C10-c: the broken-call opener shares the assignment line, so measure it at the RHS's true rendered block/type
        // column ({@link LayoutWidth#nodeLine}, which folds in the already-prefixed firstLine) instead of the fixed BLOCK.
        if (layoutWidth.nodeLine(methodCall, firstLine) > options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                expressionRenderer.format(assignExpr.getTarget(), LayoutContext.root()),
                Doc.text(" " + assignExpr.getOperator().asString() + " "),
                brokenMethodCall(methodCall),
                Doc.text(finalSegmentSuffix)
            )
        );
    }

    private Optional<Doc> assignmentValueChain(AssignExpr assignExpr, Doc chain) {
        return Optional.of(
            Doc.concat(
                expressionRenderer.format(assignExpr.getTarget(), LayoutContext.root()),
                Doc.text(" " + assignExpr.getOperator().asString() + " "),
                chain
            )
        );
    }

    boolean shouldPrintScopeAsDoc(Expression expression) {
        return expression instanceof ArrayCreationExpr
            || expression instanceof ArrayAccessExpr
            || expression instanceof TextBlockLiteralExpr
            || (expression instanceof EnclosedExpr enclosedExpr && enclosedExpr.getInner() instanceof CastExpr);
    }

    private Doc methodCallLine(MethodCallBreakMode breakMode) {
        return breakMode.argumentLine();
    }
}
