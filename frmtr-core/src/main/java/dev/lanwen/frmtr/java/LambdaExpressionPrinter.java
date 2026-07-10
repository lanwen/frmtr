package dev.lanwen.frmtr.java;

import com.github.javaparser.Position;
import com.github.javaparser.Range;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Renders lambda expressions and the lambda-specific argument shapes used by calls and object creation.
 *
 * <p>This helper owns the lambda decision tree: parameter parentheses, commented parameter reconstruction, expression
 * versus block bodies, broken logical bodies, parenthesized lambdas, and lambda arguments that can be hugged by a method
 * call or constructor call. The boundary exists because lambdas are selected by normal expression dispatch, but their
 * argument forms also affect method-call and object-creation layout.
 *
 * <p>{@link JavaPrinter} still owns broad expression dispatch, enclosed-expression suffix decisions, raw source
 * helpers, range predicates, and binary-expression policy. {@link ObjectCreationPrinter} owns constructor layout, and
 * {@link MethodCallPrinter} still owns call and chain layout. This helper receives those decisions as callbacks and
 * only chooses the lambda-specific structure. Representative coverage lives in the block-lambda, method-chain, and
 * variable-declaration formatter fixtures.
 */
final class LambdaExpressionPrinter {

    private final CommentTracker comments;

    private final JavaCommentPlacementPolicy commentPlacement;

    private final SourceText sourceText;

    private final ObjectCreationLayoutPolicy objectCreationLayoutPolicy;

    private final ArgumentHeaviness argumentHeaviness = new ArgumentHeaviness();

    private final FormatterOptions options;

    private final LayoutWidth layoutWidth;

    private final JavaFormatRule<Expression> expressionRenderer;

    private final Function<MethodCallExpr, Doc> brokenMethodCallRenderer;

    private final Function<ObjectCreationExpr, Doc> brokenObjectCreationRenderer;

    private final JavaFormatRule<Statement> statementRenderer;

    private final JavaFormatRule<BlockStmt> blockRenderer;

    private final JavaFormatRule<BlockStmt> methodChainLambdaBlockRenderer;

    private final BiFunction<Expression, Boolean, Doc> binaryExpressionNestedLinesRenderer;

    private final Function<Node, String> compact;

    private final Function<Node, String> compactWithoutOwnComment;

    private final Function<List<? extends Node>, String> compactJoin;

    private final ToIntFunction<String> currentIndentedWidth;

    private final ToIntFunction<String> blockStatementWidth;

    private final BiPredicate<Comment, Node> startsBefore;

    private final BiPredicate<Comment, Node> startsOnSameLine;

    private final Function<MethodCallExpr, Optional<Doc>> lambdaBodyCanonicalFanChain;

    private final Predicate<MethodCallExpr> lambdaBodyChainRootIsTrivialReceiver;

    private final ExpressionLambdaArgumentLayout expressionLambdaArguments;

    private final LambdaParameterHeaderLayout lambdaParameterHeaders;

    LambdaExpressionPrinter(
            CommentTracker comments,
            JavaCommentPlacementPolicy commentPlacement,
            RawSource rawSource,
            SourceShapePolicy sourceShapePolicy,
            SourceText sourceText,
            ObjectCreationLayoutPolicy objectCreationLayoutPolicy,
            FormatterOptions options,
            LayoutWidth layoutWidth,
            JavaFormatRule<Expression> expressionRenderer,
            Function<ObjectCreationExpr, Doc> brokenObjectCreationRenderer,
            JavaFormatRule<Statement> statementRenderer,
            JavaFormatRule<BlockStmt> blockRenderer,
            JavaFormatRule<BlockStmt> methodChainLambdaBlockRenderer,
            BiFunction<Expression, Boolean, Doc> binaryExpressionNestedLinesRenderer,
            Function<MethodCallExpr, Doc> brokenMethodCallRenderer,
            BiFunction<String, MethodCallExpr, Optional<Doc>> packedMethodCallChainBodyRenderer,
            BiFunction<String, MethodCallExpr, Optional<Doc>> huggedLambdaBodyChainRenderer,
            Predicate<MethodCallExpr> lambdaBodyChainFansByCanonicalRule,
            Function<MethodCallExpr, Optional<Doc>> lambdaBodyCanonicalFanChain,
            Predicate<MethodCallExpr> lambdaBodyChainRootIsTrivialReceiver,
            BiFunction<NodeList<Expression>, Doc, Doc> methodCallArgumentList,
            Function<Node, String> compact,
            Function<Node, String> compactWithoutOwnComment,
            Function<List<? extends Node>, String> compactJoin,
            ToIntFunction<String> currentIndentedWidth,
            ToIntFunction<String> blockStatementWidth,
            BiPredicate<Comment, Node> startsBefore,
            BiPredicate<Comment, Node> startsOnSameLine
    ) {
        this.comments = comments;
        this.commentPlacement = commentPlacement;
        this.sourceText = sourceText;
        this.objectCreationLayoutPolicy = objectCreationLayoutPolicy;
        this.options = options;
        this.layoutWidth = layoutWidth;
        this.expressionRenderer = expressionRenderer;
        this.brokenMethodCallRenderer = brokenMethodCallRenderer;
        this.brokenObjectCreationRenderer = brokenObjectCreationRenderer;
        this.statementRenderer = statementRenderer;
        this.blockRenderer = blockRenderer;
        this.methodChainLambdaBlockRenderer = methodChainLambdaBlockRenderer;
        this.binaryExpressionNestedLinesRenderer = binaryExpressionNestedLinesRenderer;
        this.compact = compact;
        this.compactWithoutOwnComment = compactWithoutOwnComment;
        this.compactJoin = compactJoin;
        this.currentIndentedWidth = currentIndentedWidth;
        this.blockStatementWidth = blockStatementWidth;
        this.startsBefore = startsBefore;
        this.startsOnSameLine = startsOnSameLine;
        this.lambdaBodyCanonicalFanChain = lambdaBodyCanonicalFanChain;
        this.lambdaBodyChainRootIsTrivialReceiver = lambdaBodyChainRootIsTrivialReceiver;
        this.lambdaParameterHeaders = new LambdaParameterHeaderLayout(
            rawSource,
            options,
            compact,
            compactJoin,
            currentIndentedWidth
        );
        this.expressionLambdaArguments = new ExpressionLambdaArgumentLayout(
            sourceShapePolicy,
            rawSource,
            sourceText,
            options,
            expressionRenderer,
            brokenMethodCallRenderer,
            brokenObjectCreationRenderer,
            packedMethodCallChainBodyRenderer,
            huggedLambdaBodyChainRenderer,
            lambdaBodyChainFansByCanonicalRule,
            statementRenderer,
            methodCallArgumentList,
            compact,
            compactJoin,
            binaryExpressionNestedLinesRenderer,
            this::lambdaParameters,
            this::lambdaParametersShouldBreak,
            blockStatementWidth,
            layoutWidth
        );
    }

    Doc parenthesizedLambdaBreak(LambdaExpr expression) {
        String parameters = lambdaParameters(expression);
        // Trivial-receiver first-selector attach (gjf/prettier-java, comment #3). A parenthesized lambda statement whose body
        // is a fan-threshold chain rooted at a TRIVIAL RECEIVER keeps the root (and its already-attached first selector, from
        // {@code chainFanOut}) ANCHORED on the {@code ->} line — {@code (dispatchJob -> orderEvent.validateOrder()}⏎{@code
        // .deliveryPlan()}… — rather than breaking after the arrow. This mirrors the same anchor
        // {@link #lambdaBodyChainArrowBestFitting} applies for the argument/return positions and is keyed only on the root's
        // AST kind, so it stays a structural fixpoint. Every other body keeps the unconditional break-after-arrow below.
        Optional<Doc> attachedTrivialReceiverBody = expression.getExpressionBody()
                .filter(MethodCallExpr.class::isInstance)
                .map(MethodCallExpr.class::cast)
                .filter(lambdaBodyChainRootIsTrivialReceiver)
                .flatMap(chainBody -> lambdaBodyCanonicalFanChain.apply(chainBody))
                .map(fanDoc -> Doc.concat(
                        Doc.text("(" + parameters + " -> "),
                        fanDoc,
                        Doc.text(")")
                ));
        if (attachedTrivialReceiverBody.isPresent()) {
            return attachedTrivialReceiverBody.orElseThrow();
        }
        return Doc.concat(
            Doc.text("(" + parameters + " ->"),
            Doc.indent(Doc.concat(Doc.HARD_LINE, lambdaExpressionBody(expression))),
            Doc.text(")")
        );
    }

    private Doc lambdaExpressionBody(LambdaExpr expression) {
        return expression.getExpressionBody()
                .map(node -> expressionRenderer.format(node, LayoutContext.root()))
                .orElseGet(() -> statementRenderer.format(expression.getBody(), LayoutContext.root()));
    }

    Doc brokenExpressionLambda(LambdaExpr expression) {
        String parameters = lambdaParameters(expression);
        return Doc.concat(
            lambdaParameterHeaders.forHeader(expression, parameters),
            Doc.text(" ->"),
            Doc.indent(Doc.concat(Doc.HARD_LINE, brokenLambdaExpressionBody(expression)))
        );
    }

    Doc lambdaExpression(LambdaExpr expression) {
        String parameters = lambdaParameters(expression);
        if (expression.getBody().isBlockStmt()) {
            return Doc.concat(
                lambdaParameterHeaders.forHeader(expression, parameters),
                Doc.text(" -> "),
                blockRenderer.format(expression.getBody().asBlockStmt(), LayoutContext.root())
            );
        }
        boolean parametersHaveComments = lambdaParameterHeaders.haveComments(expression);
        if (parametersHaveComments) {
            Optional<String> inlineCommentedLambda = lambdaParameterHeaders.inlineCommentedLambda(expression);
            if (
                inlineCommentedLambda.filter(lambda -> currentIndentedWidth.applyAsInt(lambda) <= options.lineWidth()).isPresent()
            ) {
                return Doc.text(inlineCommentedLambda.orElseThrow());
            }
        }
        Optional<Expression> expressionBody = expression.getExpressionBody();
        String flat = parameters
            + " -> "
            + expressionBody.map(compact).orElseGet(() -> compact.apply(expression.getBody()));
        // The flat-lambda gate admits the flat form purely on the width+comment invariants, which are pass-invariant
        // functions of the AST: no contained line comments, the flat text fits the current column, and the flat text
        // would still fit inside a broken argument list. A body whose flat form fits reprints flat; one whose flat form
        // overflows falls through to the width-driven broken shapes below.
        if (
            !parametersHaveComments
            && expressionBody.filter(commentPlacement::hasContainedLineComments).isEmpty()
            && !lambdaFlatOverflowsInBrokenArgumentList(flat)
            && expressionBody.filter(this::methodCallBodyOverflowsInBrokenArgumentList).isEmpty()
            && currentIndentedWidth.applyAsInt(flat) <= options.lineWidth()
        ) {
            return Doc.text(flat);
        }
        Optional<Doc> bodyLeadingCommentLambda = brokenLambdaWithLeadingBodyComment(expression, parameters);
        if (bodyLeadingCommentLambda.isPresent()) {
            return bodyLeadingCommentLambda.orElseThrow();
        }
        if (parametersHaveComments && expression.getExpressionBody().isPresent()) {
            Expression body = expression.getExpressionBody().orElseThrow();
            if (currentIndentedWidth.applyAsInt(") -> " + compact.apply(body)) <= options.lineWidth()) {
                return Doc.concat(
                    lambdaParameterHeaders.forHeader(expression, parameters),
                    Doc.text(" -> "),
                    expressionRenderer.format(body, LayoutContext.root())
                );
            }
        }
        if (
            lambdaParametersShouldBreak(expression, parameters)
            && expression.getExpressionBody().filter(this::shouldHugBrokenLambdaBody).isPresent()
        ) {
            return Doc.concat(
                lambdaParameterHeaders.forHeader(expression, parameters),
                Doc.text(" -> "),
                expressionRenderer.format(expression.getExpressionBody().orElseThrow(), LayoutContext.root())
            );
        }
        if (!parametersHaveComments && expressionBody.filter(this::bodyEndsInBlock).isPresent()) {
            return Doc.concat(
                lambdaParameterHeaders.forHeader(expression, parameters),
                Doc.text(" -> "),
                expressionRenderer.format(expressionBody.orElseThrow(), LayoutContext.root())
            );
        }
        Optional<Doc> methodCallBodyWithOpener = expressionBody.filter(MethodCallExpr.class::isInstance)
                .map(MethodCallExpr.class::cast)
                .flatMap(methodCall -> expressionLambdaArguments.methodCallBodyWithOpener(parameters, methodCall));
        if (methodCallBodyWithOpener.isPresent()) {
            return methodCallBodyWithOpener.orElseThrow();
        }
        // Canonical-fan cutover seam, the lambda-body ARROW position (SPIKE, #190). Checked BEFORE the body branches
        // below ({@code methodCallBodyWithHeader} and the broken-after-arrow fallback) — because the oscillation it closes
        // is exactly those branches disagreeing across passes for a fan-carrying lambda body. A
        // {@code () -> admin.createTopics(...).all().get()} whose flat form does not fit could otherwise alternate between
        // break-after-{@code ->} ({@code () ->}⏎{@code admin}⏎{@code .createTopics(…)…}) and attach-root-to-{@code ->}
        // ({@code () -> admin}⏎{@code .createTopics(…)…}) depending on the body's rendered shape. Ranking two AST-derived
        // arms with {@code Doc.bestFitting} at the true rendered column makes the arrow verdict a fixpoint by construction:
        // the U7 fan (root hugs the arrow, one selector per continuation line) is the attached arm, the same fan under an
        // indented {@code ->} break is the broken arm, and {@code bestFitting} picks attach whenever the root fits after
        // {@code params -> } and break only when it overflows.
        //
        // Placed AFTER {@code methodCallBodyWithOpener} (which fires only when the body's OUTERMOST call carries arguments,
        // e.g. {@code entry -> entry.a().b().compose(x, y)} — that call keeps its opener shape, exploding its own argument
        // list) so this seam does not reshape a chain the opener path already renders stably; the arrow-oscillating kafka
        // shapes ({@code .all().get()}, {@code .stream()}, {@code .isPresent()}) have an argument-less outermost call, so
        // the opener path returns empty for them and they fall here. It self-gates to fan-threshold comment/lambda-free
        // carriers ({@code lambdaBodyCanonicalFanChain} returns empty otherwise — object-creation roots,
        // chain-selector-hosted lambdas, comment/lambda chains), so every other body still reaches the unchanged branches
        // below byte-identically.
        Optional<Doc> chainArrowBestFitting = expressionBody.filter(MethodCallExpr.class::isInstance)
                .map(MethodCallExpr.class::cast)
                .flatMap(chainBody -> lambdaBodyChainArrowBestFitting(expression, parameters, chainBody));
        if (chainArrowBestFitting.isPresent()) {
            return chainArrowBestFitting.orElseThrow();
        }
        Optional<Doc> methodCallBodyWithHeader = parametersHaveComments
            ? Optional.empty()
            : expressionBody.filter(MethodCallExpr.class::isInstance)
                    .map(MethodCallExpr.class::cast)
                    .flatMap(methodCall -> expressionLambdaArguments.methodCallBodyWithHeader(parameters, methodCall));
        if (methodCallBodyWithHeader.isPresent()) {
            return methodCallBodyWithHeader.orElseThrow();
        }
        Optional<Doc> binaryMethodCallBodyWithOpener = expressionBody.filter(BinaryExpr.class::isInstance)
                .map(BinaryExpr.class::cast)
                .flatMap(binary -> expressionLambdaArguments.binaryMethodCallBodyWithOpener(parameters, binary));
        if (binaryMethodCallBodyWithOpener.isPresent()) {
            return binaryMethodCallBodyWithOpener.orElseThrow();
        }
        Optional<Doc> binaryBodyWithOpener = expressionBody.flatMap(
            body -> binaryBodyWithFirstOperandOnHeader(
                expression,
                parameters,
                body
            )
        );
        if (binaryBodyWithOpener.isPresent()) {
            return binaryBodyWithOpener.orElseThrow();
        }
        Optional<Doc> objectCreationBodyWithOpener = expressionBody.filter(ObjectCreationExpr.class::isInstance)
                .map(ObjectCreationExpr.class::cast)
                .flatMap(objectCreation -> objectCreationBodyWithOpener(expression, parameters, objectCreation));
        if (objectCreationBodyWithOpener.isPresent()) {
            return objectCreationBodyWithOpener.orElseThrow();
        }
        // PR #279 review (#3/#4, arrow-hug rule): a method-call chain body whose receiver carries a BLOCK lambda
        // ({@code Try.of(a, () -> { … }).getOrElseThrow(…)}, the {@code createInstance} shape) renders through the full
        // chain printer as {@code Try.of(a, () -> {}⏎ block ⏎{@code }).getOrElseThrow(…)} — a short opener head above an
        // already-multi-line block. Breaking the outer lambda after {@code ->} would orphan the arrow above that head, so
        // hug the body's first line onto the arrow line ({@code ctor -> Try.of(a, () -> {}) and let only the contained
        // block break, the same {@code -> } + body render {@link #bodyEndsInBlock} applies to a directly block-bodied
        // body. {@code brokenNonBinaryLambdaBody} already renders this receiver-block-lambda body through
        // {@link JavaFormatRule#format} (its {@code brokenMethodCallReceiverCompactsCleanly} guard declines the compact
        // reconstruction), so the hugged body is byte-identical to the broken-after-arrow body — a pure AST function, so
        // the hug is a fixpoint.
        if (
            !parametersHaveComments
            && expressionBody.filter(MethodCallExpr.class::isInstance)
                    .map(MethodCallExpr.class::cast)
                    .filter(chain -> !brokenMethodCallReceiverCompactsCleanly(chain))
                    .isPresent()
        ) {
            return Doc.concat(
                lambdaParameterHeaders.forHeader(expression, parameters),
                Doc.text(" -> "),
                expressionRenderer.format(expressionBody.orElseThrow(), LayoutContext.root())
            );
        }
        Doc body = brokenLambdaExpressionBody(expression);
        return Doc.concat(
            lambdaParameterHeaders.forHeader(expression, parameters),
            Doc.text(" ->"),
            Doc.indent(Doc.concat(Doc.HARD_LINE, body))
        );
    }

    /**
     * SPIKE (fan-root-true-column, #190). Makes the break-after-{@code ->} versus attach-root-to-{@code ->} verdict of a
     * fan-carrying expression-lambda body SOURCE-NEUTRAL by ranking two AST-derived shapes with {@link Doc#bestFitting} at
     * the true rendered column, so the two shapes cannot diverge across passes for a chain whose rendered body force-fans.
     *
     * <p>Both arms wrap the SAME fan Doc, produced ONCE by {@code lambdaBodyCanonicalFanChain} through the source-neutral
     * {@code chainFanOut} with an empty {@link LayoutContext#leftEdgePrefix()} ({@link LayoutContext#root()}):
     * <ul>
     *   <li><b>Attached</b> ({@code () -> admin}⏎{@code .createTopics(…)}⏎{@code .all()}⏎{@code .get()}): the header text
     *       {@code params -> } precedes the fan, so the chain root hugs the arrow line and the fan's own continuation indent
     *       lays each selector one per line under it — byte-identical to the U7 hug shape.</li>
     *   <li><b>Broken</b> ({@code () ->}⏎{@code admin}⏎{@code .createTopics(…)}…): the arrow breaks and the same fan renders
     *       under one continuation indent, byte-identical to the broken-after-arrow fallback's fanned body.</li>
     * </ul>
     * Rendering one prefix-agnostic fan and sharing it across arms is load-bearing (the initializer-seam lesson): the fan's
     * root renders at {@link LayoutContext#root()} in BOTH arms, so a promoted-factory or method-call root's opener group
     * cannot break differently between the two arms and re-flip. {@code bestFitting} scores line-count + overflow at the
     * live column, so the attached arm (fewer lines, root hugged) wins whenever the root fits after {@code params -> } and
     * the broken arm wins only when it overflows; both arms are pure AST functions, so the verdict is a fixpoint.
     *
     * <p>Returns empty when {@code lambdaBodyCanonicalFanChain} withholds the fan (a non-fan chain, an object-creation
     * root, a chain-selector-hosted lambda, or any comment/block-lambda carrier) or when the lambda parameters must break
     * or carry comments (those keep their dedicated header shapes), so every such body reaches the unchanged branches below.
     */
    private Optional<Doc> lambdaBodyChainArrowBestFitting(
            LambdaExpr lambda,
            String parameters,
            MethodCallExpr chainBody
    ) {
        if (lambdaParameterHeaders.haveComments(lambda) || lambdaParametersShouldBreak(lambda, parameters)) {
            return Optional.empty();
        }
        Optional<Doc> fan = lambdaBodyCanonicalFanChain.apply(chainBody);
        if (fan.isEmpty()) {
            return Optional.empty();
        }
        Doc fanDoc = fan.orElseThrow();
        Doc attached = Doc.concat(Doc.text(parameters + " -> "), fanDoc);
        // Trivial-receiver first-selector attach (gjf/prettier-java, comment #3). When the body chain's root is a TRIVIAL
        // RECEIVER, {@code chainFanOut} has already glued the first selector to the root ({@code orderEvent.validateOrder()}),
        // so the attached arm's opening line is just {@code params -> root.firstSelector()} — short by construction. The
        // maintainer's convention keeps such a body ANCHORED on the {@code ->} line rather than breaking after the arrow, so
        // commit the attached shape directly instead of ranking it against a break-after-arrow arm. This is a DETERMINISTIC
        // STRUCTURAL rule keyed only on the root kind — never on width — so it stays a fixpoint, and it matches the attach the
        // method-call-argument opener path ({@code dispatchJobForOrder(orderEvent -> orderEvent.validateOrder()}⏎…) already
        // produces for the identical chain. A call/factory/object-creation root keeps the {@code bestFitting} ranking below,
        // because there the opening line carries {@code params -> root(args)} and can genuinely overflow after the arrow.
        if (lambdaBodyChainRootIsTrivialReceiver.test(chainBody)) {
            return Optional.of(attached);
        }
        Doc broken = Doc.concat(
            Doc.text(parameters + " ->"),
            Doc.indent(Doc.concat(Doc.HARD_LINE, fanDoc))
        );
        return Optional.of(Doc.bestFitting(List.of(attached, broken)));
    }

    private Optional<Doc> objectCreationBodyWithOpener(
            LambdaExpr lambda,
            String parameters,
            ObjectCreationExpr objectCreation
    ) {
        // D3 flip-assembly (Read retirement): drop the {@code lambdaBodyStartsAfterHeader} source-shape disjunct so the
        // broken-object-creation body is chosen purely when the flat form does not fit ({@code objectCreationLambdaBodyFits}
        // false). Keying on whether the author broke the body forced a broken layout on a body whose flat form fits,
        // oscillating with the flat gate above.
        if (
            objectCreation.getArguments().isEmpty()
            || objectCreationLambdaBodyFits(parameters, objectCreation)
        ) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                lambdaParameterHeaders.forHeader(lambda, parameters),
                Doc.text(" -> "),
                brokenObjectCreationRenderer.apply(objectCreation)
            )
        );
    }

    private boolean objectCreationLambdaBodyFits(String parameters, ObjectCreationExpr objectCreation) {
        String flat = parameters + " -> " + compact.apply(objectCreation);
        return currentIndentedWidth.applyAsInt(flat) <= options.lineWidth()
            && !lambdaFlatOverflowsInBrokenArgumentList(flat);
    }

    private Optional<Doc> binaryBodyWithFirstOperandOnHeader(
            LambdaExpr lambda,
            String parameters,
            Expression body
    ) {
        if (lambdaParameterHeaders.haveComments(lambda) || lambdaParametersShouldBreak(lambda, parameters)) {
            return Optional.empty();
        }
        Optional<Doc> binaryBody = binaryBodyDoc(body);
        if (binaryBody.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> firstOperand = binaryBodyFirstOperandLine(body);
        if (
            firstOperand.filter(
                        operand -> currentIndentedWidth.applyAsInt(parameters + " -> " + operand) <= options.lineWidth()
                    )
                    .isEmpty()
        ) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                lambdaParameterHeaders.forHeader(lambda, parameters),
                Doc.text(" -> "),
                Doc.indent(binaryBody.orElseThrow())
            )
        );
    }

    private boolean methodCallBodyOverflowsInBrokenArgumentList(Expression body) {
        if (!(body instanceof MethodCallExpr methodCall)) {
            return false;
        }
        int nestedBodyWidth = blockStatementWidth.applyAsInt(
            options.indentUnit().repeat(3) + compact.apply(methodCall)
        );
        return nestedBodyWidth > options.lineWidth();
    }

    private boolean lambdaFlatOverflowsInBrokenArgumentList(String flat) {
        return blockStatementWidth.applyAsInt(options.indentUnit().repeat(3) + flat) > options.lineWidth();
    }

    private boolean shouldHugBrokenLambdaBody(Expression body) {
        return body instanceof MethodCallExpr methodCall
            && methodCall.getArguments().isEmpty()
            && currentIndentedWidth.applyAsInt(") -> " + compact.apply(methodCall)) <= options.lineWidth();
    }

    /**
     * Reports whether the lambda's expression body ultimately renders as a brace block, so the outer lambda should hug
     * its arrow and let only the block break multi-line.
     *
     * <p>The block-bearing body always ends in a brace block whose own renderer breaks the body onto following lines,
     * so breaking the outer lambda after {@code ->} would orphan the arrow above an already-multi-line block with
     * no readability gain. This holds for a direct block-bodied lambda body and for one wrapped in a cast (the common
     * {@code () -> (Iface) args -> { ... }} adapter shape), where the cast renderer prefixes the type and delegates the
     * inner block-bodied lambda — which hugs its own arrow via the block-statement branch. The check unwraps casts and
     * redundant parentheses but stays deliberately narrow: only a body that ends in a block qualifies, leaving every
     * other body layout to the broken-after-arrow paths below.
     */
    private boolean bodyEndsInBlock(Expression body) {
        if (body instanceof LambdaExpr lambda) {
            return lambda.getBody().isBlockStmt();
        }
        if (body instanceof CastExpr cast) {
            return bodyEndsInBlock(cast.getExpression());
        }
        if (body instanceof EnclosedExpr enclosed) {
            return bodyEndsInBlock(enclosed.getInner());
        }
        return false;
    }

    /**
     * Breaks expression bodies, forcing logical and over-wide binary bodies into the binary-line renderer.
     *
     * <p>A logical body such as {@code a && b} reads as one condition tree, and a wide relational body can hide an
     * overflowing method-call operand behind the lambda arrow. If the lambda body is already broken, the binary renderer
     * keeps that tree aligned instead of letting the expression dispatcher choose a flat fallback.
     */
    private Doc brokenLambdaExpressionBody(LambdaExpr expression) {
        return expression.getExpressionBody()
                .map(body -> binaryBodyDoc(body).orElseGet(() -> brokenNonBinaryLambdaBody(body)))
                .orElseGet(() -> statementRenderer.format(expression.getBody(), LayoutContext.root()));
    }

    private Doc brokenNonBinaryLambdaBody(Expression body) {
        if (
            body instanceof ObjectCreationExpr objectCreation
            && currentIndentedWidth.applyAsInt(compact.apply(objectCreation)) > options.lineWidth()
        ) {
            return brokenObjectCreationRenderer.apply(objectCreation);
        }
        if (
            body instanceof MethodCallExpr methodCall
            && currentIndentedWidth.applyAsInt(compact.apply(methodCall)) > options.lineWidth()
            && brokenMethodCallReceiverCompactsCleanly(methodCall)
        ) {
            return brokenMethodCallRenderer.apply(methodCall);
        }
        return expressionRenderer.format(body, LayoutContext.root());
    }

    /**
     * Reports whether the over-wide chain body can render through {@code brokenMethodCallRenderer} — which breaks only the
     * OUTERMOST call's argument list and reconstructs the whole receiver chain from a single compacted line — without that
     * receiver reconstruction collapsing a multi-line construct.
     *
     * <p>The compact receiver reconstruction has no {@link com.github.javaparser.ast.expr.LambdaExpr} case, so a receiver
     * that carries a BLOCK lambda ({@code Try.of(a, () -> { … }).getOrElseThrow(…)}, the {@code createInstance} shape) would
     * flatten its {@code { … }} onto one over-wide line, and a contained comment would de-indent and merge into the
     * following token — the malformed shapes PR #279 flagged. When the receiver carries either, this yields {@code false} so
     * {@link #brokenNonBinaryLambdaBody} falls through to {@link JavaFormatRule#format} — the full method-chain printer,
     * which renders the block lambda / comment through their own multi-line printers. This mirrors the identical guard
     * {@code ExpressionLambdaArgumentLayout#brokenMethodCallReceiverCompactsCleanly} applies on the call-argument side.
     */
    private boolean brokenMethodCallReceiverCompactsCleanly(MethodCallExpr methodCall) {
        return methodCall.getScope()
                .map(receiver -> receiver.findAll(LambdaExpr.class)
                        .stream()
                        .noneMatch(lambda -> lambda.getBody().isBlockStmt())
                    && receiver.getAllContainedComments().isEmpty())
                .orElse(true);
    }

    private boolean isLogicalBinaryOperator(BinaryExpr expression) {
        return expression.getOperator() == BinaryExpr.Operator.AND
            || expression.getOperator() == BinaryExpr.Operator.OR;
    }

    private Optional<Doc> binaryBodyDoc(Expression body) {
        return binaryBody(body)
                .filter(binary -> isLogicalBinaryOperator(binary)
                        || lambdaBodyOverflowsInBrokenArgumentList(body)
                )
                .map(binary -> {
                    Doc lines = binaryExpressionNestedLinesRenderer.apply(binary, true);
                    for (int i = 0; i < enclosedDepth(body); i++) {
                        lines = Doc.concat(Doc.text("("), lines, Doc.text(")"));
                    }
                    return lines;
                });
    }

    private boolean lambdaBodyOverflowsInBrokenArgumentList(Expression body) {
        String flat = compact.apply(body);
        return currentIndentedWidth.applyAsInt(flat) > options.lineWidth()
            || blockStatementWidth.applyAsInt(options.indentUnit().repeat(3) + flat) > options.lineWidth();
    }

    private Optional<BinaryExpr> binaryBody(Expression body) {
        if (body instanceof BinaryExpr binaryExpr) {
            return Optional.of(binaryExpr);
        }
        if (body instanceof EnclosedExpr enclosedExpr) {
            return binaryBody(enclosedExpr.getInner());
        }
        return Optional.empty();
    }

    private Optional<String> binaryBodyFirstOperandLine(Expression body) {
        if (body instanceof EnclosedExpr enclosedExpr) {
            return binaryBodyFirstOperandLine(enclosedExpr.getInner()).map(line -> "(" + line);
        }
        if (body instanceof BinaryExpr binaryExpr) {
            return Optional.of(compact.apply(firstBinaryOperand(binaryExpr)));
        }
        return Optional.empty();
    }

    private Expression firstBinaryOperand(BinaryExpr binaryExpr) {
        Expression left = binaryExpr.getLeft();
        while (left instanceof BinaryExpr leftBinary && leftBinary.getOperator() == binaryExpr.getOperator()) {
            left = leftBinary.getLeft();
        }
        return left;
    }

    private int enclosedDepth(Expression body) {
        int depth = 0;
        Expression current = body;
        while (current instanceof EnclosedExpr enclosedExpr) {
            depth++;
            current = enclosedExpr.getInner();
        }
        return depth;
    }

    boolean lambdaParametersShouldBreak(LambdaExpr expression, String flatParameters) {
        return lambdaParameterHeaders.shouldBreak(expression, flatParameters);
    }

    String lambdaParameters(LambdaExpr expression) {
        return lambdaParameterHeaders.parameters(expression);
    }

    /**
     * Hugs a single block-body lambda argument when it is at the start or end of the argument list.
     *
     * <p>Those edge positions let the call keep the ordinary argument prefix or suffix without hiding another argument
     * after the lambda body. A block lambda in the middle would make the remaining arguments read like part of the
     * lambda block, so the normal call formatter handles that case.
     */
    Optional<Doc> huggableBlockLambdaArguments(String prefix, NodeList<Expression> arguments) {
        return huggableBlockLambdaArguments(
            prefix,
            arguments,
            blockStatementWidth,
            this::lambdaExpression,
            blockRenderer
        );
    }

    Optional<Doc> huggableMethodChainBlockLambdaArguments(String prefix, NodeList<Expression> arguments) {
        return huggableBlockLambdaArguments(
            prefix,
            arguments,
            blockStatementWidth,
            this::methodChainLambdaExpression,
            methodChainLambdaBlockRenderer
        );
    }

    /**
     * Hugs a block-lambda argument after the caller supplies the width check for the first rendered line.
     *
     * <p>Statement, method-call, and object-creation contexts use normal block-statement width. Field declarations include
     * the declaration prefix before the call, so they provide their own width probe while sharing the same eligibility and
     * rendering rules.
     */
    Optional<Doc> huggableBlockLambdaArguments(
            String prefix,
            NodeList<Expression> arguments,
            ToIntFunction<String> firstLineWidth
    ) {
        return huggableBlockLambdaArguments(prefix, arguments, firstLineWidth, this::lambdaExpression, blockRenderer);
    }

    private Optional<Doc> huggableBlockLambdaArguments(
            String prefix,
            NodeList<Expression> arguments,
            ToIntFunction<String> firstLineWidth,
            Function<LambdaExpr, Doc> lambdaRenderer,
            JavaFormatRule<BlockStmt> lambdaBlockRenderer
    ) {
        Optional<HuggableBlockLambdaArgument> huggable = huggableBlockLambdaArgument(prefix, arguments);
        if (huggable.isEmpty()) {
            return Optional.empty();
        }
        HuggableBlockLambdaArgument argument = huggable.orElseThrow();
        Optional<Doc> sourceMultilineParameters =
            SourceMultilineLambdaCallLayout.blockLambdaArgumentWithSourceMultilineParameters(
                prefix,
                arguments,
                argument.lambdaIndex(),
                argument.lambdaExpr(),
                argument.leadingArguments(),
                compactJoin,
                lambdaParameterHeaders,
                lambdaBlockRenderer
            );
        if (sourceMultilineParameters.isPresent()) {
            return sourceMultilineParameters;
        }
        if (firstLineWidth.applyAsInt(argument.firstLine()) > options.lineWidth()) {
            return Optional.empty();
        }
        String trailingArguments = compactJoin.apply(arguments.subList(argument.lambdaIndex() + 1, arguments.size()));
        return Optional.of(
            Doc.concat(
                Doc.text(prefix + "(" + (argument.leadingArguments().isEmpty() ? "" : argument.leadingArguments() + ", ")),
                lambdaRenderer.apply(argument.lambdaExpr()),
                Doc.text((trailingArguments.isEmpty() ? "" : ", " + trailingArguments) + ")")
            )
        );
    }

    private Doc methodChainLambdaExpression(LambdaExpr expression) {
        String parameters = lambdaParameters(expression);
        if (expression.getBody().isBlockStmt()) {
            return Doc.concat(
                lambdaParameterHeaders.forHeader(expression, parameters),
                Doc.text(" -> "),
                methodChainLambdaBlockRenderer.format(expression.getBody().asBlockStmt(), LayoutContext.root())
            );
        }
        return lambdaExpression(expression);
    }

    /**
     * Returns the exact first line used by the huggable block-lambda argument layout before width is considered.
     */
    Optional<String> huggableBlockLambdaFirstLine(String prefix, NodeList<Expression> arguments) {
        return huggableBlockLambdaArgument(prefix, arguments).map(HuggableBlockLambdaArgument::firstLine);
    }

    /**
     * Applies the shared block-lambda argument eligibility rules for both rendering and external first-line probing.
     */
    private Optional<HuggableBlockLambdaArgument> huggableBlockLambdaArgument(
            String prefix,
            NodeList<Expression> arguments
    ) {
        int lambdaIndex = SourceMultilineLambdaCallLayout.blockLambdaArgumentIndex(arguments);
        if (lambdaIndex < 0 || (lambdaIndex > 0 && lambdaIndex < arguments.size() - 1)) {
            return Optional.empty();
        }
        if (SourceMultilineLambdaCallLayout.hasOtherLambdaArgument(arguments, lambdaIndex)) {
            return Optional.empty();
        }
        if (nonLambdaArgumentHasConstructorChainRootNeedingBreak(arguments, lambdaIndex)) {
            return Optional.empty();
        }
        LambdaExpr lambdaExpr = (LambdaExpr) arguments.get(lambdaIndex);
        if (hugWouldDropComment(lambdaExpr)) {
            return Optional.empty();
        }
        String parameters = lambdaParameters(lambdaExpr);
        if (lambdaParametersShouldBreak(lambdaExpr, parameters)) {
            return Optional.empty();
        }
        String leadingArguments = compactJoin.apply(arguments.subList(0, lambdaIndex));
        String firstLine = prefix
            + "("
            + (leadingArguments.isEmpty() ? "" : leadingArguments + ", ")
            + parameters
            + " -> {";
        return Optional.of(new HuggableBlockLambdaArgument(lambdaIndex, lambdaExpr, leadingArguments, firstLine));
    }

    /**
     * Reports whether hugging the block lambda onto the call's opener line would silently drop a comment.
     *
     * <p>The hug reconstructs the call prefix and the lambda parameter header from compact, comment-stripped text and only
     * renders the lambda <em>body block</em> through a comment-preserving path. A line or block comment that sits in the
     * gap between the call opener and the lambda body — on the lambda argument itself, on the call's orphan slots, or on
     * the call selector — therefore has no slot in this layout and would vanish when the source line it lived on is
     * collapsed onto the opener.
     *
     * <p>JavaParser attaches such a comment differently depending on the source shape: a comment written on the line
     * before the lambda becomes the lambda's own comment (the issue #131 shape,
     * {@code forEach(\n  // note\n  (a, b) -> { ... })}), while collapsing the surrounding whitespace can re-home the same
     * comment onto the call selector's {@code SimpleName}. Both shapes are caught here. When such a comment is present the
     * hug is suppressed and the call falls through to the comment-preserving broken argument-list path, which prints the
     * comment before the argument on its own line.
     *
     * <p>The selector slot is filtered with the same boundary rules {@link #commentedExpressionLambdaArgument} uses: a
     * comment that {@linkplain #trailsCompletedCall trails a completed call} or {@linkplain #precedesCallSelector precedes
     * this call's selector} is a chain-link comment owned by the surrounding method-chain printer, not part of this call's
     * opener-to-lambda gap, so it must not suppress the hug. Without that filter a trailing comment on an earlier chain
     * segment — which JavaParser parks on the next segment's selector {@code SimpleName} — would flip the hug off and back
     * on across re-formats and break idempotence.
     */
    private boolean hugWouldDropComment(LambdaExpr lambdaExpr) {
        if (lambdaExpr.getComment().filter(this::isLineOrBlockComment).isPresent()) {
            return true;
        }
        return lambdaExpr.getParentNode()
                .filter(MethodCallExpr.class::isInstance)
                .map(MethodCallExpr.class::cast)
                .map(this::callCarriesOpenerGapComment)
                .orElse(false);
    }

    /**
     * Reports whether the call's orphan pool or selector name carries a comment that belongs to the opener-to-lambda gap.
     *
     * <p>Expanding the source can detach the comment from the lambda and park it in the call's orphan pool, while
     * collapsing it can re-home the comment onto the selector {@code SimpleName}; both slots are the ones
     * {@link #commentedExpressionLambdaArgument} reads. A comment that trails a completed call or precedes this call's
     * selector is a chain-link comment owned by the method-chain printer, not part of this call's opener-to-lambda gap, so
     * it is excluded.
     */
    private boolean callCarriesOpenerGapComment(MethodCallExpr call) {
        boolean orphanComment = call.getOrphanComments()
                .stream()
                .filter(this::isLineOrBlockComment)
                .filter(comment -> !trailsCompletedCall(call, comment))
                .anyMatch(comment -> !precedesCallSelector(call, comment));
        boolean selectorComment = call.getName()
                .getComment()
                .filter(this::isLineOrBlockComment)
                .filter(comment -> !trailsCompletedCall(call, comment))
                .filter(comment -> !precedesCallSelector(call, comment))
                .isPresent();
        return orphanComment || selectorComment;
    }

    private boolean nonLambdaArgumentHasConstructorChainRootNeedingBreak(
            NodeList<Expression> arguments,
            int lambdaIndex
    ) {
        for (int index = 0; index < arguments.size(); index++) {
            if (index != lambdaIndex && expressionHasConstructorChainRootNeedingBreak(arguments.get(index))) {
                return true;
            }
        }
        return false;
    }

    private boolean expressionHasConstructorChainRootNeedingBreak(Expression expression) {
        return expression.findAll(MethodCallExpr.class)
                .stream()
                .anyMatch(this::methodCallRootConstructorNeedsBreak);
    }

    private boolean methodCallRootConstructorNeedsBreak(MethodCallExpr expression) {
        List<MethodCallExpr> calls = new ArrayList<>();
        Expression root = methodCallChainRoot(expression, calls);
        if (calls.isEmpty() || !(root instanceof ObjectCreationExpr objectCreation)) {
            return false;
        }
        // A "heavy" root constructor breaks its argument list even when it fits the width (see ArgumentHeaviness), so a
        // trailing lambda must not hug it flat onto the opener; suppress the hug so the enclosing call explodes its
        // arguments and the constructor root breaks on its own line (PR #279 comment #1 cascade).
        if (argumentHeaviness.isHeavy(objectCreation.getArguments(), true)) {
            return true;
        }
        int compactRootWidth = currentIndentedWidth.applyAsInt(compact.apply(objectCreation));
        boolean compactRootCanStay = objectCreationLayoutPolicy.canKeepCompactChainRoot(
            objectCreation,
            compactRootWidth,
            options.lineWidth()
        );
        return !compactRootCanStay;
    }

    private Expression methodCallChainRoot(MethodCallExpr expression, List<MethodCallExpr> calls) {
        if (expression.getScope().orElse(null) instanceof MethodCallExpr methodCallExpr) {
            Expression root = methodCallChainRoot(methodCallExpr, calls);
            calls.add(expression);
            return root;
        }
        if (expression.getScope().isEmpty()) {
            return expression;
        }
        calls.add(expression);
        return expression.getScope().orElseThrow();
    }

    private record HuggableBlockLambdaArgument(
        int lambdaIndex,
        LambdaExpr lambdaExpr,
        String leadingArguments,
        String firstLine
    ) {}

    /**
     * Rebuilds a single expression-lambda argument when comments sit around the lambda boundary.
     *
     * <p>JavaParser can attach those comments to the call, the method name, or the lambda itself. This method collects
     * only line and block comments around the single lambda argument, then prints leading comments before the lambda and
     * trailing comments after it inside the broken call argument list. Comments after the completed call stay out of this
     * path so chain renderers can keep them after the call's closing parenthesis.
     */
    Optional<Doc> commentedExpressionLambdaArgument(String prefix, MethodCallExpr expression) {
        if (
            expression.getArguments().size() != 1
            || !(expression.getArgument(0) instanceof LambdaExpr lambdaExpr)
            || lambdaExpr.getExpressionBody().isEmpty()
        ) {
            return Optional.empty();
        }
        Optional<Doc> bodyGapComment = bodyGapCommentedExpressionLambdaArgument(prefix, lambdaExpr);
        if (bodyGapComment.isPresent()) {
            return bodyGapComment;
        }
        List<Comment> commentsAroundLambda = new ArrayList<>();
        expression.getOrphanComments()
                .stream()
                .filter(this::isLineOrBlockComment)
                .filter(comment -> !trailsCompletedCall(expression, comment))
                .filter(comment -> !precedesCallSelector(expression, comment))
                .forEach(commentsAroundLambda::add);
        lambdaExpr.getComment()
                .filter(this::isLineOrBlockComment)
                .filter(comment -> !trailsCompletedCall(expression, comment))
                .ifPresent(commentsAroundLambda::add);
        expression.getName()
                .getComment()
                .filter(this::isLineOrBlockComment)
                .filter(comment -> !trailsCompletedCall(expression, comment))
                .filter(comment -> !precedesCallSelector(expression, comment))
                .filter(comment -> startsBefore.test(comment, lambdaExpr))
                .ifPresent(commentsAroundLambda::add);
        if (commentsAroundLambda.isEmpty()) {
            return Optional.empty();
        }
        commentsAroundLambda.sort(CommentIndex.sourceOrderComparator());
        Optional<Doc> inlineBlockComment = inlineBlockCommentedExpressionLambdaArgument(
            prefix,
            lambdaExpr,
            commentsAroundLambda
        );
        if (inlineBlockComment.isPresent()) {
            return inlineBlockComment;
        }
        Optional<Doc> brokenLeadingBlockComment = brokenLeadingBlockCommentedExpressionLambdaArgument(
            prefix,
            lambdaExpr,
            commentsAroundLambda
        );
        if (brokenLeadingBlockComment.isPresent()) {
            return brokenLeadingBlockComment;
        }
        List<Doc> leading = commentsAroundLambda.stream()
                .filter(comment -> isLeadingExpressionLambdaComment(lambdaExpr, comment))
                .map(comments::comment)
                .filter(comment -> comment != Doc.EMPTY)
                .toList();
        List<Doc> trailing = commentsAroundLambda.stream()
                .filter(comment -> !isLeadingExpressionLambdaComment(lambdaExpr, comment))
                .map(comments::comment)
                .filter(comment -> comment != Doc.EMPTY)
                .toList();
        List<Doc> argumentLines = new ArrayList<>();
        argumentLines.addAll(leading);
        argumentLines.add(lambdaExpression(lambdaExpr));
        argumentLines.addAll(trailing);
        return Optional.of(
            Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.join(Doc.HARD_LINE, argumentLines))),
                Doc.HARD_LINE,
                Doc.text(")")
            )
        );
    }

    /**
     * Breaks an expression-bodied lambda whose body is led by a comment block in the gap between the {@code ->} and the
     * body, emitting that comment on its own line(s) at the body indent before the body.
     *
     * <p>{@link #bodyGapCommentedExpressionLambdaArgument} already recovers this comment when a method call directly owns
     * a single lambda argument, but a lambda nested as another lambda's body (the kafka {@code forEach(... -> forEach(...
     * -> // comment ... body))} shape) is rendered through the generic argument-list path, which dispatches the inner
     * lambda straight into {@link #lambdaExpression} without that recovery — so the gap comment was dropped. This builds
     * the broken lambda directly so every broken body shape, not only the direct method-call argument, keeps the comment.
     *
     * <p>It is deliberately scoped to the pure-gap case (no comments around the lambda boundary or in the parameter
     * clause), leaving the boundary-comment and parameter-comment reconstructions as the owners of their shapes, and runs
     * only after the flat path has been ruled out so a lambda whose body fits on one line is unaffected.
     */
    private Optional<Doc> brokenLambdaWithLeadingBodyComment(LambdaExpr expression, String parameters) {
        if (
            lambdaParameterHeaders.haveComments(expression)
            || hasBoundaryComments(expression)
        ) {
            return Optional.empty();
        }
        List<JavaCommentTrivia> gapComments = lambdaBodyGapComments(expression);
        if (gapComments.isEmpty()) {
            return Optional.empty();
        }
        List<Doc> renderedGapComments = gapComments.stream()
                .map(trivia -> comments.comment(trivia, expression.getBody(), OwnerSlot.LEADING))
                .filter(doc -> doc != Doc.EMPTY)
                .toList();
        if (renderedGapComments.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                lambdaParameterHeaders.forHeader(expression, parameters),
                Doc.text(" ->"),
                Doc.indent(
                    Doc.concat(
                        Doc.HARD_LINE,
                        Doc.join(Doc.HARD_LINE, renderedGapComments),
                        Doc.HARD_LINE,
                        brokenLambdaExpressionBody(expression)
                    )
                )
            )
        );
    }

    /**
     * Breaks a single expression-lambda argument whose body carries a comment in the gap between the {@code ->} and the
     * body, rendering that comment on its own line before the body.
     *
     * <p>JavaParser wraps an expression-bodied lambda's body in an {@link com.github.javaparser.ast.stmt.ExpressionStmt}
     * ({@link LambdaExpr#getBody()}), and a {@code //}/block comment that sits after the arrow but before the body token
     * attaches to that wrapping statement as its own/adjacent-leading trivia. The expression-body render path unwraps to
     * {@link LambdaExpr#getExpressionBody()} and dispatches through the expression envelope, which — unlike the statement
     * envelope — never offers that statement's leading comments, so the gap comment is dropped. This path recovers it
     * through the body statement's leading cluster and places it inline before the body, matching how a block-body lambda
     * keeps the same comment via the statement renderer. It is deliberately scoped to the pure-gap case (no comments
     * around the lambda boundary), so the existing boundary-comment reconstruction below stays the owner of those shapes.
     */
    private Optional<Doc> bodyGapCommentedExpressionLambdaArgument(String prefix, LambdaExpr lambdaExpr) {
        if (hasBoundaryComments(lambdaExpr)) {
            return Optional.empty();
        }
        List<JavaCommentTrivia> gapComments = lambdaBodyGapComments(lambdaExpr);
        if (gapComments.isEmpty()) {
            return Optional.empty();
        }
        List<Doc> renderedGapComments = gapComments.stream()
                .map(trivia -> comments.comment(trivia, lambdaExpr.getBody(), OwnerSlot.LEADING))
                .filter(doc -> doc != Doc.EMPTY)
                .toList();
        if (renderedGapComments.isEmpty()) {
            return Optional.empty();
        }
        String parameters = lambdaParameters(lambdaExpr);
        if (!lambdaParameterHeaders.haveComments(lambdaExpr)) {
            return Optional.of(
                huggedGapCommentedExpressionLambdaArgument(prefix, lambdaExpr, parameters, renderedGapComments)
            );
        }
        return Optional.of(
            Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(
                    Doc.concat(
                        Doc.HARD_LINE,
                        lambdaParameterHeaders.forHeader(lambdaExpr, parameters),
                        Doc.text(" ->"),
                        Doc.indent(
                            Doc.concat(
                                Doc.HARD_LINE,
                                Doc.join(Doc.HARD_LINE, renderedGapComments),
                                Doc.HARD_LINE,
                                brokenLambdaExpressionBody(lambdaExpr)
                            )
                        )
                    )
                ),
                Doc.HARD_LINE,
                Doc.text(")")
            )
        );
    }

    /**
     * Renders a single comment-carrying expression-lambda argument with the lambda hugging the call opener, i.e.
     * {@code call(param ->} stays on one line, the gap comment(s) and body sit indented beneath it, and the call's closing
     * parenthesis collapses onto the body's last line ({@code body)}) instead of stacking on its own.
     *
     * <p>This is the comment-carrying counterpart of the comment-free hug ({@link ExpressionLambdaArgumentLayout}). The
     * comment forces the body to break, so the only choice is whether the lambda header breaks with it. Hugging keeps the
     * argument shape stable: the closing parenthesis attaches to whatever the body's broken renderer already ends with
     * ({@code ...))} for a broken nested call), which is exactly the shape that re-parses to the same gap-comment
     * attachment, so the layout is idempotent. It is scoped to clean parameters (the caller already excluded boundary and
     * parameter comments) so the parameter text can live on the opener line verbatim.
     */
    private Doc huggedGapCommentedExpressionLambdaArgument(
            String prefix,
            LambdaExpr lambdaExpr,
            String parameters,
            List<Doc> renderedGapComments
    ) {
        return Doc.concat(
            Doc.text(prefix + "(" + parameters + " ->"),
            huggedGapCommentedLambdaBody(lambdaExpr, renderedGapComments),
            Doc.text(")")
        );
    }

    private Doc huggedGapCommentedLambdaBody(LambdaExpr lambdaExpr, List<Doc> renderedGapComments) {
        return Doc.indent(
            Doc.concat(
                Doc.HARD_LINE,
                Doc.join(Doc.HARD_LINE, renderedGapComments),
                Doc.HARD_LINE,
                brokenLambdaExpressionBody(lambdaExpr)
            )
        );
    }

    /**
     * Returns the indented gap-comment-and-body fragment for a comment-carrying expression lambda whose body comments sit
     * in the {@code ->}-to-body gap (the innermost {@code parcel -> // note merge(...)} shape), or empty when the lambda
     * has no such gap comments or has boundary/parameter comments another renderer owns.
     *
     * <p>The chain printer's flat-head hug reconstructs the lambda header text itself and only needs the part after the
     * arrow; this hands back that fragment (the gap comment on its own indented line, then the broken body) without an
     * opener or closer, so the chain printer can pack the header flat and collapse the call's closing parenthesis. It is
     * the same fragment {@link #huggedGapCommentedExpressionLambdaArgument} emits, so both hug shapes stay consistent and
     * idempotent.
     */
    Optional<Doc> huggedGapCommentedLambdaBody(LambdaExpr lambdaExpr) {
        if (lambdaExpr.getExpressionBody().isEmpty() || hasBoundaryComments(lambdaExpr)) {
            return Optional.empty();
        }
        if (lambdaParameterHeaders.haveComments(lambdaExpr)) {
            return Optional.empty();
        }
        List<JavaCommentTrivia> gapComments = lambdaBodyGapComments(lambdaExpr);
        if (gapComments.isEmpty()) {
            return Optional.empty();
        }
        List<Doc> renderedGapComments = gapComments.stream()
                .map(trivia -> comments.comment(trivia, lambdaExpr.getBody(), OwnerSlot.LEADING))
                .filter(doc -> doc != Doc.EMPTY)
                .toList();
        if (renderedGapComments.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(huggedGapCommentedLambdaBody(lambdaExpr, renderedGapComments));
    }

    /**
     * Returns the comments that sit in the source gap between the lambda's {@code ->} and its expression body, in source
     * order.
     *
     * <p>JavaParser attaches such a comment inconsistently across whitespace shapes — to the body's wrapping
     * {@link com.github.javaparser.ast.stmt.ExpressionStmt} as own/adjacent-leading trivia at the {@code @default} shape,
     * but to the {@link LambdaExpr} orphan pool once a collapse/expand perturbation moves it — so a shape-specific
     * attachment query drops it on the moved shapes. This selects by source position instead: a comment contained in the
     * lambda that begins after the {@code ->} token and before the body token, which is the same set regardless of layout.
     * Comments inside the parameter clause begin before the arrow and comments inside or trailing the body begin at or
     * after it, so both are excluded. The query never claims.
     */
    private List<JavaCommentTrivia> lambdaBodyGapComments(LambdaExpr lambdaExpr) {
        if (lambdaExpr.getExpressionBody().isEmpty()) {
            return List.of();
        }
        Node body = lambdaExpr.getBody();
        Optional<Position> arrow = lambdaArrowPosition(lambdaExpr, body);
        if (arrow.isEmpty()) {
            return List.of();
        }
        Position arrowPosition = arrow.orElseThrow();
        return commentPlacement.containedComments(lambdaExpr)
                .stream()
                .filter(trivia -> CommentIndex.startsAfter(trivia.comment(), arrowPosition))
                .filter(trivia -> startsBefore.test(trivia.comment(), body))
                .sorted(java.util.Comparator.comparing(JavaCommentTrivia::comment, CommentIndex.sourceOrderComparator()))
                .toList();
    }

    /**
     * Locates the {@code ->} token between the lambda's parameter clause and its body, by source position.
     *
     * <p>The arrow has no AST node, so it is found in the source gap after the last parameter (or after the lambda's own
     * begin for a parameterless lambda) and before the body. Selecting the gap comments by their position relative to
     * this token is what keeps the selection shape-independent: a token-range string reconstruction cannot give the
     * arrow's real position once whitespace moves a comment onto its own line.
     */
    private Optional<Position> lambdaArrowPosition(LambdaExpr lambdaExpr, Node body) {
        Optional<Range> before = lambdaExpr.getParameters().isEmpty()
            ? lambdaExpr.getRange()
            : lambdaExpr.getParameter(lambdaExpr.getParameters().size() - 1).getRange();
        return before.flatMap(
            beforeRange -> body.getRange().flatMap(
                bodyRange -> sourceText.firstTokenPositionBetween(beforeRange, bodyRange, "->")
            )
        );
    }

    /**
     * Reports whether any comment is attached around the lambda boundary (call orphans, the lambda's own comment, or the
     * method name), the shapes the boundary-comment reconstruction below already owns.
     */
    private boolean hasBoundaryComments(LambdaExpr lambdaExpr) {
        return lambdaExpr.getComment().filter(this::isLineOrBlockComment).isPresent()
            || lambdaExpr.getParentNode()
                    .filter(MethodCallExpr.class::isInstance)
                    .map(MethodCallExpr.class::cast)
                    .map(call -> call.getOrphanComments().stream().anyMatch(this::isLineOrBlockComment)
                            || call.getName().getComment().filter(this::isLineOrBlockComment).isPresent())
                    .orElse(false);
    }

    private boolean isLineOrBlockComment(Comment comment) {
        return comment instanceof LineComment || comment instanceof BlockComment;
    }

    private boolean trailsCompletedCall(MethodCallExpr expression, Comment comment) {
        return CommentIndex.startsAfterNodeOnSameLine(expression, comment);
    }

    /**
     * Reports whether a comment sits before this call's own selector, i.e. it is a chain-link comment owned by the method
     * chain rather than a comment around the lambda argument.
     *
     * <p>JavaParser parks a {@code // text} that precedes a fluent chain link (the {@code Optional.of(x) // note .map(y)}
     * shape) on the {@code .map(...)} call's orphan pool, exactly where {@link #commentedExpressionLambdaArgument} reads
     * its boundary comments. That comment begins before the call name, while a genuine leading comment on the lambda
     * argument begins after the opening parenthesis. The method chain printer already renders the chain-link comment as a
     * leading comment of the segment, so collecting it here too would claim it twice; excluding it keeps a single owner.
     */
    private boolean precedesCallSelector(MethodCallExpr expression, Comment comment) {
        return CommentIndex.startsBefore(comment, expression.getName());
    }

    /**
     * Keeps a leading block comment and expression lambda on one line when the whole call still fits.
     *
     * <p>This is only valid for a same-line leading block comment, because the comment visually belongs to the lambda
     * argument. Line comments and trailing comments force the broken form so their line ownership stays clear.
     */
    private Optional<Doc> inlineBlockCommentedExpressionLambdaArgument(
            String prefix,
            LambdaExpr lambdaExpr,
            List<Comment> commentsAroundLambda
    ) {
        if (commentsAroundLambda.size() != 1) {
            return Optional.empty();
        }
        Comment comment = commentsAroundLambda.getFirst();
        if (
            !(comment instanceof BlockComment)
            || !isLeadingExpressionLambdaComment(lambdaExpr, comment)
            || !startsOnSameLine.test(comment, lambdaExpr)
        ) {
            return Optional.empty();
        }
        String call = prefix
            + "("
            + comment.toString().stripTrailing()
            + " "
            + compactWithoutOwnComment.apply(lambdaExpr)
            + ")";
        if (currentIndentedWidth.applyAsInt(call) > options.lineWidth()) {
            return Optional.empty();
        }
        comments.comment(comment);
        return Optional.of(Doc.text(call));
    }

    /**
     * Breaks a same-line leading block comment before the expression lambda when the compact call is too wide.
     */
    private Optional<Doc> brokenLeadingBlockCommentedExpressionLambdaArgument(
            String prefix,
            LambdaExpr lambdaExpr,
            List<Comment> commentsAroundLambda
    ) {
        if (commentsAroundLambda.size() != 1) {
            return Optional.empty();
        }
        Comment comment = commentsAroundLambda.getFirst();
        if (
            !(comment instanceof BlockComment)
            || !isLeadingExpressionLambdaComment(lambdaExpr, comment)
            || !startsOnSameLine.test(comment, lambdaExpr)
        ) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(
                    Doc.concat(
                        Doc.HARD_LINE,
                        comments.comment(comment),
                        Doc.text(" "),
                        lambdaExpression(lambdaExpr)
                    )
                ),
                Doc.HARD_LINE,
                Doc.text(")")
            )
        );
    }

    private boolean isLeadingExpressionLambdaComment(LambdaExpr lambdaExpr, Comment comment) {
        return lambdaExpr.getComment().filter(ownComment -> ownComment == comment).isPresent()
            || startsBefore.test(comment, lambdaExpr);
    }

    /**
     * Hugs expression-body lambdas whose body naturally wants to start on the next line of a call argument.
     *
     * <p>Method calls with arguments, conditional expressions, and nested expression lambdas have a useful first line
     * ending at {@code ->}. Once the flat call is too wide but that first line still fits, the body can break underneath
     * the lambda header without switching the whole method call to the generic argument-list shape.
     */
    Optional<Doc> huggableMethodCallExpressionLambdaArguments(
            String prefix,
            NodeList<Expression> arguments,
            ToIntFunction<String> columnWidth
    ) {
        return expressionLambdaArguments.huggableMethodCallArguments(prefix, arguments, columnWidth);
    }

    /**
     * Exposes {@link ExpressionLambdaArgumentLayout#methodCallBodyWithOpener} — the source-neutral lambda-body opener hug
     * ({@code params -> bodyCall(}⏎ body arguments ⏎{@code )}) — so a fanned chain selector whose sole argument is a
     * single-method-call-body expression lambda can hug its opener directly ({@code MethodCallChainPrinter}'s
     * {@code singleCallLambdaBodyOpenerHug}) when the shared {@code huggableMethodCallArguments} renderer handed back only the
     * degenerate flat one-liner (review round 2, comment #3).
     */
    Optional<Doc> expressionLambdaMethodCallBodyOpener(
            String parameters,
            MethodCallExpr methodCall,
            ToIntFunction<String> columnWidth
    ) {
        return expressionLambdaArguments.methodCallBodyWithOpener(parameters, methodCall, columnWidth);
    }

    /**
     * Exposes {@link ExpressionLambdaArgumentLayout#logicalBinaryLambdaBodyOpenerHug} — the source-neutral logical-binary
     * opener hug ({@code param -> <first operand>}⏎ each following {@code &&}/{@code ||} operand ⏎{@code )}) — so a fanned
     * chain selector whose sole argument is a logical-binary-body expression lambda can hug its opener with a dedented close
     * ({@code MethodCallChainPrinter}'s {@code expressionBodyOpenerHug}, review round 3) instead of breaking the selector
     * parenthesis onto its own line.
     */
    Optional<Doc> expressionLambdaLogicalBinaryBodyOpenerHug(
            String prefix,
            MethodCallExpr expression,
            ToIntFunction<String> columnWidth
    ) {
        return expressionLambdaArguments.logicalBinaryLambdaBodyOpenerHug(prefix, expression, columnWidth);
    }

    /**
     * Exposes {@link ExpressionLambdaArgumentLayout#plan} to the call and chain printers.
     *
     * <p>D1g (#190) threads {@code layout} so the true continuation column is available to the lambda-hug admission gate.
     * It is not yet consulted (byte-identical); see the {@code plan} Javadoc for why the internal hug-renderer caller
     * keeps {@link LayoutContext#root()} while the return / assignment / initializer / single-segment-root positions
     * thread their real context.
     */
    Optional<ExpressionLambdaArgumentLayout.Plan> huggableExpressionLambdaArgumentPlan(
            String prefix,
            NodeList<Expression> arguments,
            LayoutContext layout
    ) {
        return expressionLambdaArguments.plan(prefix, arguments, layout);
    }
}
